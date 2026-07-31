/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

/**
 * Hosts the video surface and its subtitles, and sizes the surface according to a [VideoScaleType].
 *
 * The surface is deliberately allowed to be measured larger than this layout for the cropping scale
 * types; the overflow is clipped by this layout, which is the same approach
 * `androidx.media3.ui.PlayerView` takes for its zoom resize mode. Unlike
 * `AspectRatioFrameLayout` this also supports showing the video at its original pixel size.
 */
@OptIn(UnstableApi::class)
class MediaVideoLayout : FrameLayout {
    val surfaceView: SurfaceView
    private val subtitleView: SubtitleView

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelWidthHeightRatio = 1f

    var scaleType: VideoScaleType = VideoScaleType.BEST_FIT
        set(value) {
            if (field == value) {
                return
            }
            field = value
            requestLayout()
        }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    init {
        clipChildren = true
        surfaceView = SurfaceView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(surfaceView)
        subtitleView = SubtitleView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
        addView(subtitleView)
    }

    fun setVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float) {
        val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
        if (videoWidth == width && videoHeight == height && videoPixelWidthHeightRatio == ratio) {
            return
        }
        videoWidth = width
        videoHeight = height
        videoPixelWidthHeightRatio = ratio
        requestLayout()
    }

    fun setCues(cues: List<Cue>?) {
        subtitleView.setCues(cues)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = measuredWidth
        val height = measuredHeight
        if (width == 0 || height == 0) {
            return
        }
        val (surfaceWidth, surfaceHeight) = measureSurface(width, height)
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
        )
        subtitleView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val surfaceWidth = surfaceView.measuredWidth
        val surfaceHeight = surfaceView.measuredHeight
        // Center the surface, which may put it partially outside of our bounds when it is being
        // cropped.
        val surfaceLeft = (width - surfaceWidth) / 2
        val surfaceTop = (height - surfaceHeight) / 2
        surfaceView.layout(
            surfaceLeft, surfaceTop, surfaceLeft + surfaceWidth, surfaceTop + surfaceHeight
        )
        subtitleView.layout(0, 0, width, height)
    }

    private fun measureSurface(width: Int, height: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return width to height
        }
        val videoAspectRatio = videoWidth * videoPixelWidthHeightRatio / videoHeight
        if (!videoAspectRatio.isFinite() || videoAspectRatio <= 0f) {
            return width to height
        }
        return when (scaleType) {
            VideoScaleType.BEST_FIT -> fitInside(width, height, videoAspectRatio)
            VideoScaleType.FIT_SCREEN -> cover(width, height, videoAspectRatio)
            VideoScaleType.FILL -> width to height
            VideoScaleType.RATIO_16_9 -> fitInside(width, height, 16f / 9)
            VideoScaleType.RATIO_4_3 -> fitInside(width, height, 4f / 3)
            VideoScaleType.ORIGINAL ->
                (videoWidth * videoPixelWidthHeightRatio).roundToInt().coerceAtLeast(1) to
                    videoHeight
        }
    }

    private fun fitInside(width: Int, height: Int, aspectRatio: Float): Pair<Int, Int> =
        if (width.toFloat() / height > aspectRatio) {
            (height * aspectRatio).roundToInt().coerceAtLeast(1) to height
        } else {
            width to (width / aspectRatio).roundToInt().coerceAtLeast(1)
        }

    private fun cover(width: Int, height: Int, aspectRatio: Float): Pair<Int, Int> =
        if (width.toFloat() / height > aspectRatio) {
            width to (width / aspectRatio).roundToInt().coerceAtLeast(1)
        } else {
            (height * aspectRatio).roundToInt().coerceAtLeast(1) to height
        }
}
