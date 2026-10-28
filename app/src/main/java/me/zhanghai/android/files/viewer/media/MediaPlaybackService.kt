package me.zhanghai.android.files.viewer.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.fileProviderPath
import me.zhanghai.android.files.filejob.OpenCacheManager
import java.io.Closeable

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    @Volatile private var decoderMode = 0
    private var background = true
    private val leases = mutableMapOf<String, Closeable>()
    private val fallbackLyrics = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        decoderMode = preferences.getInt(DECODER, 0).coerceIn(0, 2)
        background = preferences.getBoolean(BACKGROUND, true)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (!mime.startsWith("video/")) codecs else when (decoderMode) {
                    1 -> codecs.filter { it.hardwareAccelerated }
                    2 -> codecs.filter { it.softwareOnly }
                    else -> codecs.sortedByDescending { it.hardwareAccelerated }
                }
            }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory { PlaybackDataSource(this) })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryBassFallback(error)
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateCacheLeases()
            }
        })
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MediaPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .setCallback(SessionCallback())
            .setSessionExtras(settings())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(2401)
            .setChannelId("media_playback")
            .setChannelName(R.string.media_notification_channel)
            .build())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!background) pauseAllPlayersAndStopSelf()
        else super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        leases.values.forEach { it.close() }
        leases.clear()
        super.onDestroy()
    }

    private fun tryBassFallback(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        val config = item.localConfiguration ?: return
        val groups = player.currentTracks.groups
        if (!PlaybackPolicy.canFallback(
                code = error.errorCode,
                scheme = config.uri.scheme,
                isMusic = item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC,
                hasAudioTrack = groups.any { it.type == C.TRACK_TYPE_AUDIO },
                hasVideoTrack = groups.any { it.type == C.TRACK_TYPE_VIDEO },
                hasDrm = config.drmConfiguration != null
            )) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val play = player.playWhenReady
        EmbeddedLyrics.read(player.currentTracks)?.let { fallbackLyrics[item.mediaId] = it }
        val fallback = item.buildUpon().setUri(BassPcmDataSource.wrap(config.uri))
            .setMimeType(MimeTypes.AUDIO_WAV)
            .setMediaMetadata(player.mediaMetadata.buildUpon().setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .build()
        player.replaceMediaItem(index, fallback)
        player.seekTo(index, position)
        player.prepare()
        player.playWhenReady = play
    }

    private fun updateCacheLeases() {
        val active = mutableSetOf<String>()
        val fallbackIds = mutableSetOf<String>()
        for (index in 0 until player.mediaItemCount) {
            val config = player.getMediaItemAt(index).localConfiguration ?: continue
            val uri = if (config.uri.scheme == BassPcmDataSource.SCHEME) {
                fallbackIds += player.getMediaItemAt(index).mediaId
                android.net.Uri.parse(config.uri.getQueryParameter("uri"))
            } else config.uri
            if (uri.authority != BuildConfig.FILE_PROVIDIER_AUTHORITY) continue
            val key = uri.toString()
            active += key
            if (key !in leases) {
                try {
                    OpenCacheManager.acquire(uri.fileProviderPath)?.let { leases[key] = it }
                } catch (e: Exception) {
                    // The data source reports unavailable files through Player.Listener.
                    e.printStackTrace()
                }
            }
        }
        val removed = leases.keys - active
        removed.forEach { leases.remove(it)?.close() }
        fallbackLyrics.keys.retainAll(fallbackIds)
    }

    private fun settings() = Bundle().apply {
        putInt(DECODER, decoderMode)
        putBoolean(BACKGROUND, background)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) {
                return MediaSession.ConnectionResult.reject()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (controller.packageName == packageName) {
                commands.add(SessionCommand(SETTINGS, Bundle.EMPTY))
                commands.add(SessionCommand(GET_LYRICS, Bundle.EMPTY))
            }
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            if (controller.packageName != packageName) {
                playerCommands.remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(playerCommands.build()).build()
        }

        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            if (controller.packageName == packageName && mediaItems.all { it.localConfiguration != null }) {
                Futures.immediateFuture(mediaItems)
            } else Futures.immediateFailedFuture(IllegalArgumentException("不允许此媒体来源"))

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            if (customCommand.customAction == GET_LYRICS) {
                val mediaId = player.currentMediaItem?.mediaId
                if (mediaId == null || mediaId != args.getString("media_id")) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                }
                // Format.metadata is not serialized through MediaController, so read it here.
                val lyrics = EmbeddedLyrics.read(player.currentTracks)
                    ?: fallbackLyrics[mediaId]
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                    putString("media_id", mediaId)
                    putString("lyrics", lyrics)
                }))
            }
            if (customCommand.customAction != SETTINGS) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            if (args.containsKey(DECODER)) {
                val mode = args.getInt(DECODER)
                if (mode !in 0..2) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                if (decoderMode != mode) {
                    decoderMode = mode
                    // stop/prepare releases renderers while retaining the playlist, position and session.
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition
                    val play = player.playWhenReady
                    player.stop()
                    if (player.mediaItemCount > 0) {
                        player.seekTo(index, position)
                        player.prepare()
                        player.playWhenReady = play
                    }
                }
            }
            if (args.containsKey(BACKGROUND)) background = args.getBoolean(BACKGROUND)
            if (args.containsKey("subtitle_uri")) {
                val uri = android.net.Uri.parse(args.getString("subtitle_uri"))
                val mime = args.getString("subtitle_mime")
                if (uri.scheme != "content" || mime !in setOf(MimeTypes.APPLICATION_SUBRIP,
                    MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML)) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val subtitle = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime)
                    .setLabel(args.getString("subtitle_name"))
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                if (!PlaybackActions.applySubtitle(player, args.getString("subtitle_media_id").orEmpty(), subtitle)) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                }
            }
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putInt(DECODER, decoderMode).putBoolean(BACKGROUND, background).apply()
            session.setSessionExtras(settings())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, settings()))
        }
    }

    companion object {
        const val SETTINGS = "me.zhanghai.android.files.media.SETTINGS"
        const val GET_LYRICS = "me.zhanghai.android.files.media.GET_LYRICS"
        const val DECODER = "decoder"
        const val BACKGROUND = "background"
        const val PREFERENCES = "media_playback"
    }
}
