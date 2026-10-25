package me.zhanghai.android.files.media

import android.content.Context
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@UnstableApi
class VideoPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    PlayerView(context, attrs) {
    private var sourceRatio = 0f
    var forcedRatio = 0f
        set(value) {
            field = value
            findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                ?.setAspectRatio(if (value > 0) value else sourceRatio)
        }

    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, aspectRatio: Float) {
        sourceRatio = aspectRatio
        super.onContentAspectRatioChanged(contentFrame, if (forcedRatio > 0) forcedRatio else aspectRatio)
    }
}
