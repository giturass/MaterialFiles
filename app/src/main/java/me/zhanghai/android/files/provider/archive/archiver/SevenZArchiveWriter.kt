/*
 * Copyright (c) 2026 Material Files contributors
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
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.posixFileType
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.toInt
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Supplies one complete update to 7-Zip so it can compress files into shared solid blocks. */
internal class SevenZArchiveWriter(
    private val channel: SeekableByteChannel,
    private val password: String?,
    private val encryptFileNames: Boolean,
    private val compressionPreset: ArchiveCompressionPreset
) : Closeable {
    private var written = false
    private var closed = false

    fun writeEntries(
        entries: List<Pair<Path, Path>>,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?,
        onEntry: ((Path) -> Unit)?,
        onEntryComplete: ((Path) -> Unit)?
    ) {
        check(!closed && !written) { "7z entries must be written in a single batch" }
        // A failed update must not be finalized by close(), which would hide the original error.
        written = true
        val sources = entries.mapIndexed { index, (file, name) ->
            checkInterrupted()
            Source(index, file, name)
        }
        val completedEntries = BooleanArray(sources.size)
        val totalStreamSize = sources.sumOf { it.entry.size }
        var reportedStreamSize = 0L
        var lastProgressMillis = System.currentTimeMillis()
        var pendingCompletion = -1
        fun completeEntry(index: Int) {
            if (completedEntries[index]) {
                return
            }
            completedEntries[index] = true
            val source = sources[index]
            // Directory metadata consumes no input stream but is included in FileJob's scan.
            if (source.entry.directory) {
                listener?.invoke(source.scannedSize)
            }
            onEntryComplete?.invoke(source.file)
        }
        val dictionarySize = when (compressionPreset) {
            ArchiveCompressionPreset.SPEED -> 4 * 1024 * 1024
            ArchiveCompressionPreset.STANDARD -> 16 * 1024 * 1024
            ArchiveCompressionPreset.QUALITY -> 32 * 1024 * 1024
        }
        NativeSevenZip.create(channel, sources.map { it.entry }.toTypedArray(),
            object : NativeSevenZip.CreateCallback {
                override fun openInput(index: Int): InputStream? {
                    checkInterrupted()
                    val source = sources[index]
                    onEntry?.invoke(source.file)
                    return source.newInputStream()
                }

                override fun onProgress(completed: Long) {
                    checkInterrupted()
                    val now = System.currentTimeMillis()
                    if (now - lastProgressMillis < intervalMillis) {
                        return
                    }
                    // Leave the final byte pending until UpdateItems has written the headers.
                    val progress = completed.coerceIn(
                        reportedStreamSize, (totalStreamSize - 1).coerceAtLeast(0)
                    )
                    listener?.invoke(progress - reportedStreamSize)
                    reportedStreamSize = progress
                    lastProgressMillis = now
                }

                override fun onResult(index: Int, failure: IOException?) {
                    checkInterrupted()
                    if (failure != null) {
                        throw failure
                    }
                    // Keep one file pending as well, for all-empty and directory-only archives.
                    if (pendingCompletion >= 0) {
                        completeEntry(pendingCompletion)
                    }
                    pendingCompletion = index
                }
            }, password, encryptFileNames, compressionPreset.level, dictionarySize,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
        checkInterrupted()
        listener?.invoke(totalStreamSize - reportedStreamSize)
        // The handler can omit GetStream/SetOperationResult for empty files and directories.
        for (index in sources.indices) {
            if (!completedEntries[index]) {
                onEntry?.invoke(sources[index].file)
                completeEntry(index)
            }
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        if (!written) {
            writeEntries(emptyList(), 0, null, null, null)
        }
        closed = true
    }

    private class Source(index: Int, val file: Path, name: Path) {
        val entry: NativeSevenZip.Entry
        val scannedSize: Long
        private val symbolicLinkBytes: ByteArray?
        private val sourceAttributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )

        init {
            val attributes = sourceAttributes
            val type = attributes.posixFileType
            if (type != PosixFileType.REGULAR_FILE && type != PosixFileType.DIRECTORY
                && type != PosixFileType.SYMBOLIC_LINK) {
                throw IOException("7z does not support file type $type: $file")
            }
            val mode = (attributes as? PosixFileAttributes)?.mode() ?: when (type) {
                PosixFileType.DIRECTORY -> PosixFileMode.DIRECTORY_DEFAULT
                PosixFileType.SYMBOLIC_LINK -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
                else -> PosixFileMode.FILE_DEFAULT
            }
            symbolicLinkBytes = if (type == PosixFileType.SYMBOLIC_LINK) {
                file.readSymbolicLinkByteString().toBytes()
            } else {
                null
            }
            scannedSize = attributes.size()
            entry = NativeSevenZip.Entry().apply {
                this.index = index
                this.name = name.toString()
                directory = type == PosixFileType.DIRECTORY
                size = symbolicLinkBytes?.size?.toLong() ?: if (directory) 0 else scannedSize
                lastModifiedTime = attributes.lastModifiedTime().toMillis()
                lastAccessTime = attributes.lastAccessTime().toMillis()
                creationTime = attributes.creationTime().toMillis()
                hasAttributes = true
                // Official Unix 7-Zip stores the file type and mode in the high attribute bits.
                this.attributes = ((type.mode or mode.toInt()) shl 16) or 0x8000 or
                    if (directory) 0x10 else 0x20
            }
        }

        fun newInputStream(): InputStream? {
            if (entry.directory) {
                return null
            }
            symbolicLinkBytes?.let { return ByteArrayInputStream(it) }
            val input = file.newInputStream(LinkOption.NOFOLLOW_LINKS)
            return object : FilterInputStream(input) {
                private var count = 0L

                override fun read(): Int {
                    checkInterrupted()
                    val value = `in`.read()
                    recordRead(if (value < 0) -1 else 1)
                    return value
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    checkInterrupted()
                    val size = `in`.read(bytes, offset, length)
                    recordRead(size)
                    return size
                }

                private fun recordRead(size: Int) {
                    if (size >= 0) {
                        count += size
                    }
                    if (count > entry.size || size < 0 && count != entry.size) {
                        throw IOException("The source changed while creating the archive: $file")
                    }
                }

                override fun close() {
                    try {
                        val currentAttributes = file.readAttributes(
                            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                        )
                        if (count != entry.size || currentAttributes.size() != entry.size
                            || currentAttributes.lastModifiedTime()
                            != sourceAttributes.lastModifiedTime()
                            || !currentAttributes.isRegularFile
                            || sourceAttributes.fileKey() != null
                            && sourceAttributes.fileKey() != currentAttributes.fileKey()) {
                            throw IOException("The source changed while creating the archive: $file")
                        }
                    } finally {
                        super.close()
                    }
                }
            }
        }
    }

    companion object {
        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException()
            }
        }
    }
}
