/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

val MimeType.isApk: Boolean
    get() = this == MimeType.APK

val MimeType.isSupportedArchive: Boolean
    get() = this in supportedArchiveMimeTypes

private val supportedArchiveMimeTypes = mutableListOf(
    "application/gzip",
    "application/java-archive",
    "application/rar",
    "application/zip",
    "application/zstd",
    "application/vnd.android.package-archive",
    "application/vnd.debian.binary-package",
    "application/vnd.ms-cab-compressed",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-bzip2",
    "application/x-cab",
    "application/x-compress",
    "application/x-cpio",
    "application/x-deb",
    "application/x-debian-package",
    "application/x-gtar",
    "application/x-gtar-compressed",
    "application/x-iso9660-image",
    "application/x-java-archive",
    "application/x-lha",
    "application/x-lzma",
    "application/x-redhat-package-manager",
    "application/x-tar",
    "application/x-ustar",
    "application/x-xz"
).map { it.asMimeType() }.toSet()

val MimeType.isImage: Boolean
    get() = icon == MimeTypeIcon.IMAGE

val MimeType.isAudio: Boolean
    get() = icon == MimeTypeIcon.AUDIO

val MimeType.isVideo: Boolean
    get() = icon == MimeTypeIcon.VIDEO

val MimeType.isMedia: Boolean
    get() = isAudio || isVideo

/**
 * Whether our own player can actually play this, as opposed to merely recognizing it as media.
 *
 * MIDI is the exception: neither ExoPlayer nor BASS can render it without a SoundFont, which we
 * don't ship, so it goes to whatever the system offers instead of opening a player that would only
 * report an error.
 */
val MimeType.isPlayableMedia: Boolean
    get() = isMedia && this !in unplayableMediaMimeTypes

private val unplayableMediaMimeTypes = mutableListOf(
    "audio/midi",
    "audio/sp-midi",
    "audio/x-midi"
).map { it.asMimeType() }.toSet()

val MimeType.isPdf: Boolean
    get() = this == MimeType.PDF

val MimeType.isSqlite: Boolean
    get() = this in sqliteMimeTypes

private val sqliteMimeTypes = mutableListOf(
    "application/vnd.sqlite3",
    "application/x-sqlite3",
    "application/geopackage+sqlite3"
).map { it.asMimeType() }.toSet()
