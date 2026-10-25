package me.zhanghai.android.files.media

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPathList

@OptIn(UnstableApi::class)
class MediaPlayerActivity : AppActivity() {
    private lateinit var preferences: MediaPreferences
    private lateinit var toolbar: MaterialToolbar
    private lateinit var root: LinearLayout
    private lateinit var videoView: VideoPlayerView
    private lateinit var audioView: FluidAudioView
    private lateinit var errorView: TextView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var newIntentPending = false
    private var pickingSubtitle = false
    private var subtitleTarget: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { audioView.update(); handler.postDelayed(this, 500) }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update() }
    }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingSubtitle = false
        if (uri != null) attachSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = MediaPreferences(this)
        newIntentPending = savedInstanceState == null && intent.data != null
        pickingSubtitle = savedInstanceState?.getBoolean("picking_subtitle") ?: false
        subtitleTarget = savedInstanceState?.getString("subtitle_target")
        createViews()
        val future = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                controller = future.get().also {
                    it.addListener(listener)
                    videoView.player = it
                    audioView.player = it
                }
                if (newIntentPending || (controller?.mediaItemCount == 0 && intent.data != null)) openIntent()
                update()
            } catch (e: Exception) {
                errorView.text = getString(R.string.media_connect_error)
                errorView.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createViews() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.media_title)
            setNavigationIcon(R.drawable.media_back)
            setNavigationOnClickListener { finish() }
            for ((id, title) in listOf(
                SPEED to R.string.media_speed, LOOP to R.string.media_loop,
                ROTATE to R.string.media_rotate, ASPECT to R.string.media_aspect,
                DECODER to R.string.media_decoder, AUDIO_TRACK to R.string.media_audio_track,
                SUBTITLE_TRACK to R.string.media_subtitles, ADD_SUBTITLE to R.string.media_add_subtitle,
                BACKGROUND to R.string.media_background, PIP to R.string.media_pip,
                PLAYLIST to R.string.media_playlist, CLOSE to R.string.media_close
            )) menu.add(Menu.NONE, id, Menu.NONE, title)
            setOnMenuItemClickListener { handleMenu(it.itemId); true }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(this)
        videoView = VideoPlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            setShowPreviousButton(true)
            setShowNextButton(true)
            setRepeatToggleModes(3)
        }
        content.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        audioView = FluidAudioView(this)
        content.addView(audioView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            val margin = (20 * resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, margin)
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        errorView = TextView(this).apply {
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
            setOnClickListener { controller?.prepare(); controller?.play() }
        }
        root.addView(errorView, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyAspect()
        update()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntentPending = intent.data != null
        if (controller != null && newIntentPending) openIntent()
    }

    private fun openIntent() {
        val player = controller ?: return
        newIntentPending = false
        try {
            val paths = intent.extraPathList
            val uri = intent.data
            val video = intent.getBooleanExtra(MediaFiles.EXTRA_VIDEO,
                MediaFiles.isVideo(paths.firstOrNull()?.fileName?.toString()
                    ?: intent.data?.lastPathSegment.orEmpty(), intent.type))
            // extraPathList also returns ACTION_VIEW's data as a single fallback path. Keep that
            // external URI intact so its read grant and streaming MIME reach the service.
            val externalUri = uri != null && (paths.isEmpty()
                || (paths.size == 1 && paths[0].toUri().toString() == uri.toString()))
            val items = if (externalUri) listOf(MediaFiles.item(uri!!,
                uri.lastPathSegment ?: getString(R.string.media_title), video, intent.type))
                else paths.map { MediaFiles.item(it, video) }
            if (items.isEmpty()) return
            val position = intent.getIntExtra(MediaFiles.EXTRA_POSITION, 0).coerceIn(items.indices)
            // Extend external ACTION_VIEW URI grants to the started playback service's lifetime.
            startService(Intent(this, MediaPlaybackService::class.java).apply {
                clipData = ClipData.newRawUri("media", items.first().localConfiguration!!.uri).apply {
                    items.drop(1).forEach { addItem(ClipData.Item(it.localConfiguration!!.uri)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            if (player.currentMediaItem?.mediaId != items[position].mediaId) {
                player.setMediaItems(items, position, 0)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            errorView.text = getString(R.string.media_open_error, e.localizedMessage.orEmpty())
            errorView.visibility = View.VISIBLE
        }
    }

    private fun update() {
        val player = controller
        val video = player?.currentMediaItem?.let(MediaFiles::isVideo) ?: intent.getBooleanExtra(MediaFiles.EXTRA_VIDEO, false)
        videoView.visibility = if (video) View.VISIBLE else View.GONE
        audioView.visibility = if (video) View.GONE else View.VISIBLE
        toolbar.title = player?.mediaMetadata?.title ?: getString(if (video) R.string.media_video_title else R.string.media_audio_title)
        for (id in listOf(ROTATE, ASPECT, DECODER, AUDIO_TRACK, SUBTITLE_TRACK, ADD_SUBTITLE, BACKGROUND, PIP)) {
            toolbar.menu.findItem(id).isVisible = video
        }
        toolbar.menu.findItem(BACKGROUND).apply {
            isCheckable = true
            isChecked = preferences.backgroundVideo
        }
        audioView.update()
        val error = player?.playerError
        if (error != null) {
            errorView.text = getString(R.string.media_play_error, error.errorCodeName)
            errorView.visibility = View.VISIBLE
        } else errorView.visibility = View.GONE
        if (video && player?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleMenu(id: Int) {
        val player = controller ?: return
        when (id) {
            SPEED -> {
                val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
                choose(R.string.media_speed, speeds.map { "$it×" }, speeds.indexOf(player.playbackParameters.speed)) {
                    player.setPlaybackSpeed(speeds[it])
                }
            }
            LOOP -> choose(R.string.media_loop, resources.getStringArray(R.array.media_loop_modes).toList(),
                player.repeatMode) { player.repeatMode = it }
            ROTATE -> requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ASPECT -> choose(R.string.media_aspect, resources.getStringArray(R.array.media_aspect_modes).toList(),
                preferences.aspectMode) { preferences.aspectMode = it; applyAspect() }
            DECODER -> choose(R.string.media_decoder, resources.getStringArray(R.array.media_decoder_modes).toList(),
                listOf("auto", "software", "hardware").indexOf(preferences.decoderMode)) {
                player.sendCustomCommand(SessionCommand(MediaPlaybackService.COMMAND_DECODER, Bundle.EMPTY),
                    Bundle().apply { putString("mode", listOf("auto", "software", "hardware")[it]) })
            }
            AUDIO_TRACK -> chooseTrack(C.TRACK_TYPE_AUDIO)
            SUBTITLE_TRACK -> chooseTrack(C.TRACK_TYPE_TEXT)
            ADD_SUBTITLE -> {
                pickingSubtitle = true
                subtitleTarget = player.currentMediaItem?.mediaId
                subtitlePicker.launch(arrayOf("*/*"))
            }
            BACKGROUND -> { preferences.backgroundVideo = !preferences.backgroundVideo; update() }
            PIP -> enterPip()
            PLAYLIST -> {
                if (player.mediaItemCount == 0) return
                choose(R.string.media_playlist, (0 until player.mediaItemCount).map {
                    player.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty()
                }, player.currentMediaItemIndex) { player.seekToDefaultPosition(it); player.play() }
            }
            CLOSE -> {
                player.sendCustomCommand(SessionCommand(MediaPlaybackService.COMMAND_CLOSE, Bundle.EMPTY), Bundle.EMPTY)
                finish()
            }
        }
    }

    private fun choose(title: Int, entries: List<String>, selected: Int, action: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(entries.toTypedArray(), selected) { dialog, position ->
                action(position)
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseTrack(type: Int) {
        val player = controller ?: return
        val tracks = mutableListOf<Pair<Tracks.Group, Int>>()
        val names = mutableListOf(getString(R.string.media_track_auto))
        var selected = 0
        if (type == C.TRACK_TYPE_TEXT) names += getString(R.string.media_track_off)
        val offset = names.size
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                tracks += group to index
                names += listOfNotNull(format.label, format.language, format.sampleMimeType).joinToString(" · ")
                    .ifBlank { getString(R.string.media_track_number, tracks.size) }
                if (group.isTrackSelected(index)) selected = names.lastIndex
            }
        }
        if (type == C.TRACK_TYPE_TEXT && type in player.trackSelectionParameters.disabledTrackTypes) selected = 1
        choose(if (type == C.TRACK_TYPE_TEXT) R.string.media_subtitles else R.string.media_audio_track, names, selected) {
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && it == 1)
            if (it >= offset) {
                val (group, index) = tracks[it - offset]
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    private fun attachSubtitle(uri: Uri) {
        val player = controller ?: return
        val item = player.currentMediaItem ?: return
        if (item.mediaId != subtitleTarget) return
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.media_open_error, e.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show()
            return
        }
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ssa", "ass" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> { Toast.makeText(this, R.string.media_subtitle_unsupported, Toast.LENGTH_LONG).show(); return }
        }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* The picker grant is still valid for this playback. */ }
        val subtitles = item.localConfiguration?.subtitleConfigurations.orEmpty() +
            MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(name)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt).toMutableList()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val playing = player.playWhenReady
        items[index] = item.buildUpon().setSubtitleConfigurations(subtitles).build()
        player.setMediaItems(items, index, position)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        player.prepare()
        player.playWhenReady = playing
    }

    private fun applyAspect() {
        videoView.forcedRatio = when (preferences.aspectMode) { 3 -> 16f / 9; 4 -> 4f / 3; else -> 0f }
        videoView.resizeMode = when (preferences.aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        val size = controller?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.39f)
        } else 16f / 9
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        videoView.useController = !isInPictureInPictureMode
    }

    override fun onStart() { super.onStart(); handler.post(ticker) }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controller?.let {
            if (it.currentMediaItem?.let(MediaFiles::isVideo) == true && !preferences.backgroundVideo
                && !isChangingConfigurations && !isInPictureInPictureMode && !pickingSubtitle) it.pause()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picking_subtitle", pickingSubtitle)
        outState.putString("subtitle_target", subtitleTarget)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        videoView.player = null
        audioView.player = null
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        super.onDestroy()
    }

    companion object {
        private const val SPEED = 1
        private const val LOOP = 2
        private const val ROTATE = 3
        private const val ASPECT = 4
        private const val DECODER = 5
        private const val AUDIO_TRACK = 6
        private const val SUBTITLE_TRACK = 7
        private const val ADD_SUBTITLE = 8
        private const val BACKGROUND = 9
        private const val PIP = 10
        private const val PLAYLIST = 11
        private const val CLOSE = 12
    }
}
