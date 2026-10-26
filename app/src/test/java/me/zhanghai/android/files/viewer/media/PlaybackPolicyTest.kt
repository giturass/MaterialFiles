package me.zhanghai.android.files.viewer.media

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    private fun canFallback(
        code: Int = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        scheme: String? = "content",
        isMusic: Boolean = true,
        hasAudioTrack: Boolean = false,
        hasVideoTrack: Boolean = false,
        hasDrm: Boolean = false
    ) = PlaybackPolicy.canFallback(code, scheme, isMusic, hasAudioTrack, hasVideoTrack, hasDrm)

    @Test fun unsupportedAudioCanFallbackButNetworkAndOutputFailuresCannot() {
        assertTrue(canFallback())
        assertTrue(canFallback(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)) {
            assertFalse(canFallback(code))
        }
    }

    @Test fun detectedVideoPreventsAudioOnlyFallbackEvenWhenMislabeledAsMusic() {
        assertFalse(canFallback(hasAudioTrack = true, hasVideoTrack = true))
        assertFalse(canFallback(isMusic = false, hasVideoTrack = true))
        assertFalse(canFallback(isMusic = false))
        assertTrue(canFallback(isMusic = false, hasAudioTrack = true))
    }

    @Test fun fallbackRequiresLocalUnencryptedSourceAndCannotRecurse() {
        assertTrue(canFallback(scheme = "file"))
        for (scheme in listOf("http", "https", "basspcm", null)) {
            assertFalse(canFallback(scheme = scheme))
        }
        assertFalse(canFallback(hasDrm = true))
    }
    @Test fun losslessExtensionsWorkWhenProvidersReturnOctetStream() {
        for (name in listOf("歌曲.ALAC", "recording.m4a", "lossless.wv", "track.flac")) {
            assertTrue(PlaybackPolicy.isAudio(name, "application/octet-stream"))
            assertTrue(PlaybackPolicy.isMedia(name, null))
        }
        assertTrue(PlaybackPolicy.isMedia("video.mkv", "application/octet-stream"))
        assertFalse(PlaybackPolicy.isAudio("video.mkv", "video/x-matroska"))
        assertFalse(PlaybackPolicy.isMedia("notes.txt", "text/plain"))
    }
}
