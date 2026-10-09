/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Bridges the app's provider channels to Commons Compress without transferring ownership. */
@RequiresApi(Build.VERSION_CODES.N)
internal class CommonsSeekableByteChannel(
    private val delegate: java8.nio.channels.SeekableByteChannel
) : SeekableByteChannel {
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException()
        }
    }

    override fun read(buffer: ByteBuffer): Int {
        checkInterrupted()
        return delegate.read(buffer)
    }

    override fun write(buffer: ByteBuffer): Int {
        checkInterrupted()
        return delegate.write(buffer)
    }

    override fun position(): Long = delegate.position()

    override fun position(position: Long): SeekableByteChannel = apply {
        checkInterrupted()
        delegate.position(position)
    }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel = apply { delegate.truncate(size) }

    override fun isOpen(): Boolean = delegate.isOpen

    override fun close() = Unit
}
