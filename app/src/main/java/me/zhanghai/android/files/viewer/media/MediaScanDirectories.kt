/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object MediaScanDirectories {
    val value: List<MediaScanDirectory>
        get() = Settings.MEDIA_PLAYER_SCAN_DIRECTORIES.valueCompat

    /** Returns false when the folder is already configured, which is not an error worth failing. */
    fun add(directory: MediaScanDirectory): Boolean {
        val directories = value
        if (directories.any { it.path == directory.path }) {
            return false
        }
        Settings.MEDIA_PLAYER_SCAN_DIRECTORIES.putValue(directories + directory)
        return true
    }

    fun remove(directory: MediaScanDirectory) {
        Settings.MEDIA_PLAYER_SCAN_DIRECTORIES.putValue(
            value.filter { it.path != directory.path }
        )
    }
}
