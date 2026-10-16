/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import java8.nio.file.Path
import me.zhanghai.android.files.apk.core.ApkVerificationService

data class ApkProgress(val stage: String, val completed: Long = 0, val total: Long = 0)

data class ApkPrivateKey(val path: Path, val lastModified: Long, val size: Long = 0) {
    val displayName: String
        get() = path.fileName.toString().replace(STORAGE_SUFFIX, "")

    val format: String
        get() = when (path.fileName.toString().substringAfterLast('.').lowercase()) {
            "jks" -> "JKS"
            "p12", "pfx", "pkcs12" -> "PKCS12"
            else -> "AUTO"
        }

    private companion object {
        val STORAGE_SUFFIX = Regex(
            "-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=\\.[^.]+$)"
        )
    }
}

data class ApkImportedKey @JvmOverloads constructor(
    val key: ApkPrivateKey, val aliases: List<String>, val format: String, val reused: Boolean = false
)

/** A password-free snapshot. The ID binds the original entry name to its complete content. */
data class ApkKeyImportEntry(val id: String, val name: String, val format: String, val size: Long)

data class ApkInspection(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val label: String,
    val minSdk: Int,
    val targetSdk: Int,
    val size: Long,
    val verification: ApkVerificationService.Result,
    val verificationMinSdk: Int = minSdk
)

// Passwords are never parcelled, saved as UI state, or included in a generated toString().
class ApkSignRequest(
    val input: Path,
    val outputDirectory: Path,
    val outputName: String,
    val keyStore: Path,
    val keyStoreFormat: String,
    val alias: String,
    val storePassword: CharArray,
    val keyPassword: CharArray,
    val minSdk: Int,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val targetPackageName: String? = null
)

data class ApkSignResult(
    val output: Path,
    val inspection: ApkInspection,
    val coexistenceReport: String? = null
)
