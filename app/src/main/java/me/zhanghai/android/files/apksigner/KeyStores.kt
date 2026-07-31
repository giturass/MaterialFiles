/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A private key with the certificate chain that vouches for it, ready to sign an APK with. */
class SigningKey(val privateKey: PrivateKey, val certificateChain: List<X509Certificate>)

/** An opened keystore, whichever format it turned out to be in. */
interface KeyStoreHandle {
    /** Aliases that hold a private key; entries holding only a certificate are left out. */
    val aliases: List<String>

    @Throws(IOException::class, GeneralSecurityException::class)
    fun getSigningKey(alias: String, keyPassword: CharArray): SigningKey
}

object KeyStores {
    /**
     * Bouncy Castle from our own dependency rather than by provider name, so that we get the same
     * behavior no matter what the platform registered.
     */
    private val bouncyCastleProvider by lazy { BouncyCastleProvider() }

    /**
     * Opens [file] and checks it against [storePassword].
     *
     * The format is taken from the file's own header rather than its name, because `.jks` files
     * written by JDK 9 and later actually hold PKCS #12.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun load(file: File, storePassword: CharArray): KeyStoreHandle {
        when (KeyStoreFormat.detect(file)) {
            KeyStoreFormat.JKS -> return JksKeyStoreHandle(JavaKeyStore.read(file, storePassword))
            KeyStoreFormat.PKCS12 -> return loadJca(file, storePassword, "PKCS12")
            else -> {}
        }
        if (KeyStoreFormat.isJceks(file)) {
            throw IOException(
                "JCEKS keystores are not supported; convert it with" +
                    " \"keytool -importkeystore -destkeystore <out> -deststoretype PKCS12\""
            )
        }
        // Bouncy Castle's formats have no distinctive header, so the only way to tell is to try.
        var lastException: Exception? = null
        for (type in listOf("BKS", "BCFKS", "PKCS12")) {
            try {
                return loadJca(file, storePassword, type)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw IOException("Unrecognized keystore format", lastException)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun loadJca(file: File, storePassword: CharArray, type: String): KeyStoreHandle {
        val keyStore = try {
            KeyStore.getInstance(type)
        } catch (e: GeneralSecurityException) {
            KeyStore.getInstance(type, bouncyCastleProvider)
        }
        try {
            file.inputStream().use { keyStore.load(it, storePassword) }
        } catch (e: IOException) {
            // The platform PKCS #12 reader is stricter than Bouncy Castle's about some files that
            // keytool nonetheless produces, so give Bouncy Castle a chance before giving up.
            val fallback = KeyStore.getInstance(type, bouncyCastleProvider)
            try {
                file.inputStream().use { fallback.load(it, storePassword) }
            } catch (fallbackException: Exception) {
                throw e
            }
            return JcaKeyStoreHandle(fallback)
        }
        return JcaKeyStoreHandle(keyStore)
    }
}

private class JcaKeyStoreHandle(private val keyStore: KeyStore) : KeyStoreHandle {
    override val aliases: List<String> =
        keyStore.aliases().toList().filter { keyStore.isKeyEntry(it) }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun getSigningKey(alias: String, keyPassword: CharArray): SigningKey {
        val privateKey = keyStore.getKey(alias, keyPassword) as? PrivateKey
            ?: throw IOException("No private key for alias \"$alias\"")
        val chain = keyStore.getCertificateChain(alias)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("No certificate chain for alias \"$alias\"")
        return SigningKey(privateKey, chain)
    }
}

private class JksKeyStoreHandle(private val entries: List<JavaKeyStore.Entry>) : KeyStoreHandle {
    override val aliases: List<String> = entries.map { it.alias }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun getSigningKey(alias: String, keyPassword: CharArray): SigningKey {
        val entry = entries.firstOrNull { it.alias == alias }
            ?: throw IOException("No private key for alias \"$alias\"")
        if (entry.certificateChain.isEmpty()) {
            throw IOException("No certificate chain for alias \"$alias\"")
        }
        return SigningKey(entry.getPrivateKey(keyPassword), entry.certificateChain)
    }
}
