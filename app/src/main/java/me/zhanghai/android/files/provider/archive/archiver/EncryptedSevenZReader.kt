/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Build
import androidx.annotation.RequiresApi
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.compat.toJavaSeekableByteChannel
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.libarchive.ArchiveException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads 7z archives whose header is encrypted, i.e. the ones that hide entry names as well as
 * entry content.
 *
 * libarchive can decrypt 7z content but not a 7z header: it fails the whole archive with
 * [ENCRYPTED_HEADER_MESSAGE] before a single entry can be listed, even when the passphrase is
 * known. Commons Compress does support it, and is already a dependency because
 * [EncryptedSevenZArchive] writes these archives with it.
 *
 * Password errors are reported as the [ArchiveException]s libarchive would have raised, so that
 * they end up prompting for a password just like every other encrypted archive.
 */
@RequiresApi(Build.VERSION_CODES.N)
object EncryptedSevenZReader {
    /**
     * Whether [exception] is libarchive giving up on a 7z archive because its header is encrypted.
     *
     * Matched on keywords rather than on the exact sentence, so that a libarchive that reworded it
     * doesn't silently turn every such archive into an unexplained failure.
     */
    fun isEncryptedHeaderException(exception: ArchiveException): Boolean {
        val message = exception.message ?: return false
        return message.contains("header", ignoreCase = true) &&
            message.contains("encrypted", ignoreCase = true)
    }

    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        charset: Charset
    ): List<ReadArchive.Entry> =
        open(file, passwords).use { archive ->
            buildList {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    this += archive.toReadArchiveEntry(entry, charset)
                }
            }
        }

    @Throws(IOException::class)
    fun newInputStream(
        file: Path,
        passwords: List<String>,
        entry: ReadArchive.Entry
    ): InputStream? {
        if (passwords.isEmpty()) {
            throw ArchiveException(ARCHIVE_ERRNO_MISC, PASSPHRASE_REQUIRED_MESSAGE)
        }
        // Reading a whole archive out means one call per entry, and reopening for each of them
        // would parse the header and decompress everything before it all over again.
        SequentialArchiveCache.take(file, entry.name)?.let { cached ->
            try {
                return ProbedInputStream(file, cached, cached.readProbe())
            } catch (e: IOException) {
                e.printStackTrace()
                cached.closeSafe()
            }
        }
        var lastException: IOException? = null
        for (password in passwords) {
            val archive = try {
                open(file, password)
            } catch (e: IOException) {
                lastException = e
                continue
            }
            var successful = false
            try {
                var found = false
                while (true) {
                    val currentEntry = archive.nextEntry ?: break
                    if (currentEntry.name == entry.name) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    // No other password is going to make an entry that isn't there appear.
                    return null
                }
                val probe = archive.readProbe()
                successful = true
                return ProbedInputStream(file, archive, probe)
            } catch (e: IOException) {
                lastException = e
            } finally {
                if (!successful) {
                    archive.closeSafe()
                }
            }
        }
        throw ArchiveException(ARCHIVE_ERRNO_MISC, INCORRECT_PASSPHRASE_MESSAGE, lastException)
    }

    /**
     * Opens [file] with the first password that works, and reports a missing or wrong password the
     * same way libarchive does so that the user gets prompted for one.
     */
    @Throws(IOException::class)
    private fun open(file: Path, passwords: List<String>): SevenZFile {
        if (passwords.isEmpty()) {
            throw ArchiveException(ARCHIVE_ERRNO_MISC, PASSPHRASE_REQUIRED_MESSAGE)
        }
        var lastException: IOException? = null
        for (password in passwords) {
            try {
                return open(file, password)
            } catch (e: PasswordRequiredException) {
                lastException = e
            } catch (e: IOException) {
                // A wrong password fails the header checksum, which is indistinguishable from a
                // corrupt archive here, so keep trying the remaining passwords.
                lastException = e
            }
        }
        throw ArchiveException(ARCHIVE_ERRNO_MISC, INCORRECT_PASSPHRASE_MESSAGE, lastException)
    }

    @Throws(IOException::class)
    private fun open(file: Path, password: String): SevenZFile {
        val channel = file.newByteChannel()
        var successful = false
        try {
            // SevenZFile.close() closes the channel it was given.
            val archive = SevenZFile(channel.toJavaSeekableByteChannel(), password.toCharArray())
            successful = true
            return archive
        } finally {
            if (!successful) {
                channel.closeSafe()
            }
        }
    }

    /**
     * Reads the start of the current entry, both to prove the password decrypts it and to keep
     * those bytes for the caller. An archive that only has its content encrypted has a header that
     * parses with any password at all, so decrypting something is the only way to tell a wrong
     * password from a right one before handing a stream back.
     */
    @Throws(IOException::class)
    private fun SevenZFile.readProbe(): ByteArray {
        val buffer = ByteArray(PROBE_SIZE)
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = read(buffer, offset, buffer.size - offset)
            if (bytesRead < 0) {
                break
            }
            offset += bytesRead
        }
        return buffer.copyOf(offset)
    }

    private fun SevenZFile.closeSafe() {
        try {
            close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun SeekableByteChannel.closeSafe() {
        try {
            close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun SevenZFile.toReadArchiveEntry(
        entry: SevenZArchiveEntry,
        charset: Charset
    ): ReadArchive.Entry {
        val windowsAttributes = if (entry.hasWindowsAttributes) entry.windowsAttributes else 0
        val unixMode = if (windowsAttributes and FILE_ATTRIBUTE_UNIX_EXTENSION != 0) {
            windowsAttributes ushr 16
        } else {
            0
        }
        val unixType = PosixFileType.fromMode(unixMode)
        val hasUnixMode = unixType != PosixFileType.UNKNOWN
        val type = if (hasUnixMode) {
            unixType
        } else {
            if (entry.isDirectory) PosixFileType.DIRECTORY else PosixFileType.REGULAR_FILE
        }
        val mode = if (hasUnixMode) {
            PosixFileMode.fromInt(unixMode)
        } else {
            when (type) {
                PosixFileType.DIRECTORY -> PosixFileMode.DIRECTORY_DEFAULT
                PosixFileType.SYMBOLIC_LINK -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
                else -> PosixFileMode.FILE_DEFAULT
            }
        }
        val lastModifiedTime = if (entry.hasLastModifiedDate) {
            FileTime.fromMillis(entry.lastModifiedDate.time)
        } else {
            null
        }
        val symbolicLinkTarget = if (type == PosixFileType.SYMBOLIC_LINK && entry.hasStream()) {
            readCurrentEntry(entry).toString(charset)
        } else {
            null
        }
        return ReadArchive.Entry(
            entry.name, true, lastModifiedTime, null, null, type, entry.size, null, null, mode,
            symbolicLinkTarget
        )
    }

    @Throws(IOException::class)
    private fun SevenZFile.readCurrentEntry(entry: SevenZArchiveEntry): ByteArray {
        val size = entry.size
        if (size <= 0 || size > MAX_SYMBOLIC_LINK_TARGET_SIZE) {
            return ByteArray(0)
        }
        val bytes = ByteArrayOutputStream(size.toInt())
        val buffer = ByteArray(size.toInt())
        while (true) {
            val bytesRead = read(buffer)
            if (bytesRead == -1) {
                break
            }
            bytes.write(buffer, 0, bytesRead)
        }
        return bytes.toByteArray()
    }

    /** The bytes already read to check the password, followed by the rest of the entry. */
    private class ProbedInputStream(
        private val file: Path,
        private val archive: SevenZFile,
        private val probe: ByteArray
    ) : InputStream() {
        private var probeOffset = 0
        /**
         * Whether the entry was read to its end. Only then is the archive positioned exactly at the
         * next entry, which is what makes it worth keeping around for the next call.
         */
        private var isDrained = false
        private var isClosed = false

        @Throws(IOException::class)
        override fun read(): Int {
            if (probeOffset < probe.size) {
                return probe[probeOffset++].toInt() and 0xFF
            }
            return archive.read().also { if (it < 0) isDrained = true }
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            if (probeOffset >= probe.size) {
                return archive.read(b, off, len).also { if (it < 0) isDrained = true }
            }
            val length = minOf(len, probe.size - probeOffset)
            probe.copyInto(b, off, probeOffset, probeOffset + length)
            probeOffset += length
            return length
        }

        @Throws(IOException::class)
        override fun close() {
            if (isClosed) {
                return
            }
            isClosed = true
            if (isDrained && SequentialArchiveCache.offer(file, archive)) {
                return
            }
            archive.close()
        }
    }

    /**
     * Holds on to one archive that has been read to the end of an entry, so that reading the next
     * one continues rather than starting over. A 7z archive is solid, so starting over means
     * decompressing everything in front of the entry again.
     *
     * A single slot, because the only access pattern this helps is one caller walking one archive.
     * Anything else simply misses and opens its own.
     */
    private object SequentialArchiveCache {
        private var file: Path? = null
        private var archive: SevenZFile? = null
        /** The name of the entry the held archive is already positioned on. */
        private var heldEntryName: String? = null

        /** Takes the held archive when it is already positioned on [entryName]. */
        @Synchronized
        fun take(file: Path, entryName: String): SevenZFile? {
            if (this.file != file) {
                // Another archive is being read now, so the held one is not going to be resumed.
                closeHeldLocked()
                return null
            }
            if (heldEntryName != entryName) {
                closeHeldLocked()
                return null
            }
            val archive = archive
            clearLocked()
            return archive
        }

        /**
         * Hands an archive over to be kept for the next call, or returns false when there is nothing
         * left in it to keep it for, in which case the caller still owns it.
         */
        @Synchronized
        fun offer(file: Path, archive: SevenZFile): Boolean {
            // Stepping onto the next entry here is what lets take() hand it straight back without
            // having to step, and tells us whether there is a next entry at all.
            val nextEntry = try {
                archive.nextEntry
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            } ?: return false
            closeHeldLocked()
            this.file = file
            this.archive = archive
            heldEntryName = nextEntry.name
            return true
        }

        private fun clearLocked() {
            file = null
            archive = null
            heldEntryName = null
        }

        private fun closeHeldLocked() {
            archive?.closeSafe()
            clearLocked()
        }
    }

    // See also libarchive/archive_read_support_format_7zip.c .
    private const val ENCRYPTED_HEADER_MESSAGE =
        "The archive header is encrypted, but currently not supported"
    // See also ArchiveExceptionExtensions.kt, which turns these into a password prompt.
    private const val PASSPHRASE_REQUIRED_MESSAGE = "Passphrase required for this entry"
    private const val INCORRECT_PASSPHRASE_MESSAGE = "Incorrect passphrase"
    // See also libarchive/archive_platform.h .
    private const val ARCHIVE_ERRNO_MISC = -1
    // See also EncryptedSevenZArchive .
    private const val FILE_ATTRIBUTE_UNIX_EXTENSION = 0x8000
    private const val MAX_SYMBOLIC_LINK_TARGET_SIZE = 4096L
    /** Enough of an entry to fail a wrong password on, and small enough not to be felt. */
    private const val PROBE_SIZE = 4096
}
