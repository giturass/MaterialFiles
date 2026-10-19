/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.content.Intent

fun Intent.removeFlagsCompat(flags: Int) {
    removeFlags(flags)
}
