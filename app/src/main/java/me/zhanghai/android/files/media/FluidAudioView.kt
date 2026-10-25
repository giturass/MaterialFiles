package me.zhanghai.android.files.media

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.card.MaterialCardView
import me.zhanghai.android.files.R

/** In-app capsule and expanded music card, available on every Android device. */
@OptIn(UnstableApi::class)
class FluidAudioView(context: Context) : MaterialCardView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private val title = label(18f)
    private val artist = label(13f).apply { setTextColor(0xFFBDBDBD.toInt()) }
    private val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.media_music)
        contentDescription = context.getString(R.string.media_cover)
    }
    private val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val seek = SeekBar(context)
    private val time = label(12f).apply { gravity = Gravity.CENTER }
    private val play = button(R.string.media_play) { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    private val previous = button(R.string.media_previous) { player?.seekToPreviousMediaItem() }
    private val next = button(R.string.media_next) { player?.seekToNextMediaItem() }
    private var expanded = true
    private var dragging = false
    private var artworkBytes: ByteArray? = null
    var player: Player? = null
        set(value) { field = value; update() }

    init {
        radius = dp(32).toFloat()
        setCardBackgroundColor(Color.BLACK)
        strokeColor = 0xFF383838.toInt()
        strokeWidth = dp(1)
        cardElevation = dp(6).toFloat()
        addView(content)
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(artwork, LinearLayout.LayoutParams(dp(56), dp(56)))
        heading.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(title)
            addView(artist)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val expand = button(R.string.media_collapse) {
            TransitionManager.beginDelayedTransition(content, AutoTransition().setDuration(220))
            expanded = !expanded
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            (it as TextView).setText(if (expanded) R.string.media_collapse else R.string.media_expand)
        }
        heading.addView(expand)
        content.addView(heading)
        details.addView(seek)
        details.addView(time)
        val transport = LinearLayout(context).apply { gravity = Gravity.CENTER }
        for (control in listOf(previous, play, next)) {
            transport.addView(control, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        details.addView(transport)
        content.addView(details)
        seek.max = 10000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player?.let { if (it.duration > 0 && it.duration != C.TIME_UNSET) {
                    it.seekTo(it.duration * seekBar.progress / 10000)
                } }
                dragging = false
            }
        })
    }

    fun update() {
        val player = player ?: return
        val metadata = player.mediaMetadata
        title.text = metadata.title ?: context.getString(R.string.media_audio_title)
        artist.text = metadata.artist
        artist.visibility = if (metadata.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        val bytes = metadata.artworkData
        if (bytes !== artworkBytes) {
            artworkBytes = bytes
            // Embedded covers are bounded to avoid allocating arbitrarily large bitmaps on the UI thread.
            val bitmap = bytes?.let {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, options)
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 256).coerceAtLeast(1)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(it, 0, it.size, options)
            }
            if (bitmap != null) artwork.setImageBitmap(bitmap) else artwork.setImageResource(R.drawable.media_music)
        }
        play.setText(if (player.isPlaying) R.string.media_pause else R.string.media_play)
        previous.isEnabled = player.hasPreviousMediaItem()
        next.isEnabled = player.hasNextMediaItem()
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        seek.isEnabled = duration > 0 && player.isCurrentMediaItemSeekable
        if (!dragging) seek.progress = if (duration > 0) (player.currentPosition * 10000 / duration).toInt() else 0
        time.text = "${formatTime(player.currentPosition)} / ${formatTime(duration)}"
    }

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun button(text: Int, action: (View) -> Unit) = label(14f).apply {
        setText(text)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attrs.getDrawable(0)
        attrs.recycle()
        setOnClickListener(action)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun formatTime(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
