/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.net.Uri

/**
 * Playback state as reported by an engine. [MediaPlaybackService] owns the two states that aren't a
 * property of the engine, namely [MediaPlaybackService.Status.IDLE] and
 * [MediaPlaybackService.Status.STOPPED].
 */
enum class MediaEngineStatus {
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

/**
 * How the video surface is sized within its container. The order matches
 * `R.array.media_player_video_scale_entries`.
 */
enum class VideoScaleType {
    /** Largest size that fits inside the container while keeping the video aspect ratio. */
    BEST_FIT,
    /** Smallest size that covers the container while keeping the video aspect ratio, cropping. */
    FIT_SCREEN,
    /** Stretched to exactly the container size, ignoring the video aspect ratio. */
    FILL,
    RATIO_16_9,
    RATIO_4_3,
    /** The video's own pixel size, centered and cropped when larger than the container. */
    ORIGINAL
}

/** A selectable audio or subtitle track. An [id] of [DISABLED_ID] turns the track type off. */
data class MediaTrack(val id: Int, val name: String) {
    companion object {
        const val DISABLED_ID = -1
    }
}

/**
 * A decoding backend behind [MediaPlaybackService]. Everything that isn't decoding - audio focus,
 * the media session, the notification, wake locks and the service lifecycle - stays in the service
 * so that engines can be swapped without disturbing an ongoing playback session.
 *
 * All methods are called on the main thread, and all [Listener] callbacks are delivered on it.
 */
interface MediaPlaybackEngine {
    interface Listener {
        fun onStatusChanged(status: MediaEngineStatus)

        /** The playback position advanced. Engines throttle this to [PROGRESS_INTERVAL_MILLIS]. */
        fun onProgressChanged()

        fun onDurationChanged()

        fun onSeekableChanged()

        fun onTracksChanged()

        fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float)

        /**
         * @param recoverable whether another engine might succeed where this one failed, i.e. the
         *   media is readable but its container or codec isn't supported.
         */
        fun onError(throwable: Throwable, recoverable: Boolean)
    }

    val hasMedia: Boolean
    val isPlaying: Boolean
    val isSeekable: Boolean
    val currentTimeMillis: Long
    val durationMillis: Long
    val bufferingPercent: Float

    val audioTracks: List<MediaTrack>
    val selectedAudioTrackId: Int
    val subtitleTracks: List<MediaTrack>
    val selectedSubtitleTrackId: Int

    fun prepare(uri: Uri, startTimeMillis: Long, playWhenReady: Boolean)

    fun play()

    fun pause()

    fun seekTo(timeMillis: Long)

    fun setRate(rate: Float)

    /** @param scale a linear volume multiplier within `[0, 1]`, used for ducking. */
    fun setVolumeScale(scale: Float)

    fun setRepeat(enabled: Boolean)

    fun selectAudioTrack(id: Int): Boolean

    fun selectSubtitleTrack(id: Int): Boolean

    fun attachVideoLayout(layout: MediaVideoLayout)

    fun detachVideoLayout()

    /** Releases all resources and detaches the listener, so late callbacks are dropped. */
    fun release()

    companion object {
        const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
