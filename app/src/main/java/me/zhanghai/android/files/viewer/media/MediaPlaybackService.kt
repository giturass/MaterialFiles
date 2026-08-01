/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java8.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import me.zhanghai.android.files.compat.getSystemServiceCompat
import me.zhanghai.android.files.compat.use
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.RuntimeBroadcastReceiver
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.valueCompat

/**
 * Owns the playback session: audio focus, the media session, the notification, wake locks and the
 * service lifetime. Decoding itself is delegated to a [MediaPlaybackEngine], which is
 * [ExoPlaybackEngine] for video and for audio in any format ExoPlayer supports, and
 * [BassPlaybackEngine] for the audio formats it doesn't.
 */
@OptIn(UnstableApi::class)
class MediaPlaybackService : Service() {

    enum class Status {
        IDLE,
        OPENING,
        BUFFERING,
        PLAYING,
        PAUSED,
        STOPPED,
        ENDED,
        ERROR
    }

    interface Listener {
        fun onPlaybackStateChanged()

        /**
         * Only the playback position moved, four times a second. Kept apart from
         * [onPlaybackStateChanged] so that a listener doesn't have to refresh everything that often.
         */
        fun onPlaybackProgressChanged() {
            onPlaybackStateChanged()
        }
    }

    inner class LocalBinder : Binder() {
        val service: MediaPlaybackService
            get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Opening a remote provider or extracting artwork can block. A cached pool prevents a stale
    // metadata request from delaying a newly selected media file.
    private val sourceExecutor = Executors.newCachedThreadPool()

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnAudioFocusGain = false
    private var isDucking = false

    private lateinit var wakeWifiLock: WakeWifiLock
    private lateinit var noisyAudioReceiver: RuntimeBroadcastReceiver

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notification: MediaPlaybackNotification

    private var engine: MediaPlaybackEngine? = null
    private var attachedVideoLayout: MediaVideoLayout? = null
    private var triedBassFallback = false
    private var metadataGeneration = 0
    private var playWhenReady = false
    private var hasBoundClients = false

    private val leavePausedForegroundRunnable = Runnable {
        if (status == Status.PAUSED) {
            notification.leaveForeground()
        }
    }
    private val stopTerminalServiceRunnable = Runnable {
        if (!hasBoundClients && (status == Status.ENDED || status == Status.ERROR)) {
            releaseEngine()
            mediaSession.isActive = false
            notification.stopForeground()
            stopSelf()
        }
    }

    var currentUri: Uri? = null
        private set
    var currentMimeType: String? = null
        private set
    private var currentPath: Path? = null
    private var playlist: MediaPlaylist? = null
    private var currentSourceTitle = ""
    var displayTitle = ""
        private set
    var displaySubtitle: String? = null
        private set
    var isAudio = false
        private set
    var artwork: Bitmap? = null
        private set
    var lyrics: Lyrics? = null
        private set
    var status = Status.IDLE
        private set
    var errorMessage: String? = null
        private set
    var repeat = false
        private set
    var playbackRate = 1f
        private set
    var videoScale = VideoScaleType.BEST_FIT
        private set
    var menuRevision = 0
        private set

    val hasMedia: Boolean
        get() = engine?.hasMedia == true && currentUri != null

    val isPlaying: Boolean
        get() = engine?.isPlaying == true

    val isPlayingOrPreparing: Boolean
        get() = playWhenReady && (status == Status.OPENING || status == Status.BUFFERING
            || status == Status.PLAYING)

    val currentTime: Long
        get() = engine?.currentTimeMillis ?: 0L

    val duration: Long
        get() = engine?.durationMillis ?: 0L

    val isSeekable: Boolean
        get() = engine?.isSeekable == true

    /** Whether there is another audio file before the current one in its folder. */
    val hasPrevious: Boolean
        get() = playlist?.hasPrevious == true

    /** Whether there is another audio file after the current one in its folder. */
    val hasNext: Boolean
        get() = playlist?.hasNext == true

    val bufferingPercent: Float
        get() = engine?.bufferingPercent ?: 0f

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { onAudioFocusChanged(change) }
    }

