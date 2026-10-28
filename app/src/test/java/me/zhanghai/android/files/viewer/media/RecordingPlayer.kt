package me.zhanghai.android.files.viewer.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import java.lang.reflect.Proxy

/** Records public Player calls without a decoder, media service or audio output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RecordingPlayer(
    var items: List<MediaItem> = emptyList(),
    var index: Int = 0,
    var positionMs: Long = 0,
    var playWhenReady: Boolean = false
) {
    var state = Player.STATE_IDLE
    var parameters = PlaybackParameters.DEFAULT
    var repeatMode = Player.REPEAT_MODE_OFF
    var shuffle = false
    var selections = TrackSelectionParameters.Builder().build()
    val commands = mutableSetOf(Player.COMMAND_PREPARE, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    val calls = mutableListOf<String>()
    val playWhenPrepared = mutableListOf<Boolean>()

    @Suppress("UNCHECKED_CAST")
    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader,
        arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args!![0] as Int]
            "getCurrentMediaItem" -> items.getOrNull(index)
            "getCurrentMediaItemIndex" -> index
            "getCurrentPosition" -> positionMs
            "getPlayWhenReady" -> playWhenReady
            "getPlaybackState" -> state
            "getPlaybackParameters" -> parameters
            "getRepeatMode" -> repeatMode
            "getShuffleModeEnabled" -> shuffle
            "getTrackSelectionParameters" -> selections
            "isCommandAvailable" -> args!![0] as Int in commands
            "setMediaItems" -> {
                items = args!![0] as List<MediaItem>
                index = args[1] as Int
                positionMs = args[2] as Long
                calls += "setMediaItems"
                null
            }
            "replaceMediaItem" -> {
                items = items.toMutableList().apply { set(args!![0] as Int, args[1] as MediaItem) }
                calls += "replaceMediaItem"
                null
            }
            "setPlayWhenReady" -> {
                playWhenReady = args!![0] as Boolean
                calls += "playWhenReady:$playWhenReady"
                null
            }
            "setPlaybackParameters" -> {
                parameters = args!![0] as PlaybackParameters
                calls += "setPlaybackParameters"
                null
            }
            "setRepeatMode" -> {
                repeatMode = args!![0] as Int
                calls += "setRepeatMode"
                null
            }
            "setShuffleModeEnabled" -> {
                shuffle = args!![0] as Boolean
                calls += "setShuffleModeEnabled"
                null
            }
            "setTrackSelectionParameters" -> {
                selections = args!![0] as TrackSelectionParameters
                calls += "setTrackSelectionParameters"
                null
            }
            "seekTo" -> {
                index = args!![0] as Int
                positionMs = args[1] as Long
                calls += "seekTo"
                null
            }
            "seekToNextMediaItem", "seekToPreviousMediaItem" -> {
                index += if (method.name == "seekToNextMediaItem") 1 else -1
                positionMs = 0
                calls += method.name
                null
            }
            "prepare" -> {
                calls += "prepare"
                playWhenPrepared += playWhenReady
                state = Player.STATE_BUFFERING
                null
            }
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordingPlayer"
            else -> error("Unexpected Player call: ${method.name}")
        }
    } as Player
}
