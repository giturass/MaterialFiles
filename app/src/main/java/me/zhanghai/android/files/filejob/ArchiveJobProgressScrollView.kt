/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsets
import android.widget.ScrollView

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
        val limitedHeightMeasureSpec = if (heightMode == MeasureSpec.AT_MOST) {
            // Leave room for the files, including in landscape and a small split-screen window.
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec) / 2, MeasureSpec.AT_MOST
            )
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, limitedHeightMeasureSpec)

        val progressLayout = getChildAt(0) as? ViewGroup ?: return
        if (progressLayout.childCount <= 2) {
            return
        }
        // Use the actual card heights so the two-task limit follows the user's font size.
        var twoCardsHeight = paddingTop + paddingBottom +
            progressLayout.paddingTop + progressLayout.paddingBottom
        for (index in 0 until 2) {
            val card = progressLayout.getChildAt(index)
            val params = card.layoutParams as MarginLayoutParams
            twoCardsHeight += card.measuredHeight + params.topMargin + params.bottomMargin
        }
        if (measuredHeight > twoCardsHeight) {
            super.onMeasure(
                widthMeasureSpec, MeasureSpec.makeMeasureSpec(twoCardsHeight, MeasureSpec.AT_MOST)
            )
        }
    }
}
