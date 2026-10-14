/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import java.io.Closeable
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/** Keeps a directory's password request alive across recreation of its fragment. */
internal class FileListUserAction(
    private val currentPath: () -> Path?,
    private val reload: () -> Unit
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handledException: UserActionRequiredException? = null
    private var pendingRequest: Any? = null
    private var isClosed = false

    fun request(
        exception: UserActionRequiredException,
        context: Context,
        launch: (Intent) -> Unit
    ) {
        if (isClosed || pendingRequest != null || handledException === exception) return
        val path = currentPath() ?: return
        val request = Any()
        handledException = exception
        pendingRequest = request
        val continuation = Continuation<Boolean>(EmptyCoroutineContext) { result ->
            val complete = Runnable {
                if (!isClosed && pendingRequest === request) {
                    pendingRequest = null
                    // Cancellation leaves this failure handled until a fresh directory load.
                    // A password supplied after navigation must not reload another directory.
                    if (result.getOrNull() == true && currentPath() == path) reload()
                }
            }
            // Parcelable dialog callbacks can arrive from a Binder thread.
            if (Looper.myLooper() == mainHandler.looper) complete.run()
            else mainHandler.post(complete)
        }
        try {
            launch(exception.getUserAction(continuation, context).intent)
        } catch (e: Exception) {
            if (pendingRequest === request) pendingRequest = null
            throw e
        }
    }

    override fun close() {
        isClosed = true
        pendingRequest = null
        handledException = null
        mainHandler.removeCallbacksAndMessages(null)
    }
}
