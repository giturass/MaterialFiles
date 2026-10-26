package me.zhanghai.android.files.viewer.media

import android.app.PictureInPictureParams
import android.app.Dialog
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import android.view.View
import android.view.MenuItem
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTrackNameProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import java8.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayerActivity : AppActivity() {
    private lateinit var playerView: AspectPlayerView
    private lateinit var music: View
    private lateinit var video: View
    private lateinit var videoChrome: View
    private lateinit var musicTransport: MediaTransportControls
    private lateinit var videoTransport: MediaTransportControls
    private lateinit var status: TextView
    private var videoMode = false
    private var videoControlsVisible = true
    private var videoScrubbing = false
    private var dialog: Dialog? = null
    private var artworkMediaId: String? = null
    private var artworkBytes: ByteArray? = null
    private var artworkUri: Uri? = null
    private val hideVideoControls = Runnable { setVideoControlsVisible(false) }
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var openJob: Job? = null
    private var pendingOpen = false
    private var background = true
    private var decoder = 0
    private var aspect = 0
    private var rotation = 0
    private var subtitleSize = 20f
    private var isStarted = false
    private var fullscreen = false
    private var subtitleMediaId: String? = null
    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            fullscreen = false
            applyFullscreen()
            setVideoControlsVisible(true)
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updateUi() }
    }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* A provider may only offer a transient grant. */ }
            lifecycleScope.launch {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val mime = when (name.substringAfterLast('.', "").lowercase()) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                    else -> null
                }
                if (mime == null) toast(getString(R.string.media_subtitle_invalid))
                else sendSettings(Bundle().apply {
                    putString("subtitle_media_id", subtitleMediaId)
                    putString("subtitle_uri", uri.toString())
                    putString("subtitle_mime", mime)
                    putString("subtitle_name", name)
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.media_player_activity)
        music = findViewById(R.id.media_music)
        video = findViewById(R.id.media_video)
        videoChrome = findViewById(R.id.media_video_chrome)
        playerView = findViewById(R.id.media_player)
        status = findViewById(R.id.media_status)
        musicTransport = MediaTransportControls(music)
        videoTransport = MediaTransportControls(video) { scrubbing ->
            videoScrubbing = scrubbing
            scheduleVideoControlsHide()
        }
        fullscreen = savedInstanceState?.getBoolean("fullscreen") ?: false
        subtitleMediaId = savedInstanceState?.getString("subtitle_media_id")
        onBackPressedDispatcher.addCallback(this, fullscreenBack)
        findViewById<MaterialToolbar>(R.id.media_music_toolbar).setNavigationOnClickListener { finish() }
        val videoToolbar = findViewById<MaterialToolbar>(R.id.media_video_toolbar)
        videoToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            videoToolbar.menu.add(R.string.media_pip).apply {
                setIcon(R.drawable.media_pip_icon)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { enterPip(); true }
            }
        }
        videoToolbar.menu.add(R.string.media_settings).apply {
            setIcon(R.drawable.media_tune_icon)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { showSettings(); true }
        }
        playerView.contentDescription = getString(R.string.media_show_controls)
        playerView.setOnClickListener { setVideoControlsVisible(true) }
        videoChrome.setOnClickListener { setVideoControlsVisible(false) }
        findViewById<View>(R.id.media_music_speed).setOnClickListener { chooseSpeed() }
        findViewById<View>(R.id.media_music_queue).setOnClickListener { showQueue() }
        findViewById<View>(R.id.media_music_settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.media_video_speed).setOnClickListener { chooseSpeed() }
        findViewById<View>(R.id.media_video_subtitles).setOnClickListener { chooseSubtitles() }
        findViewById<View>(R.id.media_video_queue).setOnClickListener { showQueue() }
        findViewById<View>(R.id.media_video_fullscreen).setOnClickListener {
            fullscreen = !fullscreen
            applyFullscreen()
            setVideoControlsVisible(true)
        }
        val root = findViewById<View>(R.id.media_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            music.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            videoChrome.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            videoChrome.post { updateVideoLayout() }
            (status.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = bars.left + dp(16)
                rightMargin = bars.right + dp(16)
                bottomMargin = bars.bottom + dp(16)
                status.layoutParams = this
            }
            insets
        }
        findViewById<View>(R.id.media_music_scroll).addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMusicLayout(view.width, view.height)
            }
        }
        videoChrome.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateVideoLayout()
        }
        val prefs = getSharedPreferences(MediaPlaybackService.PREFERENCES, MODE_PRIVATE)
        background = prefs.getBoolean(MediaPlaybackService.BACKGROUND, true)
        decoder = prefs.getInt(MediaPlaybackService.DECODER, 0)
        aspect = savedInstanceState?.getInt("aspect") ?: 0
        rotation = savedInstanceState?.getInt("rotation") ?: 0
        subtitleSize = savedInstanceState?.getFloat("subtitle_size") ?: 20f
        applyAspect()
        playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
        pendingOpen = savedInstanceState?.getBoolean("pending_open") ?: (intent.data != null)
        videoMode = savedInstanceState?.getBoolean("video_mode")
            ?: (intent.data != null && !PlaybackPolicy.isAudio(intent.data?.lastPathSegment.orEmpty(), intent.type))
        applyMediaMode()
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        val future = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))).buildAsync()
        connection = future
        future.addListener({
            if (connection !== future || !isStarted) return@addListener
            try {
                controller = future.get().also {
                    it.addListener(listener)
                    background = it.sessionExtras.getBoolean(MediaPlaybackService.BACKGROUND, background)
                    decoder = it.sessionExtras.getInt(MediaPlaybackService.DECODER, decoder)
                }
                updateUi()
                if (pendingOpen) openIntent()
            } catch (e: Exception) { showError(getString(R.string.media_connect_error, e.cause?.message ?: e.message)) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) {
            pendingOpen = true
            if (controller != null) openIntent()
        }
    }

    override fun onStop() {
        isStarted = false
        openJob?.cancel()
        dialog?.dismiss()
        videoChrome.removeCallbacks(hideVideoControls)
        if (!background && !isChangingConfigurations && !isInPictureInPictureMode) controller?.pause()
        playerView.player = null
        musicTransport.player = null
        videoTransport.player = null
        controller?.removeListener(listener)
        controller = null
        connection?.let(MediaController::releaseFuture)
        connection = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_open", pendingOpen)
        outState.putInt("aspect", aspect)
        outState.putInt("rotation", rotation)
        outState.putFloat("subtitle_size", subtitleSize)
        outState.putBoolean("fullscreen", fullscreen)
        outState.putBoolean("video_mode", videoMode)
        outState.putString("subtitle_media_id", subtitleMediaId)
        super.onSaveInstanceState(outState)
    }

    private fun openIntent() {
        val player = controller ?: return
        val source = intent
        openJob?.cancel()
        openJob = lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val uris = source.getStringArrayListExtra(EXTRA_URIS)?.take(100)
                        ?.map(Uri::parse) ?: listOfNotNull(source.data)
                    uris.map { uri ->
                        val name = displayName(uri)
                        val mime = if (uri == source.data) source.type else contentResolver.getType(uri)
                        MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(name)
                                .setIsPlayable(true).setIsBrowsable(false)
                                .setMediaType(if (PlaybackPolicy.isAudio(name, mime))
                                    MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_VIDEO)
                                .build()).build()
                    }
                }
                if (items.isEmpty()) { showError(getString(R.string.media_empty)); return@launch }
                player.setMediaItems(items, source.getIntExtra(EXTRA_POSITION, 0).coerceIn(items.indices), 0)
                player.prepare()
                player.play()
                pendingOpen = false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.media_error, e.message)) }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
                }
            } catch (_: Exception) { }
        }
        return uri.lastPathSegment ?: getString(R.string.media_player_title)
    }

    private fun updateUi() {
        val player = controller ?: return
        if (player.mediaItemCount > 0) {
            val isVideo = player.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_MUSIC
            if (videoMode != isVideo) {
                videoMode = isVideo
                fullscreen = false
                dialog?.dismiss()
                applyMediaMode()
            }
        }
        playerView.player = if (videoMode) player else null
        musicTransport.player = if (videoMode) null else player
        videoTransport.player = if (videoMode) player else null
        val metadata = player.mediaMetadata
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: getString(
            if (videoMode) R.string.media_video_title else R.string.media_music_title)
        val index = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
        val queueInfo = getString(R.string.media_queue_position, index, player.mediaItemCount)
        findViewById<MaterialToolbar>(R.id.media_video_toolbar).apply {
            this.title = title
            val size = player.videoSize
            subtitle = if (size.height > 0) getString(R.string.media_video_info, size.width, size.height, queueInfo) else queueInfo
        }
        findViewById<TextView>(R.id.media_music_title).text = title
        findViewById<TextView>(R.id.media_music_artist).text = listOfNotNull(metadata.artist, metadata.albumTitle)
            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { getString(R.string.media_local_audio) }
        val state = getString(when {
            player.playbackState == Player.STATE_BUFFERING -> R.string.media_buffering
            player.playbackState == Player.STATE_ENDED -> R.string.media_finished
            player.isPlaying -> R.string.media_playing
            else -> R.string.media_paused
        })
        findViewById<TextView>(R.id.media_music_info).text = getString(R.string.media_playback_info, state, queueInfo)
        val speed = player.playbackParameters.speed.toString().removeSuffix(".0") + "×"
        for (id in intArrayOf(R.id.media_music_speed, R.id.media_video_speed)) {
            findViewById<MaterialButton>(id).apply {
                text = speed
                contentDescription = getString(R.string.media_speed_value, speed)
            }
        }
        if (!videoMode) updateArtwork(player)
        if (videoMode && player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (videoMode && !player.isPlaying) setVideoControlsVisible(true)
        else scheduleVideoControlsHide()
        val error = player.playerError
        when {
            error != null -> showError(getString(R.string.media_error, error.cause?.message ?: error.errorCodeName))
            player.mediaItemCount == 0 -> showError(getString(R.string.media_empty))
            else -> status.visibility = View.GONE
        }
    }

    private fun updateArtwork(player: Player) {
        val metadata = player.mediaMetadata
        val id = player.currentMediaItem?.mediaId
        if (artworkMediaId == id && artworkBytes.contentEquals(metadata.artworkData) && artworkUri == metadata.artworkUri) return
        artworkMediaId = id
        artworkBytes = metadata.artworkData
        artworkUri = metadata.artworkUri
        findViewById<ImageView>(R.id.media_artwork).load(metadata.artworkData ?: metadata.artworkUri) {
            placeholder(R.drawable.media_album_placeholder)
            error(R.drawable.media_album_placeholder)
            fallback(R.drawable.media_album_placeholder)
            crossfade(true)
        }
    }

    private fun updateMusicLayout(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        val wide = width >= dp(600) && width > height
        val padding = dp(if (width < dp(360)) 16 else 24)
        val body = findViewById<LinearLayout>(R.id.media_music_body)
        body.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.setPadding(padding, dp(12), padding, dp(24))
        val size = if (wide) minOf((width * 0.4f).toInt(), height - dp(36), dp(400))
            else minOf(width - padding * 2, (height * 0.43f).toInt().coerceAtLeast(dp(180)), dp(400))
        findViewById<View>(R.id.media_artwork).layoutParams = LinearLayout.LayoutParams(size, size)
        findViewById<View>(R.id.media_music_details).apply {
            layoutParams = if (wide) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(minOf(width - padding * 2, dp(480)), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(if (wide) dp(32) else 0, if (wide) 0 else dp(28), 0, 0)
        }
    }

    private fun applyMediaMode() {
        music.isVisible = !videoMode
        video.isVisible = videoMode
        setVideoControlsVisible(true)
        applyFullscreen()
    }

    private fun updateVideoLayout() {
        val height = videoChrome.height - videoChrome.paddingTop - videoChrome.paddingBottom
        if (height <= 0) return
        val compact = height < dp(320)
        val toolbar = findViewById<View>(R.id.media_video_toolbar)
        val toolbarHeight = dp(if (compact) 48 else 64)
        if (toolbar.layoutParams.height != toolbarHeight) {
            toolbar.layoutParams = toolbar.layoutParams.apply { this.height = toolbarHeight }
        }
        val transport = findViewById<View>(R.id.media_video_transport)
        val bottom = findViewById<View>(R.id.media_video_bottom)
        (transport.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = toolbarHeight
            bottomMargin = bottom.height
            transport.layoutParams = this
        }
        val play = transport.findViewById<MaterialButton>(R.id.media_play_pause)
        val size = dp(if (compact) 64 else 80)
        if (play.layoutParams.height != size) {
            play.layoutParams = play.layoutParams.apply { width = size; this.height = size }
            play.iconSize = dp(if (compact) 32 else 44)
            play.setPadding(dp(12), dp(12), dp(12), dp(12))
        }
    }

    private fun setVideoControlsVisible(visible: Boolean) {
        videoControlsVisible = visible
        videoChrome.isVisible = visible && videoMode && !isInPictureInPictureMode
        scheduleVideoControlsHide()
    }

    private fun scheduleVideoControlsHide() {
        videoChrome.removeCallbacks(hideVideoControls)
        val accessibility = getSystemService(AccessibilityManager::class.java)
        if (isStarted && videoMode && videoControlsVisible && !videoScrubbing
            && dialog == null && controller?.isPlaying == true && !isInPictureInPictureMode
            && !accessibility.isTouchExplorationEnabled) {
            videoChrome.postDelayed(hideVideoControls, 3500)
        }
    }

    private val playerContext: Context get() = if (videoMode) video.context else this

    private fun showDialog(value: Dialog) {
        dialog?.dismiss()
        dialog = value
        videoChrome.removeCallbacks(hideVideoControls)
        value.setOnDismissListener {
            if (dialog === value) dialog = null
            scheduleVideoControlsHide()
        }
        value.show()
    }

    private fun showSettings() {
        val player = controller ?: return
        val options = mutableListOf<MediaOption>()
        fun option(icon: Int, label: Int, value: String = "", action: () -> Unit) {
            options += MediaOption(icon, getString(label), value, action = action)
        }
        option(R.drawable.media_speed_icon, R.string.media_speed,
            player.playbackParameters.speed.toString().removeSuffix(".0") + "×", ::chooseSpeed)
        option(R.drawable.media_repeat_icon, R.string.media_repeat,
            resources.getStringArray(R.array.media_repeat_modes)[player.repeatMode], ::chooseRepeat)
        if (videoMode) {
            option(R.drawable.media_rotate_icon, R.string.media_rotation,
                resources.getStringArray(R.array.media_rotation_modes)[rotation], ::chooseRotation)
            option(R.drawable.media_aspect_icon, R.string.media_aspect,
                resources.getStringArray(R.array.media_aspect_modes)[aspect], ::chooseAspect)
            option(R.drawable.audio_icon_white_24dp, R.string.media_audio_track) { chooseTrack(C.TRACK_TYPE_AUDIO) }
            option(R.drawable.media_subtitles_icon, R.string.media_subtitle, action = ::chooseSubtitles)
            option(R.drawable.media_tune_icon, R.string.media_decoder,
                resources.getStringArray(R.array.media_decoder_modes)[decoder.coerceIn(0, 2)], ::chooseDecoder)
        }
        option(R.drawable.media_pip_icon, R.string.media_background,
            getString(if (background) R.string.media_on else R.string.media_off), ::chooseBackground)
        option(R.drawable.notification_icon_white_24dp, R.string.media_live_alerts, action = ::showSystemControls)
        option(R.drawable.reset_icon_white_24dp, R.string.media_retry) { player.prepare(); player.play() }
        option(R.drawable.stop_icon_white_24dp, R.string.media_stop) {
            player.stop()
            player.clearMediaItems()
            stopService(Intent(this, MediaPlaybackService::class.java))
            finish()
        }
        showDialog(MediaOptionsSheet(playerContext, getString(R.string.media_settings),
            getString(if (videoMode) R.string.media_video_title else R.string.media_music_title), options,
            findViewById<View>(R.id.media_root).height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun chooseSpeed() {
        val player = controller ?: return
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        choose(R.string.media_speed, speeds.map { "$it×" }.toTypedArray(),
            speeds.indexOfFirst { it == player.playbackParameters.speed }) { player.setPlaybackSpeed(speeds[it]) }
    }
    private fun chooseRepeat() {
        val player = controller ?: return
        choose(R.string.media_repeat, resources.getStringArray(R.array.media_repeat_modes), player.repeatMode) { player.repeatMode = it }
    }
    private fun chooseRotation() = choose(R.string.media_rotation,
        resources.getStringArray(R.array.media_rotation_modes), rotation) {
        rotation = it
        requestedOrientation = when (it) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    private fun chooseAspect() = choose(R.string.media_aspect,
        resources.getStringArray(R.array.media_aspect_modes), aspect) { aspect = it; applyAspect() }
    private fun applyAspect() {
        playerView.resizeMode = when (aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.forcedAspect = when (aspect) { 3 -> 16f / 9; 4 -> 4f / 3; else -> 0f }
    }
    private fun chooseDecoder() = choose(R.string.media_decoder,
        resources.getStringArray(R.array.media_decoder_modes), decoder) {
        sendSettings(Bundle().apply { putInt(MediaPlaybackService.DECODER, it) })
    }
    private fun chooseBackground() = choose(R.string.media_background,
        arrayOf(getString(R.string.media_off), getString(R.string.media_on)), if (background) 1 else 0) {
        sendSettings(Bundle().apply { putBoolean(MediaPlaybackService.BACKGROUND, it == 1) })
    }

    private fun chooseTrack(type: Int) {
        val player = controller ?: return
        val tracks = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val names = mutableListOf(getString(R.string.media_auto), getString(R.string.media_off))
        val provider = DefaultTrackNameProvider(resources)
        names += tracks.map { (group, index) -> provider.getTrackName(group.getTrackFormat(index)) }
        val selected = when {
            type in player.trackSelectionParameters.disabledTrackTypes -> 1
            player.trackSelectionParameters.overrides.values.any { it.type == type } ->
                tracks.indexOfFirst { (group, index) -> group.isTrackSelected(index) }.let { if (it < 0) 0 else it + 2 }
            else -> 0
        }
        choose(if (type == C.TRACK_TYPE_AUDIO) R.string.media_audio_track else R.string.media_subtitle,
            names.toTypedArray(), selected) { choice ->
            val parameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(type).setTrackTypeDisabled(type, choice == 1)
            if (choice >= 2) {
                val (group, index) = tracks[choice - 2]
                parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            player.trackSelectionParameters = parameters.build()
        }
    }
    private fun chooseSubtitles() {
        showDialog(MaterialAlertDialogBuilder(playerContext).setTitle(R.string.media_subtitle).setItems(arrayOf(
            getString(R.string.media_subtitle), getString(R.string.media_subtitle_file), getString(R.string.media_subtitle_size)
        )) { _, which ->
            when (which) {
                0 -> chooseTrack(C.TRACK_TYPE_TEXT)
                1 -> {
                    subtitleMediaId = controller?.currentMediaItem?.mediaId
                    subtitlePicker.launch(arrayOf("*/*"))
                }
                2 -> choose(R.string.media_subtitle_size, arrayOf("16", "20", "24", "28", "32"),
                    listOf(16f, 20f, 24f, 28f, 32f).indexOf(subtitleSize)) {
                    subtitleSize = floatArrayOf(16f, 20f, 24f, 28f, 32f)[it]
                    playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                }
            }
        }.create())
    }
    private fun showQueue() {
        val player = controller ?: return
        val options = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            val selected = index == player.currentMediaItemIndex
            MediaOption(
                if (selected) R.drawable.media_play_icon else if (videoMode) R.drawable.video_icon_white_24dp else R.drawable.audio_icon_white_24dp,
                item.mediaMetadata.title ?: getString(R.string.media_player_title),
                if (selected) getString(R.string.media_now_playing) else getString(R.string.media_queue_item, index + 1),
                selected
            ) {
                // The service may have replaced the item for a decoder/subtitle while the sheet was open.
                val currentIndex = (0 until player.mediaItemCount).firstOrNull {
                    player.getMediaItemAt(it).mediaId == item.mediaId
                } ?: return@MediaOption
                player.seekToDefaultPosition(currentIndex)
                player.play()
            }
        }
        showDialog(MediaOptionsSheet(playerContext, getString(R.string.media_queue),
            getString(R.string.media_queue_count, player.mediaItemCount), options,
            findViewById<View>(R.id.media_root).height, player.currentMediaItemIndex))
    }
    private fun enterPip() {
        val player = controller ?: return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            || player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC) return
        val size = player.videoSize
        val ratio = if (size.height > 0) (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.39f) else 16f / 9
        try {
            dialog?.dismiss()
            enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build())
        } catch (e: IllegalStateException) { toast(e.message.orEmpty()) }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        applyFullscreen()
        setVideoControlsVisible(!isInPictureInPictureMode)
        if (isInPictureInPictureMode) status.isVisible = false else updateUi()
    }
    private fun applyFullscreen() {
        val hidden = (videoMode && fullscreen) || isInPictureInPictureMode
        findViewById<MaterialButton>(R.id.media_video_fullscreen).apply {
            setIconResource(if (fullscreen) R.drawable.media_fullscreen_exit_icon else R.drawable.media_fullscreen_icon)
            contentDescription = getString(if (fullscreen) R.string.media_exit_fullscreen else R.string.media_fullscreen)
        }
        fullscreenBack.isEnabled = videoMode && fullscreen
        WindowInsetsControllerCompat(window, playerView).apply {
            val light = !videoMode && ColorUtils.calculateLuminance(getColorByAttr(MaterialR.attr.colorSurface)) > 0.5
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.media_root))
    }
    private fun showSystemControls() {
        showDialog(MaterialAlertDialogBuilder(playerContext).setTitle(R.string.media_live_alerts)
            .setMessage(R.string.media_live_alerts_info)
            .setPositiveButton(R.string.media_notification_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton(android.R.string.cancel, null).create())
    }
    private fun sendSettings(args: Bundle) {
        val player = controller ?: return
        val future = player.sendCustomCommand(SessionCommand(MediaPlaybackService.SETTINGS, Bundle.EMPTY), args)
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode != 0) { toast(getString(R.string.media_error, result.resultCode.toString())); return@addListener }
                background = result.extras.getBoolean(MediaPlaybackService.BACKGROUND, background)
                decoder = result.extras.getInt(MediaPlaybackService.DECODER, decoder)
            } catch (e: Exception) { toast(getString(R.string.media_error, e.message)) }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun choose(title: Int, items: Array<String>, selected: Int, action: (Int) -> Unit) {
        showDialog(MaterialAlertDialogBuilder(playerContext).setTitle(title).setSingleChoiceItems(items, selected) { dialog, which ->
            dialog.dismiss(); action(which)
        }.setNegativeButton(android.R.string.cancel, null).create())
    }
    private fun showError(message: String) {
        status.text = message
        status.isVisible = !isInPictureInPictureMode
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_URIS = "media.uris"
        private const val EXTRA_POSITION = "media.position"
        fun putPlaylist(intent: Intent, paths: List<Path>, position: Int) {
            intent.putStringArrayListExtra(EXTRA_URIS, ArrayList(paths.map { it.fileProviderUri.toString() }))
            intent.putExtra(EXTRA_POSITION, position)
            intent.extraPathList = paths
        }
    }
}
