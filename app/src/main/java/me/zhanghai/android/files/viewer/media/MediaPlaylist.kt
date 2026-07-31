/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.net.Uri
import java8.nio.file.Path
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.intentType
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.provider.common.newDirectoryStream
import java.text.Collator

/**
 * The audio files sitting next to the one that was opened, ordered the way the file list orders
 * them, so that the player can move to the previous or the next track.
 */
class MediaPlaylist private constructor(
    private val items: List<Item>,
    private val index: Int
) {
    class Item(val path: Path, val uri: Uri, val mimeType: String, val title: String)

    val current: Item?
        get() = items.getOrNull(index)

    val hasPrevious: Boolean
        get() = index > 0

    val hasNext: Boolean
        get() = index >= 0 && index < items.size - 1

    /** The same playlist moved by [offset] tracks, or null when that would run off either end. */
    fun movedBy(offset: Int): MediaPlaylist? {
        if (index < 0) {
            return null
        }
        val newIndex = index + offset
        return if (newIndex in items.indices) MediaPlaylist(items, newIndex) else null
    }

    /** The same playlist pointing at [uri], or null when it isn't one of the tracks. */
    fun movedTo(uri: Uri): MediaPlaylist? {
        val newIndex = items.indexOfFirst { it.uri == uri }
        return if (newIndex != -1) MediaPlaylist(items, newIndex) else null
    }

    companion object {
        /**
         * Lists the audio files next to [path], with [path] itself as the current track.
         *
         * Blocking: the directory may live on a remote file system, so this must not be called on
         * the main thread. Returns null when there is nothing to navigate, i.e. the file has no
         * parent, its directory cannot be listed, or it isn't itself an audio file by name.
         */
        fun load(path: Path): MediaPlaylist? {
            val parent = path.parent ?: return null
            val items = try {
                parent.newDirectoryStream().use { directoryStream ->
                    val collator = Collator.getInstance()
                    directoryStream
                        .mapNotNull { it.toItemOrNull() }
                        // Collation keys are not cheap to compute, so compute each one once.
                        .map { it to collator.getCollationKeyForFileName(it.title) }
                        .sortedBy { it.second }
                        .map { it.first }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
            val index = items.indexOfFirst { it.path == path }
            return if (index != -1) MediaPlaylist(items, index) else null
        }

        private fun Path.toItemOrNull(): Item? {
            val name = fileName?.toString()?.takeIf { it.isNotEmpty() } ?: return null
            val mimeType = MimeType.guessFromPath(name).takeIf { it.isAudio } ?: return null
            return Item(this, fileProviderUri, mimeType.intentType, name)
        }
    }
}
