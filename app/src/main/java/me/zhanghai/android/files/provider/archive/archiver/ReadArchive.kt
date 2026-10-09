/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.charset.Charset
import java.time.Instant
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException

class ReadArchive : Closeable {
    private val archive = Archive.readNew()

    @Throws(ArchiveException::class)
    constructor(inputStream: InputStream, passwords: List<String>) {
        var successful = false
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readSupportFormatRaw(archive)
            Archive.readSetCallbackData(archive, null)
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            Archive.readSetReadCallback<Any?>(archive) { _, _ ->
                buffer.clear()
                val bytesRead = try {
                    inputStream.read(buffer.array())
                } catch (e: IOException) {
                    throw e.toArchiveException("InputStream.read")
                }
                if (bytesRead != -1) {
                    buffer.limit(bytesRead)
                    buffer
                } else {
                    null
                }
            }
            Archive.readSetSkipCallback<Any?>(archive) { _, _, request ->
                try {
                    inputStream.skip(request)
                } catch (e: IOException) {
                    throw e.toArchiveException("InputStream.skip")
                }
            }
            for (password in passwords) {
                Archive.readAddPassphrase(archive, password.toByteArray())
            }
            Archive.readOpen1(archive)
            successful = true
        } finally {
            if (!successful) {
                close()
            }
        }
    }

    @Throws(ArchiveException::class)
    constructor(channel: SeekableByteChannel, passwords: List<String>) {
        var successful = false
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readSupportFormatRaw(archive)
            Archive.readSetCallbackData(archive, null)
            val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
            Archive.readSetReadCallback<Any?>(archive) { _, _ ->
                buffer.clear()
                val bytesRead = try {
                    channel.read(buffer)
                } catch (e: IOException) {
                    throw e.toArchiveException("SeekableByteChannel.read")
                }
                if (bytesRead != -1) {
                    buffer.flip()
                    buffer
                } else {
                    null
                }
            }
            Archive.readSetSkipCallback<Any?>(archive) { _, _, request ->
                try {
                    channel.position(channel.position() + request)
                } catch (e: IOException) {
                    throw e.toArchiveException("SeekableByteChannel.position")
                }
                request
            }
            Archive.readSetSeekCallback<Any?>(archive) { _, _, offset, whence ->
                val newPosition: Long
                try {
                    newPosition = when (whence) {
                        OsConstants.SEEK_SET -> offset
                        OsConstants.SEEK_CUR -> channel.position() + offset
                        OsConstants.SEEK_END -> channel.size() + offset
                        else -> throw ArchiveException(
                            Archive.ERRNO_FATAL,
                            "Unknown whence $whence"
                        )
                    }
                    channel.position(newPosition)
                } catch (e: IOException) {
                    throw e.toArchiveException("SeekableByteChannel.position")
                }
                newPosition
            }
            for (password in passwords) {
                Archive.readAddPassphrase(archive, password.toByteArray())
            }
            Archive.readOpen1(archive)
            successful = true
        } finally {
            if (!successful) {
                close()
            }
        }
    }

    private fun IOException.toArchiveException(message: String): ArchiveException =
        when (this) {
            is InterruptedIOException, is ClosedByInterruptException ->
                ArchiveException(OsConstants.EINTR, message, this)
            else -> ArchiveException(Archive.ERRNO_FATAL, message, this)
        }

    @JvmOverloads
    @Throws(ArchiveException::class)
    fun readEntry(charset: Charset, rawEntryName: String? = null): Entry? {
        val entry = Archive.readNextHeader(archive)
        if (entry == 0L) {
            return null
        }
        val isRaw = Archive.format(archive) == Archive.FORMAT_RAW
        if (isRaw && (0 until Archive.filterCount(archive)).none {
                Archive.filterCode(archive, it) != Archive.FILTER_NONE
            }) {
            // Raw is a fallback for single-file gzip/bzip2/xz streams, not for arbitrary data.
            throw ArchiveException(Archive.ERRNO_FATAL, "Unrecognized archive format")
        }
        val storedName =
            getEntryString(ArchiveEntry.pathnameUtf8(entry), ArchiveEntry.pathname(entry), charset)
                ?: throw ArchiveException(
                    Archive.ERRNO_FATAL, "pathname == null && pathnameUtf8 == null"
                )
        val name = if (isRaw && storedName == "data" && !rawEntryName.isNullOrEmpty()) {
            rawEntryName
        } else {
            storedName
        }
        val isEncrypted = ArchiveEntry.isEncrypted(entry)
        val stat = ArchiveEntry.stat(entry)
        val lastModifiedTime = if (ArchiveEntry.mtimeIsSet(entry)) {
            FileTime.from(
                Instant.ofEpochSecond(stat.stMtim.tvSec, stat.stMtim.tvNsec)
            )
        } else {
            null
        }
        val lastAccessTime = if (ArchiveEntry.atimeIsSet(entry)) {
            FileTime.from(
                Instant.ofEpochSecond(stat.stAtim.tvSec, stat.stAtim.tvNsec)
            )
        } else {
            null
        }
        val creationTime = if (ArchiveEntry.birthtimeIsSet(entry)) {
            FileTime.from(
                Instant.ofEpochSecond(
                    ArchiveEntry.birthtime(entry), ArchiveEntry.birthtimeNsec(entry)
                )
            )
        } else {
            null
        }
        val type = PosixFileType.fromMode(stat.stMode)
        val size = stat.stSize
        // TODO: There's no way to know if UID/GID is unset or root.
        val owner = PosixUser(
            stat.stUid, getEntryString(
                ArchiveEntry.unameUtf8(entry), ArchiveEntry.uname(entry), charset
            )?.toByteString()
        )
        val group = PosixGroup(
            stat.stGid, getEntryString(
                ArchiveEntry.gnameUtf8(entry), ArchiveEntry.gname(entry), charset
            )?.toByteString()
        )
        val mode = PosixFileMode.fromInt(stat.stMode)
        val symbolicLinkTarget =
            getEntryString(ArchiveEntry.symlinkUtf8(entry), ArchiveEntry.symlink(entry), charset)
        return Entry(
            name, isEncrypted, lastModifiedTime, lastAccessTime, creationTime, type, size, owner,
            group, mode, symbolicLinkTarget
        )
    }

    private fun getEntryString(stringUtf8: String?, string: ByteArray?, charset: Charset): String? =
        stringUtf8 ?: string?.toString(charset)

    @Throws(ArchiveException::class)
    fun newDataInputStream(): InputStream = DataInputStream()

    @Throws(ArchiveException::class)
    override fun close() {
        Archive.readFree(archive)
    }

    class Entry(
        val name: String,
        val isEncrypted: Boolean,
        val lastModifiedTime: FileTime?,
        val lastAccessTime: FileTime?,
        val creationTime: FileTime?,
        val type: PosixFileType,
        val size: Long,
        val owner: PosixUser?,
        val group: PosixGroup?,
        val mode: Set<PosixFileModeBit>,
        val symbolicLinkTarget: String?
    ) {
        val isDirectory: Boolean
            get() = type == PosixFileType.DIRECTORY

        val isSymbolicLink: Boolean
            get() = type == PosixFileType.SYMBOLIC_LINK
    }

    private inner class DataInputStream : InputStream() {
        private val oneByteBuffer = ByteBuffer.allocateDirect(1)

        @Throws(IOException::class)
        override fun read(): Int {
            oneByteBuffer.clear()
            return if (read(oneByteBuffer) != -1) {
                oneByteBuffer.get(0).toUByte().toInt()
            } else {
                -1
            }
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val buffer = ByteBuffer.wrap(b, off, len)
            if (!buffer.hasRemaining()) {
                return 0
            }
            return read(buffer)
        }

        @Throws(IOException::class)
        private fun read(buffer: ByteBuffer): Int {
            val position = buffer.position()
            Archive.readData(archive, buffer)
            val bytesRead = buffer.position() - position
            return if (bytesRead > 0) bytesRead else -1
        }
    }
}
