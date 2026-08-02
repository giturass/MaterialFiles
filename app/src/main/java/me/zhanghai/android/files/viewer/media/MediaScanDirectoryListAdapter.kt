/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.MediaScanDirectoryItemBinding
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

class MediaScanDirectoryListAdapter(
    private val listener: Listener
) : SimpleAdapter<MediaScanDirectory, MediaScanDirectoryListAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            MediaScanDirectoryItemBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val directory = getItem(position)
        val binding = holder.binding
        binding.nameText.text = directory.name
        binding.pathText.text = directory.path.toUserFriendlyString()
        binding.removeButton.setOnClickListener { listener.removeScanDirectory(directory) }
    }

    class ViewHolder(
        val binding: MediaScanDirectoryItemBinding
    ) : RecyclerView.ViewHolder(binding.root)

    interface Listener {
        fun removeScanDirectory(directory: MediaScanDirectory)
    }
}
