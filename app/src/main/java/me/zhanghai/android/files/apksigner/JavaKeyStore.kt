/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Reads the legacy JKS keystore format, which Android has no provider for although plenty of
 * `.jks` files created by older `keytool` and Android Studio versions still use it.
 *
 * The format is a flat record stream ending in a SHA-1 digest that doubles as the password check,
 * and private keys are wrapped by Sun's "key protector", a SHA-1 keystream XORed over the key.
 *
 * @see <a href="https://cr.openjdk.org/~mullan/webrevs/ascarpin/webrev.00/raw_files/new/src/java.base/share/classes/sun/security/provider/JavaKeyStore.java">JavaKeyStore</a>
 */
object JavaKeyStore {
    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2

    private const val TAG_PRIVATE_KEY = 1
    private const val TAG_TRUSTED_CERTIFICATE = 2

    private const val DIGEST_LENGTH = 20

    /** Mixed into the integrity digest by the original implementation. */
    private val DIGEST_SALT = "Mighty Aphrodite".toByteArray(Charsets.UTF_8)

    /** Sun's proprietary key protection algorithm, the only one JKS uses. */
    private val KEY_PROTECTOR_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.42.2.17.1.1")

    /** One private key entry: the still-encrypted key plus the certificate chain in front of it. */
    class Entry(
        val alias: String,
        private val encryptedKey: ByteArray,
        val certificateChain: List<X509Certificate>
    ) {
        /**
         * Unwraps the private key. The key password is often, but not always, the same as the
         * keystore password.
         */
        @Throws(IOException::class, GeneralSecurityException::class)
        fun getPrivateKey(keyPassword: CharArray): PrivateKey {
            val keyBytes = decryptKey(encryptedKey, keyPassword)
            val privateKeyInfo = PrivateKeyInfo.getInstance(keyBytes)
            val algorithm = privateKeyInfo.privateKeyAlgorithm.toJcaAlgorithmName()
            return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        }
    }

