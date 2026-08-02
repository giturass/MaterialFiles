/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.MediaPlaylistItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater

/**
 * Shows the folder of songs one per row, numbered, with the track being played standing out from
 * the rest. Tapping a row plays it.
 */
class MediaPlaylistAdapter(
    private val onItemClicked: (Int) -> Unit
) : SimpleAdapter<MediaPlaylist.Item, MediaPlaylistAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = false

    var currentPosition = RecyclerView.NO_POSITION
        set(value) {
            if (field == value) {
                return
            }
            val oldValue = field
            field = value
            if (oldValue != RecyclerView.NO_POSITION) {
                notifyItemChanged(oldValue)
            }
            if (value != RecyclerView.NO_POSITION) {
                notifyItemChanged(value)
            }
        }

    /** A single folder needs no folder line; a scanned library across folders does. */
    var showFolders = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            MediaPlaylistItemBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val item = getItem(position)
        val isCurrent = position == currentPosition
        binding.indexText.text = (position + 1).toString()
        // Kept in place rather than removed, so that the titles stay on the same line as the mark
        // replaces the number.
        binding.indexText.isInvisible = isCurrent
        binding.currentIcon.isVisible = isCurrent
        binding.titleText.text = item.title
        binding.folderText.isVisible = showFolders
        if (showFolders) {
            binding.folderText.text = item.folderName
        }
        binding.titleText.setTextColor(
            binding.titleText.context.getColorByAttr(
                if (isCurrent) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                }
            )
        )
        binding.root.setOnClickListener { onItemClicked(position) }
    }

    class ViewHolder(
        val binding: MediaPlaylistItemBinding
    ) : RecyclerView.ViewHolder(binding.root)
}
