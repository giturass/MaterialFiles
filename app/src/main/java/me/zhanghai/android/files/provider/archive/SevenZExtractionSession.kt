/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java8.nio.file.Path
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.archive.archiver.SevenZArchiveReader

/**
 * Adapts one push-based 7-Zip Extract call to the file providers' pull-based copy API.
 *
 * The job must visit [files] in archive order and call [skip] when a conflict is skipped. A
 * worker waits for each copy decision and uses a bounded 64 KiB queue. In particular, it does not
 * unpack a solid archive again for every file or stage its uncompressed contents on disk.
 */
internal class SevenZExtractionSession(
    private val selectedEntries: List<Pair<Path, ReadArchive.Entry>>,
    initialArchive: SevenZArchiveReader,
    private val openArchive: () -> SevenZArchiveReader,
    private val onClose: () -> Unit
) : Closeable {
    val files: List<Path> = selectedEntries.map { it.first }

    private val indices = files.withIndex().associate { it.value to it.index }
    private val skipped = mutableSetOf<Path>()
    private var initialArchive: SevenZArchiveReader? = initialArchive
    private var run: ExtractionRun? = null
    private var lastIndex = -1
    private var lastPipe: EntryPipe? = null
    private var isClosed = false

    fun contains(file: Path): Boolean = file in indices

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream {
        check(!isClosed) { "Extraction session is closed" }
        val index = indices.getValue(file)
        // A retry, including a newly supplied password or a failed destination stream, must
        // start from this entry again. Successful copies continue the same Extract call.
        if (run != null && (index <= lastIndex || lastPipe?.completedSuccessfully != true
                || run!!.failure != null)) {
            run!!.close()
            run = null
        }
        checkInterrupted()
        skipped.remove(file)
        val pipe = EntryPipe()
        var currentRun = run
        val needsStart = currentRun == null
        if (currentRun == null) {
            val archive = initialArchive?.also { initialArchive = null } ?: openArchive()
            currentRun = ExtractionRun(
                archive, selectedEntries.drop(index).filter { it.first !in skipped }
                    .map { it.second }
            )
            run = currentRun
            currentRun.request(selectedEntries[index].second.name, pipe)
        } else {
            // Entries omitted by the caller can only be skipped; buffering a later entry
            // while the native worker waits for an earlier one would otherwise deadlock.
            for (previous in lastIndex + 1 until index) {
                currentRun.skip(selectedEntries[previous].second.name)
            }
            currentRun.request(selectedEntries[index].second.name, pipe)
        }
        lastIndex = index
        lastPipe = pipe
        if (needsStart) {
            currentRun.start()
        }
        return pipe.inputStream
    }

    fun skip(file: Path) {
        val index = indices.getValue(file)
        skipped.add(file)
        val currentRun = run ?: return
        if (index == lastIndex && lastPipe?.completedSuccessfully != true) {
            // The copy error has already been handled by the job. Abandon this failed or
            // partially consumed run so finish() cannot report the same skipped error again.
            currentRun.close()
            run = null
        } else {
            currentRun.skip(selectedEntries[index].second.name)
        }
    }

    /** Waits for validation that the engine may perform after the last entry's CRC result. */
    @Throws(IOException::class)
    fun finish() {
        check(!isClosed) { "Extraction session is closed" }
        checkInterrupted()
        run?.finish()
    }

    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        try {
            run?.close()
        } finally {
            try {
                initialArchive?.close()
            } finally {
                onClose()
            }
        }
    }

    private class ExtractionRun(
        private val archive: SevenZArchiveReader,
        private val entries: List<ReadArchive.Entry>
    ) : Closeable {
        private val lock = Object()
        // An absent key means that the job has not decided yet; a null value means skip.
        private val requests = mutableMapOf<String, EntryPipe?>()

        @Volatile
        private var isClosed = false

        @Volatile
        private var isFinished = false

        @Volatile
        var failure: IOException? = null
            private set

        private val thread = Thread({ extract() }, "7z-extract").apply { isDaemon = true }

        fun start() {
            try {
                thread.start()
            } catch (e: Throwable) {
                isFinished = true
                val failure = IOException("Unable to start 7z extraction", e)
                this.failure = failure
                try {
                    archive.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        fun request(name: String, pipe: EntryPipe) {
            val failure = synchronized(lock) {
                requests[name] = pipe
                lock.notifyAll()
                failure ?: if (isFinished) IOException("7z entry was not extracted: $name") else null
            }
            if (failure != null) {
                pipe.finish(failure)
            }
        }

        fun skip(name: String) {
            synchronized(lock) {
                if (!requests.containsKey(name)) {
                    requests[name] = null
                }
                lock.notifyAll()
            }
        }

        private fun extract() {
            try {
                archive.extract(entries, object : SevenZArchiveReader.ExtractionCallback {
                    override fun openOutput(entry: ReadArchive.Entry): OutputStream? =
                        synchronized(lock) {
                            while (!requests.containsKey(entry.name) && !isClosed) {
                                waitForDecision()
                            }
                            checkInterrupted()
                            if (isClosed) {
                                throw InterruptedIOException("Extraction session closed")
                            }
                            requests[entry.name]?.outputStream
                        }

                    override fun onResult(entry: ReadArchive.Entry, failure: IOException?) {
                        val pipe = synchronized(lock) { requests[entry.name] }
                        // EOF is published only after 7-Zip has checked the entry's CRC.
                        pipe?.finish(failure)
                    }
                })
            } catch (e: Throwable) {
                failure = e as? IOException ?: IOException("7z extraction failed", e)
            } finally {
                try {
                    archive.close()
                } catch (e: Throwable) {
                    if (failure == null) {
                        failure = e as? IOException ?: IOException("Unable to close 7z archive", e)
                    } else {
                        failure!!.addSuppressed(e)
                    }
                }
                val pending = synchronized(lock) {
                    isFinished = true
                    lock.notifyAll()
                    requests.values.filterNotNull().toList()
                }
                val failure = failure ?: IOException("7z entry was not extracted")
                for (pipe in pending) {
                    try {
                        pipe.finish(failure)
                    } catch (e: InterruptedIOException) {
                        pipe.inputStream.close()
                    }
                }
            }
        }

        private fun waitForDecision() {
            try {
                lock.wait()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException().apply { initCause(e) }
            }
        }

        fun finish() {
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException().apply { initCause(e) }
            }
            checkInterrupted()
            failure?.let { throw it }
        }

        override fun close() {
            val pending = synchronized(lock) {
                isClosed = true
                lock.notifyAll()
                requests.values.filterNotNull().toList()
            }
            for (pipe in pending) {
                pipe.inputStream.close()
            }
            thread.interrupt()
            // The worker owns its native handle and closes it itself. Join before opening
            // a replacement, while preserving the job's cancellation flag during cleanup.
            var interrupted = Thread.interrupted()
            try {
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private class EntryPipe {
        private val queue = ArrayBlockingQueue<Packet>(8)
        private val finished = AtomicBoolean()

        @Volatile
        private var inputClosed = false

        @Volatile
        var completedSuccessfully = false
            private set

        val inputStream = object : InputStream() {
            private var packet: Packet? = null
            private var offset = 0

            override fun read(): Int {
                val bytes = ByteArray(1)
                return if (read(bytes) == -1) -1 else bytes[0].toInt() and 0xFF
            }

            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (off < 0 || len < 0 || off > bytes.size - len) {
                    throw IndexOutOfBoundsException()
                }
                if (len == 0) {
                    return 0
                }
                checkInterrupted()
                if (inputClosed) {
                    throw IOException("Entry stream is closed")
                }
                var current = packet
                if (current == null || current.bytes != null && offset == current.bytes.size) {
                    current = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException().apply { initCause(e) }
                    }
                    packet = current
                    offset = 0
                }
                val data = current.bytes
                if (data == null) {
                    current.failure?.let { throw it }
                    completedSuccessfully = true
                    return -1
                }
                val count = minOf(len, data.size - offset)
                data.copyInto(bytes, off, offset, offset + count)
                offset += count
                return count
            }

            override fun close() {
                inputClosed = true
                queue.clear()
                // Release either a consumer or a producer blocked in a queue operation.
                queue.offer(Packet(null, InterruptedIOException("Entry stream closed")))
            }
        }

        val outputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, off: Int, len: Int) {
                if (off < 0 || len < 0 || off > bytes.size - len) {
                    throw IndexOutOfBoundsException()
                }
                var offset = off
                val end = off + len
                while (offset < end) {
                    val count = minOf(8192, end - offset)
                    put(Packet(bytes.copyOfRange(offset, offset + count), null))
                    offset += count
                }
            }
        }

        fun finish(failure: IOException?) {
            if (!finished.compareAndSet(false, true) || inputClosed) {
                return
            }
            try {
                put(Packet(null, failure))
            } catch (e: Throwable) {
                // A failed enqueue has not signalled EOF. Let the worker's final cleanup
                // retry or close the input, so the consumer can never wait forever.
                finished.set(false)
                throw e
            }
        }

        private fun put(packet: Packet) {
            checkInterrupted()
            if (inputClosed) {
                if (packet.bytes == null) {
                    return
                }
                throw InterruptedIOException("Entry stream closed")
            }
            try {
                queue.put(packet)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException().apply { initCause(e) }
            }
            if (inputClosed && packet.bytes != null) {
                throw InterruptedIOException("Entry stream closed")
            }
        }

        private class Packet(val bytes: ByteArray?, val failure: IOException?)
    }

    companion object {
        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException()
            }
        }
    }
}
