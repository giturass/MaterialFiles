/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * Copyright (c) 2026 Material Files contributors.
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import java.io.IOException
import java.io.InputStream

object ArchiveReader {
    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        rootPath: Path
    ): Pair<Map<Path, ArchiveEntry>, Map<Path, List<Path>>> {
        val entries = linkedMapOf<Path, ArchiveEntry>()
        for (entry in readEntries(file, passwords)) {
            val name = safeEntryName(entry.name) ?: continue
            val path = rootPath.resolve(name).normalize()
            if (!path.startsWith(rootPath) || path == rootPath && !entry.isDirectory) continue
            entries.putIfAbsent(path, entry)
        }
        entries.putIfAbsent(rootPath, directoryEntry(""))
        // Parents must be directories even if an archive contains conflicting file names.
        for (path in entries.keys.toList()) {
            var parent = path.parent
            while (parent != null && parent.startsWith(rootPath)) {
                if (entries[parent]?.isDirectory != true) {
                    entries[parent] = directoryEntry(rootPath.relativize(parent).toString())
                }
                parent = parent.parent
            }
        }
        val tree = linkedMapOf<Path, MutableList<Path>>()
        for ((path, entry) in entries) {
            if (entry.isDirectory) tree.getOrPut(path) { mutableListOf() }
            path.parent?.let { parent ->
                if (parent.startsWith(rootPath)) tree.getOrPut(parent) { mutableListOf() }.add(path)
            }
        }
        return entries to tree
    }

    private fun readEntries(file: Path, passwords: List<String>): List<ArchiveEntry> =
        SevenZipArchiveReader.open(file, passwords).use { it.readEntries() }

    @Throws(IOException::class)
    fun newInputStream(file: Path, passwords: List<String>, entry: ArchiveEntry): InputStream? {
        val archive = SevenZipArchiveReader.open(file, passwords)
        var successful = false
        try {
            val input = archive.newInputStream(entry.name) ?: return null
            successful = true
            return object : DelegateInputStream(input) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        archive.close()
                    }
                }
            }
        } finally {
            if (!successful) archive.close()
        }
    }

    /** Reject rooted names and traversal before resolving them in the archive filesystem. */
    internal fun safeEntryName(name: String): String? {
        val normalized = name.replace('\\', '/')
        if ('\u0000' in normalized || normalized.startsWith('/')
            || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val parts = mutableListOf<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun directoryEntry(name: String) = ArchiveEntry(
        name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
        PosixFileMode.DIRECTORY_DEFAULT, null
    )
}
