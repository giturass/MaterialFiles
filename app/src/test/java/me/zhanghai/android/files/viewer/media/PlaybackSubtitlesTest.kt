package me.zhanghai.android.files.viewer.media

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
class PlaybackSubtitlesTest {
    private val mediaId = "content://library/video/1"
    private val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse("content://documents/subtitle/1"))
        .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLabel("字幕.srt")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    @Test fun attachingSubtitlePreservesPlaybackAndEnablesTextWithoutChangingAudioSelection() {
        for (play in listOf(false, true)) {
            val fake = videoPlayer(play)
            val audio = TrackGroup("audio", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
            val text = TrackGroup("old-text", Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
            val audioOverride = TrackSelectionOverride(audio, 0)
            fake.selections = fake.selections.buildUpon().addOverride(audioOverride)
                .addOverride(TrackSelectionOverride(text, 0)).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            assertTrue(PlaybackActions.applySubtitle(fake.player, mediaId, subtitle))
            assertEquals(50000L, fake.positionMs)
            assertEquals(play, fake.playWhenReady)
            assertEquals(listOf(play), fake.playWhenPrepared)
            assertEquals(listOf(subtitle), fake.items.single().localConfiguration?.subtitleConfigurations)
            val extras = checkNotNull(fake.items.single().mediaMetadata.extras)
            assertEquals(subtitle.uri.toString(), extras.getString(PlaybackSnapshot.SUBTITLE_URI))
            assertEquals(subtitle.mimeType, extras.getString(PlaybackSnapshot.SUBTITLE_MIME))
            assertEquals(subtitle.label, extras.getString(PlaybackSnapshot.SUBTITLE_NAME))
            assertEquals(mapOf(audio to audioOverride), fake.selections.overrides)
            assertFalse(C.TRACK_TYPE_TEXT in fake.selections.disabledTrackTypes)
        }
    }

    @Test fun resendingACompletedRequestDoesNotSeekOrReloadTheVideo() {
        val fake = videoPlayer(play = true)
        assertTrue(PlaybackActions.applySubtitle(fake.player, mediaId, subtitle))
        fake.positionMs = 72000
        fake.calls.clear()
        fake.playWhenPrepared.clear()
        assertTrue(PlaybackActions.applySubtitle(fake.player, mediaId, subtitle))
        assertEquals(72000L, fake.positionMs)
        assertTrue(fake.playWhenReady)
        assertTrue(fake.playWhenPrepared.isEmpty())
        assertEquals(listOf("replaceMediaItem", "setTrackSelectionParameters"), fake.calls)
    }

    @Test fun aLateSubtitleResultCannotModifyADifferentOrEmptyPlaylist() {
        val fake = videoPlayer(play = false)
        assertFalse(PlaybackActions.applySubtitle(fake.player, "content://library/video/2", subtitle))
        assertTrue(fake.calls.isEmpty())
        assertTrue(checkNotNull(fake.items.single().localConfiguration).subtitleConfigurations.isEmpty())
        val empty = RecordingPlayer()
        assertFalse(PlaybackActions.applySubtitle(empty.player, mediaId, subtitle))
        assertTrue(empty.calls.isEmpty())
    }

    private fun videoPlayer(play: Boolean) = RecordingPlayer(
        listOf(MediaItem.Builder().setMediaId(mediaId).setUri(mediaId).build()),
        positionMs = 50000, playWhenReady = play)
}
