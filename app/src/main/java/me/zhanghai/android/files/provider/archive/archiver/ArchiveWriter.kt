/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.libarchive.Archive
import java.io.Closeable
import java.io.IOException

class ArchiveWriter @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    encryptFileNames: Boolean = false,
    compressionPreset: ArchiveCompressionPreset = ArchiveCompressionPreset.STANDARD
) : Closeable {
    init {
        require(!encryptFileNames || format == Archive.FORMAT_7ZIP && !password.isNullOrEmpty())
        require(format != Archive.FORMAT_7ZIP || filter == Archive.FILTER_NONE)
    }

    private val sevenZArchive = if (format == Archive.FORMAT_7ZIP) {
        SevenZArchiveWriter(
            channel, password?.takeUnless { it.isEmpty() }, encryptFileNames, compressionPreset
        )
    } else {
        null
    }
    private val archive = if (sevenZArchive == null) {
        WriteArchive(channel, format, filter, password, compressionPreset)
    } else {
        null
    }

    /** All 7z inputs must be supplied together so a solid block is encoded only once. */
    @Throws(IOException::class)
    fun writeEntries(
        entries: List<Pair<Path, Path>>,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?,
        onEntry: ((Path) -> Unit)? = null,
        onEntryComplete: ((Path) -> Unit)? = null
    ) {
        val sevenZArchive = sevenZArchive
        if (sevenZArchive != null) {
            sevenZArchive.writeEntries(entries, intervalMillis, listener, onEntry, onEntryComplete)
            return
        }
        for ((file, entryName) in entries) {
            onEntry?.invoke(file)
            write(file, entryName, intervalMillis, listener)
            onEntryComplete?.invoke(file)
        }
    }

    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
        val sevenZArchive = sevenZArchive
        if (sevenZArchive != null) {
            sevenZArchive.writeEntries(listOf(file to entryName), intervalMillis, listener, null, null)
            return
        }
        val name = entryName.toString()
        val lastAccessTime = null
        val creationTime = null
        val attributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val lastModifiedTime = attributes.lastModifiedTime()
        val type = when {
            attributes is PosixFileAttributes -> attributes.type()
            attributes.isDirectory -> PosixFileType.DIRECTORY
            attributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val size = attributes.size()
        val posixAttributes = attributes as? PosixFileAttributes
        val owner = posixAttributes?.owner()
        val group = posixAttributes?.group()
        val mode = posixAttributes?.mode() ?: when {
            attributes.isDirectory -> PosixFileMode.DIRECTORY_DEFAULT
            attributes.isSymbolicLink -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
            else -> PosixFileMode.FILE_DEFAULT
        }
        val symbolicLinkTarget = if (attributes.isSymbolicLink) {
            file.readSymbolicLinkByteString().toString()
        } else {
            null
        }
        val archive = archive!!
        archive.Entry(
            name, lastModifiedTime, lastAccessTime, creationTime, type, size, owner, group, mode,
            symbolicLinkTarget
        ).use { archive.writeEntry(it) }
        if (type == PosixFileType.REGULAR_FILE) {
            file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                var copiedSize = 0L
                inputStream.copyTo(archive.newDataOutputStream(), intervalMillis) {
                    copiedSize += it
                    listener?.invoke(it)
                }
                val currentAttributes = file.readAttributes(
                    BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
                if (copiedSize != size || currentAttributes.size() != size
                    || currentAttributes.lastModifiedTime() != lastModifiedTime
                    || currentAttributes.isDirectory || currentAttributes.isSymbolicLink
                    || attributes.fileKey() != null
                    && attributes.fileKey() != currentAttributes.fileKey()) {
                    throw IOException("The source changed while creating the archive: $file")
                }
            }
        } else {
            listener?.invoke(attributes.size())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        sevenZArchive?.close() ?: archive!!.close()
    }
}
