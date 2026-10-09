/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

enum class ArchiveCompressionPreset(val level: Int) {
    SPEED(1),
    STANDARD(5),
    QUALITY(9)
}
