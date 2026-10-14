/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getColorStateListByAttr
import me.zhanghai.android.files.util.getDimensionPixelSizeByAttr
import me.zhanghai.android.files.util.getDrawableByAttr
import me.zhanghai.android.files.util.getFloatByAttr
import me.zhanghai.android.files.util.getResourceIdByAttr
import me.zhanghai.android.files.util.withTheme
import kotlin.math.roundToInt

/** Keeps every primary file action reachable even on a narrow screen. */
class FileSelectionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val touchTargetSize = resources.getDimensionPixelSize(R.dimen.touch_target_size)
    private val barHeight = context
        .getDimensionPixelSizeByAttr(androidx.appcompat.R.attr.actionBarSize)
        .coerceAtLeast(touchTargetSize)
    private val disabledImageAlpha =
        (context.getFloatByAttr(android.R.attr.disabledAlpha) * 255).roundToInt()
    private val popupContext = context.withTheme(
        context.getResourceIdByAttr(androidx.appcompat.R.attr.actionBarPopupTheme)
    )

    private val actionButtons: List<AppCompatImageButton>
    private val overflowButton: AppCompatImageButton

    private var sourceMenu: Menu? = null
    private var onItemClicked: ((MenuItem) -> Boolean)? = null
    private var popupMenu: PopupMenu? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = barHeight
        actionButtons = PRIMARY_ACTION_IDS.map { actionId ->
            createActionButton().apply {
                id = actionId
                setOnClickListener { performItemClick(actionId) }
                addView(this)
            }
        }
        overflowButton = createActionButton().apply {
            setImageResource(R.drawable.more_vertical_icon_white_24dp)
            contentDescription = context.getString(R.string.file_list_more_actions)
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { showOverflow() }
            addView(this)
        }
    }

    private fun createActionButton(): AppCompatImageButton =
        AppCompatImageButton(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            minimumWidth = touchTargetSize
            minimumHeight = touchTargetSize
            scaleType = ImageView.ScaleType.CENTER
            val iconPadding = resources.getDimensionPixelSize(R.dimen.touch_target_icon_padding)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            background = context.getDrawableByAttr(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless
            )
            supportImageTintList =
                context.getColorStateListByAttr(androidx.appcompat.R.attr.colorControlNormal)
            isFocusable = true
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val resolvedHeightMeasureSpec = if (heightMode == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            val maximumHeight = if (heightMode == MeasureSpec.AT_MOST) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                Int.MAX_VALUE
            }
            MeasureSpec.makeMeasureSpec(barHeight.coerceAtMost(maximumHeight), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, resolvedHeightMeasureSpec)
    }

    /** Call again after changing the source menu's titles, visibility or enabled states. */
    fun bind(menu: Menu, onItemClicked: (MenuItem) -> Boolean) {
        dismissPopup()
        sourceMenu = menu
        this.onItemClicked = onItemClicked
        actionButtons.forEach { button ->
            val item = menu.findItem(button.id)
            val icon = item?.icon
            button.setImageDrawable(icon?.constantState?.newDrawable(resources)?.mutate() ?: icon)
            button.contentDescription = item?.title
            TooltipCompat.setTooltipText(button, item?.title)
            // Keep all five slots, including unavailable operations, in the same positions.
            button.isEnabled = item?.isVisible == true && item.isEnabled
            button.imageAlpha = if (button.isEnabled) 255 else disabledImageAlpha
        }
        overflowButton.isEnabled = (0 until menu.size()).any {
            val item = menu.getItem(it)
            item.itemId !in PRIMARY_ACTION_IDS && item.isVisible
        }
        overflowButton.imageAlpha = if (overflowButton.isEnabled) 255 else disabledImageAlpha
    }

    private fun performItemClick(itemId: Int): Boolean {
        val item = sourceMenu?.findItem(itemId) ?: return false
        if (!item.isVisible || !item.isEnabled) {
            return false
        }
        return onItemClicked?.invoke(item) ?: false
    }

    private fun showOverflow() {
        val menu = sourceMenu ?: return
        dismissPopup()
        val popup = PopupMenu(popupContext, overflowButton, Gravity.END)
        popup.inflate(R.menu.file_list_select_bottom)
        PRIMARY_ACTION_IDS.forEach { popup.menu.removeItem(it) }
        for (index in 0 until popup.menu.size()) {
            val item = popup.menu.getItem(index)
            val sourceItem = menu.findItem(item.itemId)
            item.isVisible = sourceItem?.isVisible == true
            item.isEnabled = sourceItem?.isEnabled == true
            if (sourceItem != null) {
                item.title = sourceItem.title
                item.isCheckable = sourceItem.isCheckable
                item.isChecked = sourceItem.isChecked
            }
        }
        if (!popup.menu.hasVisibleItems()) {
            return
        }
        popup.setOnMenuItemClickListener { performItemClick(it.itemId) }
        popup.setOnDismissListener {
            if (popupMenu === it) {
                popupMenu = null
            }
        }
        popupMenu = popup
        popup.show()
    }

    fun dismissPopup() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    override fun onDetachedFromWindow() {
        dismissPopup()
        super.onDetachedFromWindow()
    }

    companion object {
        private val PRIMARY_ACTION_IDS = intArrayOf(
            R.id.action_cut,
            R.id.action_copy,
            R.id.action_delete,
            R.id.action_archive,
            R.id.action_rename
        )
    }
}
