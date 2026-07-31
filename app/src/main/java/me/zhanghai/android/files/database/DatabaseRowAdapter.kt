/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize

/** How much of a long text value is rendered in a cell before it is cut off. */
private const val MAX_CELL_TEXT_LENGTH = 256

/** The one-line rendering of a value for a table cell. */
fun SqlValue.toDisplayText(context: Context): String =
    when (this) {
        is SqlValue.Null -> context.getString(R.string.database_editor_null)
        is SqlValue.Integer -> value.toString()
        is SqlValue.Real -> value.toString()
        is SqlValue.Text -> value.take(MAX_CELL_TEXT_LENGTH).replace('\n', ' ')
        is SqlValue.Blob -> context.getString(
            R.string.database_editor_blob_format, value.size.toLong().asFileSize()
                .formatHumanReadable(context)
        )
    }

/** Fills [layout] with one header cell per column name, replacing whatever was there. */
fun LinearLayout.bindHeaderRow(columnNames: List<String>) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)
    for (columnName in columnNames) {
        val textView = inflater.inflate(R.layout.database_editor_header_cell, this, false)
            as TextView
        textView.text = columnName
        addView(textView)
    }
}

/**
 * Renders rows as fixed-width cells. The table is scrolled horizontally by an enclosing
 * [android.widget.HorizontalScrollView], so every row is as wide as the header.
 */
class DatabaseRowAdapter(
    private val onRowClick: ((position: Int) -> Unit)? = null,
    private val onRowLongClick: ((position: Int) -> Unit)? = null
) : RecyclerView.Adapter<DatabaseRowAdapter.ViewHolder>() {
    private var columnCount = 0
    private var rows: List<SqlRow> = emptyList()

    fun setRows(columnCount: Int, rows: List<SqlRow>) {
        this.columnCount = columnCount
        this.rows = rows
        notifyDataSetChanged()
    }

    fun getRow(position: Int): SqlRow = rows[position]

    override fun getItemCount(): Int = rows.size

    // View holders are built with a fixed number of cells, so holders from a table with a different
    // column count must never be recycled into this one.
    override fun getItemViewType(position: Int): Int = columnCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = inflater.inflate(R.layout.database_editor_row, parent, false) as LinearLayout
        repeat(viewType) {
            layout.addView(inflater.inflate(R.layout.database_editor_cell, layout, false))
        }
        return ViewHolder(layout)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val layout = holder.layout
        val row = rows[position]
        val context = layout.context
        for (index in 0 until layout.childCount) {
            val textView = layout.getChildAt(index) as TextView
            val value = row.values.getOrNull(index)
            textView.text = value?.toDisplayText(context).orEmpty()
            // NULL and binary values are metadata rather than content, so they read as secondary.
            textView.alpha = if (value is SqlValue.Text || value is SqlValue.Integer
                || value is SqlValue.Real) 1f else 0.6f
        }
        layout.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                onRowClick?.invoke(adapterPosition)
            }
        }
        layout.setOnLongClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION && onRowLongClick != null) {
                onRowLongClick.invoke(adapterPosition)
                true
            } else {
                false
            }
        }
        layout.isClickable = onRowClick != null
        layout.isLongClickable = onRowLongClick != null
    }

    class ViewHolder(val layout: LinearLayout) : RecyclerView.ViewHolder(layout)
}
