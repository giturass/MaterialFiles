/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences
import me.zhanghai.android.files.theme.night.NightMode

class NightModeSettingLiveData : SettingLiveData<NightMode>(
    R.string.pref_key_night_mode, R.string.pref_default_value_night_mode
) {
    init {
        migrateLegacySettings()
        init()
    }

    override fun getDefaultValue(@StringRes defaultValueRes: Int): NightMode =
        NightMode.fromPreferenceValue(application.getString(defaultValueRes))!!

    override fun getValue(
        sharedPreferences: SharedPreferences,
        key: String,
        defaultValue: NightMode
    ): NightMode =
        NightMode.fromPreferenceValue(sharedPreferences.getString(key, null)) ?: defaultValue

    override fun putValue(sharedPreferences: SharedPreferences, key: String, value: NightMode) {
        sharedPreferences.edit { putString(key, value.preferenceValue) }
    }

    private fun migrateLegacySettings() {
        val sharedPreferences = defaultSharedPreferences
        val key = application.getString(R.string.pref_key_night_mode)
        val blackKey = application.getString(R.string.pref_key_black_night_mode)
        val oldValue = sharedPreferences.getString(key, null)
        val wasBlack = sharedPreferences.getBoolean(blackKey, false)
        val nightMode = if (wasBlack && oldValue != NightMode.OFF.preferenceValue) {
            NightMode.BLACK
        } else {
            // The removed time and battery modes now follow the system, as do invalid values.
            NightMode.fromPreferenceValue(oldValue)
                ?: getDefaultValue(R.string.pref_default_value_night_mode)
        }
        if (sharedPreferences.contains(blackKey)
            || oldValue != null && oldValue != nightMode.preferenceValue) {
            sharedPreferences.edit {
                putString(key, nightMode.preferenceValue)
                remove(blackKey)
            }
        }
    }
}
