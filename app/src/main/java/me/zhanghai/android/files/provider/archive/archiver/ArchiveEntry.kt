/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser

/** Provider metadata, independent of any native archive representation. */
data class ArchiveEntry(
    val name: String,
    val isEncrypted: Boolean,
    val lastModifiedTime: FileTime?,
    val lastAccessTime: FileTime?,
    val creationTime: FileTime?,
    val type: PosixFileType,
    val size: Long,
    val owner: PosixUser?,
    val group: PosixGroup?,
    val mode: Set<PosixFileModeBit>,
    val symbolicLinkTarget: String?,
    internal val index: Int = -1,
    internal val contentIndex: Int = index
) {
    val isDirectory: Boolean get() = type == PosixFileType.DIRECTORY
    val isSymbolicLink: Boolean get() = type == PosixFileType.SYMBOLIC_LINK
}
