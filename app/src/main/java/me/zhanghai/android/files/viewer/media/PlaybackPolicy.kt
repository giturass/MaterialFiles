package me.zhanghai.android.files.viewer.media

import androidx.media3.common.PlaybackException

internal object PlaybackPolicy {
    fun isVideo(isMusic: Boolean, hasAudioTrack: Boolean, hasVideoTrack: Boolean): Boolean = when {
        hasVideoTrack -> true
        hasAudioTrack -> false
        else -> !isMusic
    }

    // Decoder/container failures may be retried once. Access, network, DRM and output errors may not.
    fun canFallback(
        code: Int,
        scheme: String?,
        isMusic: Boolean,
        hasAudioTrack: Boolean,
        hasVideoTrack: Boolean,
        hasDrm: Boolean
    ): Boolean = scheme in setOf("content", "file") && !hasVideoTrack && !hasDrm
        && (isMusic || hasAudioTrack) && code in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    )

    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "alac", "flac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "wv", "ape", "amr", "mid", "midi")
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts", "mpg", "mpeg", "vob", "flv", "wmv")
    fun isAudio(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in audioExtensions
    fun isMedia(name: String, mime: String?): Boolean = isAudio(name, mime)
        || mime?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in videoExtensions
}
