/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.view.isVisible
import coil.dispose
import coil.load
import coil.request.ImageRequest
import coil.target.ImageViewTarget

/** Keeps an in-flight request or displayed image when a row is rebound with unchanged content. */
internal class RetainedImageRequest(
    private val view: ImageView,
    private val onSuccessChanged: (Boolean) -> Unit = {}
) {
    private var key: Any? = null
    private var token: Any? = null
    private var failed = false

    fun load(key: Any?, data: Any?, builder: ImageRequest.Builder.() -> Unit = {}) {
        if (token != null && this.key == key && !failed) {
            return
        }
        clear()
        this.key = key
        val token = Any().also { this.token = it }
        view.isVisible = key != null
        if (key == null) {
            return
        }
        view.load(data) {
            builder()
            target(object : ImageViewTarget(view) {
                override fun onStart(placeholder: Drawable?) {
                    if (this@RetainedImageRequest.token !== token) return
                    failed = false
                    super.onStart(placeholder)
                    onSuccessChanged(false)
                }

                override fun onError(error: Drawable?) {
                    if (this@RetainedImageRequest.token !== token) return
                    failed = true
                    super.onError(error)
                    onSuccessChanged(false)
                }

                override fun onSuccess(result: Drawable) {
                    if (this@RetainedImageRequest.token !== token) return
                    failed = false
                    super.onSuccess(result)
                    onSuccessChanged(true)
                }
            })
        }
    }

    fun clear() {
        // Invalidate callbacks before cancelling; cancellation can finish after another bind.
        token = null
        key = null
        failed = false
        view.dispose()
        (view.drawable as? Animatable)?.stop()
        view.setImageDrawable(null)
        view.isVisible = false
        onSuccessChanged(false)
    }
}
