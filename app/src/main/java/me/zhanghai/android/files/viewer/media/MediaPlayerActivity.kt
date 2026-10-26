package me.zhanghai.android.files.viewer.media

import android.app.PictureInPictureParams
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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayerActivity : AppActivity() {
    private lateinit var playerView: AspectPlayerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var controls: View
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
        toolbar = findViewById(R.id.media_toolbar)
        playerView = findViewById(R.id.media_player)
        status = findViewById(R.id.media_status)
        controls = findViewById(R.id.media_controls_scroll)
        toolbar.setNavigationOnClickListener { finish() }
        val root = findViewById<View>(R.id.media_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
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
        val row = findViewById<LinearLayout>(R.id.media_controls)
        fun button(label: Int, action: () -> Unit) {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(label); setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { action() }
            })
        }
        button(R.string.media_speed, ::chooseSpeed)
        button(R.string.media_repeat, ::chooseRepeat)
        button(R.string.media_rotation, ::chooseRotation)
        button(R.string.media_aspect, ::chooseAspect)
        button(R.string.media_audio_track) { chooseTrack(C.TRACK_TYPE_AUDIO) }
        button(R.string.media_subtitle, ::chooseSubtitles)
        button(R.string.media_decoder, ::chooseDecoder)
        button(R.string.media_background, ::chooseBackground)
        button(R.string.media_queue, ::showQueue)
        button(R.string.media_pip, ::enterPip)
        toolbar.menu.add(R.string.media_retry).setOnMenuItemClickListener {
            controller?.apply { prepare(); play() }; true
        }
        toolbar.menu.add(R.string.media_live_alerts).setOnMenuItemClickListener { showSystemControls(); true }
        toolbar.menu.add(R.string.media_stop).setOnMenuItemClickListener {
            controller?.apply { stop(); clearMediaItems() }
            stopService(Intent(this, MediaPlaybackService::class.java))
            finish(); true
        }
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
                    playerView.player = it
                    it.addListener(listener)
                    background = it.sessionExtras.getBoolean(MediaPlaybackService.BACKGROUND, background)
                    decoder = it.sessionExtras.getInt(MediaPlaybackService.DECODER, decoder)
                }
                if (pendingOpen) openIntent() else updateUi()
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
        if (!background && !isChangingConfigurations && !isInPictureInPictureMode) controller?.pause()
        playerView.player = null
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
        val video = player.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_MUSIC
        toolbar.title = player.mediaMetadata.title ?: getString(R.string.media_player_title)
        playerView.controllerShowTimeoutMs = if (video) 3500 else 0
        playerView.controllerHideOnTouch = video
        if (!video) playerView.showController()
        if (video && player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val error = player.playerError
        when {
            error != null -> showError(getString(R.string.media_error, error.cause?.message ?: error.errorCodeName))
            player.mediaItemCount == 0 -> showError(getString(R.string.media_empty))
            else -> status.visibility = View.GONE
        }
    }

    private fun chooseSpeed() {
        val player = controller ?: return
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        choose(R.string.media_speed, speeds.map { "$it×" }.toTypedArray(),
            speeds.indexOf(player.playbackParameters.speed)) { player.setPlaybackSpeed(speeds[it]) }
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
    private fun chooseDecoder() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.media_decoder)
            .setMessage(R.string.media_decoder_hint)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                choose(R.string.media_decoder, resources.getStringArray(R.array.media_decoder_modes), decoder) {
                    sendSettings(Bundle().apply { putInt(MediaPlaybackService.DECODER, it) })
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
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
        MaterialAlertDialogBuilder(this).setTitle(R.string.media_subtitle).setItems(arrayOf(
            getString(R.string.media_subtitle), getString(R.string.media_subtitle_file), getString(R.string.media_subtitle_size)
        )) { _, which ->
            when (which) {
                0 -> chooseTrack(C.TRACK_TYPE_TEXT)
                1 -> subtitlePicker.launch(arrayOf("*/*"))
                2 -> choose(R.string.media_subtitle_size, arrayOf("16", "20", "24", "28", "32"),
                    listOf(16f, 20f, 24f, 28f, 32f).indexOf(subtitleSize)) {
                    subtitleSize = floatArrayOf(16f, 20f, 24f, 28f, 32f)[it]
                    playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                }
            }
        }.show()
    }
    private fun showQueue() {
        val player = controller ?: return
        val names = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty()
        }.toTypedArray()
        choose(R.string.media_queue, names, player.currentMediaItemIndex) { player.seekToDefaultPosition(it); player.play() }
    }
    private fun enterPip() {
        val player = controller ?: return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            || player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC) return
        val size = player.videoSize
        val ratio = if (size.height > 0) (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.39f) else 16f / 9
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build())
        } catch (e: IllegalStateException) { toast(e.message.orEmpty()) }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        controls.visibility = toolbar.visibility
        playerView.useController = !isInPictureInPictureMode
    }
    private fun showSystemControls() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.media_live_alerts)
            .setMessage(R.string.media_live_alerts_info)
            .setPositiveButton(R.string.media_notification_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.setNegativeButton(android.R.string.cancel, null).show()
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
        MaterialAlertDialogBuilder(this).setTitle(title).setSingleChoiceItems(items, selected) { dialog, which ->
            dialog.dismiss(); action(which)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun showError(message: String) { status.text = message; status.visibility = View.VISIBLE }
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
