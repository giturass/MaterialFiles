/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.MediaPlayerLyricsLineBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater

/**
 * Shows the lyrics one line per row, with the line currently being sung standing out from the rest.
 * Tapping a line jumps playback to it, when the lyrics are synced.
 */
class LyricsAdapter(
    private val onLineClicked: (Lyrics.Line) -> Unit
) : SimpleAdapter<Lyrics.Line, LyricsAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = false

    var highlightedPosition = RecyclerView.NO_POSITION
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            MediaPlayerLyricsLineBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val textView = holder.binding.root
        val line = getItem(position)
        textView.text = line.text
        val isHighlighted = position == highlightedPosition
        textView.setTextColor(
            textView.context.getColorByAttr(
                if (isHighlighted) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
        textView.alpha = if (isHighlighted) 1f else INACTIVE_LINE_ALPHA
        val isSeekable = line.timeMillis != Lyrics.UNTIMED
        textView.isClickable = isSeekable
        if (isSeekable) {
            textView.setOnClickListener { onLineClicked(line) }
        } else {
            textView.setOnClickListener(null)
        }
    }

    class ViewHolder(
        val binding: MediaPlayerLyricsLineBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val INACTIVE_LINE_ALPHA = 0.6f
    }
}
