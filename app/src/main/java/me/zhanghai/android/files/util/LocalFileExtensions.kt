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
import java.security.MessageDigest

/**
 * Libraries that work on raw file descriptors (SQLite, apksig) cannot consume our [Path]
 * abstraction, which may point into an archive or a remote share. These helpers materialize such a
 * path as a real [File], copying it into the cache when it isn't already on the local file system.
 */

/** The backing [File] when this path is a plain local one, or `null` when it isn't. */
val Path.localFileOrNull: File?
    get() = if (isLinuxPath) toFile() else null

/** The cache subdirectories working copies are made in, so that they can all be swept at startup. */
object CacheFiles {
    const val DATABASE_EDITOR = "database_editor"
    const val KEY_STORE = "key_store"

    /**
     * Where the APK signer stages its input, its output and a copy of the key store. Every entry
     * here has to be in [ALL], or a process that dies mid-signing leaves an APK and an unprotected
     * key store behind for good.
     */
    const val SIGN_APK = "sign_apk"

    private val ALL = listOf(DATABASE_EDITOR, KEY_STORE, SIGN_APK)

    /**
     * Removes every working copy left behind by a process that died before it could delete its own.
     * Only correct while nothing can be holding one, i.e. at startup.
     */
    fun pruneAll(context: Context) {
        for (subdirectory in ALL) {
            File(context.cacheDir, subdirectory).deleteRecursively()
        }
    }
}

/**
 * Copies this path into [context]'s cache directory and returns the resulting file.
 *
 * Each source path gets a directory of its own, keyed by the path, because two files that share a
 * name but not a directory would otherwise overwrite each other's copy while both are open. Inside
 * it the file name is kept, so that error messages and derived names stay recognizable.
 *
 * The caller owns the result and is expected to hand it to [deleteCacheFile] when done with it.
 */
@Throws(IOException::class)
fun Path.copyToCacheFile(context: Context, cacheSubdirectory: String): File {
    val directory = File(
        File(context.cacheDir, cacheSubdirectory), toString().toCacheDirectoryName()
    )
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Cannot create cache directory ${directory.path}")
    }
    val file = File(directory, name)
    newInputStream().use { inputStream ->
        file.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
    }
    return file
}

/** Removes a copy made by [copyToCacheFile], along with the directory that was made to hold it. */
fun deleteCacheFile(file: File) {
    file.delete()
    // Fails harmlessly if something else ended up in there.
    file.parentFile?.delete()
}

private fun String.toCacheDirectoryName(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }
