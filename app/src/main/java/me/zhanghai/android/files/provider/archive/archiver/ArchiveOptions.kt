/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

/** Creation formats supported by the bundled 7-Zip engine and its JBinding adapter. */
enum class ArchiveCreationFormat(
    val extension: String,
    val singleFileOnly: Boolean = false,
    val supportsPassword: Boolean = false
) {
    ZIP("zip", supportsPassword = true),
    SEVEN_Z("7z", supportsPassword = true),
    TAR("tar"),
    GZIP("gz", singleFileOnly = true),
    BZIP2("bz2", singleFileOnly = true),
    XZ("xz", singleFileOnly = true)
}

// The pinned 7-Zip ZIP AES writer accepts at most 99 printable ASCII bytes (WzAes.h).
const val MAX_ZIP_PASSWORD_LENGTH = 99

enum class ArchiveCompressionPreset(val level: Int) {
    SPEED(1),
    STANDARD(5),
    // JBinding has no dictionary-size setter. Level 7 keeps the 7z dictionary at 32 MiB.
    QUALITY(7)
}
