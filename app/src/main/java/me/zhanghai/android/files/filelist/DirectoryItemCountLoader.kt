/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.MainThread
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.androidDirectory
import me.zhanghai.android.files.provider.common.newDirectoryStream
import java.io.InterruptedIOException

/** Loads direct child counts only for rows that have been bound, without blocking the list. */
@MainThread
internal class DirectoryItemCountLoader(
    private val scope: CoroutineScope,
    private val onCountChanged: (Path) -> Unit
) {
    private val countSlots = Semaphore(2)

    private val entries = object : LruCache<Path, Entry>(256) {
        override fun entryRemoved(evicted: Boolean, key: Path, oldValue: Entry, newValue: Entry?) {
            oldValue.job?.cancel()
        }
    }

    fun getCount(file: FileItem): Count? {
        val path = file.path
        // Only opening these directories should request root/Shizuku permission.
        if (path.androidDirectory?.isRoot == true) return Count(null)
        val entry = entries[path] ?: Entry(file).also { entries.put(path, it) }
        entry.update(file, SystemClock.elapsedRealtime())
        if (entry.needsReload && entry.job == null) {
            load(path, entry)
        }
        return entry.count
    }

    fun retainFiles(files: List<FileItem>): Set<Path> {
        val filesByPath = files.associateBy { it.path }
        val changedPaths = mutableSetOf<Path>()
        val now = SystemClock.elapsedRealtime()
        for ((path, entry) in entries.snapshot()) {
            val file = filesByPath[path]
            if (file == null || !file.attributes.isDirectory) {
                entries.remove(path)
            } else if (entry.update(file, now)) {
                changedPaths.add(path)
            }
        }
        return changedPaths
    }

    fun invalidate(): Set<Path> {
        val snapshot = entries.snapshot()
        for (entry in snapshot.values) {
            entry.needsReload = true
        }
        return snapshot.keys
    }

    fun clear() {
        entries.evictAll()
    }

    private fun load(path: Path, entry: Entry) {
        entry.needsReload = false
        entry.job = scope.launch {
            val count = try {
                countSlots.withPermit {
                    runInterruptible(Dispatchers.IO) {
                        path.newDirectoryStream().use { stream ->
                            var count = 0L
                            val iterator = stream.iterator()
                            while (iterator.hasNext()) {
                                if (Thread.currentThread().isInterrupted) {
                                    throw InterruptedIOException()
                                }
                                iterator.next()
                                ++count
                            }
                            count
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (entries[path] === entry) {
                    entry.job = null
                    entry.needsReload = true
                }
                throw e
            } catch (e: Exception) {
                // Unreadable directories must not be presented as empty directories.
                null
            }
            if (entries[path] !== entry) {
                return@launch
            }
            entry.count = Count(count)
            entry.lastLoadedMillis = SystemClock.elapsedRealtime()
            entry.job = null
            // If another listing arrived during this scan, the next bind starts one trailing
            // reload. Keep the last result visible and do not starve slow providers by cancelling.
            onCountChanged(path)
        }
    }

    data class Count(val value: Long?)

    private class Entry(var file: FileItem) {
        var count: Count? = null
        var needsReload = true
        var job: Job? = null
        var lastLoadedMillis = 0L

        fun update(file: FileItem, now: Long): Boolean {
            val oldAttributes = this.file.attributes
            val newAttributes = file.attributes
            val metadataChanged =
                oldAttributes.lastModifiedTime() != newAttributes.lastModifiedTime()
                    || oldAttributes.size() != newAttributes.size()
                    || oldAttributes.fileKey() != newAttributes.fileKey()
                    || this.file.symbolicLinkTarget != file.symbolicLinkTarget
            this.file = file
            // Ignore refreshed FileItem identities and access times. A short maximum age also
            // covers providers that do not update directory mtimes, without recounting on every
            // unrelated file write. An in-flight scan already refreshes an aged result.
            val expired = job == null && count != null
                && now - lastLoadedMillis >= MAX_AGE_MILLIS
            if (metadataChanged || expired) {
                needsReload = true
                return true
            }
            return false
        }
    }

    companion object {
        private const val MAX_AGE_MILLIS = 5000L
    }
}
