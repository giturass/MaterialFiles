/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Context
import androidx.annotation.StringRes

internal class NavigationSectionItem(
    @StringRes private val titleRes: Int,
    @StringRes private val emptyMessageRes: Int? = null
) : NavigationItem() {
    override val id: Long = Long.MIN_VALUE + titleRes

    override val iconRes: Int? = null

    override fun getTitle(context: Context): String = context.getString(titleRes)

    override fun getSubtitle(context: Context): String? = emptyMessageRes?.let(context::getString)

    override fun onClick(listener: Listener) {}
}