    /**
     * Parses [file] and verifies it against [storePassword].
     *
     * Only private key entries are returned; trusted certificate entries cannot be signed with.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun read(file: File, storePassword: CharArray): List<Entry> {
        val bytes = file.readBytes()
        if (bytes.size < DIGEST_LENGTH) {
            throw IOException("File is too short to be a JKS keystore")
        }
        // An empty password means the caller has none to check with, which the original
        // implementation also treats as skipping the integrity check.
        if (storePassword.isNotEmpty()) {
            verifyIntegrity(bytes, storePassword)
        }
        return parse(bytes)
    }

    /**
     * The trailing digest covers the password and everything before it, so a mismatch means either
     * a wrong password or a damaged file.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun verifyIntegrity(bytes: ByteArray, storePassword: CharArray) {
        val contentLength = bytes.size - DIGEST_LENGTH
        val digest = MessageDigest.getInstance("SHA-1").run {
            update(storePassword.toUtf16BeBytes())
            update(DIGEST_SALT)
            update(bytes, 0, contentLength)
            digest()
        }
        val expected = bytes.copyOfRange(contentLength, bytes.size)
        if (!digest.contentEquals(expected)) {
            throw IOException("Wrong keystore password, or the keystore has been tampered with")
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun parse(bytes: ByteArray): List<Entry> {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return DataInputStream(ByteArrayInputStream(bytes)).use { inputStream ->
            val magic = inputStream.readInt()
            if (magic != MAGIC) {
                throw IOException("Not a JKS keystore")
            }
            val version = inputStream.readInt()
            if (version != VERSION_1 && version != VERSION_2) {
                throw IOException("Unsupported JKS keystore version $version")
            }
            val entryCount = inputStream.readInt()
            if (entryCount < 0) {
                throw IOException("Invalid JKS entry count $entryCount")
            }
            buildList {
                repeat(entryCount) {
                    when (val tag = inputStream.readInt()) {
                        TAG_PRIVATE_KEY -> {
                            val alias = inputStream.readUTF()
                            // Creation date, which we have no use for.
                            inputStream.readLong()
                            val encryptedKey = inputStream.readByteArray()
                            val chainLength = inputStream.readInt()
                            if (chainLength < 0) {
                                throw IOException("Invalid certificate chain length $chainLength")
                            }
                            val chain = (0 until chainLength).map {
                                inputStream.readCertificate(certificateFactory, version)
                            }
                            add(Entry(alias, encryptedKey, chain))
                        }
                        TAG_TRUSTED_CERTIFICATE -> {
                            inputStream.readUTF()
                            inputStream.readLong()
                            inputStream.readCertificate(certificateFactory, version)
                        }
                        else -> throw IOException("Unrecognized JKS entry tag $tag")
                    }
                }
            }
        }
    }

    private fun DataInputStream.readByteArray(): ByteArray {
        val length = readInt()
        if (length < 0) {
            throw IOException("Invalid length $length")
        }
        return ByteArray(length).also { readFully(it) }
    }

    private fun DataInputStream.readCertificate(
        certificateFactory: CertificateFactory,
        version: Int
    ): X509Certificate {
        if (version == VERSION_2) {
            val type = readUTF()
            if (type != "X.509" && type != "X509") {
                throw IOException("Unsupported certificate type $type")
            }
        }
        val encoded = readByteArray()
        return certificateFactory.generateCertificate(ByteArrayInputStream(encoded))
            as X509Certificate
    }

    /**
     * Undoes Sun's key protector: a SHA-1 keystream seeded from the password and a random salt is
     * XORed over the key, and a final digest of password plus plaintext detects a wrong password.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun decryptKey(encryptedKey: ByteArray, keyPassword: CharArray): ByteArray {
        val sequence = try {
            ASN1Sequence.getInstance(encryptedKey)
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed encrypted private key", e)
        }
        if (sequence.size() < 2) {
            throw IOException("Malformed encrypted private key")
        }
        val algorithm = AlgorithmIdentifier.getInstance(sequence.getObjectAt(0))
        if (algorithm.algorithm != KEY_PROTECTOR_OID) {
            throw IOException("Unsupported key protection algorithm ${algorithm.algorithm}")
        }
        val protectedKey = ASN1OctetString.getInstance(sequence.getObjectAt(1)).octets
        if (protectedKey.size < 2 * DIGEST_LENGTH) {
            throw IOException("Malformed protected private key")
        }
        val keyLength = protectedKey.size - 2 * DIGEST_LENGTH
        val salt = protectedKey.copyOfRange(0, DIGEST_LENGTH)
        val encrypted = protectedKey.copyOfRange(DIGEST_LENGTH, DIGEST_LENGTH + keyLength)
        val expectedDigest = protectedKey.copyOfRange(DIGEST_LENGTH + keyLength, protectedKey.size)

        val passwordBytes = keyPassword.toUtf16BeBytes()
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val key = ByteArray(keyLength)
        var previous = salt
        var offset = 0
        while (offset < keyLength) {
            messageDigest.update(passwordBytes)
            messageDigest.update(previous)
            previous = messageDigest.digest()
            messageDigest.reset()
            val count = minOf(DIGEST_LENGTH, keyLength - offset)
            for (index in 0 until count) {
                key[offset + index] = (encrypted[offset + index].toInt()
                    xor previous[index].toInt()).toByte()
            }
            offset += count
        }

        messageDigest.update(passwordBytes)
        messageDigest.update(key)
        if (!messageDigest.digest().contentEquals(expectedDigest)) {
            throw IOException("Wrong key password")
        }
        return key
    }

    private fun AlgorithmIdentifier.toJcaAlgorithmName(): String =
        when (algorithm) {
            PKCSObjectIdentifiers.rsaEncryption -> "RSA"
            X9ObjectIdentifiers.id_dsa -> "DSA"
            X9ObjectIdentifiers.id_ecPublicKey -> "EC"
            else -> throw IOException("Unsupported private key algorithm $algorithm")
        }
}

/** Passwords are digested as UTF-16BE, which is how the original implementation encodes them. */
private fun CharArray.toUtf16BeBytes(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, char ->
        bytes[index * 2] = (char.code shr 8).toByte()
        bytes[index * 2 + 1] = char.code.toByte()
    }
    return bytes
}
