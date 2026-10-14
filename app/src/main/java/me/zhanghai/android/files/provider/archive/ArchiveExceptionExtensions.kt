/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * Copyright (c) 2026 Material Files contributors.
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.FileSystemException
import java8.nio.file.Path
import me.zhanghai.android.files.provider.archive.archiver.ArchivePassphraseException
import me.zhanghai.android.files.provider.common.DelegateInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException

internal fun IOException.toFileSystemOrInterruptedIOException(file: Path): IOException = when (this) {
    is ArchivePasswordRequiredException, is InterruptedIOException -> this
    is ClosedByInterruptException -> InterruptedIOException(message).apply { initCause(this@toFileSystemOrInterruptedIOException) }
    is ArchivePassphraseException -> ArchivePasswordRequiredException(file, message).apply {
        initCause(this@toFileSystemOrInterruptedIOException)
    }
    else -> FileSystemException(file.toString(), null, message).apply {
        initCause(this@toFileSystemOrInterruptedIOException)
    }
}

/** Also translates failures delivered asynchronously by the native extraction worker. */
internal class ArchiveExceptionInputStream(
    inputStream: InputStream,
    private val file: Path
) : DelegateInputStream(inputStream) {
    override fun read(): Int = translate { super.read() }
    override fun read(b: ByteArray): Int = translate { super.read(b) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = translate { super.read(b, off, len) }
    override fun skip(n: Long): Long = translate { super.skip(n) }
    override fun available(): Int = translate { super.available() }
    override fun close() = translate { super.close() }
    override fun reset() = translate { super.reset() }

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e.toFileSystemOrInterruptedIOException(file)
    }
}
