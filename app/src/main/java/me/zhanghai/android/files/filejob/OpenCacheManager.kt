package me.zhanghai.android.files.filejob

import android.os.Environment
import java8.nio.file.Path
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.notificationManager
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object OpenCacheManager {
    const val RETENTION_MILLIS = OpenCacheStore.RETENTION_MILLIS
    const val NOTIFICATION_ID = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val cleanupPending = AtomicBoolean()
    private val store = OpenCacheStore(
        ::cacheRoots,
        onRemove = { notificationManager.cancel(it, NOTIFICATION_ID) }
    )

    fun cleanupAsync() {
        if (!cleanupPending.compareAndSet(false, true)) return
        executor.execute {
            try {
                store.cleanup()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cleanupPending.set(false)
            }
        }
    }

    internal fun create(): OpenCacheStore.Session {
        cleanupAsync()
        val context = application
        val cache = context.externalCacheDir?.takeIf {
            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        } ?: context.cacheDir
        return store.create(cacheRoot(cache))
    }

    fun isCachePath(path: Path): Boolean = cacheFile(path) != null

    fun acquire(path: Path): Closeable? = cacheFile(path)?.let(store::acquire)

    private fun cacheFile(path: Path): File? {
        val file = localFile(path) ?: return null
        // Keep paths inside the cache uncanonicalized so the store can reject symlinks there.
        if (store.contains(file)) return file
        return try {
            // /sdcard aliases must have the same read-only policy.
            file.canonicalFile.takeIf(store::contains)
        } catch (e: IOException) {
            null
        }
    }

    private fun cacheRoots(): List<File> =
        (listOf(application.cacheDir) + application.externalCacheDirs.filterNotNull())
            .mapNotNull {
                try {
                    cacheRoot(it)
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }.distinct()

    private fun cacheRoot(cache: File): File = File(cache.canonicalFile, "open_cache")

    private fun localFile(path: Path): File? {
        if (path.isArchivePath) return localFile(path.archiveFile)
        if (path.isLinuxPath) return path.toFile()
        // An internal viewer can also receive our content URI from an external chooser.
        val uri = path.toUri()
        if (uri.scheme == "content" && uri.authority == BuildConfig.FILE_PROVIDIER_AUTHORITY) {
            return try {
                val encoded = uri.path?.takeIf { it.startsWith('/') } ?: return null
                val nested = URI.create(android.net.Uri.decode(encoded).substring(1))
                localFile(java8.nio.file.Paths.get(nested))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return null
    }
}
