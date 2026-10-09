/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsets
import android.widget.ScrollView
import me.zhanghai.android.files.R

// A platform ScrollView leaves RecyclerView as the ScrollingView that receives bottom insets.
class ArchiveJobProgressScrollView : ScrollView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // PersistentBarLayout subtracts insets consumed by its bottom toolbar before dispatch.
        // Keep the progress and its cancel buttons above the remaining system navigation area.
        val params = layoutParams as MarginLayoutParams
        if (params.bottomMargin != insets.systemWindowInsetBottom) {
            params.bottomMargin = insets.systemWindowInsetBottom
            layoutParams = params
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (heightMode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val rowHeight = resources.getDimensionPixelSize(R.dimen.touch_target_size) +
            2 * resources.getDimensionPixelSize(R.dimen.list_vertical_padding)
        var maximumHeight = 2 * rowHeight
        if (heightMode == MeasureSpec.AT_MOST) {
            // Leave room for the files, including in landscape and a small split-screen window.
            maximumHeight = maximumHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec) / 2)
        }
        super.onMeasure(
            widthMeasureSpec, MeasureSpec.makeMeasureSpec(maximumHeight, MeasureSpec.AT_MOST)
        )
    }
}
