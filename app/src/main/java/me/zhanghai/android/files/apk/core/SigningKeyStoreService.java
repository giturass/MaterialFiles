/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.security.auth.DestroyFailedException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jcajce.provider.keystore.pkcs12.PKCS12KeyStoreSpi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Password arguments are consumed and cleared on every exit, including validation failures.
 * Keys are never cached globally; close the returned SigningKey as soon as the operation finishes.
 */
public final class SigningKeyStoreService {
    static final int MAX_STORE_SIZE = 16 * 1024 * 1024;
    static final int MAX_ENTRIES = 1024;
    // An instance is passed explicitly. Do not replace Android's process-wide crypto providers.
    static final Provider PROVIDER = new BouncyCastleProvider();

    public List<String> aliases(File file, String format, char[] storePassword)
            throws IOException, GeneralSecurityException {
        byte[] encoded = null;
        try {
            requirePassword(storePassword);
            ApkIo.check(ApkOperationCallback.NONE);
            encoded = readStore(file);
            if (isJks(encoded, format)) return JksKeyStore.aliases(encoded, storePassword);
            return Pkcs12KeyStore.aliases(encoded, storePassword);
        } finally {
            clear(storePassword);
            clear(encoded);
        }
    }

    public SigningKey load(File file, String format, char[] storePassword, String alias,
            char[] keyPassword) throws IOException, GeneralSecurityException {
        byte[] encoded = null;
        try {
            requirePassword(storePassword);
            requirePassword(keyPassword);
            requireAlias(alias);
            ApkIo.check(ApkOperationCallback.NONE);
            encoded = readStore(file);
            SigningKey key = isJks(encoded, format)
                    ? JksKeyStore.load(encoded, storePassword, alias, keyPassword)
                    : Pkcs12KeyStore.load(encoded, storePassword, alias, keyPassword);
            try {
                ApkIo.check(ApkOperationCallback.NONE);
                return key;
            } catch (IOException | RuntimeException | Error exception) {
                key.close();
                throw exception;
            }
        } finally {
            clear(storePassword);
            clear(keyPassword);
            clear(encoded);
        }
    }

    public SigningKey generate(File output, String alias, char[] password, String commonName)
            throws IOException, GeneralSecurityException {
        return generate(output, alias, password, commonName, ApkOperationCallback.NONE);
    }

