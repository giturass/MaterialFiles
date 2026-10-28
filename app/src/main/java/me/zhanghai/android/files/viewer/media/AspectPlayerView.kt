package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AspectPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : PlayerView(context, attrs) {
    private var videoAspect = 0f
    var forcedAspect = 0f
        set(value) {
            field = value
            findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                ?.setAspectRatio(value.takeIf { it > 0 } ?: videoAspect)
        }

    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, contentAspectRatio: Float) {
        videoAspect = contentAspectRatio
        super.onContentAspectRatioChanged(contentFrame, forcedAspect.takeIf { it > 0 } ?: contentAspectRatio)
    }
}
