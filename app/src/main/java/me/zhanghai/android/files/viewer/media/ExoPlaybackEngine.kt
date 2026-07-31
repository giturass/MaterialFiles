/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import me.zhanghai.android.files.R
import java.io.IOException
import java.util.Locale

/**
 * Plays video, and audio in every format ExoPlayer can handle.
 * [MediaPlaybackService] falls back to [BassPlaybackEngine] for audio this engine reports as
 * unsupported.
 */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    private val context: Context,
    hardwareDecoding: Boolean,
    private var listener: MediaPlaybackEngine.Listener?
) : MediaPlaybackEngine {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val player = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(
                if (hardwareDecoding) {
                    MediaCodecSelector.DEFAULT
                } else {
                    SoftwareMediaCodecSelector
                }
            )
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            // Audio focus, becoming-noisy and wake locks are all owned by the service, which needs
            // them for both engines and keeps them tied to the notification's lifetime.
            false
        )
        .setHandleAudioBecomingNoisy(false)
        .setWakeMode(C.WAKE_MODE_NONE)
        .build()

    private var videoLayout: MediaVideoLayout? = null
    private var status: MediaEngineStatus? = null
    private var videoSize = VideoSize.UNKNOWN
    private var audioTrackGroups = emptyList<Tracks.Group>()
    private var textTrackGroups = emptyList<Tracks.Group>()
    private var reportedUnplayable = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            listener?.onProgressChanged()
            mainHandler.postDelayed(this, MediaPlaybackEngine.PROGRESS_INTERVAL_MILLIS)
        }
    }

    override val hasMedia: Boolean
        get() = player.currentMediaItem != null

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val isSeekable: Boolean
        get() = player.isCurrentMediaItemSeekable

    override val currentTimeMillis: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    override val durationMillis: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    override val bufferingPercent: Float
        get() = player.bufferedPercentage.toFloat().coerceIn(0f, 100f)

    override val audioTracks: List<MediaTrack>
        get() = audioTrackGroups.mapIndexed { index, group -> MediaTrack(index, group.name(index)) }

    override val subtitleTracks: List<MediaTrack>
        get() = textTrackGroups.mapIndexed { index, group -> MediaTrack(index, group.name(index)) }

    override val selectedAudioTrackId: Int
        get() = audioTrackGroups.indexOfFirst { it.isSelected }

    override val selectedSubtitleTrackId: Int
        get() = if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            MediaTrack.DISABLED_ID
        } else {
            textTrackGroups.indexOfFirst { it.isSelected }
        }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateStatus()
                if (playbackState == Player.STATE_READY) {
                    listener?.onDurationChanged()
                    listener?.onSeekableChanged()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateStatus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateProgressTicker(isPlaying)
                updateStatus()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                listener?.onProgressChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error, error.isUnsupportedMedia)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                this@ExoPlaybackEngine.videoSize = videoSize
                applyVideoSize()
                listener?.onVideoSizeChanged(
                    videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                listener?.onTracksChanged()
                // A track whose codec has no decoder on this device is simply left unselected
                // instead of raising an error, which would silently play nothing. Surface it as a
                // recoverable failure so that audio can fall back to BASS.
                if (!reportedUnplayable && tracks.groups.isNotEmpty()
                    && tracks.groups.none { it.isSelected }) {
                    reportedUnplayable = true
                    listener?.onError(
                        IOException(context.getString(R.string.media_player_error_unsupported)),
                        true
                    )
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                videoLayout?.setCues(cueGroup.cues)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                listener?.onDurationChanged()
                listener?.onSeekableChanged()
            }
        })
    }

    override fun prepare(uri: Uri, startTimeMillis: Long, playWhenReady: Boolean) {
        reportedUnplayable = false
        setStatus(MediaEngineStatus.OPENING)
        player.setMediaItem(MediaItem.fromUri(uri))
        if (startTimeMillis > 0L) {
            player.seekTo(startTimeMillis)
        }
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(timeMillis: Long) {
        player.seekTo(timeMillis.coerceAtLeast(0L))
    }

    override fun setRate(rate: Float) {
        player.playbackParameters = PlaybackParameters(rate)
    }

    override fun setVolumeScale(scale: Float) {
        player.volume = scale.coerceIn(0f, 1f)
    }

    override fun setRepeat(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun selectAudioTrack(id: Int): Boolean {
        val group = audioTrackGroups.getOrNull(id) ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return true
    }

    override fun selectSubtitleTrack(id: Int): Boolean {
        val parameters = player.trackSelectionParameters.buildUpon()
        if (id == MediaTrack.DISABLED_ID) {
            parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = textTrackGroups.getOrNull(id) ?: return false
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = parameters.build()
        return true
    }

    override fun attachVideoLayout(layout: MediaVideoLayout) {
        if (videoLayout === layout) {
            return
        }
        videoLayout = layout
        player.setVideoSurfaceView(layout.surfaceView)
        applyVideoSize()
        layout.setCues(player.currentCues.cues)
    }

    override fun detachVideoLayout() {
        if (videoLayout == null) {
            return
        }
        videoLayout = null
        player.clearVideoSurface()
    }

    override fun release() {
        listener = null
        mainHandler.removeCallbacks(progressRunnable)
        videoLayout = null
        player.release()
    }

    private fun applyVideoSize() {
        videoLayout?.setVideoSize(
            videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio
        )
    }

    private fun updateProgressTicker(playing: Boolean) {
        mainHandler.removeCallbacks(progressRunnable)
        if (playing) {
            mainHandler.postDelayed(
                progressRunnable, MediaPlaybackEngine.PROGRESS_INTERVAL_MILLIS
            )
        }
    }

    private fun updateStatus() {
        setStatus(
            when (player.playbackState) {
                Player.STATE_IDLE -> if (player.playerError != null) {
                    MediaEngineStatus.ERROR
                } else {
                    MediaEngineStatus.OPENING
                }
                Player.STATE_BUFFERING ->
                    if (player.playWhenReady) {
                        MediaEngineStatus.BUFFERING
                    } else {
                        MediaEngineStatus.PAUSED
                    }
                Player.STATE_READY ->
                    if (player.playWhenReady) {
                        MediaEngineStatus.PLAYING
                    } else {
                        MediaEngineStatus.PAUSED
                    }
                Player.STATE_ENDED -> MediaEngineStatus.ENDED
                else -> return
            }
        )
    }

    private fun setStatus(status: MediaEngineStatus) {
        if (this.status == status) {
            return
        }
        this.status = status
        listener?.onStatusChanged(status)
    }

    private fun Tracks.Group.name(index: Int): String {
        val format = mediaTrackGroup.getFormat(0)
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { language ->
                Locale.forLanguageTag(language).displayName
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        return context.getString(R.string.media_player_track_format, index + 1)
    }

    /**
     * Only errors that mean "this device can't decode this media" are worth retrying with another
     * engine. I/O, DRM and remote errors would fail again just the same.
     */
    private val PlaybackException.isUnsupportedMedia: Boolean
        get() = when (errorCode) {
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> true
            else -> false
        }

    /**
     * Backs the "hardware decoding" preference: with it off we prefer software decoders, and only
     * fall back to the full list when a format has no software decoder at all.
     */
    private object SoftwareMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            return decoderInfos.filter { !it.hardwareAccelerated }.ifEmpty { decoderInfos }
        }
    }
}
