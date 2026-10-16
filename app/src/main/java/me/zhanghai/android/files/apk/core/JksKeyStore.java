/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Read-only adapter for the published JKS v1/v2 format. Android has no JKS private-key provider;
 * BC 1.86's JKS reader supports trusted certificates only. This implementation accepts legacy JKS
 * imports but never creates new JKS stores or uses the legacy protector for new keys.
 */
final class JksKeyStore {
    private JksKeyStore() {}

    static List<String> aliases(byte[] encoded, char[] password) throws IOException, GeneralSecurityException {
        Map<String, Entry> entries = parse(encoded, password);
        try {
            List<String> aliases = new ArrayList<>(entries.keySet());
            Collections.sort(aliases);
            return aliases;
        } finally {
            clear(entries);
        }
    }

    static SigningKeyStoreService.SigningKey load(byte[] encoded, char[] storePassword, String alias,
            char[] keyPassword) throws IOException, GeneralSecurityException {
        Map<String, Entry> entries = parse(encoded, storePassword);
        PrivateKey key = null;
        try {
            Entry entry = entries.get(alias.toLowerCase(Locale.ENGLISH));
            if (entry == null) throw new GeneralSecurityException("No private signing key exists for alias: " + alias);
            key = decrypt(entry.protectedKey, keyPassword);
            SigningKeyStoreService.SigningKey result = new SigningKeyStoreService.SigningKey(alias, key, entry.certificates);
            key = null;
            return result;
        } finally {
            SigningKeyStoreService.destroy(key);
            clear(entries);
        }
    }

    private static Map<String, Entry> parse(byte[] encoded, char[] password)
            throws IOException, GeneralSecurityException {
        if (encoded.length < 32) throw new IOException("Truncated JKS key store");
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        updatePassword(digest, password);
        digest.update("Mighty Aphrodite".getBytes(StandardCharsets.US_ASCII));
        digest.update(encoded, 0, encoded.length - 20);
        byte[] expected = Arrays.copyOfRange(encoded, encoded.length - 20, encoded.length);
        byte[] actual = digest.digest();
        boolean valid;
        try {
            valid = MessageDigest.isEqual(expected, actual);
        } finally {
            SigningKeyStoreService.clear(expected);
            SigningKeyStoreService.clear(actual);
        }
        if (!valid) throw new UnrecoverableKeyException("JKS password is incorrect or the key store has been modified");
        Map<String, Entry> entries = new LinkedHashMap<>();
        boolean success = false;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded, 0, encoded.length - 20))) {
            if (input.readInt() != 0xfeedfeed) throw new IOException("Invalid JKS magic");
            int version = input.readInt();
            if (version != 1 && version != 2) throw new IOException("Only JKS versions 1 and 2 are supported");
            int count = input.readInt();
            if (count < 0 || count > SigningKeyStoreService.MAX_ENTRIES) throw new IOException("Excessive JKS entry count");
            CertificateFactory factory = CertificateFactory.getInstance("X.509", SigningKeyStoreService.PROVIDER);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (int i = 0; i < count; ++i) {
                ApkIo.check(ApkOperationCallback.NONE);
                int kind = input.readInt();
                String alias = input.readUTF().toLowerCase(Locale.ENGLISH);
                SigningKeyStoreService.requireAlias(alias);
                if (!names.add(alias)) throw new IOException("Duplicate key store alias");
                input.readLong();
                if (kind == 1) {
                    byte[] protectedKey = readBytes(input);
                    boolean retained = false;
                    try {
                        int chainSize = input.readInt();
                        if (chainSize < 1 || chainSize > 16) throw new IOException("Unsupported JKS certificate chain size");
                        List<X509Certificate> chain = new ArrayList<>(chainSize);
                        for (int j = 0; j < chainSize; ++j) chain.add(readCertificate(input, version, factory));
                        entries.put(alias, new Entry(protectedKey, chain));
                        retained = true;
                    } finally {
                        if (!retained) SigningKeyStoreService.clear(protectedKey);
                    }
                } else if (kind == 2) {
                    readCertificate(input, version, factory);
                } else throw new IOException("Unsupported JKS entry type");
            }
            if (input.available() != 0) throw new IOException("JKS has unexpected trailing data");
            success = true;
            return entries;
        } finally {
            if (!success) clear(entries);
        }
    }

    private static X509Certificate readCertificate(DataInputStream input, int version,
            CertificateFactory factory) throws IOException, GeneralSecurityException {
        if (version == 2 && !input.readUTF().equals("X.509")) {
            throw new IOException("Only X.509 signing certificates are supported");
        }
        byte[] bytes = readBytes(input);
        try (ByteArrayInputStream certificateInput = new ByteArrayInputStream(bytes)) {
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(certificateInput);
            if (certificateInput.available() != 0) throw new IOException("Invalid JKS certificate encoding");
            return certificate;
        } finally {
            SigningKeyStoreService.clear(bytes);
        }
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 1024 * 1024 || length > input.available()) {
            throw new IOException("Invalid or excessively large JKS entry");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static PrivateKey decrypt(byte[] encoded, char[] password)
            throws IOException, GeneralSecurityException {
        EncryptedPrivateKeyInfo info;
        try {
            info = EncryptedPrivateKeyInfo.getInstance(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed JKS protected private key", exception);
        }
        if (!info.getEncryptionAlgorithm().getAlgorithm().getId().equals("1.3.6.1.4.1.42.2.17.1.1")) {
            throw new GeneralSecurityException("Unsupported JKS private key protection algorithm");
        }
        byte[] protectedKey = info.getEncryptedData();
        if (protectedKey.length <= 40) throw new IOException("Truncated JKS private key");
        byte[] plain = new byte[protectedKey.length - 40];
        byte[] round = Arrays.copyOf(protectedKey, 20);
        byte[] actual = null;
        byte[] expected = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            int offset = 0;
            while (offset < plain.length) {
                ApkIo.check(ApkOperationCallback.NONE);
                updatePassword(digest, password);
                digest.update(round);
                SigningKeyStoreService.clear(round);
                round = digest.digest();
                for (int i = 0; i < round.length && offset < plain.length; ++i, ++offset) {
                    plain[offset] = (byte) (protectedKey[20 + offset] ^ round[i]);
                }
            }
            updatePassword(digest, password);
            digest.update(plain);
            actual = digest.digest();
            expected = Arrays.copyOfRange(protectedKey, protectedKey.length - 20, protectedKey.length);
            if (!MessageDigest.isEqual(actual, expected)) throw new UnrecoverableKeyException("Incorrect private key password");
            return new JcaPEMKeyConverter().setProvider(SigningKeyStoreService.PROVIDER)
                    .getPrivateKey(PrivateKeyInfo.getInstance(plain));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed JKS private key", exception);
        } finally {
            SigningKeyStoreService.clear(plain);
            SigningKeyStoreService.clear(round);
            SigningKeyStoreService.clear(actual);
            SigningKeyStoreService.clear(expected);
            SigningKeyStoreService.clear(protectedKey);
        }
    }

    private static void updatePassword(MessageDigest digest, char[] password) {
        for (char character : password) {
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }

    private static void clear(Map<String, Entry> entries) {
        for (Entry entry : entries.values()) SigningKeyStoreService.clear(entry.protectedKey);
    }

    private static final class Entry {
        final byte[] protectedKey;
        final List<X509Certificate> certificates;
        Entry(byte[] protectedKey, List<X509Certificate> certificates) {
            this.protectedKey = protectedKey;
            this.certificates = certificates;
        }
    }
}
