/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.FileChannels
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.archive.ARCHIVE_ERRNO_MISC
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException

/** Uses the official 7-Zip handler for every 7z archive, including solid and encrypted archives. */
internal class SevenZArchiveReader private constructor(
    private val channel: SeekableByteChannel,
    private val owner: Closeable,
    private val passwords: List<String>
) : Closeable {
    private var archive: NativeSevenZip? = null
    private var nativeEntries: Array<NativeSevenZip.Entry>? = null
    private var currentPassword: String? = null
    private var passwordVerified = false
    private var inputStream: EntryInputStream? = null
    private var closed = false

    private fun open(password: String?): NativeSevenZip {
        archive?.close()
        archive = null
        nativeEntries = null
        passwordVerified = false
        checkInterrupted()
        channel.position(0)
        val result = NativeSevenZip(
            channel, password,
            (Runtime.getRuntime().maxMemory() / 3).coerceIn(16L * 1024 * 1024, 128L * 1024 * 1024)
        )
        var successful = false
        try {
            nativeEntries = result.readEntries()
            archive = result
            currentPassword = password
            successful = true
            return result
        } finally {
            if (!successful) {
                result.close()
            }
        }
    }

    private inline fun <T> withPassword(block: (NativeSevenZip) -> T): T {
        ensureOpen()
        val candidates = buildList {
            if (archive != null) {
                add(currentPassword)
            }
            addAll(passwords.asReversed())
            add(null)
        }.distinct()
        var lastFailure: ArchiveException? = null
        for (password in candidates) {
            try {
                checkInterrupted()
                return block(archive?.takeIf { currentPassword == password } ?: open(password))
            } catch (e: ArchiveException) {
                if (!e.isPasswordFailure()) {
                    throw e
                }
                lastFailure = e
            }
        }
        throw passwordException(lastFailure)
    }

    private fun entries(): Array<NativeSevenZip.Entry> =
        nativeEntries ?: withPassword { nativeEntries!! }

    fun readEntries(): List<ReadArchive.Entry> {
        val entries = entries()
        val symbolicLinkEntries = entries.filter { it.fileType == PosixFileType.SYMBOLIC_LINK }
        val symbolicLinkTargets = mutableMapOf<Int, String>()
        if (symbolicLinkEntries.isNotEmpty()) {
            // A Unix 7z link stores its target as entry data. Decode all links in one pass and
            // bound each target, without retaining the plaintext of regular files.
            var output: ByteArrayOutputStream? = null
            extractNative(symbolicLinkEntries, object : NativeSevenZip.ExtractCallback {
                override fun openOutput(index: Int): OutputStream {
                    output = ByteArrayOutputStream()
                    return object : OutputStream() {
                        override fun write(value: Int) {
                            if (output!!.size() >= MAX_SYMBOLIC_LINK_SIZE) {
                                throw IOException("The archived symbolic link is too long")
                            }
                            output!!.write(value)
                        }

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (length > MAX_SYMBOLIC_LINK_SIZE - output!!.size()) {
                                throw IOException("The archived symbolic link is too long")
                            }
                            output!!.write(bytes, offset, length)
                        }
                    }
                }

                override fun onResult(index: Int, failure: IOException?) {
                    if (failure != null) {
                        throw failure
                    }
                    symbolicLinkTargets[index] = output!!.toByteArray().toString(Charsets.UTF_8)
                    output = null
                }
            })
        }
        return entries.map { entry ->
            ReadArchive.Entry(
                entry.name ?: throw IOException("The 7z entry has no name"), entry.encrypted,
                entry.lastModifiedTime.toFileTime(), entry.lastAccessTime.toFileTime(),
                entry.creationTime.toFileTime(), entry.fileType, entry.size, null, null,
                entry.fileMode, symbolicLinkTargets[entry.index]
            )
        }
    }

    /** Keeps a solid extraction in archive order regardless of file-tree traversal order. */
    fun orderedEntries(entries: List<ReadArchive.Entry>): List<ReadArchive.Entry> {
        val requested = entries.associateBy { it.name }
        val seen = mutableSetOf<String>()
        val result = entries().mapNotNull { entry ->
            if (seen.add(entry.name)) requested[entry.name] else null
        }
        if (result.size != requested.size) {
            throw IOException("An entry is missing from the 7z archive")
        }
        return result
    }

    interface ExtractionCallback {
        /** The caller closes each output in [onResult]; returning null skips the entry. */
        fun openOutput(entry: ReadArchive.Entry): OutputStream?

        /** A null failure means that the engine has checked this entry's CRC. */
        fun onResult(entry: ReadArchive.Entry, failure: IOException?)
    }

    fun extract(entries: List<ReadArchive.Entry>, callback: ExtractionCallback) {
        val requested = entries.associateBy { it.name }
        val selected = entries().distinctBy { it.name }.filter { requested.containsKey(it.name) }
        if (selected.size != requested.size) {
            throw IOException("An entry is missing from the 7z archive")
        }
        val byIndex = selected.associate { it.index to requested.getValue(it.name) }
        extractNative(selected, object : NativeSevenZip.ExtractCallback {
            override fun openOutput(index: Int): OutputStream? =
                callback.openOutput(byIndex.getValue(index))

            override fun onResult(index: Int, failure: IOException?) =
                callback.onResult(byIndex.getValue(index), failure)
        })
    }

    private fun prepareExtraction(entries: List<NativeSevenZip.Entry>): NativeSevenZip {
        ensureOpen()
        if (inputStream != null) {
            throw IOException("A 7z entry stream is already open")
        }
        val encryptedEntry = entries.firstOrNull { it.encrypted && it.size > 0 }
        if (encryptedEntry != null) {
            if (passwords.isEmpty()) {
                throw passwordException(null)
            }
            if (passwords.distinct().size > 1 && !passwordVerified) {
                // AES has no authentication tag. When several passwords are remembered, test
                // one encrypted entry through its CRC before opening any destination streams.
                withPassword { archive ->
                    archive.extract(intArrayOf(encryptedEntry.index),
                        object : NativeSevenZip.ExtractCallback {
                            override fun openOutput(index: Int): OutputStream? = null

                            override fun onResult(index: Int, failure: IOException?) {
                                if (failure != null) {
                                    throw failure
                                }
                            }
                        }, true)
                }
                passwordVerified = true
            }
        }
        return archive ?: withPassword { it }
    }

    private fun extractNative(
        entries: List<NativeSevenZip.Entry>,
        callback: NativeSevenZip.ExtractCallback
    ) {
        if (entries.isEmpty()) {
            return
        }
        val archive = prepareExtraction(entries)
        checkInterrupted()
        archive.extract(entries.map { it.index }.sorted().toIntArray(), callback, false)
    }

    fun newInputStream(name: String): InputStream? {
        val entry = entries().firstOrNull { it.name == name } ?: return null
        val archive = prepareExtraction(listOf(entry))
        return EntryInputStream(archive, entry.index).also { inputStream = it }
    }

    // Keep the existing error contract: ArchiveFileSystem attaches the ArchivePath needed by
    // the password dialog, including failures delivered later through an entry stream.
    private fun passwordException(cause: IOException?): ArchiveException =
        ArchiveException(
            ARCHIVE_ERRNO_MISC, if (passwords.isEmpty()) "Passphrase required for this entry"
            else "Incorrect passphrase", cause
        )

    private fun ensureOpen() {
        if (closed) {
            throw IOException("The 7z archive is closed")
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            try {
                inputStream?.close()
            } finally {
                archive?.close()
                archive = null
                nativeEntries = null
            }
        } finally {
            owner.close()
        }
    }

    /** Bridges push-based extraction to a bounded stream; plaintext never needs a cache file. */
    private inner class EntryInputStream(archive: NativeSevenZip, index: Int) : InputStream() {
        private val input = PipedInputStream(64 * 1024)
        private val output = PipedOutputStream(input)
        @Volatile private var failure: IOException? = null
        private var streamClosed = false
        private val worker = Thread({
            try {
                archive.extract(intArrayOf(index), object : NativeSevenZip.ExtractCallback {
                    override fun openOutput(index: Int): OutputStream = output

                    override fun onResult(index: Int, failure: IOException?) {
                        if (failure != null) {
                            throw failure
                        }
                    }
                }, false)
            } catch (e: Throwable) {
                failure = e as? IOException ?: IOException("Unable to extract the 7z entry", e)
            } finally {
                output.close()
            }
        }, "7z-entry-reader").apply {
            isDaemon = true
            start()
        }

        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (streamClosed) {
                throw IOException("The 7z entry stream is closed")
            }
            checkInterrupted()
            val count = try {
                input.read(bytes, offset, length)
            } catch (e: IOException) {
                throw failure ?: e
            }
            if (count < 0) {
                failure?.let { throw it }
            }
            return count
        }

        override fun available(): Int = input.available()

        override fun close() {
            if (streamClosed) {
                return
            }
            streamClosed = true
            input.close()
            worker.interrupt()
            // Close must not free the native handler while the producer still uses it. Keep
            // cancellation on the calling thread while allowing the worker to unwind safely.
            var interrupted = false
            while (worker.isAlive) {
                try {
                    worker.join()
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
            if (inputStream === this) {
                inputStream = null
            }
        }
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        private const val MAX_SYMBOLIC_LINK_SIZE = 64 * 1024

        fun openOrNull(file: Path, passwords: List<String>): SevenZArchiveReader? {
            val providerChannel = try {
                file.newArchiveByteChannel()
            } catch (e: UnsupportedOperationException) {
                null
            } catch (e: IOException) {
                e.rethrowIfInterrupted()
                null
            }
            if (providerChannel != null) {
                var successful = false
                try {
                    val buffer = ByteBuffer.allocate(SIGNATURE.size)
                    while (buffer.hasRemaining()) {
                        checkInterrupted()
                        val count = providerChannel.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count == 0) {
                            throw IOException("The archive channel made no read progress")
                        }
                    }
                    if (buffer.position() != SIGNATURE.size
                        || !buffer.array().contentEquals(SIGNATURE)) {
                        return null
                    }
                    // Check both capabilities before committing to this provider channel.
                    providerChannel.size()
                    providerChannel.position(0)
                    successful = true
                    return SevenZArchiveReader(providerChannel, providerChannel, passwords)
                } catch (e: UnsupportedOperationException) {
                    // Some providers expose a channel while rejecting random access.
                } catch (e: IOException) {
                    e.rethrowIfInterrupted()
                    // Reopen using the provider's sequential input below.
                } finally {
                    if (!successful) {
                        providerChannel.close()
                    }
                }
            }
            file.newArchiveInputStream().use { input ->
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
                // Only the original archive is cached when the source cannot seek. Encrypted
                // payloads and headers remain encrypted, including while asking for a password.
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
                    val channel = FileChannels.from(randomAccess.channel)
                    successful = true
                    return SevenZArchiveReader(channel, owner, passwords)
                } finally {
                    if (!successful) {
                        temporary.delete()
                    }
                }
            }
        }

        private fun ArchiveException.isPasswordFailure(): Boolean =
            code == ARCHIVE_ERRNO_MISC && (message == "Incorrect passphrase"
                || message == "Passphrase required for this entry")

        private fun IOException.rethrowIfInterrupted() {
            if (this is InterruptedIOException) {
                throw this
            }
            if (this is ClosedByInterruptException) {
                throw InterruptedIOException().apply { initCause(this@rethrowIfInterrupted) }
            }
        }

        private fun Long.toFileTime(): FileTime? =
            if (this != Long.MIN_VALUE) FileTime.fromMillis(this) else null

        private val NativeSevenZip.Entry.fileType: PosixFileType
            get() = if (hasAttributes && attributes and 0x8000 != 0) {
                PosixFileType.fromMode(attributes ushr 16)
            } else if (directory) {
                PosixFileType.DIRECTORY
            } else {
                PosixFileType.REGULAR_FILE
            }

        private val NativeSevenZip.Entry.fileMode
            get() = if (hasAttributes && attributes and 0x8000 != 0) {
                PosixFileMode.fromInt(attributes ushr 16)
            } else if (directory) {
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
