package me.zhanghai.android.files.viewer.media

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getColorByAttr
import java.util.Locale
import com.google.android.material.R as MaterialR

/** Shared session bindings; music and video deliberately supply different control layouts. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaTransportControls(
    private val root: View,
    private val onInteraction: (scrubbing: Boolean) -> Unit = {}
) {
    private val play = root.findViewById<MaterialButton>(R.id.media_play_pause)
    private val previous: MaterialButton? = root.findViewById(R.id.media_previous)
    private val next: MaterialButton? = root.findViewById(R.id.media_next)
    private val rewind: MaterialButton? = root.findViewById(R.id.media_rewind)
    private val forward: MaterialButton? = root.findViewById(R.id.media_forward)
    private val shuffle: MaterialButton? = root.findViewById(R.id.media_shuffle)
    private val repeat: MaterialButton? = root.findViewById(R.id.media_repeat)
    private val seek = root.findViewById<Slider>(R.id.media_seek)
    private val position = root.findViewById<TextView>(R.id.media_position)
    private val duration = root.findViewById<TextView>(R.id.media_duration)
    private val handler = Handler(Looper.getMainLooper())
    private var scrubbing = false
    private var scrubMediaId: String? = null
    private var scrubDuration = 0L
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (player != null) handler.postDelayed(this, 500)
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    var player: Player? = null
        set(value) {
            if (field === value) return
            field?.removeListener(listener)
            handler.removeCallbacks(tick)
            field = value
            scrubbing = false
            value?.addListener(listener)
            update()
            if (value != null) handler.post(tick)
        }

    init {
        play.setOnClickListener { player?.let(Util::handlePlayPauseButtonAction); onInteraction(false) }
        previous?.setOnClickListener { player?.seekToPreviousMediaItem(); onInteraction(false) }
        next?.setOnClickListener { player?.seekToNextMediaItem(); onInteraction(false) }
        rewind?.setOnClickListener { player?.seekBack(); onInteraction(false) }
        forward?.setOnClickListener { player?.seekForward(); onInteraction(false) }
        shuffle?.setOnClickListener {
            player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            onInteraction(false)
        }
        repeat?.setOnClickListener {
            player?.let { it.repeatMode = (it.repeatMode + 1) % 3 }
            onInteraction(false)
        }
        seek.setLabelFormatter { formatTime((it / 1000 * seekDuration()).toLong()) }
        seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val target = (value / 1000 * seekDuration()).toLong()
                position.text = formatTime(target)
                // Keyboard and accessibility actions do not dispatch touch tracking callbacks.
                if (!scrubbing) {
                    seekTo(target)
                    onInteraction(false)
                }
            }
        }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                scrubbing = true
                scrubMediaId = player?.currentMediaItem?.mediaId
                scrubDuration = player?.duration?.coerceAtLeast(0) ?: 0
                onInteraction(true)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                if (scrubMediaId == player?.currentMediaItem?.mediaId) {
                    seekTo((slider.value / 1000 * scrubDuration).toLong())
                }
                scrubbing = false
                updateProgress()
                onInteraction(false)
            }
        })
        update()
    }

    private fun seekDuration() = if (scrubbing) scrubDuration else player?.duration?.coerceAtLeast(0) ?: 0

    private fun seekTo(positionMs: Long) {
        val player = player ?: return
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            && player.isCurrentMediaItemSeekable && player.duration > 0) {
            player.seekTo(positionMs.coerceIn(0, player.duration))
        }
    }

    private fun update() {
        val player = player
        val playing = !Util.shouldShowPlayButton(player)
        play.setIconResource(if (playing) R.drawable.media_pause_icon else R.drawable.media_play_icon)
        play.contentDescription = root.context.getString(if (playing) R.string.media_pause else R.string.media_play)
        enable(play, player != null && player.mediaItemCount > 0 && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        enable(previous, player?.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) == true)
        enable(next, player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) == true)
        enable(rewind, player?.isCommandAvailable(Player.COMMAND_SEEK_BACK) == true)
        enable(forward, player?.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) == true)
        enable(shuffle, player?.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) == true)
        enable(repeat, player?.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) == true)
        select(shuffle, player?.shuffleModeEnabled == true)
        val repeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        select(repeat, repeatMode != Player.REPEAT_MODE_OFF)
        repeat?.setIconResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.media_repeat_one_icon else R.drawable.media_repeat_icon)
        repeat?.stateDescription = root.resources.getStringArray(R.array.media_repeat_modes)[repeatMode]
        updateProgress()
    }

    private fun select(button: MaterialButton?, selected: Boolean) {
        button ?: return
        button.isSelected = selected
        button.stateDescription = root.context.getString(if (selected) R.string.media_on else R.string.media_off)
        button.iconTint = ColorStateList.valueOf(root.context.getColorByAttr(
            if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurfaceVariant))
        button.backgroundTintList = ColorStateList.valueOf(
            if (selected) root.context.getColorByAttr(MaterialR.attr.colorSecondaryContainer) else android.graphics.Color.TRANSPARENT)
    }

    private fun enable(view: View?, enabled: Boolean) {
        view?.isEnabled = enabled
        view?.alpha = if (enabled) 1f else 0.38f
    }

    private fun updateProgress() {
        if (scrubbing) return
        val player = player
        val length = player?.duration?.coerceAtLeast(0) ?: 0
        val current = player?.currentPosition?.coerceAtLeast(0) ?: 0
        seek.isEnabled = length > 0 && player?.isCurrentMediaItemSeekable == true
            && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        seek.value = if (length > 0) (current.toDouble() / length * 1000).toFloat().coerceIn(0f, 1000f) else 0f
        position.text = formatTime(current)
        duration.text = when {
            player?.isCurrentMediaItemLive == true -> root.context.getString(R.string.media_live)
            length > 0 -> formatTime(length)
            else -> root.context.getString(R.string.media_unknown_time)
        }
    }

    companion object {
        fun formatTime(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
