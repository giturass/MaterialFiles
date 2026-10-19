/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.coil.RetainedImageRequest
import me.zhanghai.android.files.coil.imageCacheKeyOf
import me.zhanghai.android.files.coil.pathAttributesImageCacheKey
import me.zhanghai.android.files.compat.isSingleLineCompat
import me.zhanghai.android.files.databinding.FileItemListBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.AnimatedListAdapter
import me.zhanghai.android.files.ui.CheckableItemBackground
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.valueCompat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class FileListAdapter(
    lifecycleOwner: LifecycleOwner,
    private val listener: Listener
) : AnimatedListAdapter<FileItem, FileListAdapter.ViewHolder>(CALLBACK), PopupTextProvider {
    private var isSearching = false

    private lateinit var _sortOptions: FileSortOptions
    var sortOptions: FileSortOptions
        get() = _sortOptions
        set(value) {
            _sortOptions = value
            if (!isSearching) {
                val sortedList = list.sortedWith(value.createComparator())
                super.replace(sortedList, true)
                rebuildFilePositionMap()
            }
        }

    var pickOptions: PickOptions? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    private val selectedFiles = fileItemSetOf()

    private val filePositionMap = mutableMapOf<Path, Int>()

    private val directoryItemCountLoader =
        DirectoryItemCountLoader(lifecycleOwner.lifecycleScope) { path ->
            filePositionMap[path]?.let { notifyItemChanged(it, PAYLOAD_DETAILS_CHANGED) }
        }

    private lateinit var _nameEllipsize: TextUtils.TruncateAt
    var nameEllipsize: TextUtils.TruncateAt
        get() = _nameEllipsize
        set(value) {
            _nameEllipsize = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    fun replaceSelectedFiles(files: FileItemSet) {
        val changedFiles = fileItemSetOf()
        val iterator = selectedFiles.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file !in files) {
                iterator.remove()
                changedFiles.add(file)
            }
        }
        for (file in files) {
            // MapSet.add() also replaces the value for an already-selected path, keeping its
            // attributes current when the ViewModel publishes a newer FileItem.
            if (selectedFiles.add(file)) {
                changedFiles.add(file)
            }
        }
        for (file in changedFiles) {
            val position = filePositionMap[file.path]
            position?.let { notifyItemChanged(it, PAYLOAD_STATE_CHANGED) }
        }
    }

    private fun selectFile(file: FileItem) {
        if (!isFileSelectable(file)) {
            return
        }
        val selected = file in selectedFiles
        val pickOptions = pickOptions
        if (!selected && pickOptions != null && !pickOptions.allowMultiple) {
            listener.clearSelectedFiles()
        }
        listener.selectFile(file, !selected)
    }

    fun selectAllFiles() {
        if (pickOptions?.allowMultiple == false) {
            return
        }
        val files = fileItemSetOf()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            if (isFileSelectable(file)) {
                files.add(file)
            }
        }
        listener.selectFiles(files, true)
    }

    fun getInvertedSelection(selection: FileItemSet): FileItemSet {
        // Selection may span directories; only invert rows in the current filtered list.
        val files = fileItemSetOf().apply { addAll(selection) }
        if (pickOptions?.allowMultiple == false) {
            return files
        }
        for (file in list) {
            if (!isFileSelectable(file)) {
                continue
            }
            if (file in selection) {
                files.remove(file)
            } else {
                files.add(file)
            }
        }
        return files
    }

    private fun isFileSelectable(file: FileItem): Boolean {
        val pickOptions = pickOptions ?: return true
        return when (pickOptions.mode) {
            PickOptions.Mode.OPEN_FILE, PickOptions.Mode.CREATE_FILE ->
                !file.attributes.isDirectory &&
                    pickOptions.mimeTypes.any { it.match(file.mimeType) }
            PickOptions.Mode.OPEN_DIRECTORY -> file.attributes.isDirectory
        }
    }

    override fun clear() {
        directoryItemCountLoader.clear()
        super.clear()

        rebuildFilePositionMap()
    }

    fun invalidateDirectoryCounts() {
        for (path in directoryItemCountLoader.invalidate()) {
            filePositionMap[path]?.let { notifyItemChanged(it, PAYLOAD_DETAILS_CHANGED) }
        }
    }

    @Deprecated("", ReplaceWith("replaceListAndSearching(list, searching)"))
    override fun replace(list: List<FileItem>, clear: Boolean) {
        throw UnsupportedOperationException()
    }

    fun replaceListAndIsSearching(list: List<FileItem>, isSearching: Boolean) {
        val clear = this.isSearching != isSearching
        this.isSearching = isSearching
        val sortedList = if (!isSearching) list.sortedWith(sortOptions.createComparator()) else list
        val changedCountPaths = directoryItemCountLoader.retainFiles(sortedList)
        super.replace(sortedList, clear)
        rebuildFilePositionMap()
        for (path in changedCountPaths) {
            filePositionMap[path]?.let { notifyItemChanged(it, PAYLOAD_DETAILS_CHANGED) }
        }
    }

    private fun rebuildFilePositionMap() {
        filePositionMap.clear()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            filePositionMap[file.path] = index
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = parent.context.layoutInflater
        val holder = ViewHolder(FileItemListBinding.inflate(inflater, parent, false))
        return holder.apply {
            itemLayout.apply {
                background = CheckableItemBackground.create(0f, 0f, context)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        throw UnsupportedOperationException()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val file = getItem(position)
        val isDirectory = file.attributes.isDirectory
        val isEnabled = isFileSelectable(file) || isDirectory
        holder.itemLayout.isEnabled = isEnabled
        val path = file.path
        val checked = file in selectedFiles
        holder.itemLayout.isChecked = checked
        holder.iconLayout.isEnabled = isFileSelectable(file)
        holder.iconLayout.contentDescription = holder.iconLayout.context.getString(
            if (checked) R.string.file_item_deselect_format else R.string.file_item_select_format,
            file.name
        )
        holder.nameText.apply {
            if (isSingleLineCompat) {
                val nameEllipsize = nameEllipsize
                ellipsize = nameEllipsize
                isSelected = nameEllipsize == TextUtils.TruncateAt.MARQUEE
            }
        }
        if (payloads.isNotEmpty()) {
            if (PAYLOAD_DETAILS_CHANGED in payloads) {
                bindDetails(holder, file)
            }
            return
        }
        bindViewHolderAnimation(holder)
        holder.itemLayout.apply {
            setOnClickListener {
                if (selectedFiles.isEmpty()) {
                    listener.openFile(file)
                } else {
                    selectFile(file)
                }
            }
            setOnLongClickListener {
                selectFile(file)
                true
            }
        }
        holder.iconLayout.setOnClickListener { selectFile(file) }
        val iconRes = file.mimeType.iconRes
        holder.iconImage.setImageResource(iconRes)
        holder.directoryBackgroundView.isVisible = isDirectory
        val supportsThumbnail = file.supportsThumbnail
        val attributes = file.attributes
        val thumbnailKey = if (supportsThumbnail) {
            imageCacheKeyOf(
                pathAttributesImageCacheKey(path, attributes), file.mimeType.value,
                file.symbolicLinkTarget,
                if (file.attributesNoFollowLinks.isSymbolicLink) {
                    pathAttributesImageCacheKey(path, file.attributesNoFollowLinks)
                } else {
                    null
                },
                Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.valueCompat
            )
        } else {
            null
        }
        holder.thumbnailRequest.load(thumbnailKey, path to attributes) {
            // Include link metadata and settings in Coil's cache as well as the retention key.
            setParameter("fileListThumbnail", thumbnailKey)
        }
        val appDirectoryPackageName = file.appDirectoryPackageName
        holder.appIconBadgeRequest.load(
            appDirectoryPackageName?.let { path to it },
            appDirectoryPackageName?.let { AppIconPackageName(it) }
        )
        holder.badgeImage.apply {
            val badgeIconRes = if (file.attributesNoFollowLinks.isSymbolicLink) {
                if (file.isSymbolicLinkBroken) {
                    R.drawable.error_badge_icon_18dp
                } else {
                    R.drawable.symbolic_link_badge_icon_18dp
                }
            } else if (file.attributesNoFollowLinks.isEncrypted()) {
                R.drawable.encrypted_badge_icon_18dp
            } else {
                null
            }
            val hasBadge = badgeIconRes != null
            isVisible = hasBadge
            if (hasBadge) {
                setImageResource(badgeIconRes!!)
            } else {
                setImageDrawable(null)
            }
        }
        holder.nameText.text = file.name
        bindDetails(holder, file)
    }

    private fun bindDetails(holder: ViewHolder, file: FileItem) {
        val context = holder.descriptionText.context
        val attributes = file.attributes
        holder.descriptionText.contentDescription = null
        if (attributes.isDirectory) {
            val result = directoryItemCountLoader.getCount(file)
            val count = result?.value
            when {
                result == null -> {
                    holder.descriptionText.setText(R.string.file_item_count_loading)
                    holder.descriptionText.contentDescription = context.getString(R.string.loading)
                }
                count == null -> {
                    holder.descriptionText.setText(R.string.file_item_count_unavailable)
                    holder.descriptionText.contentDescription =
                        context.getString(R.string.file_item_count_unavailable_description)
                }
                else -> {
                    holder.descriptionText.text = context.resources.getQuantityString(
                        R.plurals.file_item_directory_count,
                        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count
                    )
                }
            }
        } else {
            holder.descriptionText.text = Formatter.formatShortFileSize(context, attributes.size())
        }
        val lastModified = LAST_MODIFIED_FORMATTER.withZone(ZoneId.systemDefault())
            .format(attributes.lastModifiedTime().toInstant())
        holder.lastModifiedText.text = lastModified
        holder.lastModifiedText.contentDescription = lastModified
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbnailRequest.clear()
        holder.appIconBadgeRequest.clear()
        super.onViewRecycled(holder)
    }

    override fun getPopupText(view: View, position: Int): CharSequence {
        val file = getItem(position)
        return when (sortOptions.by) {
            FileSortOptions.By.NAME -> file.name.take(1).uppercase(Locale.getDefault())
            FileSortOptions.By.TYPE -> file.extension.uppercase(Locale.getDefault())
            FileSortOptions.By.SIZE -> file.attributes.fileSize.formatHumanReadable(view.context)
            FileSortOptions.By.LAST_MODIFIED ->
                file.attributes.lastModifiedTime().toInstant().formatShort(view.context)
        }
    }

    override val isAnimationEnabled: Boolean
        get() = Settings.FILE_LIST_ANIMATION.valueCompat

    companion object {
        private val PAYLOAD_STATE_CHANGED = Any()
        private val PAYLOAD_DETAILS_CHANGED = Any()

        private val LAST_MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)

        private val CALLBACK = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem == newItem
        }
    }

    class ViewHolder(binding: FileItemListBinding) : RecyclerView.ViewHolder(binding.root) {
        val itemLayout = binding.itemLayout
        val iconLayout = binding.iconLayout
        val directoryBackgroundView = binding.directoryBackgroundView
        val iconImage = binding.iconImage
        val thumbnailImage = binding.thumbnailImage
        val appIconBadgeImage = binding.appIconBadgeImage
        val badgeImage = binding.badgeImage
        val nameText = binding.nameText
        val descriptionText = binding.descriptionText
        val lastModifiedText = binding.lastModifiedText

        internal val thumbnailRequest = RetainedImageRequest(thumbnailImage) { success ->
            iconImage.isVisible = !success
        }
        internal val appIconBadgeRequest = RetainedImageRequest(appIconBadgeImage)
    }

    interface Listener {
        fun clearSelectedFiles()
        fun selectFile(file: FileItem, selected: Boolean)
        fun selectFiles(files: FileItemSet, selected: Boolean)
        fun openFile(file: FileItem)
        fun openFileWith(file: FileItem)
        fun cutFile(file: FileItem)
        fun copyFile(file: FileItem)
        fun confirmDeleteFile(file: FileItem)
        fun showRenameFileDialog(file: FileItem)
        fun extractFile(file: FileItem)
        fun showCreateArchiveDialog(file: FileItem)
        fun shareFile(file: FileItem)
        fun copyPath(file: FileItem)
        fun addBookmark(file: FileItem)
        fun createShortcut(file: FileItem)
        fun showPropertiesDialog(file: FileItem)
    }
}
