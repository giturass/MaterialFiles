package me.zhanghai.android.files.viewer.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** Small activity checkpoint; a live service's playlist always takes precedence. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class PlaybackSnapshot(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val parameters: PlaybackParameters,
    val repeatMode: Int,
    val shuffle: Boolean,
    val audioDisabled: Boolean,
    val textDisabled: Boolean
) {
    fun restore(player: Player): Boolean {
        if (player.mediaItemCount != 0 || items.isEmpty()) return false
        player.playWhenReady = false
        player.setMediaItems(items, index.coerceIn(items.indices), positionMs.coerceAtLeast(0))
        player.playbackParameters = parameters
        player.repeatMode = repeatMode
        player.shuffleModeEnabled = shuffle
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioDisabled)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textDisabled).build()
        player.prepare()
        player.playWhenReady = playWhenReady
        return true
    }

    fun toBundle() = Bundle().apply {
        putInt("interface_version", MediaLibraryInfo.INTERFACE_VERSION)
        putParcelableArrayList("items", ArrayList(items.map {
            it.toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION)
        }))
        putInt("index", index)
        putLong("position", positionMs)
        putBoolean("play", playWhenReady)
        putFloat("speed", parameters.speed)
        putFloat("pitch", parameters.pitch)
        putInt("repeat", repeatMode)
        putBoolean("shuffle", shuffle)
        putBoolean("audio_disabled", audioDisabled)
        putBoolean("text_disabled", textDisabled)
    }

    companion object {
        const val SOURCE_MIME = "media.source_mime"
        const val SUBTITLE_URI = "media.subtitle_uri"
        const val SUBTITLE_MIME = "media.subtitle_mime"
        const val SUBTITLE_NAME = "media.subtitle_name"

        fun capture(player: Player): PlaybackSnapshot? {
            if (player.mediaItemCount == 0) return null
            val items = (0 until player.mediaItemCount.coerceAtMost(100)).map { index ->
                val item = player.getMediaItemAt(index)
                val metadata = item.mediaMetadata
                val extras = Bundle().apply {
                    for (key in listOf(SOURCE_MIME, SUBTITLE_URI, SUBTITLE_MIME, SUBTITLE_NAME,
                        MusicLyricsController.SOURCE_PATH)) {
                        metadata.extras?.getString(key)?.let { putString(key, it) }
                    }
                }
                // MediaController omits LocalConfiguration. Our media IDs retain the original
                // URI even after BASS fallback. Save only display/source fields, never artwork.
                MediaItem.Builder().setMediaId(item.mediaId).setUri(item.mediaId)
                    .setMimeType(extras.getString(SOURCE_MIME))
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(metadata.title?.take(256)).setArtist(metadata.artist?.take(256))
                        .setAlbumTitle(metadata.albumTitle?.take(256)).setMediaType(metadata.mediaType)
                        .setIsPlayable(true).setIsBrowsable(false).setExtras(extras).build())
                    .apply {
                        val uri = extras.getString(SUBTITLE_URI)
                        if (uri != null) setSubtitleConfigurations(listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                                .setMimeType(extras.getString(SUBTITLE_MIME))
                                .setLabel(extras.getString(SUBTITLE_NAME))
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                        ))
                    }.build()
            }
            val disabled = player.trackSelectionParameters.disabledTrackTypes
            return PlaybackSnapshot(items, player.currentMediaItemIndex, player.currentPosition,
                player.playWhenReady, player.playbackParameters, player.repeatMode,
                player.shuffleModeEnabled, C.TRACK_TYPE_AUDIO in disabled, C.TRACK_TYPE_TEXT in disabled)
        }

        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle): PlaybackSnapshot? = runCatching {
            val version = bundle.getInt("interface_version", MediaLibraryInfo.INTERFACE_VERSION)
            val items = bundle.getParcelableArrayList<Bundle>("items")?.take(100)
                ?.map { MediaItem.fromBundle(it, version) } ?: return null
            if (items.isEmpty() || items.any { it.localConfiguration == null }) return null
            PlaybackSnapshot(items, bundle.getInt("index"), bundle.getLong("position"),
                bundle.getBoolean("play"), PlaybackParameters(bundle.getFloat("speed", 1f),
                    bundle.getFloat("pitch", 1f)), bundle.getInt("repeat").coerceIn(0, 2),
                bundle.getBoolean("shuffle"), bundle.getBoolean("audio_disabled"), bundle.getBoolean("text_disabled"))
        }.getOrNull()
    }
}
