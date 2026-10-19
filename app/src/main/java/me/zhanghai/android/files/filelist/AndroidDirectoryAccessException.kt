/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.filelist

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.Path
import me.zhanghai.android.files.file.AndroidDirectory
import me.zhanghai.android.files.file.androidDirectory
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException

internal class AndroidDirectoryAccessException(
    val directory: AndroidDirectory,
    cause: Exception
) : IOException(cause)

internal fun Exception.withAndroidDirectoryFallback(path: Path): Exception {
    if (Thread.currentThread().isInterrupted) return this
    val causes = generateSequence(this as Throwable) { it.cause }.toList()
    if (causes.any { it is InterruptedException || it is InterruptedIOException }) return this
    if (causes.none { it is AccessDeniedException || it is RemoteFileSystemException
            || it is SecurityException }) return this
    val directory = path.androidDirectory ?: return this
    return AndroidDirectoryAccessException(directory, this)
}
