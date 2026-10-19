/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.core.widget.TextViewCompat

val TextView.isSingleLineCompat: Boolean
    get() = isSingleLine

fun TextView.setTextAppearanceCompat(@StyleRes resId: Int) {
    TextViewCompat.setTextAppearance(this, resId)
}
