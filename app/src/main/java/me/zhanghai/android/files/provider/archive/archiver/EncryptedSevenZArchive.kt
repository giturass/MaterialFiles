/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Build
import androidx.annotation.RequiresApi
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.compat.toJavaSeekableByteChannel
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.toInt
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZHeaderEncryptor
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.Date

@RequiresApi(Build.VERSION_CODES.N)
class EncryptedSevenZArchive @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    password: String,
    private val encryptFileNames: Boolean = false
) : Closeable {
    private val javaChannel = channel.toJavaSeekableByteChannel()
    private val passwordChars = password.toCharArray()
    private val archive = SevenZOutputFile(javaChannel, passwordChars)

    private val dataOutputStream = object : OutputStream() {
        override fun write(byte: Int) {
            archive.write(byte)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            archive.write(bytes, offset, length)
        }
    }

    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
        val attributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val type = when {
            attributes is PosixFileAttributes -> attributes.type()
            attributes.isDirectory -> PosixFileType.DIRECTORY
            attributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val mode = (attributes as? PosixFileAttributes)?.mode() ?: when (type) {
            PosixFileType.DIRECTORY -> PosixFileMode.DIRECTORY_DEFAULT
            PosixFileType.SYMBOLIC_LINK -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
            else -> PosixFileMode.FILE_DEFAULT
        }
        val entry = SevenZArchiveEntry().apply {
            name = entryName.toString()
            isDirectory = type == PosixFileType.DIRECTORY
            lastModifiedDate = Date(file.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS).toMillis())
            hasWindowsAttributes = true
            windowsAttributes = ((type.mode or mode.toInt()) shl 16) or
                FILE_ATTRIBUTE_UNIX_EXTENSION or if (isDirectory) {
                    FILE_ATTRIBUTE_DIRECTORY
                } else {
                    FILE_ATTRIBUTE_ARCHIVE
                }
        }
        archive.putArchiveEntry(entry)
        var isListenerNotified = false
        when (type) {
            PosixFileType.REGULAR_FILE -> {
                file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                    inputStream.copyTo(dataOutputStream, intervalMillis, listener)
                }
                isListenerNotified = true
            }
            PosixFileType.SYMBOLIC_LINK ->
                archive.write(file.readSymbolicLinkByteString().borrowBytes())
            PosixFileType.DIRECTORY -> Unit
            else -> throw IOException(UnsupportedOperationException(type.toString()))
        }
        archive.closeArchiveEntry()
        if (!isListenerNotified) {
            listener?.invoke(attributes.size())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (encryptFileNames) {
            // The header only exists once the entries have been finalised, and it has to be
            // rewritten while the channel is still open.
            archive.finish()
            SevenZHeaderEncryptor.encryptHeader(javaChannel, passwordChars)
        }
        archive.close()
    }

    companion object {
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        private const val FILE_ATTRIBUTE_ARCHIVE = 0x20
        private const val FILE_ATTRIBUTE_UNIX_EXTENSION = 0x8000
    }
}