    /** Generates RSA 3072, SHA-256, a 25-year certificate, and an AES-protected PKCS#12 key store. */
    public SigningKey generate(File output, String alias, char[] password, String commonName,
            ApkOperationCallback callback) throws IOException, GeneralSecurityException {
        boolean created = false;
        boolean complete = false;
        PrivateKey privateKey = null;
        Throwable failure = null;
        callback = ApkIo.callback(callback);
        try {
            requirePassword(password);
            if (password.length < 8) throw new IllegalArgumentException("Use at least 8 characters for a new key store password");
            requireAlias(alias);
            if (commonName == null || commonName.trim().isEmpty() || commonName.length() > 256
                    || commonName.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Enter a certificate owner name of 1 to 256 characters");
            }
            output = ApkIo.newOutput(output, null);
            ApkIo.check(callback);
            callback.onProgress("key", 0, -1);
            SecureRandom random = new SecureRandom();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", PROVIDER);
            generator.initialize(3072, random);
            KeyPair pair = generator.generateKeyPair();
            privateKey = pair.getPrivate();
            ApkIo.check(callback);
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build();
            long now = System.currentTimeMillis();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    new BigInteger(159, random).add(BigInteger.ONE),
                    new Date(now - 24L * 60 * 60 * 1000),
                    new Date(now + 25L * 366 * 24 * 60 * 60 * 1000), subject, pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(PROVIDER)
                    .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider(PROVIDER).build(privateKey)));
            certificate.verify(pair.getPublic(), PROVIDER);
            // Use the same provider for key generation and the encrypted PKCS12 representation.
            KeyStore store = new ProtectedPkcs12Store();
            store.load(null, password);
            store.setKeyEntry(alias, privateKey, password, new Certificate[]{certificate});
            ApkIo.check(callback);
            ApkIo.createPrivate(output);
            created = true;
            try (FileOutputStream stream = new FileOutputStream(output)) {
                store.store(stream, password);
                stream.getFD().sync();
            }
            ApkIo.check(callback);
            callback.onProgress("key", 1, 1);
            SigningKey key = new SigningKey(alias, privateKey, Collections.singletonList(certificate));
            privateKey = null;
            complete = true;
            return key;
        } catch (OperatorCreationException exception) {
            GeneralSecurityException wrapped = new GeneralSecurityException("Cannot generate a signing certificate", exception);
            failure = wrapped;
            throw wrapped;
        } catch (IOException | GeneralSecurityException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            clear(password);
            destroy(privateKey);
            if (created && !complete) {
                IOException cleanup = ApkIo.delete(output, failure);
                if (failure == null && cleanup != null) throw cleanup;
            }
        }
    }

    private static boolean isJks(byte[] bytes, String format) throws IOException {
        String type = format == null ? "AUTO" : format.toUpperCase(Locale.ROOT);
        boolean jks = bytes.length >= 4 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xed
                && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xed;
        if (type.equals("AUTO")) return jks;
        if (type.equals("JKS")) {
            if (!jks) throw new IOException("The selected file is not a JKS key store");
            return true;
        }
        if (type.equals("PKCS12") || type.equals("PKCS#12") || type.equals("P12") || type.equals("PFX")) {
            if (jks) throw new IOException("The selected file is JKS, not PKCS#12");
            return false;
        }
        throw new IOException("Unsupported key store format; select PKCS12 or JKS");
    }

    private static byte[] readStore(File file) throws IOException {
        file = ApkIo.input(file);
        long size = file.length();
        if (size < 4 || size > MAX_STORE_SIZE) throw new IOException("Key store size must be between 4 bytes and 16 MiB");
        byte[] bytes = new byte[(int) size];
        boolean success = false;
        try (FileInputStream input = new FileInputStream(file)) {
            int position = 0;
            while (position < bytes.length) {
                ApkIo.check(ApkOperationCallback.NONE);
                int count = input.read(bytes, position, Math.min(ApkIo.BUFFER_SIZE, bytes.length - position));
                if (count < 0) throw new IOException("Key store changed while being read");
                position += count;
            }
            if (input.read() != -1) throw new IOException("Key store changed while being read");
            success = true;
            return bytes;
        } finally {
            if (!success) clear(bytes);
        }
    }

    static void requireAlias(String alias) {
        if (alias == null || alias.trim().isEmpty() || alias.length() > 256 || alias.indexOf('\0') != -1) {
            throw new IllegalArgumentException("Enter a key alias of 1 to 256 characters");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length > 4096) throw new IllegalArgumentException("Invalid key store password");
    }

    static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    static void clear(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }

    static void destroy(PrivateKey key) {
        if (key == null) return;
        try {
            key.destroy();
        } catch (DestroyFailedException | UnsupportedOperationException ignored) {
            // Some providers cannot erase their immutable key objects. Drop the reference as well.
        }
    }

    static void requireMatchingKey(PrivateKey key, X509Certificate certificate)
            throws GeneralSecurityException {
        String algorithm = key.getAlgorithm();
        String signatureAlgorithm;
        if (algorithm.equalsIgnoreCase("RSA")) signatureAlgorithm = "SHA256withRSA";
        else if (algorithm.equalsIgnoreCase("EC") || algorithm.equalsIgnoreCase("ECDSA")) signatureAlgorithm = "SHA256withECDSA";
        else if (algorithm.equalsIgnoreCase("DSA")) signatureAlgorithm = "SHA256withDSA";
        else throw new GeneralSecurityException("APK signing does not support this private key algorithm: " + algorithm);
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        byte[] signed = null;
        try {
            Signature signature = Signature.getInstance(signatureAlgorithm, PROVIDER);
            signature.initSign(key);
            signature.update(challenge);
            signed = signature.sign();
            signature.initVerify(certificate.getPublicKey());
            signature.update(challenge);
            if (!signature.verify(signed)) throw new GeneralSecurityException("The signing private key does not match its certificate");
        } finally {
            clear(challenge);
            clear(signed);
        }
    }

    public static final class SigningKey implements AutoCloseable {
        public final String alias;
        public final List<ApkCertificateInfo> certificates;
        private PrivateKey key;
        private final List<X509Certificate> chain;

        SigningKey(String alias, PrivateKey key, List<X509Certificate> chain) throws GeneralSecurityException {
            if (chain.isEmpty()) throw new GeneralSecurityException("Signing key has no X.509 certificate");
            requireMatchingKey(key, chain.get(0));
            this.alias = alias;
            this.key = key;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
            List<ApkCertificateInfo> infos = new ArrayList<>();
            for (X509Certificate certificate : chain) infos.add(new ApkCertificateInfo(certificate));
            certificates = Collections.unmodifiableList(infos);
        }

        synchronized PrivateKey privateKey() {
            if (key == null) throw new IllegalStateException("Signing key has already been closed");
            return key;
        }

        List<X509Certificate> certificateChain() { return chain; }

        @Override public synchronized void close() {
            destroy(key);
            key = null;
        }
    }

    private static final class ProtectedPkcs12Store extends KeyStore {
        ProtectedPkcs12Store() {
            super(new PKCS12KeyStoreSpi.BCPKCS12KeyStoreAES256(), PROVIDER, "PKCS12");
        }
    }
}
