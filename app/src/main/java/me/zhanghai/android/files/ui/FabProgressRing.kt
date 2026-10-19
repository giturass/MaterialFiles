/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.NumberFormat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filejob.ArchiveJobProgress
import me.zhanghai.android.files.util.getColorByAttr

/**
 * Adds archive progress to a circular FAB without replacing its icon or click action.
 *
 * Create one with the fragment's viewLifecycleOwner and call [update] on the main thread. The ring
 * pauses with the view lifecycle and removes its overlay and listeners when that lifecycle ends.
 * The ring provides the accessible progress state; the FAB owner supplies its label and the
 * task-details action.
 */
class FabProgressRing(
    private val fab: FloatingActionButton,
    owner: LifecycleOwner
) : DefaultLifecycleObserver, View.OnAttachStateChangeListener {
    private val lifecycle = owner.lifecycle
    private val drawable = RingDrawable(fab)
    private val contentBounds = Rect()
    private val percentFormat = NumberFormat.getPercentInstance()
    private var viewTreeObserver: ViewTreeObserver? = null
    private var disposed = false

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            drawable.rotation = it.animatedValue as Float
            drawable.invalidateSelf()
        }
    }
    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateAnimation()
        true
    }

    init {
        // Material 3 may supply a rounded square. Keep the upstream circular FAB silhouette.
        fab.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
        fab.overlay.add(drawable)
        fab.addOnLayoutChangeListener(layoutChangeListener)
        fab.addOnAttachStateChangeListener(this)
        if (fab.isAttachedToWindow) {
            onViewAttachedToWindow(fab)
        }
        lifecycle.addObserver(this)
    }

    fun update(jobs: List<ArchiveJobProgress>) {
        if (disposed) {
            return
        }
        drawable.active = jobs.isNotEmpty()
        drawable.indeterminate = jobs.any { it.indeterminate || it.max <= 0 }
        // Notification maxima use different units for large archives and empty files, so average
        // the normalized job fractions instead of treating the maxima as comparable byte counts.
        drawable.progress = if (drawable.active && !drawable.indeterminate) {
            jobs.sumOf { it.progress.coerceIn(0, it.max).toDouble() / it.max }
                .div(jobs.size).toFloat()
        } else {
            0f
        }
        val stateDescription = when {
            !drawable.active -> null
            drawable.indeterminate ->
                fab.context.getString(R.string.fab_archive_progress_indeterminate)
            else -> fab.context.getString(
                R.string.fab_archive_progress_format,
                percentFormat.format(drawable.progress.toDouble())
            )
        }
        ViewCompat.setStateDescription(fab, stateDescription)
        drawable.invalidateSelf()
        updateAnimation()
    }

    override fun onStart(owner: LifecycleOwner) {
        updateAnimation()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopAnimation()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dispose()
    }

    override fun onViewAttachedToWindow(view: View) {
        viewTreeObserver = fab.viewTreeObserver.also {
            it.addOnPreDrawListener(preDrawListener)
        }
        updateBounds()
        updateAnimation()
    }

    override fun onViewDetachedFromWindow(view: View) {
        removePreDrawListener()
        stopAnimation()
    }

    fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        stopAnimation()
        lifecycle.removeObserver(this)
        removePreDrawListener()
        fab.removeOnLayoutChangeListener(layoutChangeListener)
        fab.removeOnAttachStateChangeListener(this)
        fab.overlay.remove(drawable)
        ViewCompat.setStateDescription(fab, null)
    }

    private fun removePreDrawListener() {
        viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver = null
    }

    private fun updateBounds() {
        fab.getMeasuredContentRect(contentBounds)
        drawable.bounds = contentBounds
    }

    private fun updateAnimation() {
        val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        val shouldAnimate = !disposed && drawable.active && drawable.indeterminate &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && fab.isAttachedToWindow &&
            fab.isShown && fab.windowVisibility == View.VISIBLE && animationsEnabled
        if (shouldAnimate) {
            if (!rotationAnimator.isStarted) {
                rotationAnimator.start()
            }
        } else {
            stopAnimation()
        }
    }

    private fun stopAnimation() {
        if (rotationAnimator.isStarted) {
            rotationAnimator.cancel()
        }
    }

    private class RingDrawable(private val fab: FloatingActionButton) : Drawable() {
        var active = false
        var indeterminate = false
        var progress = 0f
        var rotation = 0f

        private val density = fab.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeCap = Paint.Cap.ROUND
        }
        private val arcBounds = RectF()
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            if (!active || bounds.isEmpty) {
                return
            }
            val radius = minOf(bounds.width(), bounds.height()) / 2f - 3 * density
            if (radius <= 0f) {
                return
            }
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()
            arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            // Follow the animated icon tint to retain contrast both when the speed dial is open
            // and when it is closed, including custom and dark themes.
            val tint = fab.imageTintList
            val color = tint?.let { it.getColorForState(fab.drawableState, it.defaultColor) }
                ?: fab.context.getColorByAttr(android.R.attr.textColorPrimary)
            paint.color = color
            val alpha = Color.alpha(color) * drawableAlpha / 255
            paint.alpha = (alpha * 0.22f).toInt()
            canvas.drawOval(arcBounds, paint)
            paint.alpha = alpha
            if (indeterminate) {
                canvas.drawArc(arcBounds, rotation - 90f, 90f, false, paint)
            } else if (progress > 0f) {
                canvas.drawArc(arcBounds, -90f, progress * 360f, false, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
