/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Build
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.libarchive.Archive
import java.io.Closeable
import java.io.IOException

class ArchiveWriter @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    encryptFileNames: Boolean = false
) : Closeable {
    private val archive: WriteArchive?
    private val encryptedSevenZArchive: EncryptedSevenZArchive?

    init {
        if (format == Archive.FORMAT_7ZIP && password != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                throw IOException(UnsupportedOperationException("Encrypted 7Z archive"))
            }
            archive = null
            encryptedSevenZArchive = EncryptedSevenZArchive(channel, password, encryptFileNames)
        } else {
            archive = WriteArchive(channel, format, filter, password)
            encryptedSevenZArchive = null
        }
    }

    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
        val encryptedSevenZArchive = encryptedSevenZArchive
        if (encryptedSevenZArchive != null) {
            encryptedSevenZArchive.write(file, entryName, intervalMillis, listener)
            return
        }
        val archive = archive!!
        val name = entryName.toString()
        val lastModifiedTime = file.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS)
        val lastAccessTime = null
        val creationTime = null
        val attributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val type = when {
            attributes is PosixFileAttributes -> attributes.type()
            attributes.isDirectory -> PosixFileType.DIRECTORY
            attributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val size = file.size(LinkOption.NOFOLLOW_LINKS)
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
        archive.Entry(
            name, lastModifiedTime, lastAccessTime, creationTime, type, size, owner, group, mode,
            symbolicLinkTarget
        ).use { archive.writeEntry(it) }
        if (type == PosixFileType.REGULAR_FILE) {
            file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                inputStream.copyTo(archive.newDataOutputStream(), intervalMillis, listener)
            }
        } else {
            listener?.invoke(attributes.size())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        archive?.close()
        encryptedSevenZArchive?.close()
    }

    companion object {
        /**
         * Whether the channel this writer is given also has to be readable. Hiding the entry names
         * of a 7Z archive means reading its finished header back out of the file to encrypt it,
         * which a write-only channel cannot do.
         */
        fun needsReadableChannel(
            format: Int,
            password: String?,
            encryptFileNames: Boolean
        ): Boolean = format == Archive.FORMAT_7ZIP && password != null && encryptFileNames
    }
}
