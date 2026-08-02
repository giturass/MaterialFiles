/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.MediaScanDirectoryListFragmentBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.launchSafe
import me.zhanghai.android.files.util.showToast

/**
 * The folders the media player builds its playlist from. Adding none leaves the player listing the
 * folder of whatever song is open, which is what it did before any of this was configurable.
 */
class MediaScanDirectoryListFragment : Fragment(), MediaScanDirectoryListAdapter.Listener {
    private val openPathLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract(), ::onOpenPathResult)

    private lateinit var binding: MediaScanDirectoryListFragmentBinding

    private lateinit var adapter: MediaScanDirectoryListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        MediaScanDirectoryListFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(
            activity, RecyclerView.VERTICAL, false
        )
        adapter = MediaScanDirectoryListAdapter(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView)
        )
        binding.fab.setOnClickListener { openPathLauncher.launchSafe(null, this) }

        Settings.MEDIA_PLAYER_SCAN_DIRECTORIES.observe(viewLifecycleOwner) {
            onScanDirectoryListChanged(it)
        }
    }

    private fun onScanDirectoryListChanged(directories: List<MediaScanDirectory>) {
        binding.emptyView.fadeToVisibilityUnsafe(directories.isEmpty())
        adapter.replace(directories)
    }

    private fun onOpenPathResult(result: Path?) {
        result ?: return
        if (!MediaScanDirectories.add(MediaScanDirectory(result))) {
            showToast(R.string.media_player_scan_directory_already_added)
        }
    }

    override fun removeScanDirectory(directory: MediaScanDirectory) {
        MediaScanDirectories.remove(directory)
    }
}
