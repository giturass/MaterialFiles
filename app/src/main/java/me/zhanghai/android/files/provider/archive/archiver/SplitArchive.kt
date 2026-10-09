/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.size
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException

/** Owns only newly created output files. A failed close also rolls back every created volume. */
internal class ArchiveOutput(private val file: Path, private val splitSize: Long) : Closeable {
    private val created = mutableListOf<Path>()
    private var committed = false
    private var closed = false

    init {
        require(splitSize >= 0)
    }

    val channel: SeekableByteChannel = if (splitSize == 0L) {
        create(file)
    } else {
        SplitOutputChannel()
    }

    private fun create(path: Path): SeekableByteChannel {
        checkArchiveInterrupted()
        val channel = try {
            path.newByteChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            if (e.cause !is UnsupportedOperationException) {
                throw e
            }
            OutputChannel(path.newOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        }
        created.add(path)
        return channel
    }

    /** Called after all archive bytes have been written. close() must still succeed. */
    fun commit() {
        check(!closed)
        checkArchiveInterrupted()
        committed = true
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            channel.close()
        } catch (e: Throwable) {
            failure = e
        }
        if (!committed || failure != null) {
            for (path in created.asReversed()) {
                try {
                    path.deleteIfExists()
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
        }
        failure?.let { throw it }
    }

    private inner class SplitOutputChannel : SeekableByteChannel {
        private var part = create(file.volumePath(1))
        private var partNumber = 1
        private var partSize = 0L
        private var position = 0L
        private var open = true

        override fun write(source: ByteBuffer): Int {
            ensureOpen()
            checkArchiveInterrupted()
            if (!source.hasRemaining()) return 0
            if (partSize == splitSize) {
                part.close()
                if (partNumber == Int.MAX_VALUE) throw IOException("Too many archive volumes")
                part = create(file.volumePath(++partNumber))
                partSize = 0
            }
            val limit = source.limit()
            source.limit(source.position() + minOf(source.remaining().toLong(), splitSize - partSize).toInt())
            val count = try {
                part.write(source)
            } finally {
                source.limit(limit)
            }
            if (count <= 0) throw IOException("The archive destination made no write progress")
            partSize += count
            position += count
            return count
        }

        override fun position(): Long { ensureOpen(); return position }
        override fun position(newPosition: Long): SeekableByteChannel {
            ensureOpen()
            if (newPosition != position) throw UnsupportedOperationException("Archive volumes are written sequentially")
            return this
        }
        override fun size(): Long { ensureOpen(); return position }
        override fun truncate(size: Long): SeekableByteChannel {
            ensureOpen()
            if (size != position) throw UnsupportedOperationException("Archive volumes are written sequentially")
            return this
        }
        override fun read(target: ByteBuffer): Int = throw NonReadableChannelException()
        override fun isOpen(): Boolean = open
        override fun close() { if (open) { open = false; part.close() } }
        private fun ensureOpen() { if (!open) throw ClosedChannelException() }
    }
}

/** A raw split stream (.001, .002, ...), compatible with 7-Zip's volume convention. */
internal fun Path.newArchiveByteChannel(): SeekableByteChannel {
    if (!isFirstArchiveVolume) return newByteChannel()
    return VolumeInputChannel(archiveVolumes())
}

internal fun Path.newArchiveInputStream(): InputStream {
    if (!isFirstArchiveVolume) return newInputStream()
    val parts = archiveVolumes()
    return object : InputStream() {
        private var index = 0
        private var input: InputStream? = null
        private var partPosition = 0L
        private var closed = false

        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (closed) throw IOException("The archive input is closed")
            if (length == 0) return 0
            while (index < parts.size) {
                checkArchiveInterrupted()
                val part = parts[index]
                if (partPosition == part.size) {
                    input?.close()
                    input = null
                    partPosition = 0
                    ++index
                    continue
                }
                val current = input ?: part.path.newInputStream().also { input = it }
                val count = current.read(
                    bytes, offset, minOf(length.toLong(), part.size - partPosition).toInt()
                )
                if (count < 0) throw EOFException("Archive volume is truncated: ${part.path}")
                if (count == 0) throw IOException("The archive source made no read progress")
                partPosition += count
                return count
            }
            return -1
        }

        override fun close() { if (!closed) { closed = true; input?.close(); input = null } }
    }
}

internal val Path.isFirstArchiveVolume: Boolean
    get() = fileName?.toString()?.endsWith(".001") == true

internal fun Path.archiveFileNameWithoutVolume(): String =
    fileName.toString().let { if (isFirstArchiveVolume) it.dropLast(4) else it }

private fun Path.volumePath(number: Int): Path =
    resolveSibling(fileName.toString() + "." + number.toString().padStart(3, '0'))

private data class Volume(val path: Path, val start: Long, val size: Long)

private fun Path.archiveVolumes(): List<Volume> {
    val base = resolveSibling(archiveFileNameWithoutVolume())
    val result = mutableListOf<Volume>()
    var offset = 0L
    var number = 1
    while (true) {
        checkArchiveInterrupted()
        val part = base.volumePath(number)
        val size = try {
            part.size()
        } catch (e: NoSuchFileException) {
            if (number == 1) throw e
            break
        }
        if (size < 0 || Long.MAX_VALUE - offset < size) throw IOException("The archive volumes are too large")
        result += Volume(part, offset, size)
        offset += size
        if (number == Int.MAX_VALUE) throw IOException("Too many archive volumes")
        ++number
    }
    return result
}

private class VolumeInputChannel(private val volumes: List<Volume>) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private var partIndex = -1
    private var part: SeekableByteChannel? = null
    private val size = volumes.last().let { it.start + it.size }

    override fun read(target: ByteBuffer): Int {
        ensureOpen()
        checkArchiveInterrupted()
        if (!target.hasRemaining()) return 0
        if (position >= size) return -1
        var low = 0
        var high = volumes.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (volumes[middle].start + volumes[middle].size <= position) low = middle + 1 else high = middle
        }
        val volume = volumes[low]
        if (partIndex != low) {
            part?.close()
            part = null
            partIndex = -1
            part = volume.path.newByteChannel()
            partIndex = low
        }
        val current = part!!
        current.position(position - volume.start)
        val limit = target.limit()
        target.limit(target.position() + minOf(target.remaining().toLong(), volume.start + volume.size - position).toInt())
        val count = try {
            current.read(target)
        } finally {
            target.limit(limit)
        }
        if (count < 0) throw EOFException("Archive volume is truncated: ${volume.path}")
        if (count == 0) throw IOException("The archive source made no read progress")
        position += count
        return count
    }

    override fun position(): Long { ensureOpen(); return position }
    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        require(newPosition >= 0)
        position = newPosition
        return this
    }
    override fun size(): Long { ensureOpen(); return size }
    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { if (open) { open = false; part?.close(); part = null } }
    private fun ensureOpen() { if (!open) throw ClosedChannelException() }
}

private class OutputChannel(private val output: OutputStream) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val buffer = ByteArray(32 * 1024)

    override fun write(source: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        checkArchiveInterrupted()
        val count = minOf(source.remaining(), buffer.size)
        source.get(buffer, 0, count)
        output.write(buffer, 0, count)
        position += count
        return count
    }
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel {
        if (newPosition != position) throw UnsupportedOperationException("Sequential output")
        return this
    }
    override fun size(): Long = position
    override fun truncate(size: Long): SeekableByteChannel {
        if (size != position) throw UnsupportedOperationException("Sequential output")
        return this
    }
    override fun read(target: ByteBuffer): Int = throw NonReadableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { if (open) { open = false; output.close() } }
}

private fun checkArchiveInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
}
