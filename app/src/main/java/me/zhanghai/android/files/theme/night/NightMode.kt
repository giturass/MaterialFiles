/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.night

import androidx.appcompat.app.AppCompatDelegate

enum class NightMode(val value: Int, val preferenceValue: String) {
    // Preserve the old stored values independently of the order shown in settings. Values 3 and 4
    // belonged to the removed automatic time and battery modes.
    FOLLOW_SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "0"),
    ON(AppCompatDelegate.MODE_NIGHT_YES, "2"),
    OFF(AppCompatDelegate.MODE_NIGHT_NO, "1"),
    BLACK(AppCompatDelegate.MODE_NIGHT_YES, "5");

    companion object {
        fun fromPreferenceValue(value: String?): NightMode? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}
