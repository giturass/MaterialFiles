/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

val MimeType.isApk: Boolean
    get() = this == MimeType.APK

val MimeType.isSupportedArchive: Boolean
    get() = this in supportedArchiveMimeTypes

private val supportedArchiveMimeTypes = archiveExtensionToMimeTypeMap.values.toSet() + listOf(
    "application/gzip",
    "application/java-archive",
    "application/rar",
    "application/zip",
    "application/vnd.android.package-archive",
    "application/vnd.debian.binary-package",
    "application/vnd.ms-cab-compressed",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-ar",
    "application/x-arj-compressed",
    "application/x-bzip",
    "application/x-bzip2",
    "application/x-bzip-compressed-tar",
    "application/x-cab",
    "application/x-cabinet",
    "application/x-chm",
    "application/x-compressed-tar",
    "application/x-compress",
    "application/x-cpio",
    "application/x-deb",
    "application/x-debian-package",
    "application/x-gtar",
    "application/x-gtar-compressed",
    "application/x-gzip",
    "application/x-hfs",
    "application/x-iso9660-image",
    "application/x-java-archive",
    "application/x-lha",
    "application/x-lzh",
    "application/x-lzma",
    "application/x-lzma-compressed-tar",
    "application/x-nsis",
    "application/x-rar",
    "application/x-rar-compressed",
    "application/x-redhat-package-manager",
    "application/x-rpm",
    "application/x-tar",
    "application/x-ustar",
    "application/x-xz",
    "application/x-xz-compressed-tar",
    "application/x-zip-compressed"
).map { it.asMimeType() }

val MimeType.isImage: Boolean
    get() = icon == MimeTypeIcon.IMAGE

val MimeType.isAudio: Boolean
    get() = icon == MimeTypeIcon.AUDIO

val MimeType.isVideo: Boolean
    get() = icon == MimeTypeIcon.VIDEO

val MimeType.isMedia: Boolean
    get() = isAudio || isVideo

val MimeType.isPdf: Boolean
    get() = this == MimeType.PDF
