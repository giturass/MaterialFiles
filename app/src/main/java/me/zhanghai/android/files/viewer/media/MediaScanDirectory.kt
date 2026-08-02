/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.util.ParcelableParceler

/**
 * A folder the media player scans, subfolders included, to build the playlist from. The path is the
 * identity: the same folder twice would only list the same songs twice.
 */
@Parcelize
data class MediaScanDirectory(
    val path: @WriteWith<ParcelableParceler> Path
) : Parcelable {
    val name: String
        get() = path.name
}
