/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.MediaPlaylistDialogBinding

/**
 * The songs in the folder of the track being played, as a sheet over the player, so that any of
 * them is one tap away without going back to the file list.
 */
class MediaPlaylistDialogFragment : BottomSheetDialogFragment() {

    private lateinit var binding: MediaPlaylistDialogBinding

    /** The list only jumps to the current track when it first has one, not on every update. */
    private var scrolledToCurrent = false

    /** The sheet has no session of its own; it acts on the one the player is bound to. */
    private val playbackService: MediaPlaybackService?
        get() = (parentFragment as? MediaPlayerFragment)?.playbackService

    private val adapter = MediaPlaylistAdapter { position ->
        playbackService?.playPlaylistItem(position)
        dismissAllowingStateLoss()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        MediaPlaylistDialogBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playlistList.layoutManager = LinearLayoutManager(requireContext())
        binding.playlistList.adapter = adapter
        binding.playlistList.itemAnimator = null
        updatePlaylist()
    }

    override fun onStart() {
        super.onStart()

        // A queue is read from the top, so it opens at its full height instead of settling back to
        // the half-open state a sheet would otherwise collapse to.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /**
     * Called by the player whenever the session changes, so that the mark follows playback from one
     * track to the next while the sheet is open.
     */
    fun updatePlaylist() {
        if (!::binding.isInitialized) {
            return
        }
        // Between a rotation and the player rebinding there is no session to read, which is not the
        // same as an empty playlist, so the sheet waits rather than closing itself.
        val service = playbackService ?: return
        val playlist = service.playlist
        if (playlist == null || playlist.items.isEmpty()) {
            // Nothing left to pick from, e.g. playback moved on to a video.
            dismissAllowingStateLoss()
            return
        }
        if (adapter.list != playlist.items) {
            // The mark belongs to the list it was set against, so it goes before the list does, and
            // the new folder gets scrolled to its own current track.
            adapter.currentPosition = RecyclerView.NO_POSITION
            adapter.showFolders = playlist.items.distinctBy { it.path.parent }.size > 1
            adapter.replace(playlist.items)
            scrolledToCurrent = false
        }
        adapter.currentPosition = playlist.index
        binding.playlistSubtitle.text = resources.getQuantityString(
            R.plurals.media_player_playlist_track_count_format, playlist.size, playlist.size
        )
        if (!scrolledToCurrent) {
            scrolledToCurrent = true
            scrollToCurrent(playlist.index)
        }
    }

    /** Opens onto the current track with a few of its neighbours above it, rather than at the top. */
    private fun scrollToCurrent(position: Int) {
        val layoutManager = binding.playlistList.layoutManager as? LinearLayoutManager ?: return
        binding.playlistList.post {
            layoutManager.scrollToPositionWithOffset(position, binding.playlistList.height / 3)
        }
    }

    companion object {
        const val TAG = "MediaPlaylistDialogFragment"
    }
}
