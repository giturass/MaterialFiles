/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.webkit.MimeTypeMap
import me.zhanghai.android.files.compat.getMimeTypeFromExtensionCompat
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asPathName

fun MimeType.Companion.guessFromPath(path: String): MimeType {
    val fileName = path.asPathName().fileName ?: return DIRECTORY
    if (fileName.length > 4 && fileName.endsWith(".001")) {
        val archiveType = guessFromExtension(fileName.dropLast(4).asFileName().singleExtension)
        if (archiveType.isSupportedArchive) return archiveType
    }
    return guessFromExtension(fileName.asFileName().singleExtension)
}

fun MimeType.Companion.guessFromExtension(extension: String): MimeType {
    val extension = extension.lowercase()
    return archiveExtensionToMimeTypeMap[extension]
        ?: extensionToMimeTypeOverrideMap[extension]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtensionCompat(extension)?.asMimeTypeOrNull()
        ?: GENERIC
}

// Keep the archive entry points independent of the device's MIME database. In particular,
// some Android versions identify CHM as ChemDraw and XAR as a Xara drawing.
// Bundled formats and source versions: docs/7z.md.
// The native registry also provides XZ, PPMd, LZMA86 and Hxs outside the Java enum.
// EXE files may be NSIS installers; they are opened as archives only on explicit request.
internal val archiveExtensionToMimeTypeMap = mapOf(
    "7z" to "application/x-7z-compressed",
    "a" to "application/x-archive",
    "apk" to "application/vnd.android.package-archive",
    "ar" to "application/x-archive",
    "arj" to "application/x-arj",
    "bz" to "application/x-bzip",
    "bz2" to "application/x-bzip2",
    "bzip2" to "application/x-bzip2",
    "cab" to "application/vnd.ms-cab-compressed",
    "cb7" to "application/x-7z-compressed",
    "cbr" to "application/vnd.rar",
    "cbt" to "application/x-tar",
    "cbz" to "application/zip",
    "chi" to "application/vnd.ms-htmlhelp",
    "chm" to "application/vnd.ms-htmlhelp",
    "chq" to "application/vnd.ms-htmlhelp",
    "chw" to "application/vnd.ms-htmlhelp",
    "cpio" to "application/x-cpio",
    "deb" to "application/vnd.debian.binary-package",
    "esd" to "application/x-ms-wim",
    "fat" to "application/x-raw-disk-image",
    "gz" to "application/gzip",
    "gzip" to "application/gzip",
    "hfs" to "application/x-raw-disk-image",
    "hfsx" to "application/x-raw-disk-image",
    "hxi" to "application/x-hxs",
    "hxq" to "application/x-hxs",
    "hxr" to "application/x-hxs",
    "hxs" to "application/x-hxs",
    "hxw" to "application/x-hxs",
    "img" to "application/x-raw-disk-image",
    "iso" to "application/x-iso9660-image",
    "jar" to "application/java-archive",
    "lha" to "application/x-lha",
    "lib" to "application/x-archive",
    "lzh" to "application/x-lzh",
    "lzma" to "application/x-lzma",
    "lzma86" to "application/x-lzma",
    "nsis" to "application/x-nsis",
    "ntfs" to "application/x-raw-disk-image",
    "ova" to "application/x-tar",
    "pkg" to "application/x-xar",
    "pmd" to "application/x-ppmd",
    "r00" to "application/vnd.rar",
    "rar" to "application/vnd.rar",
    "rpm" to "application/x-redhat-package-manager",
    "swm" to "application/x-ms-wim",
    "tar" to "application/x-tar",
    "taz" to "application/x-compress",
    "tbz" to "application/x-bzip2",
    "tbz2" to "application/x-bzip2",
    "tgz" to "application/gzip",
    "tlz" to "application/x-lzma",
    "tpz" to "application/gzip",
    "txz" to "application/x-xz",
    "udeb" to "application/vnd.debian.binary-package",
    "udf" to "application/x-udf",
    "wim" to "application/x-ms-wim",
    "xar" to "application/x-xar",
    "xpi" to "application/x-xpinstall",
    // The native XzHandler is built even though the Java ArchiveFormat enum has no XZ value.
    "xz" to "application/x-xz",
    "z" to "application/x-compress",
    "z01" to "application/zip",
    "zip" to "application/zip",
    "zipx" to "application/zip",
    "001" to "application/x-split"
).mapValues { it.value.asMimeType() }

