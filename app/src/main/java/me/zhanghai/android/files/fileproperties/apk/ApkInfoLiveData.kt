/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.content.pm.PackageManager
import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.util.sha1Digest
import me.zhanghai.android.files.util.toHexString
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException

class ApkInfoLiveData(path: Path) : PathObserverLiveData<Stateful<ApkInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                // We must always pass in PackageManager.GET_SIGNATURES for
                // PackageManager.getPackageArchiveInfo() to call
                // PackageParser.collectCertificates().
                @Suppress("DEPRECATION")
                val packageInfoFlags = (PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES)
                val (packageInfo, closeable) =
                    packageManager.getPackageArchiveInfoCompat(path, packageInfoFlags)
                val apkInfo = closeable.use {
                    val applicationInfo = packageInfo?.applicationInfo
                        ?: throw IOException("ApplicationInfo is null")
                    val label = applicationInfo.loadLabel(packageManager).toString()
                    // PackageInfo.signatures returns only the oldest certificate when the APK
                    // has a signing history; signingInfo exposes the current signer instead.
                    val signingCertificates = packageInfo.signingInfo?.apkContentsSigners
                        ?: emptyArray()
                    val signingCertificateDigests = signingCertificates
                        .map { it.toByteArray().sha1Digest().toHexString() }
                    val signingInfo = packageInfo.signingInfo
                    // The history also contains the current certificate, so remove it.
                    val pastSigningCertificates =
                        if (signingInfo?.hasPastSigningCertificates() == true) {
                            signingInfo.signingCertificateHistory?.toMutableList()
                                ?.apply { removeAll(signingCertificates) }
                        } else {
                            null
                        } ?: emptyList()
                    val pastSigningCertificateDigests = pastSigningCertificates
                        .map { it.toByteArray().sha1Digest().toHexString() }
                    ApkInfo(
                        packageInfo, label, signingCertificateDigests, pastSigningCertificateDigests
                    )
                }
                Success(apkInfo)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
