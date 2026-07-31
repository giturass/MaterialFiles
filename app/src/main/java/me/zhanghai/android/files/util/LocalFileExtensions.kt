/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Context
import java8.nio.file.Path
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.File
import java.io.IOException

/**
 * Libraries that work on raw file descriptors (SQLite, apksig) cannot consume our [Path]
 * abstraction, which may point into an archive or a remote share. These helpers materialize such a
 * path as a real [File], copying it into the cache when it isn't already on the local file system.
 */

/** The backing [File] when this path is a plain local one, or `null` when it isn't. */
val Path.localFileOrNull: File?
    get() = if (isLinuxPath) toFile() else null

/**
 * Copies this path into [context]'s cache directory and returns the resulting file. The file name is
 * kept so that error messages and derived names stay recognizable, and any previous copy is
 * replaced.
 */
@Throws(IOException::class)
fun Path.copyToCacheFile(context: Context, cacheSubdirectory: String): File {
    val directory = File(context.cacheDir, cacheSubdirectory)
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Cannot create cache directory ${directory.path}")
    }
    val file = File(directory, name)
    newInputStream().use { inputStream ->
        file.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
    }
    return file
}