// @see https://android.googlesource.com/platform/external/mime-support/+/master/mime.types
// @see https://android.googlesource.com/platform/frameworks/base/+/master/mime/java-res/android.mime.types
// @see http://www.iana.org/assignments/media-types/media-types.xhtml
// @see https://salsa.debian.org/debian/media-types/-/blob/master/mime.types
// @see /usr/share/mime/packages/freedesktop.org.xml
private val extensionToMimeTypeOverrideMap = mapOf(
    // Fixes
    "csv" to "text/csv", // Was "text/comma-separated-values"
    "sh" to "application/x-sh", // Was "text/x-sh"
    // Addition
    "p7b" to "application/x-pkcs7-certificates",
    "spc" to "application/x-pkcs7-certificates", // Clashes with "chemical/x-galactic-spc"
    "ts" to "application/typescript", // Clashes with "video/mp2ts"
    "py3" to "text/x-python",
    "py3x" to "text/x-python",
    "pyx" to "text/x-python",
    "wsgi" to "text/x-python",
    "yml" to "application/yaml",
    "asm" to "text/x-asm",
    "s" to "text/x-asm",
    "cs" to "text/x-csharp",
    "azw" to "application/vnd.amazon.ebook",
    "ibooks" to "application/x-ibooks+zip",
    "msg" to "application/vnd.ms-outlook",
    "mkd" to "text/markdown",
    "conf" to "text/plain",
    "ini" to "text/plain",
    "list" to "text/plain",
    "log" to "text/plain",
    "prop" to "text/plain",
    "properties" to "text/plain",
    "rc" to "text/plain"
).mapValues { it.value.asMimeType() }

fun MimeType.Companion.forSpecialPosixFileType(type: PosixFileType): MimeType? =
    specialPosixFileTypeToMimeTypeMap[type]

// See also https://developer.gnome.org/shared-mime-info-spec/
/** @see specialPosixFileTypeToNameResMap */
private val specialPosixFileTypeToMimeTypeMap = mapOf(
    PosixFileType.CHARACTER_DEVICE to "inode/chardevice",
    PosixFileType.BLOCK_DEVICE to "inode/blockdevice",
    PosixFileType.FIFO to "inode/fifo",
    PosixFileType.SYMBOLIC_LINK to "inode/symlink",
    PosixFileType.SOCKET to "inode/socket"
).mapValues { it.value.asMimeType() }

val MimeType.extension: String?
    // TODO: Add compat implementation as well.
    get() = MimeTypeMap.getSingleton().getExtensionFromMimeType(value)

val MimeType.intentType: String
    get() = intentMimeType.value

private val MimeType.intentMimeType: MimeType
    get() = mimeTypeToIntentMimeTypeMap[this] ?: this

private val mimeTypeToIntentMimeTypeMap = listOf(
    // Allows matching "text/*"
    "application/ecmascript" to "text/ecmascript",
    "application/javascript" to "text/javascript",
    "application/json" to "text/json",
    "application/typescript" to "text/typescript",
    "application/yaml" to "text/x-yaml",
    "application/x-sh" to "text/x-shellscript",
    "application/x-shellscript" to "text/x-shellscript",
    // Allows matching generic
    MimeType.GENERIC.value to MimeType.ANY.value
).associate { it.first.asMimeType() to it.second.asMimeType() }

val Collection<MimeType>.intentType: String
    get() {
        if (isEmpty()) {
            return MimeType.ANY.value
        }
        val intentMimeTypes = map { it.intentMimeType }
        val firstIntentMimeType = intentMimeTypes.first()
        if (intentMimeTypes.all { firstIntentMimeType.match(it) }) {
            return firstIntentMimeType.value
        }
        val wildcardIntentMimeType = MimeType.of(firstIntentMimeType.type, "*", null)
        if (intentMimeTypes.all { wildcardIntentMimeType.match(it) }) {
            return wildcardIntentMimeType.value
        }
        return MimeType.ANY.value
    }
