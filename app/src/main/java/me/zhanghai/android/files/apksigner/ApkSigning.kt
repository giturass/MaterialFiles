/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

/** Which APK Signature Scheme versions to apply. */
class ApkSigningSchemes(val v1: Boolean, val v2: Boolean, val v3: Boolean) {
    val isEmpty: Boolean
        get() = !v1 && !v2 && !v3
}

/** What [ApkSigning.verify] found in a signed APK. */
class ApkVerificationResult(
    val isVerified: Boolean,
    val usedV1: Boolean,
    val usedV2: Boolean,
    val usedV3: Boolean,
    val errors: List<String>
)

/**
 * APK signing, backed by apksig — the same library the `apksigner` tool from the Android SDK is
 * built on.
 */
object ApkSigning {
    /**
     * The signer name, which ends up in the v1 signature file names (`META-INF/CERT.SF`). This is
     * the name `apksigner` uses too.
     */
    private const val SIGNER_NAME = "CERT"

    private const val CREATED_BY = "Material Files"

    /**
     * Signs [inputApk] into [outputApk], which must be a different file.
     *
     * The APK's own `minSdkVersion` decides which signature schemes actually end up being required;
     * apksig reads it from the manifest.
     */
    @Throws(Exception::class)
    fun sign(
        inputApk: File,
        outputApk: File,
        signingKey: SigningKey,
        schemes: ApkSigningSchemes
    ) {
        require(!schemes.isEmpty) { "At least one signature scheme must be enabled" }
        val signerConfig = ApkSigner.SignerConfig.Builder(
            SIGNER_NAME, signingKey.privateKey, signingKey.certificateChain
        ).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(schemes.v1)
            .setV2SigningEnabled(schemes.v2)
            .setV3SigningEnabled(schemes.v3)
            .setCreatedBy(CREATED_BY)
            .build()
            .sign()
    }

    /** Reads back a signed APK and reports which schemes verify. */
    @Throws(Exception::class)
    fun verify(apk: File): ApkVerificationResult {
        val result = ApkVerifier.Builder(apk).build().verify()
        return ApkVerificationResult(
            result.isVerified,
            result.isVerifiedUsingV1Scheme,
            result.isVerifiedUsingV2Scheme,
            result.isVerifiedUsingV3Scheme,
            result.errors.map { it.toString() }
        )
    }
}
