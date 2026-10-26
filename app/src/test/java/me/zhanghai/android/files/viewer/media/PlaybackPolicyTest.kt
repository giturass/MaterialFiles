package me.zhanghai.android.files.viewer.media

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun unsupportedAudioCanFallbackButNetworkAndOutputFailuresCannot() {
        assertTrue(PlaybackPolicy.canFallback(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(PlaybackPolicy.canFallback(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)) {
            assertFalse(PlaybackPolicy.canFallback(code))
        }
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
