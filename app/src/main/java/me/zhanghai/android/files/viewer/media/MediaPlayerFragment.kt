/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.MediaPlayerFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.intentType
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.file.isPlayableMedia
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.systemuihelper.SystemUiHelper
import kotlin.math.roundToInt

class MediaPlayerFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var source: Source
    private lateinit var binding: MediaPlayerFragmentBinding
    private lateinit var systemUiHelper: SystemUiHelper

    private var playbackService: MediaPlaybackService? = null
    private var serviceBindRequested = false
    private var viewStarted = false
    private var shouldOpenMedia = false
    private var controlsVisible = true
    private var displayedAsAudio = false
    private var seeking = false
    private var displayedArtwork: Bitmap? = null
    private var hasDisplayedArtwork = false
    private var suppressStopPlayback = false
    private var coverRotationAnimator: ObjectAnimator? = null
    private var displayedMenuRevision = Int.MIN_VALUE
    private var displayedMenuAudio: Boolean? = null
    private var displayedPlaying: Boolean? = null
    private var lyricsVisible = false
    private var displayedLyrics: Lyrics? = null
    private var lyricsUserScrolling = false
    private var displayedActivityTitle: CharSequence? = null

    /** Resolved once, since the theme cannot change while the view is alive. */
    private val repeatOnTint by lazy {
        ColorStateList.valueOf(
            requireContext().getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        )
    }
    private val repeatOffTint by lazy {
        ColorStateList.valueOf(
            requireContext()
                .getColorByAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
    }

    private val lyricsAdapter = LyricsAdapter { line ->
        playbackService?.seekTo(line.timeMillis)
    }

    private val endLyricsUserScrollingRunnable = Runnable { lyricsUserScrolling = false }

    private val playbackListener = object : MediaPlaybackService.Listener {
        override fun onPlaybackStateChanged() {
            updatePlaybackState()
        }

        override fun onPlaybackProgressChanged() {
            updateProgress()
        }
    }

    private val hideControlsRunnable = Runnable {
        if (!displayedAsAudio && playbackService?.isPlaying == true) {
            systemUiHelper.hide()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!viewStarted || !::binding.isInitialized) {
                return
            }
            val service = (binder as MediaPlaybackService.LocalBinder).service
            playbackService = service
            service.addListener(playbackListener)
            if (service.currentUri == null) {
                startPlayback(force = true)
            }
            updatePlaybackState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            updatePlaybackState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
        shouldOpenMedia = savedInstanceState == null
            && !MediaPlayerActivity.isAttachToSessionIntent(args.intent)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        MediaPlayerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val source = resolveSource(requireContext(), args.intent)
        if (source == null) {
            finish()
            return
        }
        this.source = source

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        activity.title = source.title
        activity.window.statusBarColor = Color.TRANSPARENT
        binding.appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
        binding.controlsContainer.applySystemWindowInsetsToPadding(
            left = true, right = true, bottom = true
        )

        updateMediaPresentation(source.isAudio)

        systemUiHelper = SystemUiHelper(
            activity,
            SystemUiHelper.LEVEL_IMMERSIVE,
            SystemUiHelper.FLAG_IMMERSIVE_STICKY
        ) { visible -> setControlsVisible(visible) }
        systemUiHelper.show()

        binding.mediaContent.setOnClickListener {
            systemUiHelper.toggle()
        }
        binding.lyricsList.layoutManager = LinearLayoutManager(requireContext())
        binding.lyricsList.adapter = lyricsAdapter
        binding.lyricsList.itemAnimator = null
        binding.lyricsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // Don't yank the list back under the finger that is reading ahead.
                    lyricsUserScrolling = true
                    binding.root.removeCallbacks(endLyricsUserScrollingRunnable)
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && lyricsUserScrolling) {
                    binding.root.removeCallbacks(endLyricsUserScrollingRunnable)
                    binding.root.postDelayed(
                        endLyricsUserScrollingRunnable, LYRICS_RESUME_SCROLL_DELAY_MILLIS
                    )
                }
            }
        })
        // The disc and the lyrics are two views of the same thing, so tapping either swaps them.
        binding.audioCoverCard.setOnClickListener { setLyricsVisible(true) }
        binding.lyricsPanel.setOnClickListener { setLyricsVisible(false) }
        binding.playPauseButton.setOnClickListener {
            val service = playbackService
            if (service == null || service.currentUri == null) {
                startPlayback(force = true)
            } else if (!service.hasMedia
                || service.status == MediaPlaybackService.Status.ERROR
                || service.status == MediaPlaybackService.Status.STOPPED) {
                service.play()
            } else {
                service.togglePlayPause()
            }
            scheduleHideControls()
        }
        binding.rewindButton.setOnClickListener {
            playbackService?.seekBy(-MediaPlaybackService.SEEK_INTERVAL_MILLIS)
            scheduleHideControls()
        }
        binding.forwardButton.setOnClickListener {
            playbackService?.seekBy(MediaPlaybackService.SEEK_INTERVAL_MILLIS)
            scheduleHideControls()
        }
        binding.previousButton.setOnClickListener {
            playbackService?.playPrevious()
            scheduleHideControls()
        }
        binding.nextButton.setOnClickListener {
            playbackService?.playNext()
            scheduleHideControls()
        }
        binding.repeatButton.setOnClickListener {
            playbackService?.let { it.setRepeat(!it.repeat) }
            scheduleHideControls()
        }
        binding.rotateButton.setOnClickListener {
            toggleScreenOrientation()
            scheduleHideControls()
        }
        binding.retryButton.setOnClickListener {
            val service = playbackService
            if (service?.currentUri != null) {
                service.play()
            } else {
                startPlayback(force = true)
            }
        }
        binding.seekBar.setLabelFormatter { value ->
            formatTime(timeForSeekBarValue(value))
        }
        binding.seekBar.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) {
                return@addOnChangeListener
            }
            binding.currentTimeText.text = formatTime(timeForSeekBarValue(value))
        }
        binding.seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seeking = true
                cancelHideControls()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                playbackService?.let {
                    if (it.duration > 0L) {
                        it.seekTo(timeForSeekBarValue(slider.value))
                    }
                }
                seeking = false
                scheduleHideControls()
            }
        })

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!displayedAsAudio && !controlsVisible) {
                        systemUiHelper.show()
                    } else {
                        finish()
                    }
                }
            }
        )
        updatePlaybackState()
    }

    override fun onStart() {
        super.onStart()

        if (!::source.isInitialized) {
            return
        }
        viewStarted = true
        if (shouldOpenMedia) {
            shouldOpenMedia = false
            startPlayback(force = true)
        }
        // Even a bindService() that returns false leaves the connection registered, so it has to be
        // unbound either way or it leaks.
        serviceBindRequested = true
        requireContext().bindService(
            Intent(requireContext(), MediaPlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        cancelHideControls()
        pauseCoverRotation()
        if (::source.isInitialized) {
            val service = playbackService
            if (service != null && ::binding.isInitialized) {
                service.detachVideoLayout(binding.videoLayout)
            }
            service?.removeListener(playbackListener)
            if (!suppressStopPlayback
                && !Settings.MEDIA_PLAYER_BACKGROUND_PLAYBACK.valueCompat
                && !requireActivity().isChangingConfigurations) {
                if (service != null) {
                    service.stopPlayback()
                } else {
                    MediaPlaybackService.stop(requireContext())
                }
            }
        }
        if (serviceBindRequested) {
            serviceBindRequested = false
            runCatching { requireContext().unbindService(serviceConnection) }
        }
        playbackService = null
        viewStarted = false
        super.onStop()
    }

    override fun onDestroyView() {
        coverRotationAnimator?.cancel()
        coverRotationAnimator = null
        if (::binding.isInitialized) {
            binding.audioCover.dispose()
            binding.audioBackdrop.dispose()
        }
        // The service owns the artwork and may outlive this view; only the reference is ours.
        displayedArtwork = null
        hasDisplayedArtwork = false
        displayedActivityTitle = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.media_player, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        if (!::source.isInitialized) {
            return
        }

        menu.findItem(R.id.action_background_playback)?.isChecked =
            Settings.MEDIA_PLAYER_BACKGROUND_PLAYBACK.valueCompat
        menu.findItem(R.id.action_hardware_decoding)?.isChecked =
            Settings.MEDIA_PLAYER_HARDWARE_DECODING.valueCompat
        menu.findItem(R.id.action_video_scale)?.isVisible = !displayedAsAudio
        menu.findItem(R.id.action_audio_track)?.apply {
            // A track can only be picked where there is more than one to pick from.
            isVisible = !displayedAsAudio
            isEnabled = (playbackService?.getAudioTracks()?.size ?: 0) > 1
        }
        menu.findItem(R.id.action_subtitle_track)?.apply {
            isVisible = !displayedAsAudio
            isEnabled = playbackService?.getSubtitleTracks()?.isNotEmpty() == true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_background_playback -> {
                val enabled = !Settings.MEDIA_PLAYER_BACKGROUND_PLAYBACK.valueCompat
                Settings.MEDIA_PLAYER_BACKGROUND_PLAYBACK.putValue(enabled)
                item.isChecked = enabled
                true
            }
            R.id.action_hardware_decoding -> {
                val enabled = !Settings.MEDIA_PLAYER_HARDWARE_DECODING.valueCompat
                playbackService?.setHardwareDecoding(enabled)
                    ?: Settings.MEDIA_PLAYER_HARDWARE_DECODING.putValue(enabled)
                item.isChecked = enabled
                true
            }
            R.id.action_playback_speed -> {
                showPlaybackSpeedDialog()
                true
            }
            R.id.action_video_scale -> {
                showVideoScaleDialog()
                true
            }
            R.id.action_audio_track -> {
                showAudioTrackDialog()
                true
            }
            R.id.action_subtitle_track -> {
                showSubtitleTrackDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun startPlayback(force: Boolean) {
        if (!::source.isInitialized) {
            return
        }
        MediaPlaybackService.open(
            requireContext(),
            source.uri,
            source.mimeType.intentType,
            source.title,
            source.isAudio,
            force,
            source.path
        )
    }

    private fun updatePlaybackState() {
        if (!::binding.isInitialized || !::source.isInitialized) {
            return
        }
        val service = playbackService
        val status = service?.status ?: MediaPlaybackService.Status.IDLE
        val playing = service?.isPlaying == true
        val showPause = service?.isPlayingOrPreparing == true
        val duration = service?.duration ?: 0L
        val seekable = service?.isSeekable == true
        val isAudio = if (service?.currentUri != null) service.isAudio else source.isAudio
        updateMediaPresentation(isAudio)

        val title = service?.displayTitle?.takeIf { it.isNotEmpty() } ?: source.title
        val subtitle = service?.displaySubtitle
        if (displayedAsAudio) {
            // The artwork panel already shows the track prominently, so the app bar stays out of
            // the way.
            setActivityTitle("")
            binding.toolbar.subtitle = null
            binding.audioTitle.text = title
            binding.audioSubtitle.text = subtitle
            binding.audioSubtitle.isVisible = !subtitle.isNullOrEmpty()
        } else {
            setActivityTitle(title)
            binding.toolbar.subtitle = subtitle
        }
        binding.progress.isVisible = status == MediaPlaybackService.Status.OPENING
            || status == MediaPlaybackService.Status.BUFFERING
        val hasError = status == MediaPlaybackService.Status.ERROR
        binding.errorPanel.isVisible = hasError
        if (hasError) {
            binding.errorText.text =
                service?.errorMessage ?: getString(R.string.media_player_error)
        }
        binding.playPauseButton.setIconResource(
            if (showPause) R.drawable.media_pause_icon_white_24dp
            else R.drawable.media_play_icon_white_24dp
        )
        binding.playPauseButton.contentDescription = getString(
            if (showPause) R.string.media_player_pause else R.string.media_player_play
        )
        binding.rewindButton.isEnabled = seekable
        binding.forwardButton.isEnabled = seekable
        binding.previousButton.isEnabled = service?.hasPrevious == true
        binding.nextButton.isEnabled = service?.hasNext == true
        binding.repeatButton.isEnabled = service?.hasMedia == true
        // Material 3's checkable icon button reads as primary when on and as on-surface-variant when
        // off, which is exactly the distinction repeat needs.
        binding.repeatButton.iconTint =
            if (service?.repeat == true) repeatOnTint else repeatOffTint
        binding.seekBar.isEnabled = seekable && duration > 0L
        binding.root.keepScreenOn = viewStarted && !displayedAsAudio && playing
        updateArtwork(service?.artwork)
        updateCoverRotation()
        updateProgress()
        // Only on the transition, or the periodic progress updates would keep pushing the timer
        // back and the controls would never hide.
        if (displayedPlaying != playing) {
            displayedPlaying = playing
            if (playing) {
                scheduleHideControls()
            } else {
                cancelHideControls()
            }
        }
        val menuRevision = service?.menuRevision ?: -1
        if (displayedMenuRevision != menuRevision || displayedMenuAudio != displayedAsAudio) {
            displayedMenuRevision = menuRevision
            displayedMenuAudio = displayedAsAudio
            requireActivity().invalidateOptionsMenu()
        }
    }

    /**
     * The part that has to keep up with playback, four times a second. Everything else only changes
     * when the session does, and is left to [updatePlaybackState].
     */
    private fun updateProgress() {
        if (!::binding.isInitialized || !::source.isInitialized) {
            return
        }
        val service = playbackService
        val duration = service?.duration ?: 0L
        val currentTime = service?.currentTime ?: 0L
        if (!seeking) {
            binding.seekBar.value = if (duration > 0L) {
                (currentTime.coerceAtMost(duration) * SEEK_BAR_MAX / duration).toFloat()
            } else {
                0f
            }
            binding.currentTimeText.setTextIfChanged(formatTime(currentTime))
        }
        // A stream can learn its own length as it goes, without an engine callback to say so.
        binding.durationText.setTextIfChanged(formatTime(duration))
        updateLyrics(service?.lyrics, currentTime)
    }

    private fun setActivityTitle(title: CharSequence) {
        if (displayedActivityTitle == title) {
            return
        }
        displayedActivityTitle = title
        requireActivity().title = title
    }

    /** Avoids the relayout that setting the same text on a TextView would still cause. */
    private fun TextView.setTextIfChanged(value: String) {
        if (text?.toString() != value) {
            text = value
        }
    }

    internal fun prepareForReplacement() {
        suppressStopPlayback = true
    }

    internal fun matchesIntent(intent: Intent): Boolean =
        args.intent.filterEquals(intent)
            && args.intent.extraPath == intent.extraPath
            && (MediaPlayerActivity.isAttachToSessionIntent(args.intent)
                == MediaPlayerActivity.isAttachToSessionIntent(intent))

    private fun updateMediaPresentation(isAudio: Boolean) {
        displayedAsAudio = isAudio
        if (!isAudio && lyricsVisible) {
            // Lyrics belong to a song, not to a video.
            lyricsVisible = false
        }
        binding.audioPanel.isVisible = isAudio && !lyricsVisible
        binding.lyricsPanel.isVisible = isAudio && lyricsVisible
        binding.videoLayout.isVisible = !isAudio
        binding.rotateButton.isVisible = !isAudio
        // Music moves between tracks rather than within one, so the folder replaces seeking by a
        // fixed interval for audio.
        binding.previousButton.isVisible = isAudio
        binding.nextButton.isVisible = isAudio
        binding.rewindButton.isVisible = !isAudio
        binding.forwardButton.isVisible = !isAudio
        if (isAudio) {
            binding.audioBackdrop.isVisible = hasArtwork
            binding.audioBackdropScrim.isVisible = hasArtwork
        } else {
            binding.audioBackdrop.isVisible = false
            binding.audioBackdropScrim.isVisible = false
        }
        val service = playbackService
        if (service != null && viewStarted) {
            if (isAudio) {
                service.detachVideoLayout(binding.videoLayout)
            } else {
                service.attachVideoLayout(binding.videoLayout)
            }
        }
    }

    private val hasArtwork: Boolean
        get() = displayedArtwork != null

    private fun updateArtwork(artwork: Bitmap?) {
        if (!displayedAsAudio || hasDisplayedArtwork && displayedArtwork === artwork) {
            return
        }
        hasDisplayedArtwork = true
        displayedArtwork = artwork
        binding.audioCover.dispose()
        binding.audioBackdrop.dispose()
        if (artwork == null) {
            // Fitted rather than centre-inside, so the glyph grows with the label instead of
            // sitting small in the middle of it.
            binding.audioCover.scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = resources.getDimensionPixelSize(
                R.dimen.media_player_audio_cover_placeholder_padding
            )
            binding.audioCover.setPadding(padding, padding, padding, padding)
            binding.audioCover.imageAlpha = COVER_PLACEHOLDER_ALPHA
            // On-surface-variant is Material 3's role for a de-emphasised glyph on a container, and
            // it follows dynamic colour where the white asset would not.
            binding.audioCover.imageTintList = ColorStateList.valueOf(
                requireContext()
                    .getColorByAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            binding.audioCover.setImageResource(R.drawable.audio_icon_white_24dp)
            binding.audioBackdrop.setImageDrawable(null)
            binding.audioBackdrop.isVisible = false
            binding.audioBackdropScrim.isVisible = false
        } else {
            // Cropped rather than fitted: the artwork covers the whole label circle, and a cover
            // is square, so the corners are what has to give.
            binding.audioCover.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.audioCover.setPadding(0, 0, 0, 0)
            binding.audioCover.imageAlpha = 0xFF
            // Real artwork must not pick up the placeholder tint.
            binding.audioCover.imageTintList = null
            binding.audioCover.load(artwork) {
                crossfade(mediumAnimTime)
                error(R.drawable.audio_icon_white_24dp)
            }
            binding.audioBackdrop.isVisible = true
            binding.audioBackdropScrim.isVisible = true
            binding.audioBackdrop.load(artwork) { crossfade(mediumAnimTime) }
            applyBackdropBlur()
        }
    }

    /** Swaps between the spinning disc and the lyrics. */
    private fun setLyricsVisible(visible: Boolean) {
        if (lyricsVisible == visible || !displayedAsAudio) {
            return
        }
        lyricsVisible = visible
        binding.audioPanel.isVisible = !visible
        binding.lyricsPanel.isVisible = visible
        if (visible) {
            lyricsUserScrolling = false
            binding.root.removeCallbacks(endLyricsUserScrollingRunnable)
            scrollToHighlightedLyricsLine()
        }
        updateCoverRotation()
    }

    private fun updateLyrics(lyrics: Lyrics?, currentTime: Long) {
        if (displayedLyrics !== lyrics) {
            displayedLyrics = lyrics
            lyricsAdapter.highlightedPosition = RecyclerView.NO_POSITION
            lyricsAdapter.replace(lyrics?.lines.orEmpty())
            binding.lyricsEmptyText.isVisible = lyrics == null
            lyricsUserScrolling = false
            binding.root.removeCallbacks(endLyricsUserScrollingRunnable)
            binding.lyricsList.scrollToPosition(0)
        }
        if (!lyricsVisible || lyrics == null) {
            return
        }
        val index = lyrics.indexAt(currentTime)
        if (index == lyricsAdapter.highlightedPosition) {
            return
        }
        lyricsAdapter.highlightedPosition = index
        scrollToHighlightedLyricsLine()
    }

    /** Keeps the line being sung in the middle of the screen, where the eye already is. */
    private fun scrollToHighlightedLyricsLine() {
        if (lyricsUserScrolling) {
            return
        }
        val position = lyricsAdapter.highlightedPosition
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        val layoutManager = binding.lyricsList.layoutManager as? LinearLayoutManager ?: return
        val listHeight = binding.lyricsList.height
        if (listHeight <= 0) {
            layoutManager.scrollToPosition(position)
            return
        }
        val lineHeight = binding.lyricsList.findViewHolderForAdapterPosition(position)
            ?.itemView
            ?.height
            ?: 0
        // The offset is measured from inside the list's padding, which is there to keep the lyrics
        // clear of the app bar and the controls.
        val offset = (listHeight - lineHeight) / 2 - binding.lyricsList.paddingTop
        layoutManager.scrollToPositionWithOffset(position, offset)
    }

    /**
     * Spins the record while audio plays, holding the angle whenever playback pauses so that
     * resuming continues the same revolution instead of snapping back upright.
     */
    private fun updateCoverRotation() {
        if (!displayedAsAudio) {
            // Nothing meaningful to spin, and the next track should start upright.
            coverRotationAnimator?.cancel()
            coverRotationAnimator = null
            binding.audioDisc.rotation = 0f
            return
        }
        val animator = coverRotationAnimator
            ?: ObjectAnimator.ofFloat(binding.audioDisc, View.ROTATION, 0f, 360f)
                .apply {
                    duration = COVER_ROTATION_PERIOD_MILLIS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = LinearInterpolator()
                }
                .also { coverRotationAnimator = it }
        if (playbackService?.isPlaying == true) {
            when {
                !animator.isStarted -> animator.start()
                animator.isPaused -> animator.resume()
            }
        } else if (animator.isStarted && !animator.isPaused) {
            animator.pause()
        }
    }

    private fun pauseCoverRotation() {
        coverRotationAnimator?.let {
            if (it.isStarted && !it.isPaused) {
                it.pause()
            }
        }
    }

    /** Locks the activity to the opposite orientation so a video can be watched either way. */
    private fun toggleScreenOrientation() {
        val isLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requireActivity().requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun applyBackdropBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Without RenderEffect the artwork is simply blown up and dimmed, which is soft enough
            // at the size we keep it at.
            return
        }
        val radius = BACKDROP_BLUR_RADIUS_DP * resources.displayMetrics.density
        binding.audioBackdrop.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val duration = mediumAnimTime.toLong()
        binding.appBarLayout.animate()
            .alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else -binding.appBarLayout.height.toFloat())
            .setDuration(duration)
            .start()
        binding.controlsContainer.animate()
            .alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else binding.controlsContainer.height.toFloat())
            .setDuration(duration)
            .start()
        if (visible) {
            scheduleHideControls()
        } else {
            cancelHideControls()
        }
    }

    /** Gets the controls out of the way of the video once playback has settled. */
    private fun scheduleHideControls() {
        cancelHideControls()
        if (!::binding.isInitialized || displayedAsAudio || !controlsVisible || seeking) {
            return
        }
        if (playbackService?.isPlaying != true) {
            return
        }
        binding.root.postDelayed(hideControlsRunnable, HIDE_CONTROLS_DELAY_MILLIS)
    }

    private fun cancelHideControls() {
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun timeForSeekBarValue(value: Float): Long {
        val duration = playbackService?.duration ?: 0L
        // The slider is continuous, so the fraction has to survive into the multiplication; rounding
        // it away first costs a second per twenty minutes of media.
        return (duration * value.toDouble() / SEEK_BAR_MAX).toLong()
            .coerceIn(0L, duration.coerceAtLeast(0L))
    }

    private fun showPlaybackSpeedDialog() {
        val service = playbackService ?: return
        val labels = PLAYBACK_RATES.map { formatPlaybackRate(it) }.toTypedArray()
        val checked = PLAYBACK_RATES.indices.minByOrNull {
            kotlin.math.abs(PLAYBACK_RATES[it] - service.playbackRate)
        } ?: DEFAULT_PLAYBACK_RATE_INDEX
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.media_player_playback_speed)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                service.setPlaybackRate(PLAYBACK_RATES[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVideoScaleDialog() {
        val service = playbackService ?: return
        val scales = VideoScaleType.entries
        val labels = resources.getStringArray(R.array.media_player_video_scale_entries)
        val checked = scales.indexOf(service.videoScale).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.media_player_video_scale)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                service.setVideoScale(scales[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Unlike subtitles, an audio track cannot be turned off, so there is no disabled entry. */
    private fun showAudioTrackDialog() {
        val service = playbackService ?: return
        val tracks = service.getAudioTracks()
        if (tracks.isEmpty()) {
            return
        }
        val checked = tracks.indexOfFirst { it.id == service.selectedAudioTrack }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.media_player_audio_track)
            .setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), checked) { dialog, which ->
                service.selectAudioTrack(tracks[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSubtitleTrackDialog() {
        val service = playbackService ?: return
        val tracks = service.getSubtitleTracks().toMutableList()
        if (tracks.none { it.id == MediaTrack.DISABLED_ID }) {
            tracks.add(
                0,
                MediaTrack(
                    MediaTrack.DISABLED_ID, getString(R.string.media_player_subtitles_off)
                )
            )
        }
        val checked = tracks.indexOfFirst { it.id == service.selectedSubtitleTrack }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.media_player_subtitle_track)
            .setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), checked) { dialog, which ->
                service.selectSubtitleTrack(tracks[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatTime(timeMillis: Long): String =
        DateUtils.formatElapsedTime((timeMillis.coerceAtLeast(0L) / 1000L))

    private fun formatPlaybackRate(rate: Float): String {
        val value = if (rate == rate.roundToInt().toFloat()) {
            rate.roundToInt().toString()
        } else {
            rate.toString()
        }
        return getString(R.string.media_player_playback_speed_value_format, value)
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private data class Source(
        val path: Path?,
        val uri: Uri,
        val mimeType: MimeType,
        val title: String,
        val isAudio: Boolean
    )

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val DEFAULT_PLAYBACK_RATE_INDEX = 2
        private const val HIDE_CONTROLS_DELAY_MILLIS = 3_500L
        private const val COVER_PLACEHOLDER_ALPHA = 0x5C
        private const val BACKDROP_BLUR_RADIUS_DP = 24f
        /** Slow enough not to become a distraction, quick enough to read as a record turning. */
        private const val COVER_ROTATION_PERIOD_MILLIS = 12_000L
        /** How long the lyrics stay where the user scrolled them before following along again. */
        private const val LYRICS_RESUME_SCROLL_DELAY_MILLIS = 5_000L

        private val PLAYBACK_RATES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        internal fun canHandleIntent(context: Context, intent: Intent): Boolean =
            resolveSource(context, intent) != null

        private fun resolveSource(context: Context, intent: Intent): Source? {
            val path = intent.extraPath
            val uri = intent.data ?: path?.fileProviderUri ?: return null
            val declaredMimeType = (intent.type ?: intent.resolveType(context))
                ?.asMimeTypeOrNull()
            val mimeType = declaredMimeType?.takeIf { it.isPlayableMedia }
                ?: resolveMimeType(context, uri)?.takeIf { it.isPlayableMedia }
                ?: path?.let {
                    val name = it.fileName?.toString() ?: it.toString()
                    MimeType.guessFromPath(name).takeIf { mime -> mime.isPlayableMedia }
                }
                ?: uri.lastPathSegment?.let {
                    MimeType.guessFromPath(it).takeIf { mime -> mime.isPlayableMedia }
                }
                ?: declaredMimeType
            if (mimeType == null || !mimeType.isPlayableMedia) {
                return null
            }
            val title = path?.fileName?.toString()?.takeIf { it.isNotEmpty() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.media_player_title)
            return Source(path, uri, mimeType, title, mimeType.isAudio)
        }

        private fun resolveMimeType(context: Context, uri: Uri): MimeType? = runCatching {
            context.contentResolver.getType(uri)?.asMimeTypeOrNull()
        }.getOrNull()
    }
}
