package me.zhanghai.android.files.viewer.media

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackActions {
    fun seekToAdjacentMediaItem(player: Player, next: Boolean) {
        val command = if (next) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        if (!player.isCommandAvailable(command)) return
        if (next) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
        prepareIfIdle(player)
    }

    fun prepareIfIdle(player: Player) {
        // Seeking after an error retains STATE_IDLE and playWhenReady. Prepare the new item
        // without turning a user's paused playlist into an automatically playing one.
        if (player.playbackState == Player.STATE_IDLE && player.isCommandAvailable(Player.COMMAND_PREPARE)) {
            player.prepare()
        }
    }

    fun applySubtitle(player: Player, mediaId: String, subtitle: MediaItem.SubtitleConfiguration): Boolean {
        val item = player.currentMediaItem?.takeIf { it.mediaId == mediaId } ?: return false
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(PlaybackSnapshot.SUBTITLE_URI, subtitle.uri.toString())
            putString(PlaybackSnapshot.SUBTITLE_MIME, subtitle.mimeType)
            putString(PlaybackSnapshot.SUBTITLE_NAME, subtitle.label)
        }
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        val play = player.playWhenReady
        val changed = item.localConfiguration?.subtitleConfigurations != listOf(subtitle)
        player.replaceMediaItem(index, item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .setSubtitleConfigurations(listOf(subtitle)).build())
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        // A stopped activity may resend a command whose result it never received.
        if (changed) {
            player.seekTo(index, position)
            player.prepare()
            player.playWhenReady = play
        }
        return true
    }
}
