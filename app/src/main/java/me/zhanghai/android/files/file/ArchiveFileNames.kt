/*
 * Copyright (c) 2026 Material Files contributors.
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

/** The containing directory used when extracting an archive into a separate directory. */
fun getArchiveBaseName(fileName: String): String {
    val isSplit = fileName.endsWith(".001")
    val name = if (isSplit) fileName.dropLast(4) else fileName
    val tarSuffix = compressedTarSuffixes.firstOrNull { name.endsWith(it, ignoreCase = true) }
    val rarVolume = rarVolumeName.matchEntire(name)
    val extension = name.substringAfterLast('.', "").lowercase()
    val baseName = when {
        tarSuffix != null -> name.dropLast(tarSuffix.length)
        rarVolume != null -> rarVolume.groupValues[1]
        // A split of an ordinary file keeps that file's extension: document.pdf.001 -> document.pdf.
        isSplit && extension !in archiveExtensionToMimeTypeMap -> name
        else -> {
            val dot = name.lastIndexOf('.')
            if (dot > 0 && dot < name.lastIndex) name.substring(0, dot) else name
        }
    }
    return baseName.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: fileName
}

private val compressedTarSuffixes = listOf(
    ".tar.gz", ".tar.gzip", ".tar.bz2", ".tar.bzip2", ".tar.xz", ".tar.lzma",
    ".tar.lzma86", ".tar.pmd", ".tar.z"
)

private val rarVolumeName = Regex("(.+)\\.part[0-9]+\\.rar", RegexOption.IGNORE_CASE)