    private val engineListener = object : MediaPlaybackEngine.Listener {
        override fun onStatusChanged(status: MediaEngineStatus) {
            // Errors always come with onError(), which decides between falling back to another
            // engine and surfacing the failure.
            val newStatus = when (status) {
                MediaEngineStatus.OPENING -> Status.OPENING
                MediaEngineStatus.BUFFERING -> Status.BUFFERING
                MediaEngineStatus.PLAYING -> Status.PLAYING
                MediaEngineStatus.PAUSED -> Status.PAUSED
                MediaEngineStatus.ENDED -> Status.ENDED
                MediaEngineStatus.ERROR -> return
            }
            when (newStatus) {
                Status.PLAYING -> {
                    errorMessage = null
                    wakeWifiLock.isAcquired = true
                }
                Status.ENDED -> {
                    if (!repeat && hasNext) {
                        // Continue with the folder instead of ending the session. Releasing the
                        // engine from inside its own callback is asking for trouble, so let this
                        // callback return first.
                        this@MediaPlaybackService.status = newStatus
                        mainHandler.post {
                            if (this@MediaPlaybackService.status == Status.ENDED) {
                                playNext()
                            }
                        }
                        return
                    }
                    playWhenReady = false
                    wakeWifiLock.isAcquired = false
                    abandonAudioFocus()
                }
                else -> wakeWifiLock.isAcquired = false
            }
            this@MediaPlaybackService.status = newStatus
            notifyStateChanged(updateNotification = true)
        }

        override fun onProgressChanged() {
            updateMediaSessionPlaybackState()
            listeners.forEach { it.onPlaybackProgressChanged() }
        }

        override fun onDurationChanged() {
            notifyStateChanged(updateMetadata = true)
        }

        override fun onSeekableChanged() {
            notifyStateChanged()
        }

        override fun onTracksChanged() {
            menuRevision++
            notifyListeners()
        }

        override fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float) {
            notifyListeners()
        }

