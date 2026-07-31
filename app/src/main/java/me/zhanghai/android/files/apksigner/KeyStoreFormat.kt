/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Keystore container formats we can read.
 *
 * Android ships no JKS provider, so [JKS] is handled by [JavaKeyStore] instead of
 * [java.security.KeyStore].
 */
enum class KeyStoreFormat {
    /** The modern standard, and what `keytool` writes by default since JDK 9. */
    PKCS12,

    /** The legacy Sun format, still what many older `.jks` files actually contain. */
    JKS,

    /** Bouncy Castle's keystore, occasionally used on Android. */
    BKS,

    /** Bouncy Castle's FIPS keystore. */
    BCFKS;

    companion object {
        private const val JKS_MAGIC = 0xFEEDFEED.toInt()

        /** JCEKS shares the JKS layout but encrypts keys differently, which we cannot read. */
        private const val JCEKS_MAGIC = 0xCECECECE.toInt()

        /** DER encodings, and therefore PKCS #12 files, start with a SEQUENCE tag. */
        private const val DER_SEQUENCE_TAG = 0x30.toByte()

        /**
         * Identifies the format from the file's leading bytes, or returns null when it isn't
         * recognized, in which case the caller should simply try the formats in turn.
         */
        fun detect(file: File): KeyStoreFormat? =
            try {
                DataInputStream(file.inputStream().buffered()).use { inputStream ->
                    val magic = inputStream.readInt()
                    when {
                        magic == JKS_MAGIC -> JKS
                        magic == JCEKS_MAGIC -> null
                        (magic ushr 24).toByte() == DER_SEQUENCE_TAG -> PKCS12
                        else -> null
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }

        /** True when the file is a JCEKS keystore, which we can detect but not read. */
        fun isJceks(file: File): Boolean =
            try {
                DataInputStream(file.inputStream().buffered()).use { it.readInt() == JCEKS_MAGIC }
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
    }
}
