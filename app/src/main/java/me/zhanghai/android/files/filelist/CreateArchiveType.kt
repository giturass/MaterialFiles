/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Build
import androidx.annotation.StringRes
import me.zhanghai.android.files.R
import me.zhanghai.android.libarchive.Archive

/**
 * The archive types offered when creating an archive.
 *
 * The ordinal of each value is persisted by [me.zhanghai.android.files.settings.Settings
 * .CREATE_ARCHIVE_TYPE], so values must only be appended.
 */
enum class CreateArchiveType(
    @StringRes val labelRes: Int,
    val extension: String,
    val format: Int,
    val filter: Int,
    val isPasswordSupported: Boolean,
    val isSupported: Boolean = true,
    /** Whether the format can encrypt its entry names, and not just their content. */
    val isFileNameEncryptionSupported: Boolean = false
) {
    ZIP(
        R.string.file_create_archive_type_zip, "zip", Archive.FORMAT_ZIP, Archive.FILTER_NONE, true
    ),
    TAR_GZ(
        R.string.file_create_archive_type_tar_gz, "tar.gz", Archive.FORMAT_TAR, Archive.FILTER_GZIP,
        false
    ),
    TAR_XZ(
        R.string.file_create_archive_type_tar_xz, "tar.xz", Archive.FORMAT_TAR, Archive.FILTER_XZ,
        false
    ),
    SEVEN_Z(
        R.string.file_create_archive_type_7z, "7z", Archive.FORMAT_7ZIP, Archive.FILTER_NONE, true,
        // SevenZOutputFile requires SeekableByteChannel which requires Android N.
        isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
        isFileNameEncryptionSupported = true
    );

    companion object {
        val supportedEntries = entries.filter { it.isSupported }
    }
}
