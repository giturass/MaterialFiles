/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.file

import java.io.File
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.compat.DocumentsContractCompat
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.pathFileCompat
import me.zhanghai.android.files.compat.uuidCompat
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.valueCompat

/** A restricted directory and its equivalent location in the system file manager. */
internal class AndroidDirectory(
    val volumeDirectory: File,
    val rootId: String,
    val relativePath: String
) {
    val isRoot: Boolean
        get() = relativePath == "Android/data" || relativePath == "Android/obb"

    val uri: ExternalStorageUri
        get() = ExternalStorageUri(rootId, relativePath)

    val parentPath: Path
        get() = Paths.get(volumeDirectory.resolve("Android").path)

    fun requiresPrivilege(isAttributeAccess: Boolean, packageName: String): Boolean {
        // Reading the two directory entries from Android must not request privileges.
        if (isAttributeAccess && isRoot) return false
        return listOf("Android/data/$packageName", "Android/obb/$packageName").none {
            relativePath == it || relativePath.startsWith("$it/")
        }
    }
}

internal val Path.androidDirectory: AndroidDirectory?
    get() {
        if (!isLinuxPath) return null
        val file = toFile().absoluteFile.normalize()
        for (volume in StorageVolumeListLiveData.valueCompat) {
            val directory = volume.pathFileCompat.absoluteFile.normalize()
            val rootId = if (volume.isPrimaryCompat) {
                DocumentsContractCompat.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID
            } else {
                volume.uuidCompat ?: continue
            }
            val aliases = if (volume.isPrimaryCompat) {
                listOf(directory, File("/sdcard"), File("/storage/self/primary"))
            } else {
                listOf(directory)
            }
            for (alias in aliases) {
                val relativePath = file.restrictedAndroidRelativePath(alias) ?: continue
                return AndroidDirectory(directory, rootId, relativePath)
            }
        }
        return null
    }

internal fun File.restrictedAndroidRelativePath(volumeDirectory: File): String? {
    val file = absoluteFile.normalize()
    val directory = volumeDirectory.absoluteFile.normalize()
    if (!file.startsWith(directory)) return null
    val relativePath = file.relativeTo(directory).path
    return relativePath.takeIf { it.isRestrictedAndroidRelativePath() }
}

internal fun String.isRestrictedAndroidRelativePath(): Boolean =
    this == "Android/data" || startsWith("Android/data/") ||
        this == "Android/obb" || startsWith("Android/obb/")

internal val ExternalStorageUri.androidDirectoryPath: Path?
    get() {
        val relativePath = File(path).normalize().path
        if (!relativePath.isRestrictedAndroidRelativePath()) return null
        val volume = StorageVolumeListLiveData.valueCompat.firstOrNull {
            if (rootId == DocumentsContractCompat.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID) {
                it.isPrimaryCompat
            } else {
                it.uuidCompat == rootId
            }
        } ?: return null
        return Paths.get(volume.pathFileCompat.resolve(relativePath).path)
    }