        override fun onError(throwable: Throwable, recoverable: Boolean) {
            onEngineError(throwable, recoverable)
        }
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemServiceCompat(AudioManager::class.java)
        wakeWifiLock = WakeWifiLock(MediaPlaybackService::class.java.simpleName)
        noisyAudioReceiver = RuntimeBroadcastReceiver(
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        pause()
                    }
                }
            },
            this
        ).also { it.register() }

        mediaSession = MediaSessionCompat(this, MediaPlaybackService::class.java.simpleName).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onStop() {
                    stopPlayback()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                }

                override fun onRewind() {
                    seekBy(-SEEK_INTERVAL_MILLIS)
                }

                override fun onFastForward() {
                    seekBy(SEEK_INTERVAL_MILLIS)
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }

                override fun onSkipToNext() {
                    playNext()
                }
            })
        }
        notification = MediaPlaybackNotification(this, mediaSession.sessionToken)
        updateMediaSessionMetadata()
        updateMediaSessionPlaybackState()
    }

    override fun onBind(intent: Intent): IBinder {
        hasBoundClients = true
        mainHandler.removeCallbacks(stopTerminalServiceRunnable)
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        hasBoundClients = false
        updateServiceLifecycle()
        return true
    }

    override fun onRebind(intent: Intent) {
        super.onRebind(intent)

        hasBoundClients = true
        mainHandler.removeCallbacks(stopTerminalServiceRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Whatever this command turns out to be, the system started us as a foreground service and
        // expects a notification within seconds. Leaving it again straight afterwards is allowed.
        notification.startForegroundNow()
        when (intent?.action) {
            ACTION_OPEN -> {
                val uri = intent.data
                if (uri == null) {
                    stopEmptyService(startId)
                } else {
                    openMedia(
                        uri,
                        intent.type,
                        intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        intent.getBooleanExtra(EXTRA_IS_AUDIO, false),
                        intent.getBooleanExtra(EXTRA_FORCE_OPEN, false),
                        path = intent.extraPath
                    )
                }
            }
            ACTION_PLAY -> if (currentUri != null) play() else stopEmptyService(startId)
            ACTION_PAUSE -> if (currentUri != null) pause() else stopEmptyService(startId)
            ACTION_REWIND -> if (currentUri != null) {
                seekBy(-SEEK_INTERVAL_MILLIS)
            } else {
                stopEmptyService(startId)
            }
            ACTION_FAST_FORWARD -> if (currentUri != null) {
                seekBy(SEEK_INTERVAL_MILLIS)
            } else {
                stopEmptyService(startId)
            }
            ACTION_SKIP_TO_PREVIOUS -> if (currentUri != null) {
                playPrevious()
            } else {
                stopEmptyService(startId)
            }
            ACTION_SKIP_TO_NEXT -> if (currentUri != null) {
                playNext()
            } else {
                stopEmptyService(startId)
            }
            ACTION_STOP -> stopPlayback()
            null -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Settings.MEDIA_PLAYER_BACKGROUND_PLAYBACK.valueCompat) {
            stopPlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        metadataGeneration++
        sourceExecutor.shutdownNow()
        mainHandler.removeCallbacks(leavePausedForegroundRunnable)
        mainHandler.removeCallbacks(stopTerminalServiceRunnable)
        noisyAudioReceiver.unregister()
        abandonAudioFocus()
        wakeWifiLock.isAcquired = false
        releaseEngine()
        attachedVideoLayout = null
        mediaSession.isActive = false
        mediaSession.release()
        notification.stopForeground()
        super.onDestroy()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPlaybackStateChanged()
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun attachVideoLayout(layout: MediaVideoLayout) {
        if (isAudio) {
            return
        }
        attachedVideoLayout = layout
        layout.scaleType = videoScale
        engine?.attachVideoLayout(layout)
    }

    fun detachVideoLayout(layout: MediaVideoLayout) {
        if (attachedVideoLayout !== layout) {
            return
        }
        engine?.detachVideoLayout()
        attachedVideoLayout = null
    }

    fun togglePlayPause() {
        if (isPlayingOrPreparing) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        playWhenReady = true
        mainHandler.removeCallbacks(leavePausedForegroundRunnable)
        mainHandler.removeCallbacks(stopTerminalServiceRunnable)
        if (status == Status.ERROR || status == Status.STOPPED) {
            val uri = currentUri ?: return
            openMedia(
                uri, currentMimeType, currentSourceTitle, isAudio, force = true,
                path = currentPath
            )
            return
        }
        val engine = engine
        if (engine == null) {
            status = Status.OPENING
            notifyStateChanged(updateNotification = true)
            return
        }
        if (!requestAudioFocus()) {
            playWhenReady = false
            status = Status.PAUSED
            notifyStateChanged(updateNotification = true)
            return
        }
        engine.play()
        if (!engine.hasMedia) {
            status = Status.OPENING
            notifyStateChanged(updateNotification = true)
        }
    }

    fun pause() {
        pauseInternal(true)
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        playWhenReady = false
        engine?.pause()
        if (status == Status.OPENING || status == Status.BUFFERING) {
            status = Status.PAUSED
            wakeWifiLock.isAcquired = false
            notifyStateChanged(updateNotification = true)
        }
        if (abandonFocus) {
            resumeOnAudioFocusGain = false
            abandonAudioFocus()
        }
    }

    fun stopPlayback() {
        playWhenReady = false
        resumeOnAudioFocusGain = false
        metadataGeneration++
        releaseEngine()
        status = Status.STOPPED
        wakeWifiLock.isAcquired = false
        abandonAudioFocus()
        mediaSession.isActive = false
        updateMediaSessionPlaybackState()
        notifyListeners()
        notification.stopForeground()
        stopSelf()
    }

    fun seekBy(offsetMillis: Long) {
        seekTo(currentTime + offsetMillis)
    }

    fun playPrevious() {
        moveInPlaylist(-1)
    }

    fun playNext() {
        moveInPlaylist(1)
    }

    private fun moveInPlaylist(offset: Int) {
        val moved = playlist?.movedBy(offset) ?: return
        val item = moved.current ?: return
        openMedia(
            item.uri, item.mimeType, item.title, audio = true, force = true,
            // Playback speed and repeat belong to the session rather than to a single track.
            preserveSessionSettings = true, path = item.path, playlist = moved
        )
    }

    fun seekTo(timeMillis: Long) {
        val engine = engine ?: return
        if (!engine.isSeekable) {
            return
        }
        val duration = duration
        val time = if (duration > 0) {
            timeMillis.coerceIn(0L, duration)
        } else {
            timeMillis.coerceAtLeast(0L)
        }
        engine.seekTo(time)
    }

    fun setPlaybackRate(rate: Float) {
        playbackRate = rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        engine?.setRate(playbackRate)
        updateMediaSessionPlaybackState()
        notifyListeners()
        notification.startOrUpdate()
    }

    fun setRepeat(enabled: Boolean) {
        repeat = enabled
        engine?.setRepeat(enabled)
        menuRevision++
        notifyListeners()
    }

    fun setVideoScale(scale: VideoScaleType) {
        videoScale = scale
        attachedVideoLayout?.scaleType = scale
        notifyListeners()
    }

    fun getAudioTracks(): List<MediaTrack> = engine?.audioTracks.orEmpty()

    val selectedAudioTrack: Int
        get() = engine?.selectedAudioTrackId ?: MediaTrack.DISABLED_ID

    fun selectAudioTrack(id: Int): Boolean = engine?.selectAudioTrack(id) == true

    fun getSubtitleTracks(): List<MediaTrack> = engine?.subtitleTracks.orEmpty()

    val selectedSubtitleTrack: Int
        get() = engine?.selectedSubtitleTrackId ?: MediaTrack.DISABLED_ID

    fun selectSubtitleTrack(id: Int): Boolean = engine?.selectSubtitleTrack(id) == true

    fun setHardwareDecoding(enabled: Boolean) {
        if (Settings.MEDIA_PLAYER_HARDWARE_DECODING.valueCompat == enabled) {
            return
        }
        Settings.MEDIA_PLAYER_HARDWARE_DECODING.putValue(enabled)
        val uri = currentUri ?: return
        val wasPaused = status == Status.PAUSED
        openMedia(
            uri,
            currentMimeType,
            currentSourceTitle,
            isAudio,
            force = true,
            startTime = currentTime,
            pauseAfterStart = wasPaused,
            preserveSessionSettings = true,
            path = currentPath
        )
    }

    private fun openMedia(
        uri: Uri,
        mimeType: String?,
        sourceTitle: String,
        audio: Boolean,
        force: Boolean,
        startTime: Long = 0L,
        pauseAfterStart: Boolean = false,
        preserveSessionSettings: Boolean = false,
        path: Path? = null,
        playlist: MediaPlaylist? = null
    ) {
        if (!force && currentUri == uri && hasMedia && status != Status.STOPPED
            && status != Status.ERROR) {
            notifyStateChanged(updateNotification = true, updateMetadata = true)
            return
        }

        metadataGeneration++
        val generation = metadataGeneration
        mainHandler.removeCallbacks(leavePausedForegroundRunnable)
        mainHandler.removeCallbacks(stopTerminalServiceRunnable)
        resumeOnAudioFocusGain = false
        abandonAudioFocus()
        releaseEngine()

        currentUri = uri
        currentMimeType = mimeType
        currentPath = path
        // A track picked from the same folder can keep the list that is already loaded, and moving
        // within the list brings its own.
        this.playlist = (playlist ?: this.playlist?.movedTo(uri))
        if (this.playlist == null && audio && path != null) {
            loadPlaylist(path, generation)
        }
        currentSourceTitle = sourceTitle
        displayTitle = sourceTitle
        displaySubtitle = null
        isAudio = audio
        artwork = null
        lyrics = null
        status = Status.OPENING
        errorMessage = null
        triedBassFallback = false
        playWhenReady = !pauseAfterStart
        if (!preserveSessionSettings) {
            repeat = false
            playbackRate = 1f
            videoScale = VideoScaleType.BEST_FIT
        }
        menuRevision++

        mediaSession.isActive = true
        mediaSession.setSessionActivity(notification.createContentPendingIntent())
        notifyStateChanged(updateNotification = true, updateMetadata = true)

        startEngine(useBass = audio && mimeType in BASS_ONLY_MIME_TYPES, startTime = startTime)
        loadMetadata(uri, generation)
        if (audio) {
            loadLyrics(uri, path, generation)
        }
    }

    private fun startEngine(useBass: Boolean, startTime: Long) {
        val uri = currentUri ?: return
        releaseEngine()
        if (playWhenReady && !requestAudioFocus()) {
            playWhenReady = false
        }
        val hardwareDecoding = Settings.MEDIA_PLAYER_HARDWARE_DECODING.valueCompat
        val engine = try {
            if (useBass) {
                BassPlaybackEngine(this, hardwareDecoding, engineListener)
            } else {
                ExoPlaybackEngine(this, hardwareDecoding, engineListener)
            }
        } catch (t: Throwable) {
            onOpenError(t)
            return
        }
        this.engine = engine
        engine.setRepeat(repeat)
        engine.setRate(playbackRate)
        if (!isAudio) {
            attachedVideoLayout?.let {
                it.scaleType = videoScale
                engine.attachVideoLayout(it)
            }
        }
        engine.prepare(uri, startTime, playWhenReady)
        status = if (playWhenReady) Status.OPENING else Status.PAUSED
        notifyStateChanged(updateNotification = true)
    }

    private fun onEngineError(throwable: Throwable, recoverable: Boolean) {
        // ExoPlayer can parse a container but still have no decoder for its codec, which is exactly
        // the case BASS is here for. Retry once, from where playback got to.
        if (recoverable && isAudio && !triedBassFallback && currentUri != null) {
            triedBassFallback = true
            throwable.printStackTrace()
            startEngine(useBass = true, startTime = currentTime)
            return
        }
        onOpenError(throwable)
    }

    private fun releaseEngine() {
        val engine = engine ?: return
        this.engine = null
        engine.detachVideoLayout()
        engine.release()
    }

    /**
     * Lists the folder of [path] in the background, since it may be a remote file system, and
     * enables the previous/next controls once it is known what surrounds the current track.
     */
    private fun loadPlaylist(path: Path, generation: Int) {
        sourceExecutor.execute {
            val playlist = MediaPlaylist.load(path)
            mainHandler.post {
                if (generation == metadataGeneration && playlist != null) {
                    this.playlist = playlist
                    notifyStateChanged(updateNotification = true)
                }
            }
        }
    }

    /**
     * Looks for lyrics in the background, since that means reading an `.lrc` file next to the song
     * or scanning its tags.
     */
    private fun loadLyrics(uri: Uri, path: Path?, generation: Int) {
        sourceExecutor.execute {
            val lyrics = LyricsLoader.load(this, path, uri)
            mainHandler.post {
                if (generation == metadataGeneration) {
                    this.lyrics = lyrics
                    notifyListeners()
                }
            }
        }
    }

    private fun loadMetadata(uri: Uri, generation: Int) {
        sourceExecutor.execute {
            val metadata = readMetadata(uri)
            mainHandler.post {
                if (generation == metadataGeneration && metadata != null) {
                    metadata.title?.takeIf { it.isNotBlank() }?.let { displayTitle = it }
                    displaySubtitle = listOfNotNull(
                        metadata.artist?.takeIf { it.isNotBlank() },
                        metadata.album?.takeIf { it.isNotBlank() }
                    ).distinct().joinToString(" • ").ifEmpty { null }
                    artwork = metadata.artwork
                    notifyStateChanged(updateNotification = true, updateMetadata = true)
                }
            }
        }
    }

    private fun readMetadata(uri: Uri): Metadata? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(this, uri)
            Metadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                retriever.embeddedPicture?.let(::decodeArtwork)
            )
        }
    }.getOrNull()

    private fun decodeArtwork(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_ARTWORK_DIMENSION_PX
            || bounds.outHeight / sampleSize > MAX_ARTWORK_DIMENSION_PX) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        val largestDimension = maxOf(decoded.width, decoded.height)
        if (largestDimension <= MAX_ARTWORK_DIMENSION_PX) {
            return decoded
        }
        val scale = MAX_ARTWORK_DIMENSION_PX.toFloat() / largestDimension
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) {
            decoded.recycle()
        }
        return scaled
    }

    private fun onOpenError(throwable: Throwable) {
        throwable.printStackTrace()
        playWhenReady = false
        status = Status.ERROR
        errorMessage = throwable.message ?: throwable.toString()
        wakeWifiLock.isAcquired = false
        abandonAudioFocus()
        notifyStateChanged(updateNotification = true)
    }

    private fun notifyStateChanged(
        updateNotification: Boolean = false,
        updateMetadata: Boolean = false
    ) {
        updateMediaSessionPlaybackState()
        if (updateMetadata) {
            updateMediaSessionMetadata()
        }
        if (updateNotification) {
            notification.startOrUpdate()
        }
        updateServiceLifecycle()
        notifyListeners()
    }

    private fun updateServiceLifecycle() {
        when (status) {
            Status.OPENING, Status.BUFFERING, Status.PLAYING -> {
                mainHandler.removeCallbacks(leavePausedForegroundRunnable)
                mainHandler.removeCallbacks(stopTerminalServiceRunnable)
            }
            Status.PAUSED -> {
                mainHandler.removeCallbacks(stopTerminalServiceRunnable)
                mainHandler.removeCallbacks(leavePausedForegroundRunnable)
                mainHandler.postDelayed(
                    leavePausedForegroundRunnable, PAUSED_FOREGROUND_TIMEOUT_MILLIS
                )
            }
            Status.ENDED, Status.ERROR -> {
                mainHandler.removeCallbacks(leavePausedForegroundRunnable)
                notification.leaveForeground()
                mainHandler.removeCallbacks(stopTerminalServiceRunnable)
                if (!hasBoundClients) {
                    mainHandler.postDelayed(
                        stopTerminalServiceRunnable, TERMINAL_STOP_DELAY_MILLIS
                    )
                }
            }
            Status.IDLE, Status.STOPPED -> {
                mainHandler.removeCallbacks(leavePausedForegroundRunnable)
                mainHandler.removeCallbacks(stopTerminalServiceRunnable)
            }
        }
    }

    private fun stopEmptyService(startId: Int) {
        playWhenReady = false
        resumeOnAudioFocusGain = false
        metadataGeneration++
        releaseEngine()
        wakeWifiLock.isAcquired = false
        abandonAudioFocus()
        notification.stopForeground()
        mediaSession.isActive = false
        stopSelf(startId)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onPlaybackStateChanged() }
    }

    private fun updateMediaSessionPlaybackState() {
        val state = when (status) {
            Status.IDLE -> PlaybackStateCompat.STATE_NONE
            Status.OPENING -> PlaybackStateCompat.STATE_CONNECTING
            Status.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Status.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            Status.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            Status.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            Status.ENDED -> PlaybackStateCompat.STATE_STOPPED
            Status.ERROR -> PlaybackStateCompat.STATE_ERROR
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        if (isSeekable) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD
        }
        if (hasPrevious) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        if (hasNext) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, currentTime, if (isPlaying) playbackRate else 0f)
        errorMessage?.takeIf { status == Status.ERROR }?.let { builder.setErrorMessage(it) }
        mediaSession.setPlaybackState(builder.build())
    }

    private fun updateMediaSessionMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        displaySubtitle?.let {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        duration.takeIf { it > 0L }?.let {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it)
        }
        artwork?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        stopDucking()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun stopDucking() {
        if (!isDucking) {
            return
        }
        isDucking = false
        engine?.setVolumeScale(1f)
    }

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                stopDucking()
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false
                    play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying || playWhenReady) {
                    resumeOnAudioFocusGain = true
                    pauseInternal(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!isDucking) {
                    isDucking = true
                    engine?.setVolumeScale(DUCKING_VOLUME_SCALE)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnAudioFocusGain = false
                pauseInternal(true)
            }
        }
    }

    private data class Metadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?
    )

    companion object {
        const val SEEK_INTERVAL_MILLIS = 10_000L
        const val MIN_PLAYBACK_RATE = 0.5f
        const val MAX_PLAYBACK_RATE = 2f

        private const val MAX_ARTWORK_DIMENSION_PX = 384
        private const val PAUSED_FOREGROUND_TIMEOUT_MILLIS = 30_000L
        private const val TERMINAL_STOP_DELAY_MILLIS = 2_000L
        private const val DUCKING_VOLUME_SCALE = 0.2f

        /**
         * Audio formats that ExoPlayer has no extractor for at all, so there is no point letting it
         * try and fail before BASS takes over.
         */
        private val BASS_ONLY_MIME_TYPES = setOf(
            "audio/x-monkeys-audio",
            "audio/x-wavpack",
            "audio/x-tta",
            "audio/x-musepack",
            "audio/x-dsd",
            "audio/x-mod",
            "audio/x-mo3",
            "audio/x-aiff",
            "audio/aiff",
            "audio/midi",
            "audio/sp-midi",
            "audio/x-midi"
        )

        private const val ACTION_OPEN =
            "me.zhanghai.android.files.viewer.media.action.OPEN"
        private const val ACTION_PLAY =
            "me.zhanghai.android.files.viewer.media.action.PLAY"
        private const val ACTION_PAUSE =
            "me.zhanghai.android.files.viewer.media.action.PAUSE"
        private const val ACTION_REWIND =
            "me.zhanghai.android.files.viewer.media.action.REWIND"
        private const val ACTION_FAST_FORWARD =
            "me.zhanghai.android.files.viewer.media.action.FAST_FORWARD"
        private const val ACTION_SKIP_TO_PREVIOUS =
            "me.zhanghai.android.files.viewer.media.action.SKIP_TO_PREVIOUS"
        private const val ACTION_SKIP_TO_NEXT =
            "me.zhanghai.android.files.viewer.media.action.SKIP_TO_NEXT"
        private const val ACTION_STOP =
            "me.zhanghai.android.files.viewer.media.action.STOP"

        private const val EXTRA_TITLE =
            "me.zhanghai.android.files.viewer.media.extra.TITLE"
        private const val EXTRA_IS_AUDIO =
            "me.zhanghai.android.files.viewer.media.extra.IS_AUDIO"
        private const val EXTRA_FORCE_OPEN =
            "me.zhanghai.android.files.viewer.media.extra.FORCE_OPEN"

        fun open(
            context: Context,
            uri: Uri,
            mimeType: String,
            title: String,
            isAudio: Boolean,
            force: Boolean,
            path: Path? = null
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java)
                .setAction(ACTION_OPEN)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_AUDIO, isAudio)
                .putExtra(EXTRA_FORCE_OPEN, force)
                .apply { extraPath = path }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }

        internal fun createPlayIntent(context: Context): Intent =
            createActionIntent(context, ACTION_PLAY)

        internal fun createPauseIntent(context: Context): Intent =
            createActionIntent(context, ACTION_PAUSE)

        internal fun createRewindIntent(context: Context): Intent =
            createActionIntent(context, ACTION_REWIND)

        internal fun createFastForwardIntent(context: Context): Intent =
            createActionIntent(context, ACTION_FAST_FORWARD)

        internal fun createSkipToPreviousIntent(context: Context): Intent =
            createActionIntent(context, ACTION_SKIP_TO_PREVIOUS)

        internal fun createSkipToNextIntent(context: Context): Intent =
            createActionIntent(context, ACTION_SKIP_TO_NEXT)

        internal fun createStopIntent(context: Context): Intent =
            createActionIntent(context, ACTION_STOP)

        private fun createActionIntent(context: Context, action: String): Intent =
            Intent(context, MediaPlaybackService::class.java).setAction(action)
    }
}
