/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var future: Future<Unit>? = null
    private var loadGeneration = 0
    private var reloadAfterLoading = false
    private var isClosed = false

    private val observer: PathObserver

    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() = loadValue(true)

    private fun loadValue(showLoading: Boolean) {
        if (isClosed) {
            return
        }
        future?.cancel(true)
        val generation = ++loadGeneration
        reloadAfterLoading = false
        val previousValue = value?.value
        if (showLoading) {
            value = Loading(previousValue)
        }
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val result = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (path in directoryStream) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException()
                        }
                        try {
                            fileList.add(path.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            // TODO: Ignoring such a file can be misleading and we need to support
                            //  files without information.
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                Failure(previousValue, e)
            }
            mainHandler.post {
                // A cancelled provider operation can still finish. Do not let it overwrite a
                // newer explicit refresh, or publish after the directory has been closed.
                if (isClosed || generation != loadGeneration) {
                    return@post
                }
                future = null
                val loadAgain = reloadAfterLoading
                reloadAfterLoading = false
                value = result
                if (loadAgain && generation == loadGeneration) {
                    onChangeObserved()
                }
            }
        }
    }

    private fun onChangeObserved() {
        if (isClosed) {
            return
        }
        if (hasActiveObservers()) {
            if (future != null) {
                // Coalesce changes while loading instead of repeatedly interrupting a slow
                // directory listing. A trailing reload includes the final file state.
                reloadAfterLoading = true
            } else {
                // File writes (including archive output) are not user-initiated refreshes.
                // Keep the current list visible without restarting the loading animations.
                loadValue(false)
            }
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            isChangedWhileInactive = false
            onChangeObserved()
        }
    }

    override fun close() {
        isClosed = true
        ++loadGeneration
        observer.close()
        future?.cancel(true)
        future = null
        mainHandler.removeCallbacksAndMessages(null)
    }
}
