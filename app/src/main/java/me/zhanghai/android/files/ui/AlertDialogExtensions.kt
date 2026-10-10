/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import me.zhanghai.android.files.util.dpToDimensionPixelSize

/** Arrange the already created buttons in reading order with equal widths. */
fun AlertDialog.setButtonBarEqualWidth(vararg buttonIds: Int) {
    val buttons = buttonIds.map { getButton(it) }
    val oldRow = buttons.first().parent as ViewGroup
    val parent = oldRow.parent as ViewGroup
    val buttonHeight = context.dpToDimensionPixelSize(48)
    val buttonMargin = context.dpToDimensionPixelSize(4)
    // Keep the themed buttons and their listeners, replacing the spacer and automatic stacking.
    // Replace the row on every call so restarting a dialog cannot nest rows or accumulate padding.
    val row = LinearLayout(oldRow.context).apply {
        id = oldRow.id
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = oldRow.layoutDirection
        setPaddingRelative(
            oldRow.paddingStart, oldRow.paddingTop, oldRow.paddingEnd, oldRow.paddingBottom
        )
        minimumHeight = buttonHeight + paddingTop + paddingBottom
    }
    for (button in buttons) {
        oldRow.removeView(button)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = maxOf(button.minHeight, buttonHeight)
        button.gravity = Gravity.CENTER
        // Allow longer labels and enlarged text to wrap within their equal share of the row.
        button.setSingleLine(false)
        button.ellipsize = null
        row.addView(
            button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = buttonMargin
                marginEnd = buttonMargin
            }
        )
    }
    val rowIndex = parent.indexOfChild(oldRow)
    val rowLayoutParams = oldRow.layoutParams
    parent.removeView(oldRow)
    parent.addView(row, rowIndex, rowLayoutParams)
}
