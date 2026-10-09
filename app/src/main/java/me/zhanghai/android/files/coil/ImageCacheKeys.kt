/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.ContentProviderFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.provider.document.DocumentFileAttributes
import me.zhanghai.android.files.provider.smb.SmbFileAttributes
import me.zhanghai.android.files.util.use

// Share this signature between retained requests and Coil's memory cache. In particular, an
// unchanged millisecond timestamp must not hide a change in size, inode, or sub-millisecond time.
internal fun pathAttributesImageCacheKey(path: Path, attributes: BasicFileAttributes): String =
    with(attributes) {
        val posixAttributes = this as? PosixFileAttributes
        val cacheKey = imageCacheKeyOf(
            "path-attributes-v2", path.toUri(), lastModifiedTime(), creationTime(), size(),
            isRegularFile, isDirectory, isSymbolicLink, isOther, fileKeyImageCacheKey(fileKey()),
            (this as? ContentProviderFileAttributes)?.mimeType(), isEncrypted(),
            posixAttributes?.owner()?.id, posixAttributes?.group()?.id,
            posixAttributes?.mode()?.sortedBy { it.ordinal }, posixAttributes?.seLinuxContext(),
            (this as? DocumentFileAttributes)?.flags(), (this as? SmbFileAttributes)?.attributes()
        )
        if (AndroidFileTypeDetector.getMimeType(path, this).asMimeType().isApk) {
            imageCacheKeyOf(cacheKey, "apk-icon-circle-v1")
        } else {
            cacheKey
        }
    }

// Length-prefix components so delimiters in a filename or link target cannot alias another key.
internal fun imageCacheKeyOf(vararg components: Any?): String = buildString {
    for (component in components) {
        if (component == null) {
            append("-1:")
        } else {
            val value = component.toString()
            append(value.length).append(':').append(value)
        }
    }
}

private fun fileKeyImageCacheKey(key: Any?): String? = when (key) {
    null -> null
    is Path -> key.toUri().toString()
    is Uri -> key.toString()
    // File keys have value equality, but not all implement value-based toString() (e.g. SMB).
    // They are already parcelable for FileItem; encode their fields, not their object identity.
    is Parcelable -> Parcel.obtain().use { parcel ->
        parcel.writeParcelable(key, 0)
        Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
    }
    else -> imageCacheKeyOf(key.javaClass.name, key)
}
