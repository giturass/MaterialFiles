package me.zhanghai.android.files.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Paths
import me.zhanghai.android.files.filejob.OpenCacheManager
import me.zhanghai.android.files.R

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var preferences: MediaPreferences
    private val bassItems = ConcurrentHashMap.newKeySet<String>()
    private val leases = mutableMapOf<String, Closeable>()

    override fun onCreate() {
        super.onCreate()
        preferences = MediaPreferences(this)
        // Like VLC: publish a real media-session token and a standard MediaStyle notification.
        // ColorOS owns the system music Fluid Cloud and reads this session's data and actions.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.media_notification_channel).build().apply {
                setSmallIcon(R.drawable.media_music)
            })
        player = createPlayer()
        session = MediaSession.Builder(this, player)
            .setSessionExtras(Bundle().apply {
                // Reserve the previous/next slots in Android/ColorOS's media controls, as VLC does.
                putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS", true)
                putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT", true)
            })
            .setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MediaPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCallback(SessionCallback())
            .build()
    }

    private fun createPlayer(): ExoPlayer {
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (!MimeTypes.isVideo(mime)) decoders else when (preferences.decoderMode) {
                "software" -> decoders.filter { it.softwareOnly }
                "hardware" -> decoders.filter { it.hardwareAccelerated }
                else -> decoders.sortedByDescending { it.hardwareAccelerated }
            }
        }
        val renderers = DefaultRenderersFactory(this).setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(SourceFactory())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                repeatMode = preferences.repeatMode
                setPlaybackSpeed(preferences.speed)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val item = currentMediaItem ?: return
                        val uri = item.localConfiguration?.uri ?: return
                        if (!MediaFiles.isVideo(item) && uri.scheme in listOf("content", "file")
                            && isAudioDecodeFailure(error) && bassItems.add(item.mediaId)) {
                            // Keep the same player, timeline, position, speed, repeat and session.
                            val index = currentMediaItemIndex
                            val position = currentPosition
                            val playing = playWhenReady
                            val items = (0 until mediaItemCount).map(::getMediaItemAt)
                            setMediaItems(items, index, position)
                            prepare()
                            playWhenReady = playing
                        }
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        updateLeases()
                    }

                    override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                        preferences.speed = parameters.speed
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        preferences.repeatMode = repeatMode
                    }
                })
            }
    }

    private fun isAudioDecodeFailure(error: PlaybackException) = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    )

    private fun updateLeases() {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        for (id in leases.keys.toList()) {
            if (id !in ids) leases.remove(id)?.close()
        }
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (item.mediaId in leases) continue
            val uri = item.localConfiguration?.uri ?: continue
            if (uri.authority != me.zhanghai.android.files.BuildConfig.FILE_PROVIDIER_AUTHORITY) continue
            try {
                OpenCacheManager.acquire(Paths.get(URI.create(uri.toString())))
                    ?.let { leases[item.mediaId] = it }
            } catch (_: Exception) {
                // Playback reports unreadable files through the player's error listener.
            }
        }
        bassItems.retainAll(ids)
    }

    private fun setDecoder(mode: String) {
        if (mode == preferences.decoderMode) return
        val oldPlayer = player
        val items = (0 until oldPlayer.mediaItemCount).map(oldPlayer::getMediaItemAt)
        val index = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = oldPlayer.currentPosition
        val playing = oldPlayer.playWhenReady
        val tracks = oldPlayer.trackSelectionParameters
        preferences.decoderMode = mode
        player = createPlayer().apply {
            trackSelectionParameters = tracks
            if (items.isNotEmpty()) {
                setMediaItems(items, index, position)
                prepare()
                playWhenReady = playing
            }
        }
        session.setPlayer(player)
        oldPlayer.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session.release()
        player.release()
        leases.values.forEach { it.close() }
        leases.clear()
        super.onDestroy()
    }

    private inner class SourceFactory : MediaSource.Factory {
        private val defaultFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@MediaPlaybackService))
        override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) =
            apply { defaultFactory.setDrmSessionManagerProvider(provider) }
        override fun setLoadErrorHandlingPolicy(policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) =
            apply { defaultFactory.setLoadErrorHandlingPolicy(policy) }
        override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (mediaItem.mediaId in bassItems) {
                ProgressiveMediaSource.Factory(BassDataSource.Factory(this@MediaPlaybackService),
                    ExtractorsFactory { arrayOf(WavExtractor()) })
                    .createMediaSource(mediaItem.buildUpon().setMimeType(MimeTypes.AUDIO_WAV).build())
            } else defaultFactory.createMediaSource(mediaItem)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            if (controller.packageName != packageName) {
                return Futures.immediateFailedFuture(SecurityException("External controllers cannot replace local files"))
            }
            return super.onAddMediaItems(session, controller, mediaItems)
        }

        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo):
            ListenableFuture<MediaSession.ConnectionResult> {
            if (controller.packageName != packageName) {
                return super.onConnectAsync(session, controller)
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_DECODER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CLOSE, Bundle.EMPTY)).build()
            return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands).build())
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                COMMAND_DECODER -> {
                    val mode = args.getString("mode")
                    if (mode !in listOf("auto", "software", "hardware")) {
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                    setDecoder(mode!!)
                }
                COMMAND_CLOSE -> {
                    player.stop()
                    player.clearMediaItems()
                    stopSelf()
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        const val COMMAND_DECODER = "me.zhanghai.android.files.media.DECODER"
        const val COMMAND_CLOSE = "me.zhanghai.android.files.media.CLOSE"
    }
}
