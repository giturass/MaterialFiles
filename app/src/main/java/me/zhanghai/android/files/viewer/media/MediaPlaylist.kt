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
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.newDirectoryStream
import java.text.Collator

/**
 * The audio files sitting next to the one that was opened, ordered the way the file list orders
 * them, so that the player can move to the previous or the next track.
 */
class MediaPlaylist private constructor(
    val items: List<Item>,
    val index: Int,
    /**
     * Positions into [items] in the order the tracks are played in, which is the file order until
     * the playlist is shuffled.
     */
    private val order: List<Int>
) {
    class Item(val path: Path, val uri: Uri, val mimeType: String, val title: String) {
        /** Shown to tell apart same-named tracks once a playlist spans more than one folder. */
        val folderName: String
            get() = path.parent?.name.orEmpty()
    }

    val size: Int
        get() = items.size

    val current: Item?
        get() = items.getOrNull(index)

    /**
     * Where the current track sits in the play order, as opposed to in the file order.
     *
     * Computed once rather than on each access: this is read for every previous/next check, which
     * the media session re-evaluates on every progress tick, and a scanned playlist can hold
     * thousands of tracks.
     */
    private val orderPosition: Int = order.indexOf(index)

    val hasPrevious: Boolean
        get() = orderPosition > 0

    val hasNext: Boolean
        get() = orderPosition.let { it >= 0 && it < order.size - 1 }

    /** Whether [other] lists exactly the same tracks in the same file order. */
    fun hasSameItemsAs(other: MediaPlaylist): Boolean {
        if (other === this) {
            return true
        }
        if (other.items.size != items.size) {
            return false
        }
        return items.indices.all { other.items[it].path == items[it].path }
    }

    /** The same tracks, but played in [other]'s order and pointing at [other]'s current track. */
    fun withOrderOf(other: MediaPlaylist): MediaPlaylist =
        MediaPlaylist(items, other.index, other.order)

    /**
     * The same playlist moved by [offset] tracks in play order. Without [wrap] this is null once the
     * move would run off either end; with it the two ends are joined, so a playlist of one track
     * moves to that track again.
     */
    fun movedBy(offset: Int, wrap: Boolean = false): MediaPlaylist? {
        val position = orderPosition
        if (position < 0) {
            return null
        }
        val newPosition = if (wrap && order.isNotEmpty()) {
            (position + offset).mod(order.size)
        } else {
            position + offset
        }
        val newIndex = order.getOrNull(newPosition) ?: return null
        return MediaPlaylist(items, newIndex, order)
    }

    /** The same playlist pointing at [uri], or null when it isn't one of the tracks. */
    fun movedTo(uri: Uri): MediaPlaylist? {
        val newIndex = items.indexOfFirst { it.uri == uri }
        return if (newIndex != -1) MediaPlaylist(items, newIndex, order) else null
    }

    /** The same playlist pointing at the track at [index] of [items], as picked from a list. */
    fun movedToIndex(index: Int): MediaPlaylist? =
        if (index in items.indices) MediaPlaylist(items, index, order) else null

    /**
     * The same playlist in a random play order that starts at the current track, so that turning
     * shuffle on doesn't interrupt what is playing.
     */
    fun shuffled(): MediaPlaylist {
        val shuffledOrder = items.indices.filter { it != index }.shuffled().toMutableList()
        if (index in items.indices) {
            shuffledOrder.add(0, index)
        }
        return MediaPlaylist(items, index, shuffledOrder)
    }

    /** The same playlist back in file order. */
    fun inFileOrder(): MediaPlaylist = MediaPlaylist(items, index, items.indices.toList())

    companion object {
        /** How far below a configured folder the scan descends before it stops. */
        private const val MAX_SCAN_DEPTH = 8

        /** A ceiling on one scan, so that pointing it at a whole storage cannot hang the player. */
        private const val MAX_SCAN_ITEMS = 5000

        /**
         * The tracks to play alongside [path], with [path] itself as the current one.
         *
         * When [path] is inside one of [scanDirectories], the playlist becomes every audio file
         * under all of them, so that next and shuffle span the whole configured library. Otherwise,
         * and whenever such a scan cannot account for [path] itself, it falls back to listing the
         * folder [path] sits in.
         *
         * Blocking: the folders may live on a remote file system, so this must not be called on the
         * main thread. Returns null when there is nothing to navigate, i.e. the file has no parent,
         * its folder cannot be listed, or it isn't itself an audio file by name.
         */
        fun load(path: Path, scanDirectories: List<Path>): MediaPlaylist? {
            if (scanDirectories.any { path.startsWith(it) }) {
                loadScanned(scanDirectories, path)?.let { return it }
            }
            return loadFolder(path)
        }

        /** Lists the audio files next to [path], the way the file list orders them. */
        private fun loadFolder(path: Path): MediaPlaylist? {
            val parent = path.parent ?: return null
            val items = try {
                parent.newDirectoryStream().use { directoryStream ->
                    directoryStream.mapNotNull { it.toItemOrNull() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
            return of(sortedByFolderThenName(items), path)
        }

        private fun loadScanned(scanDirectories: List<Path>, path: Path): MediaPlaylist? {
            // Keyed by path, since configuring both a folder and a folder inside it would otherwise
            // list everything below the inner one twice.
            val items = mutableMapOf<Path, Item>()
            for (directory in scanDirectories) {
                scanInto(directory, MAX_SCAN_DEPTH, items)
            }
            return of(sortedByFolderThenName(items.values), path)
        }

        private fun scanInto(directory: Path, depth: Int, items: MutableMap<Path, Item>) {
            if (depth <= 0 || items.size >= MAX_SCAN_ITEMS) {
                return
            }
            val children = try {
                directory.newDirectoryStream().use { it.toList() }
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }
            // Descending only after this folder has been read costs one list of children in memory
            // and saves a stat on every audio file, which is most of what a music folder holds.
            val subdirectories = mutableListOf<Path>()
            for (child in children) {
                if (items.size >= MAX_SCAN_ITEMS) {
                    return
                }
                val item = child.toItemOrNull()
                if (item != null) {
                    items[child] = item
                } else if (child.isDirectory()) {
                    // Not `+=`: a Path is itself an Iterable of Paths, so that would concatenate
                    // its name components instead of adding the folder.
                    subdirectories.add(child)
                }
            }
            for (subdirectory in subdirectories) {
                scanInto(subdirectory, depth - 1, items)
            }
        }

        /**
         * Keeps each folder's songs together and in the order the file list would show them, rather
         * than interleaving albums that happen to have similarly named tracks.
         */
        private fun sortedByFolderThenName(items: Collection<Item>): List<Item> {
            val collator = Collator.getInstance()
            return items
                // Collation keys are not cheap to compute, so compute each one once.
                .map {
                    Triple(
                        it,
                        collator.getCollationKey(it.path.parent?.toString().orEmpty()),
                        collator.getCollationKeyForFileName(it.title)
                    )
                }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .map { it.first }
        }

        private fun of(items: List<Item>, path: Path): MediaPlaylist? {
            val index = items.indexOfFirst { it.path == path }
            return if (index != -1) {
                MediaPlaylist(items, index, items.indices.toList())
            } else {
                null
            }
        }

        private fun Path.toItemOrNull(): Item? {
            val name = fileName?.toString()?.takeIf { it.isNotEmpty() } ?: return null
            val mimeType = MimeType.guessFromPath(name).takeIf { it.isAudio } ?: return null
            return Item(this, fileProviderUri, mimeType.intentType, name)
        }
    }
}
