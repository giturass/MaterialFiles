/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * Creates a keystore holding one freshly generated key and a self-signed certificate for it, which
 * is all an APK signing key ever needs.
 *
 * The file is written as PKCS #12 even when it is named `.jks`, matching what `keytool` has done
 * since JDK 9 and what recent Android Studio versions produce.
 */
object KeyStoreGenerator {
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val KEY_STORE_TYPE = "PKCS12"

    /**
     * Google Play requires the signing certificate to stay valid well beyond the app's lifetime, so
     * the default is deliberately long.
     */
    const val DEFAULT_VALIDITY_YEARS = 30

    /** The parts of the certificate's subject name, all optional except the common name. */
    class DistinguishedName(
        val commonName: String,
        val organizationalUnit: String? = null,
        val organization: String? = null,
        val locality: String? = null,
        val state: String? = null,
        val country: String? = null
    ) {
        fun toX500Name(): X500Name =
            X500NameBuilder(BCStyle.INSTANCE)
                .apply {
                    addRdnIfNotBlank(BCStyle.CN, commonName)
                    addRdnIfNotBlank(BCStyle.OU, organizationalUnit)
                    addRdnIfNotBlank(BCStyle.O, organization)
                    addRdnIfNotBlank(BCStyle.L, locality)
                    addRdnIfNotBlank(BCStyle.ST, state)
                    addRdnIfNotBlank(BCStyle.C, country)
                }
                .build()

        private fun X500NameBuilder.addRdnIfNotBlank(
            objectIdentifier: org.bouncycastle.asn1.ASN1ObjectIdentifier,
            value: String?
        ) {
            if (!value.isNullOrBlank()) {
                addRDN(objectIdentifier, value.trim())
            }
        }
    }

    /** Generates the key and writes the keystore to [file], which must not already exist. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun generate(
        file: File,
        storePassword: CharArray,
        alias: String,
        keyPassword: CharArray,
        distinguishedName: DistinguishedName,
        validityYears: Int = DEFAULT_VALIDITY_YEARS
    ) {
        val provider = BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
            initialize(KEY_SIZE, SecureRandom())
            generateKeyPair()
        }
        val notBefore = Date()
        val notAfter = Calendar.getInstance().run {
            time = notBefore
            add(Calendar.YEAR, validityYears)
            time
        }
        val subject = distinguishedName.toX500Name()
        val certificateBuilder = JcaX509v3CertificateBuilder(
            // Self-signed, so the issuer is the subject.
            subject,
            BigInteger(64, SecureRandom()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(provider)
            .build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(certificateBuilder.build(contentSigner))

        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(alias, keyPair.private, keyPassword, arrayOf(certificate))
        file.outputStream().use { keyStore.store(it, storePassword) }
    }
}
