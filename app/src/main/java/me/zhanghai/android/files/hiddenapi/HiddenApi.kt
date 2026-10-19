/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hiddenapi


object HiddenApi {
    fun disableHiddenApiChecks() {
        System.loadLibrary("hiddenapi")
    }
}
