package me.zhanghai.android.files.viewer.media

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PlaybackSnapshotTest {
    @Test fun controllerCheckpointSurvivesParcelAndRestoresSourceMimeAndExternalSubtitle() {
        val sourceUri = "https://example.test/live"
        val subtitleUri = "content://documents/subtitle/42"
        // Controller MediaItems omit LocalConfiguration, including URI, MIME and subtitles.
        val item = MediaItem.Builder().setMediaId(sourceUri).setMediaMetadata(
            MediaMetadata.Builder().setTitle("视频").setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .setArtworkData(ByteArray(1024 * 1024), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setExtras(Bundle().apply {
                    putString(PlaybackSnapshot.SOURCE_MIME, MimeTypes.APPLICATION_MPD)
                    putString(PlaybackSnapshot.SUBTITLE_URI, subtitleUri)
                    putString(PlaybackSnapshot.SUBTITLE_MIME, MimeTypes.APPLICATION_SUBRIP)
                    putString(PlaybackSnapshot.SUBTITLE_NAME, "字幕.srt")
                    putString(MusicLyricsController.SOURCE_PATH, "file:///music/video.mp4")
                    putByteArray("unrelated_artwork", ByteArray(1024 * 1024))
                }).build()).build()
        assertNull(item.localConfiguration)
        val restored = checkNotNull(PlaybackSnapshot.fromBundle(parcelRoundTrip(
            checkNotNull(PlaybackSnapshot.capture(RecordingPlayer(listOf(item)).player)).toBundle())))
        val saved = restored.items.single()
        assertEquals(sourceUri, saved.mediaId)
        assertEquals(Uri.parse(sourceUri), saved.localConfiguration?.uri)
        assertEquals(MimeTypes.APPLICATION_MPD, saved.localConfiguration?.mimeType)
        val subtitle = checkNotNull(saved.localConfiguration).subtitleConfigurations.single()
        assertEquals(Uri.parse(subtitleUri), subtitle.uri)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitle.mimeType)
        assertEquals("字幕.srt", subtitle.label)
        assertEquals(C.SELECTION_FLAG_DEFAULT, subtitle.selectionFlags)
        assertEquals("视频", saved.mediaMetadata.title.toString())
        assertEquals("file:///music/video.mp4", saved.mediaMetadata.extras?.getString(MusicLyricsController.SOURCE_PATH))
        assertNull(saved.mediaMetadata.artworkData)
        assertFalse(checkNotNull(saved.mediaMetadata.extras).containsKey("unrelated_artwork"))
    }

    @Test fun bassFallbackCheckpointUsesTheOriginalSourceInsteadOfPcmUri() {
        val original = "content://library/lossless/1"
        val fallback = MediaItem.Builder().setMediaId(original).setUri("basspcm:///stream?uri=$original")
            .setMimeType(MimeTypes.AUDIO_WAV).setMediaMetadata(MediaMetadata.Builder()
                .setExtras(Bundle().apply { putString(PlaybackSnapshot.SOURCE_MIME, MimeTypes.AUDIO_FLAC) })
                .build()).build()
        val saved = checkNotNull(PlaybackSnapshot.capture(RecordingPlayer(listOf(fallback)).player)).items.single()
        assertEquals(Uri.parse(original), saved.localConfiguration?.uri)
        assertEquals(MimeTypes.AUDIO_FLAC, saved.localConfiguration?.mimeType)
    }

    @Test fun restorationKeepsTheCurrentItemPositionSettingsAndPausedOrPlayingIntent() {
        for (play in listOf(false, true)) {
            val source = RecordingPlayer(listOf(mediaItem("first"), mediaItem("second")),
                index = 1, positionMs = 123456, playWhenReady = play).apply {
                parameters = PlaybackParameters(1.5f, 0.9f)
                repeatMode = Player.REPEAT_MODE_ALL
                shuffle = true
                selections = selections.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            val saved = checkNotNull(PlaybackSnapshot.fromBundle(parcelRoundTrip(
                checkNotNull(PlaybackSnapshot.capture(source.player)).toBundle())))
            val target = RecordingPlayer(playWhenReady = true)
            assertTrue(saved.restore(target.player))
            assertEquals(source.items.map { it.mediaId }, target.items.map { it.mediaId })
            assertEquals(1, target.index)
            assertEquals(123456L, target.positionMs)
            assertEquals(source.parameters, target.parameters)
            assertEquals(Player.REPEAT_MODE_ALL, target.repeatMode)
            assertTrue(target.shuffle)
            assertTrue(C.TRACK_TYPE_AUDIO in target.selections.disabledTrackTypes)
            assertTrue(C.TRACK_TYPE_TEXT in target.selections.disabledTrackTypes)
            assertEquals(listOf(false), target.playWhenPrepared)
            assertEquals("playWhenReady:false", target.calls.first())
            assertEquals("playWhenReady:$play", target.calls.last())
            assertEquals(play, target.playWhenReady)
        }
    }

    @Test fun aLiveServicesPlaylistAndProgressAlwaysTakePrecedence() {
        val saved = checkNotNull(PlaybackSnapshot.capture(RecordingPlayer(listOf(mediaItem("old"))).player))
        val live = RecordingPlayer(listOf(mediaItem("new")), positionMs = 50000, playWhenReady = true)
        assertFalse(saved.restore(live.player))
        assertEquals("content://library/new", live.items.single().mediaId)
        assertEquals(50000L, live.positionMs)
        assertTrue(live.playWhenReady)
        assertTrue(live.calls.isEmpty())
    }

    @Test fun restorationDiscardsAudioAndSubtitleOverridesFromTheOldTrackGroups() {
        val saved = checkNotNull(PlaybackSnapshot.capture(RecordingPlayer(listOf(mediaItem("old"))).player))
        val audio = TrackGroup("old-audio", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val text = TrackGroup("old-text", Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val target = RecordingPlayer().apply {
            selections = selections.buildUpon().addOverride(TrackSelectionOverride(audio, 0))
                .addOverride(TrackSelectionOverride(text, 0)).build()
        }
        assertTrue(saved.restore(target.player))
        assertTrue(target.selections.overrides.isEmpty())
    }

    @Test fun emptyOrMalformedCheckpointCannotRestoreAnUnplayablePlaylist() {
        assertNull(PlaybackSnapshot.capture(RecordingPlayer().player))
        assertNull(PlaybackSnapshot.fromBundle(Bundle()))
        assertNull(PlaybackSnapshot.fromBundle(Bundle().apply {
            putParcelableArrayList("items", arrayListOf(Bundle()))
        }))
        val valid = checkNotNull(PlaybackSnapshot.capture(RecordingPlayer(listOf(mediaItem("first"))).player))
        assertNull(PlaybackSnapshot.fromBundle(valid.toBundle().apply { putFloat("speed", 0f) }))
    }

    private fun mediaItem(name: String) = MediaItem.Builder().setMediaId("content://library/$name")
        .setUri("content://library/$name").build()

    private fun parcelRoundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(PlaybackSnapshot::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
