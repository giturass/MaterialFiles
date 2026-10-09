/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.readAttributes

/** An input that was actually visited while creating the archive, rather than a later rescan. */
internal class ArchiveSourceSnapshot(val path: Path, attributes: BasicFileAttributes) {
    private val directory = attributes.isDirectory
    private val regularFile = attributes.isRegularFile
    private val symbolicLink = attributes.isSymbolicLink
    private val other = attributes.isOther
    private val size = attributes.size()
    private val modified = attributes.lastModifiedTime()
    private val key = attributes.fileKey()

    @Throws(IOException::class)
    fun delete() {
        val current = path.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (current.isDirectory != directory || current.isRegularFile != regularFile
            || current.isSymbolicLink != symbolicLink || current.isOther != other
            || key != null && current.fileKey() != key
            || !directory && (current.size() != size || current.lastModifiedTime() != modified)) {
            throw IOException("Source changed after archiving and was kept: $path")
        }
        // Directory timestamps change as archived children are removed. Delete only this known
        // path, without walking it again: newly added children make the directory nonempty and
        // therefore preserve both themselves and their parent.
        path.delete()
    }
}
