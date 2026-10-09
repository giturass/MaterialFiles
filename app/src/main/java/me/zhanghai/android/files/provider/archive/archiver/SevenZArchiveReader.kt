/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Build
import androidx.annotation.RequiresApi
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.archive.ARCHIVE_ERRNO_MISC
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.libarchive.ArchiveException
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.tukaani.xz.CorruptedInputException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Supplies the encrypted 7z support missing from libarchive, keeping other formats unchanged. */
@RequiresApi(Build.VERSION_CODES.N)
internal class SevenZArchiveReader private constructor(
    private val channel: SeekableByteChannel,
    private val owner: Closeable,
    private val passwords: List<String>
) : Closeable {
    private var archive: SevenZFile? = null

    private fun open(password: String?): SevenZFile {
        archive?.close()
        archive = null
        channel.position(0)
        val passwordBytes = password?.toByteArray(Charsets.UTF_16LE)
        val result = try {
            SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setPassword(passwordBytes)
                .setMaxMemoryLimitKiB(
                    (Runtime.getRuntime().maxMemory() / 3 / 1024)
                        .coerceIn(16 * 1024, 128 * 1024).toInt()
                )
                .get()
        } finally {
            passwordBytes?.fill(0)
        }
        archive = result
        return result
    }

    private inline fun <T> withPassword(block: (SevenZFile, String?) -> T): T {
        var lastFailure: IOException? = null
        for (password in passwords.asReversed().distinct() + listOf(null)) {
            try {
                checkInterrupted()
                return block(open(password), password)
            } catch (e: IOException) {
                if (!e.isPasswordFailure()) {
                    throw e
                }
                lastFailure = e
            }
        }
        throw passwordException(lastFailure)
    }

    fun readEntries(): List<ReadArchive.Entry> = withPassword { archive, _ ->
        // getEntries() reads metadata without decompressing every solid file. This path is
        // used when libarchive reports an encrypted header, so every name is protected.
        archive.entries.map { entry ->
            val type = entry.fileType
            val symbolicLinkTarget = if (type == PosixFileType.SYMBOLIC_LINK) {
                archive.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (output.size() + count > 64 * 1024) {
                            throw IOException("The archived symbolic link is too long")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            } else {
                null
            }
            ReadArchive.Entry(
                entry.name ?: throw IOException("The 7z entry has no name"), true,
                if (entry.hasLastModifiedDate) FileTime.fromMillis(entry.lastModifiedDate.time)
                else null,
                if (entry.hasAccessDate) FileTime.fromMillis(entry.accessDate.time) else null,
                if (entry.hasCreationDate) FileTime.fromMillis(entry.creationDate.time) else null,
                type, entry.size, null, null, entry.fileMode, symbolicLinkTarget
            )
        }
    }

    fun newInputStream(name: String): InputStream? = withPassword { archive, password ->
        var currentArchive = archive
        var entry = archive.entries.firstOrNull { it.name == name } ?: return@withPassword null
        if (passwords.distinct().size > 1 && entry.size > 0) {
            // AES itself has no authentication tag; a Copy-coded stream can accept any
            // password until its CRC is checked. Validate before selecting among passwords,
            // otherwise a wrong recent password can hide a correct earlier one. Discard the
            // plaintext through a fixed-size buffer, then reopen with the verified password.
            archive.getInputStream(entry).use { verification ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkInterrupted()
                    if (verification.read(buffer) < 0) {
                        break
                    }
                }
            }
            currentArchive = open(password)
            entry = currentArchive.entries.firstOrNull { it.name == name }
                ?: throw IOException("The 7z archive changed while verifying its password")
        }
        val input = PushbackInputStream(currentArchive.getInputStream(entry), 1)
        // Force lazy AES/LZMA initialization while passwords can still be retried.
        val first = input.read()
        if (first >= 0) {
            input.unread(first)
        }
        object : FilterInputStream(input) {
            override fun read(): Int = translatePasswordFailure { `in`.read() }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                translatePasswordFailure { `in`.read(bytes, offset, length) }

            override fun skip(count: Long): Long = translatePasswordFailure { `in`.skip(count) }

            private inline fun <T> translatePasswordFailure(block: () -> T): T = try {
                checkInterrupted()
                block()
            } catch (e: IOException) {
                if (e.isPasswordFailure()) {
                    throw passwordException(e)
                }
                throw e
            }
        }
    }

    // Match libarchive's password errors so the file system and stream wrapper attach the
    // ArchivePath needed by the password dialog, rather than the archive's physical file path.
    private fun passwordException(cause: IOException?): ArchiveException =
        ArchiveException(
            ARCHIVE_ERRNO_MISC, if (passwords.isEmpty()) "Passphrase required for this entry"
            else "Incorrect passphrase", cause
        )

    override fun close() {
        try {
            archive?.close()
        } finally {
            owner.close()
        }
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

        fun openOrNull(file: Path, passwords: List<String>): SevenZArchiveReader? {
            val providerChannel = try {
                file.newByteChannel()
            } catch (e: UnsupportedOperationException) {
                null
            } catch (e: IOException) {
                if (e is InterruptedIOException) {
                    throw e
                }
                null
            }
            if (providerChannel != null) {
                var successful = false
                try {
                    val buffer = ByteBuffer.allocate(SIGNATURE.size)
                    while (buffer.hasRemaining()) {
                        checkInterrupted()
                        if (providerChannel.read(buffer) <= 0) {
                            break
                        }
                    }
                    if (buffer.position() != SIGNATURE.size
                        || !buffer.array().contentEquals(SIGNATURE)) {
                        return null
                    }
                    providerChannel.position(0)
                    successful = true
                    return SevenZArchiveReader(
                        CommonsSeekableByteChannel(providerChannel), providerChannel, passwords
                    )
                } catch (e: UnsupportedOperationException) {
                    // A provider can return a channel while still rejecting seek operations.
                } catch (e: IOException) {
                    if (e is InterruptedIOException) {
                        throw e
                    }
                    // Reopen through the provider's stream API below.
                } finally {
                    if (!successful) {
                        providerChannel.close()
                    }
                }
            }
            // Some document providers expose only streams. Spool the already encrypted archive
            // in private app cache so seeking never requires holding the whole archive in RAM.
            file.newInputStream().use { input ->
                val signature = ByteArray(SIGNATURE.size)
                var position = 0
                while (position < signature.size) {
                    checkInterrupted()
                    val count = input.read(signature, position, signature.size - position)
                    if (count <= 0) {
                        return null
                    }
                    position += count
                }
                if (!signature.contentEquals(SIGNATURE)) {
                    return null
                }
                val context = if (isRunningAsRoot) rootContext else application
                val temporary = File.createTempFile("7z-read-", ".tmp", context.cacheDir)
                var successful = false
                try {
                    temporary.outputStream().use { output ->
                        output.write(signature)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkInterrupted()
                            val count = input.read(buffer)
                            if (count < 0) {
                                break
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    val randomAccess = RandomAccessFile(temporary, "r")
                    val owner = Closeable {
                        try {
                            randomAccess.close()
                        } finally {
                            temporary.delete()
                        }
                    }
                    // Do not let a failed password attempt close the shared underlying file.
                    val borrowedChannel = object : SeekableByteChannel by randomAccess.channel {
                        override fun close() = Unit
                    }
                    successful = true
                    return SevenZArchiveReader(borrowedChannel, owner, passwords)
                } finally {
                    if (!successful) {
                        temporary.delete()
                    }
                }
            }
        }

        private fun IOException.isPasswordFailure(): Boolean {
            if (this is InterruptedIOException || this is MemoryLimitException) {
                return false
            }
            return this is PasswordRequiredException || this is CorruptedInputException
                || this is EOFException || message?.contains("Checksum verification failed") == true
                || message?.contains("CRC mismatch") == true
        }

        private val SevenZArchiveEntry.fileType: PosixFileType
            get() = if (hasWindowsAttributes && windowsAttributes and 0x8000 != 0) {
                PosixFileType.fromMode(windowsAttributes ushr 16)
            } else if (isDirectory) {
                PosixFileType.DIRECTORY
            } else {
                PosixFileType.REGULAR_FILE
            }

        private val SevenZArchiveEntry.fileMode
            get() = if (hasWindowsAttributes && windowsAttributes and 0x8000 != 0) {
                PosixFileMode.fromInt(windowsAttributes ushr 16)
            } else if (isDirectory) {
                PosixFileMode.DIRECTORY_DEFAULT
            } else {
                PosixFileMode.FILE_DEFAULT
            }

        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException()
            }
        }
    }
}
