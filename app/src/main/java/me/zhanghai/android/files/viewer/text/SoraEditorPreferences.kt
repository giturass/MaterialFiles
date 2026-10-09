/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import androidx.core.content.edit

/** Display and input preferences shared by editor windows, without storing document contents. */
class SoraEditorPreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("sora_editor", Context.MODE_PRIVATE)

    var fontSize: Float
        get() = preferences.getFloat("font_size", 14f).coerceIn(8f, 32f)
        set(value) = preferences.edit { putFloat("font_size", value.coerceIn(8f, 32f)) }

    var wordWrap: Boolean
        get() = preferences.getBoolean("word_wrap", false)
        set(value) = preferences.edit { putBoolean("word_wrap", value) }

    var lineNumbers: Boolean
        get() = preferences.getBoolean("line_numbers", true)
        set(value) = preferences.edit { putBoolean("line_numbers", value) }

    var whitespace: Boolean
        get() = preferences.getBoolean("whitespace", false)
        set(value) = preferences.edit { putBoolean("whitespace", value) }

    var autoCompletion: Boolean
        get() = preferences.getBoolean("auto_completion", true)
        set(value) = preferences.edit { putBoolean("auto_completion", value) }

    var stickyScroll: Boolean
        get() = preferences.getBoolean("sticky_scroll", true)
        set(value) = preferences.edit { putBoolean("sticky_scroll", value) }

    var tabWidth: Int
        get() = preferences.getInt("tab_width", 4).coerceIn(1, 8)
        set(value) = preferences.edit { putInt("tab_width", value.coerceIn(1, 8)) }
}
