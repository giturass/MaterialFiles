/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import net.sf.sevenzipjbinding.ArchiveFormat

/** The five creation formats exposed by JBinding. */
enum class ArchiveCreationFormat(
    val extension: String,
    internal val nativeFormat: ArchiveFormat,
    val singleFileOnly: Boolean = false,
    val supportsPassword: Boolean = false
) {
    ZIP("zip", ArchiveFormat.ZIP, supportsPassword = true),
    SEVEN_Z("7z", ArchiveFormat.SEVEN_ZIP, supportsPassword = true),
    TAR("tar", ArchiveFormat.TAR),
    GZIP("gz", ArchiveFormat.GZIP, singleFileOnly = true),
    BZIP2("bz2", ArchiveFormat.BZIP2, singleFileOnly = true)
}

// The pinned 7-Zip ZIP AES writer accepts at most 99 printable ASCII bytes (WzAes.h).
const val MAX_ZIP_PASSWORD_LENGTH = 99

enum class ArchiveCompressionPreset(val level: Int) {
    SPEED(1),
    STANDARD(5),
    // JBinding has no dictionary-size setter. Level 7 keeps the 7z dictionary at 32 MiB.
    QUALITY(7)
}
