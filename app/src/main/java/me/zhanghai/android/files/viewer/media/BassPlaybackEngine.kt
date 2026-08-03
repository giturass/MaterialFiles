/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.un4seen.bass.BASS
import com.un4seen.bass.BASS_FX
import me.zhanghai.android.files.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Plays audio through BASS, for the formats ExoPlayer has no extractor or decoder for: ALAC, APE,
 * WavPack, TTA, Musepack, DSD, AIFF and tracker music among others.
 *
 * Playback speed goes through a BASS_FX tempo stream so that it doesn't change the pitch, matching
 * what [ExoPlaybackEngine] does with its own time stretching.
 */
class BassPlaybackEngine(
    private val context: Context,
    hardwareDecoding: Boolean,
    private var listener: MediaPlaybackEngine.Listener?
) : MediaPlaybackEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    // Opening a file on a remote provider blocks, and BASS may prescan or connect while creating
    // the stream, so it never happens on the main thread.
    private val sourceExecutor = Executors.newSingleThreadExecutor()

    private var released = false
    private var channel = 0
    private var sourceStream = 0
    private var source: Source? = null
    private var sourceGeneration = 0
    private var status: MediaEngineStatus? = null
    private var playWhenReady = false
    private var pendingStartTimeMillis = 0L
    private var repeat = false
    private var rate = 1f
    private var volumeScale = 1f

    /** Only ever touched on [sourceExecutor], which is the only thread that initializes BASS. */
    private var hasRetainedLibrary = false

    // BASS only keeps a weak reference to sync callbacks in some configurations, and the file
    // callbacks must outlive stream creation, so both are held here.
    private val endSync = BASS.SYNCPROC { _, _, _, _ -> mainHandler.post { onEndReached() } }
    private val stallSync = BASS.SYNCPROC { _, _, data, _ ->
        mainHandler.post { onStallChanged(data == 0) }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            listener?.onProgressChanged()
            mainHandler.postDelayed(this, MediaPlaybackEngine.PROGRESS_INTERVAL_MILLIS)
        }
    }

    init {
        // BASS decodes AAC, FLAC and friends through the platform's MediaCodec unless told not to,
        // which is what the "hardware decoding" preference means on this side.
        BASS.BASS_SetConfig(BASS_CONFIG_AM_DISABLE, if (hardwareDecoding) 0 else 1)
    }

    override val hasMedia: Boolean
        get() = channel != 0

    override val isPlaying: Boolean
        get() = channel != 0 && BASS.BASS_ChannelIsActive(channel) == BASS.BASS_ACTIVE_PLAYING

    override val isSeekable: Boolean
        get() = durationMillis > 0L

    override val currentTimeMillis: Long
        get() {
            val channel = channel
            if (channel == 0) {
                return 0L
            }
            val position = BASS.BASS_ChannelGetPosition(channel, BASS.BASS_POS_BYTE)
            if (position < 0L) {
                return 0L
            }
            return (BASS.BASS_ChannelBytes2Seconds(channel, position) * 1000).toLong()
                .coerceAtLeast(0L)
        }

    override val durationMillis: Long
        get() {
            val channel = channel
            if (channel == 0) {
                return 0L
            }
            val length = BASS.BASS_ChannelGetLength(channel, BASS.BASS_POS_BYTE)
            if (length < 0L) {
                return 0L
            }
            return (BASS.BASS_ChannelBytes2Seconds(channel, length) * 1000).toLong()
                .coerceAtLeast(0L)
        }

    override val bufferingPercent: Float
        get() {
            val sourceStream = sourceStream
            if (sourceStream == 0) {
                return 0f
            }
            val buffering =
                BASS.BASS_StreamGetFilePosition(sourceStream, BASS.BASS_FILEPOS_BUFFERING)
            // Not a buffered stream, i.e. a local file that is always fully available.
            return if (buffering < 0L) 100f else buffering.toFloat().coerceIn(0f, 100f)
        }

    // BASS decodes a single stream, so there is nothing to choose between.
    override val audioTracks: List<MediaTrack>
        get() = emptyList()

    override val subtitleTracks: List<MediaTrack>
        get() = emptyList()

    override val selectedAudioTrackId: Int
        get() = MediaTrack.DISABLED_ID

    override val selectedSubtitleTrackId: Int
        get() = MediaTrack.DISABLED_ID

    override fun prepare(uri: Uri, startTimeMillis: Long, playWhenReady: Boolean) {
        freeChannel()
        sourceGeneration++
        val generation = sourceGeneration
        pendingStartTimeMillis = startTimeMillis.coerceAtLeast(0L)
        this.playWhenReady = playWhenReady
        setStatus(MediaEngineStatus.OPENING)
        sourceExecutor.execute {
            val result = runCatching { createSource(uri) }
            mainHandler.post {
                val createdSource = result.getOrNull()
                if (released || generation != sourceGeneration) {
                    createdSource?.free()
                    return@post
                }
                if (createdSource != null) {
                    onSourceCreated(createdSource)
                } else {
                    listener?.onError(
                        result.exceptionOrNull() ?: IOException(uri.toString()), false
                    )
                    setStatus(MediaEngineStatus.ERROR)
                }
            }
        }
    }

    override fun play() {
        playWhenReady = true
        val channel = channel
        if (channel == 0) {
            return
        }
        if (status == MediaEngineStatus.ENDED) {
            BASS.BASS_ChannelSetPosition(channel, 0L, BASS.BASS_POS_BYTE)
        }
        if (!BASS.BASS_ChannelPlay(channel, false)) {
            listener?.onError(bassException("BASS_ChannelPlay()"), false)
            setStatus(MediaEngineStatus.ERROR)
            return
        }
        setStatus(MediaEngineStatus.PLAYING)
        updateProgressTicker(true)
    }

    override fun pause() {
        playWhenReady = false
        val channel = channel
        if (channel != 0) {
            BASS.BASS_ChannelPause(channel)
        }
        setStatus(MediaEngineStatus.PAUSED)
        updateProgressTicker(false)
    }

    override fun seekTo(timeMillis: Long) {
        val channel = channel
        if (channel == 0) {
            return
        }
        val position =
            BASS.BASS_ChannelSeconds2Bytes(channel, timeMillis.coerceAtLeast(0L) / 1000.0)
        if (position < 0L) {
            return
        }
        BASS.BASS_ChannelSetPosition(channel, position, BASS.BASS_POS_BYTE)
        if (status == MediaEngineStatus.ENDED && playWhenReady) {
            play()
        }
        listener?.onProgressChanged()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        val channel = channel
        if (channel != 0) {
            // BASS_FX takes a percentage change rather than a multiplier.
            BASS.BASS_ChannelSetAttribute(
                channel, BASS_FX.BASS_ATTRIB_TEMPO, (rate - 1f) * 100f
            )
        }
    }

    override fun setVolumeScale(scale: Float) {
        volumeScale = scale.coerceIn(0f, 1f)
        val channel = channel
        if (channel != 0) {
            BASS.BASS_ChannelSetAttribute(channel, BASS.BASS_ATTRIB_VOL, volumeScale)
        }
    }

    override fun setRepeat(enabled: Boolean) {
        repeat = enabled
        val sourceStream = sourceStream
        if (sourceStream != 0) {
            // The loop has to be on the decoding source, since that is what runs out of data.
            BASS.BASS_ChannelFlags(
                sourceStream, if (enabled) BASS.BASS_SAMPLE_LOOP else 0, BASS.BASS_SAMPLE_LOOP
            )
        }
    }

    override fun selectAudioTrack(id: Int): Boolean = false

    override fun selectSubtitleTrack(id: Int): Boolean = false

    override fun attachVideoLayout(layout: MediaVideoLayout) {}

    override fun detachVideoLayout() {}

    override fun release() {
        released = true
        listener = null
        mainHandler.removeCallbacks(progressRunnable)
        freeChannel()
        // Queued onto the source thread rather than done here: a stream may still be being created
        // on it, and taking the output device away mid-call is not something BASS expects. A single
        // thread means this runs once that has finished, and shutdown() lets it through where
        // shutdownNow() would drop it.
        runCatching {
            sourceExecutor.execute {
                if (hasRetainedLibrary) {
                    hasRetainedLibrary = false
                    BassLibrary.release()
                }
            }
        }
        sourceExecutor.shutdown()
    }

    private fun onSourceCreated(source: Source) {
        this.source = source
        sourceStream = source.stream
        channel = source.channel
        setRate(rate)
        setVolumeScale(volumeScale)
        setRepeat(repeat)
        BASS.BASS_ChannelSetSync(source.channel, BASS.BASS_SYNC_END, 0L, endSync, null)
        BASS.BASS_ChannelSetSync(source.stream, BASS.BASS_SYNC_STALL, 0L, stallSync, null)
        if (pendingStartTimeMillis > 0L) {
            val position = BASS.BASS_ChannelSeconds2Bytes(
                source.channel, pendingStartTimeMillis / 1000.0
            )
            if (position > 0L) {
                BASS.BASS_ChannelSetPosition(source.channel, position, BASS.BASS_POS_BYTE)
            }
            pendingStartTimeMillis = 0L
        }
        listener?.onDurationChanged()
        listener?.onSeekableChanged()
        listener?.onTracksChanged()
        if (playWhenReady) {
            play()
        } else {
            setStatus(MediaEngineStatus.PAUSED)
        }
    }

    private fun onEndReached() {
        if (released) {
            return
        }
        updateProgressTicker(false)
        playWhenReady = false
        setStatus(MediaEngineStatus.ENDED)
    }

    private fun onStallChanged(stalled: Boolean) {
        if (released || channel == 0) {
            return
        }
        setStatus(
            when {
                stalled -> MediaEngineStatus.BUFFERING
                playWhenReady -> MediaEngineStatus.PLAYING
                else -> MediaEngineStatus.PAUSED
            }
        )
    }

    private fun updateProgressTicker(playing: Boolean) {
        mainHandler.removeCallbacks(progressRunnable)
        if (playing) {
            mainHandler.postDelayed(progressRunnable, MediaPlaybackEngine.PROGRESS_INTERVAL_MILLIS)
        }
    }

    private fun setStatus(status: MediaEngineStatus) {
        if (this.status == status) {
            return
        }
        this.status = status
        listener?.onStatusChanged(status)
    }

    private fun freeChannel() {
        updateProgressTicker(false)
        source?.free()
        source = null
        channel = 0
        sourceStream = 0
    }

    /** Runs on [sourceExecutor]. */
    private fun createSource(uri: Uri): Source {
        if (!hasRetainedLibrary) {
            BassLibrary.retain(context)
            // Only once it actually succeeded, so that a failed initialization isn't released.
            hasRetainedLibrary = true
        }
        val flags = BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT or BASS.BASS_ASYNCFILE
        var descriptor: AssetFileDescriptor? = null
        var inputStream: InputStream? = null
        val stream: Int
        try {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE -> {
                    descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?: throw IOException("Unable to open $uri")
                    stream = if (descriptor.isSeekable) {
                        BASS.BASS_StreamCreateFile(
                            descriptor.parcelFileDescriptor,
                            descriptor.startOffset,
                            descriptor.declaredLength.coerceAtLeast(0L),
                            flags
                        )
                    } else {
                        // A pipe from a remote provider can only be read forwards, so let BASS
                        // buffer it and give up on seeking.
                        inputStream = descriptor.createInputStream()
                        BASS.BASS_StreamCreateFileUser(
                            BASS.STREAMFILE_BUFFER, flags, InputStreamFileProcs(inputStream!!), null
                        )
                    }
                }
                ContentResolver.SCHEME_FILE ->
                    stream = BASS.BASS_StreamCreateFile(uri.path, 0L, 0L, flags)
                null -> stream = BASS.BASS_StreamCreateFile(uri.toString(), 0L, 0L, flags)
                else -> stream = BASS.BASS_StreamCreateURL(uri.toString(), 0, flags, null, null)
            }
            if (stream == 0) {
                throw bassException("Opening $uri")
            }
        } catch (t: Throwable) {
            inputStream?.closeQuietly()
            descriptor?.closeQuietly()
            throw t
        }
        // BASS duplicates the file descriptor, so ours is no longer needed. The buffered variant
        // keeps reading from the input stream, which BASS closes through FILECLOSEPROC.
        descriptor?.closeQuietly()
        try {
            checkPlayable(stream)
            val channel = BASS_FX.BASS_FX_TempoCreate(stream, BASS_FX.BASS_FX_FREESOURCE)
            if (channel == 0) {
                throw bassException("BASS_FX_TempoCreate()")
            }
            return Source(stream, channel)
        } catch (t: Throwable) {
            BASS.BASS_StreamFree(stream)
            throw t
        }
    }

    private fun checkPlayable(stream: Int) {
        val info = BASS.BASS_CHANNELINFO()
        if (!BASS.BASS_ChannelGetInfo(stream, info)) {
            return
        }
        // BASSMIDI happily opens a MIDI file and then renders silence when no SoundFont is
        // configured, which is worse than saying we can't play it.
        if (info.ctype == BASS_CTYPE_STREAM_MIDI) {
            throw IOException(context.getString(R.string.media_player_error_no_soundfont))
        }
    }

    private fun bassException(operation: String): IOException =
        IOException(
            context.getString(
                R.string.media_player_error_bass_format, operation, BASS.BASS_ErrorGetCode()
            )
        )

    private val AssetFileDescriptor.isSeekable: Boolean
        get() = runCatching {
            Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        }.getOrDefault(false)

    private fun AssetFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private fun InputStream.closeQuietly() {
        runCatching { close() }
    }

    private class Source(val stream: Int, val channel: Int) {
        fun free() {
            // The tempo stream was created with BASS_FX_FREESOURCE, so this frees the source too.
            BASS.BASS_StreamFree(channel)
        }
    }

    /** Feeds BASS from a forward-only stream, used for pipes from remote file providers. */
    private class InputStreamFileProcs(private val inputStream: InputStream) : BASS.BASS_FILEPROCS {
        private val buffer = ByteArray(READ_BUFFER_SIZE)

        override fun FILECLOSEPROC(user: Any?) {
            runCatching { inputStream.close() }
        }

        override fun FILELENPROC(user: Any?): Long = 0L

        override fun FILEREADPROC(target: ByteBuffer, length: Int, user: Any?): Int {
            var total = 0
            try {
                while (total < length) {
                    val read = inputStream.read(buffer, 0, minOf(buffer.size, length - total))
                    if (read <= 0) {
                        break
                    }
                    target.put(buffer, 0, read)
                    total += read
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return total
        }

        override fun FILESEEKPROC(offset: Long, user: Any?): Boolean = false

        companion object {
            private const val READ_BUFFER_SIZE = 64 * 1024
        }
    }

    /**
     * Initializes BASS and its decoder plugins for the process, and hands the output device back
     * once nothing is using it any more.
     *
     * Held by count rather than left initialized for good: an initialized BASS keeps the output
     * device open, and there is no reason to hold on to it while nothing is playing.
     */
    private object BassLibrary {
        private var retainCount = 0
        private var initialized = false
        private var pluginsLoaded = false
        private var initializationError: Throwable? = null

        @Synchronized
        fun retain(context: Context) {
            if (!initialized) {
                initializationError?.let { throw it }
                try {
                    val frequency = deviceFrequency(context)
                    if (!BASS.BASS_Init(-1, frequency, 0)
                        && BASS.BASS_ErrorGetCode() != BASS.BASS_ERROR_ALREADY) {
                        throw IOException(
                            "BASS_Init() failed with error ${BASS.BASS_ErrorGetCode()}"
                        )
                    }
                    // Plugins outlive BASS_Free(), so they are only ever loaded once.
                    if (!pluginsLoaded) {
                        loadPlugins(context)
                        pluginsLoaded = true
                    }
                    initialized = true
                } catch (t: Throwable) {
                    initializationError = t
                    throw t
                }
            }
            retainCount++
        }

        @Synchronized
        fun release() {
            if (retainCount == 0) {
                return
            }
            retainCount--
            if (retainCount > 0 || !initialized) {
                return
            }
            initialized = false
            // Whatever went wrong last time is worth trying again from a clean device.
            initializationError = null
            BASS.BASS_Free()
        }

        /**
         * The rate the device actually outputs at. Asking BASS for anything else would have it
         * resample, only for the platform to resample the result right back.
         */
        private fun deviceFrequency(context: Context): Int {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return DEFAULT_DEVICE_FREQUENCY
            return audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_DEVICE_FREQUENCY
        }

        private fun loadPlugins(context: Context) {
            val libraryDirectory = File(context.applicationInfo.nativeLibraryDir)
            val libraryNames = libraryDirectory.list() ?: return
            for (libraryName in libraryNames) {
                if (!PLUGIN_NAME_REGEX.matches(libraryName)) {
                    continue
                }
                // BASS_FX isn't a decoder plugin, it is loaded by its own Java class instead.
                if (libraryName == BASS_FX_LIBRARY_NAME) {
                    continue
                }
                BASS.BASS_PluginLoad(libraryName, 0)
            }
        }

        private const val DEFAULT_DEVICE_FREQUENCY = 44100
        private const val BASS_FX_LIBRARY_NAME = "libbass_fx.so"
        private val PLUGIN_NAME_REGEX = Regex("libbass.+\\.so")
    }

    companion object {
        /** `BASS_CONFIG_AM_DISABLE`, disables decoding through the Android Media codecs. */
        private const val BASS_CONFIG_AM_DISABLE = 58

        /** `BASS_CTYPE_STREAM_MIDI` from BASSMIDI, whose Java wrapper isn't vendored. */
        private const val BASS_CTYPE_STREAM_MIDI = 0x10d00
    }
}
