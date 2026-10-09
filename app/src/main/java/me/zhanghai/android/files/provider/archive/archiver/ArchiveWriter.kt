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
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.libarchive.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.Date

class ArchiveWriter @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    encryptFileNames: Boolean = false
) : Closeable {
    init {
        require(!encryptFileNames || format == Archive.FORMAT_7ZIP && !password.isNullOrEmpty())
        require(format != Archive.FORMAT_7ZIP || filter == Archive.FILTER_NONE)
        require(format != Archive.FORMAT_7ZIP || password.isNullOrEmpty()
            || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
    }

    private val sevenZArchive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        && format == Archive.FORMAT_7ZIP && !password.isNullOrEmpty()) {
        val passwordChars = password.toCharArray()
        try {
            EncryptedSevenZOutputFile(
                CommonsSeekableByteChannel(channel), passwordChars, encryptFileNames
            )
        } finally {
            passwordChars.fill('\u0000')
        }
    } else {
        null
    }
    private val archive = if (sevenZArchive == null) {
        WriteArchive(channel, format, filter, password)
    } else {
        null
    }

    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
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
        val sevenZArchive = sevenZArchive
        if (sevenZArchive != null) {
            if (type != PosixFileType.REGULAR_FILE && type != PosixFileType.DIRECTORY
                && type != PosixFileType.SYMBOLIC_LINK) {
                throw IOException("7z does not support file type $type: $file")
            }
            val symbolicLinkBytes = symbolicLinkTarget?.toByteArray()
            val entry = SevenZArchiveEntry().apply {
                this.name = name
                isDirectory = type == PosixFileType.DIRECTORY
                setLastModifiedDate(Date(lastModifiedTime.toMillis()))
                this.size = symbolicLinkBytes?.size?.toLong() ?: if (isDirectory) 0 else size
                hasWindowsAttributes = true
                // 7-Zip stores Unix file types and permissions in the high attribute bits.
                windowsAttributes = ((type.mode or mode.toInt()) shl 16) or 0x8000 or
                    if (isDirectory) 0x10 else 0x20
            }
            try {
                sevenZArchive.putArchiveEntry(entry)
                if (type == PosixFileType.REGULAR_FILE) {
                    file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                        inputStream.copyTo(object : OutputStream() {
                            override fun write(value: Int) {
                                write(byteArrayOf(value.toByte()), 0, 1)
                            }

                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                sevenZArchive.write(bytes, offset, length)
                            }
                        }, intervalMillis, listener)
                    }
                } else {
                    if (symbolicLinkBytes != null) {
                        sevenZArchive.write(symbolicLinkBytes, 0, symbolicLinkBytes.size)
                    }
                    listener?.invoke(attributes.size())
                }
                sevenZArchive.closeArchiveEntry()
            } catch (e: Exception) {
                sevenZArchive.abort()
                throw e
            }
            return
        }
        val archive = archive!!
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
        sevenZArchive?.close() ?: archive!!.close()
    }
}
