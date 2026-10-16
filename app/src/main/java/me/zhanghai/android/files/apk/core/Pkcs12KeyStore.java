/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.EncryptedData;
import org.bouncycastle.asn1.pkcs.MacData;
import org.bouncycastle.asn1.pkcs.PBES2Parameters;
import org.bouncycastle.asn1.pkcs.PBKDF2Params;
import org.bouncycastle.asn1.pkcs.PKCS12PBEParams;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.pkcs.PKCS12PfxPdu;
import org.bouncycastle.pkcs.PKCS12SafeBag;
import org.bouncycastle.pkcs.PKCS12SafeBagFactory;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilderProvider;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;

/** Supports separate store and private-key passwords without depending on Android's KeyStore SPI. */
final class Pkcs12KeyStore {
    private static final BigInteger MAX_ITERATIONS = BigInteger.valueOf(2_000_000);

    private Pkcs12KeyStore() {}

    static List<String> aliases(byte[] encoded, char[] password) throws IOException, GeneralSecurityException {
        Material material = parse(encoded, password);
        List<String> result = new ArrayList<>();
        for (KeyBag bag : material.keys.values()) result.add(bag.alias);
        Collections.sort(result);
        return result;
    }

    static SigningKeyStoreService.SigningKey load(byte[] encoded, char[] storePassword, String alias,
            char[] keyPassword) throws IOException, GeneralSecurityException {
        Material material = parse(encoded, storePassword);
        KeyBag bag = material.keys.get(alias.toLowerCase(Locale.ENGLISH));
        if (bag == null) throw new GeneralSecurityException("No private signing key exists for alias: " + alias);
        PrivateKey key = null;
        try {
            ApkIo.check(ApkOperationCallback.NONE);
            PrivateKeyInfo privateKeyInfo;
            if (bag.value instanceof PKCS8EncryptedPrivateKeyInfo) {
                PKCS8EncryptedPrivateKeyInfo encrypted = (PKCS8EncryptedPrivateKeyInfo) bag.value;
                checkEncryption(encrypted.getEncryptionAlgorithm());
                privateKeyInfo = encrypted.decryptPrivateKeyInfo(new JcePKCSPBEInputDecryptorProviderBuilder()
                        .setProvider(SigningKeyStoreService.PROVIDER).build(keyPassword));
            } else {
                privateKeyInfo = (PrivateKeyInfo) bag.value;
            }
            key = new JcaPEMKeyConverter().setProvider(SigningKeyStoreService.PROVIDER).getPrivateKey(privateKeyInfo);
            ApkIo.check(ApkOperationCallback.NONE);
            X509Certificate leaf = findCertificate(bag, material.certificates);
            List<X509Certificate> chain = buildChain(leaf, material.certificates);
            SigningKeyStoreService.SigningKey result = new SigningKeyStoreService.SigningKey(bag.alias, key, chain);
            key = null;
            return result;
        } catch (PKCSException exception) {
            throw new GeneralSecurityException("Incorrect private key password or unsupported PKCS12 key encryption", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed PKCS12 private key", exception);
        } finally {
            SigningKeyStoreService.destroy(key);
        }
    }

    private static Material parse(byte[] encoded, char[] password) throws IOException, GeneralSecurityException {
        try {
            ApkIo.check(ApkOperationCallback.NONE);
            PKCS12PfxPdu pfx = new PKCS12PfxPdu(encoded);
            if (!pfx.hasMac()) throw new GeneralSecurityException("PKCS12 key store has no integrity protection");
            MacData mac = pfx.toASN1Structure().getMacData();
            checkIterations(mac.getIterationCount());
            if (mac.getSalt().length > 1024) throw new IOException("Excessive PKCS12 MAC salt length");
            if (!pfx.isMacValid(new JcePKCS12MacCalculatorBuilderProvider()
                    .setProvider(SigningKeyStoreService.PROVIDER), password)) {
                throw new UnrecoverableKeyException("PKCS12 password is incorrect or the key store has been modified");
            }
            ApkIo.check(ApkOperationCallback.NONE);
            Material result = new Material();
            ContentInfo[] contents = pfx.getContentInfos();
            if (contents.length > SigningKeyStoreService.MAX_ENTRIES) throw new IOException("Too many PKCS12 contents");
            int bagCount = 0;
            for (ContentInfo content : contents) {
                ApkIo.check(ApkOperationCallback.NONE);
                PKCS12SafeBagFactory factory;
                if (content.getContentType().equals(PKCSObjectIdentifiers.data)) {
                    factory = new PKCS12SafeBagFactory(content);
                } else if (content.getContentType().equals(PKCSObjectIdentifiers.encryptedData)) {
                    checkEncryption(EncryptedData.getInstance(content.getContent()).getEncryptionAlgorithm());
                    factory = new PKCS12SafeBagFactory(content, new JcePKCSPBEInputDecryptorProviderBuilder()
                            .setProvider(SigningKeyStoreService.PROVIDER).build(password));
                } else throw new GeneralSecurityException("Unsupported PKCS12 authenticated-safe type");
                for (PKCS12SafeBag bag : factory.getSafeBags()) {
                    ApkIo.check(ApkOperationCallback.NONE);
                    if (++bagCount > SigningKeyStoreService.MAX_ENTRIES) throw new IOException("Too many PKCS12 entries");
                    Attributes attributes = attributes(bag);
                    if (bag.getType().equals(PKCSObjectIdentifiers.pkcs8ShroudedKeyBag)
                            || bag.getType().equals(PKCSObjectIdentifiers.keyBag)) {
                        String name = attributes.alias;
                        if (name == null) name = "key-" + (result.keys.size() + 1);
                        SigningKeyStoreService.requireAlias(name);
                        String canonical = name.toLowerCase(Locale.ENGLISH);
                        if (result.keys.containsKey(canonical)) throw new IOException("Duplicate PKCS12 signing alias");
                        Object value = bag.getBagValue();
                        if (!(value instanceof PKCS8EncryptedPrivateKeyInfo) && !(value instanceof PrivateKeyInfo)) {
                            throw new IOException("Unsupported PKCS12 private key entry");
                        }
                        result.keys.put(canonical, new KeyBag(name, attributes.localId, value));
                    } else if (bag.getType().equals(PKCSObjectIdentifiers.certBag)) {
                        Object value = bag.getBagValue();
                        if (!(value instanceof X509CertificateHolder)) throw new IOException("Only X.509 PKCS12 certificates are supported");
                        X509Certificate certificate = new JcaX509CertificateConverter()
                                .setProvider(SigningKeyStoreService.PROVIDER).getCertificate((X509CertificateHolder) value);
                        result.certificates.add(new CertificateBag(attributes.alias, attributes.localId, certificate));
                    } else if (bag.getType().equals(PKCSObjectIdentifiers.safeContentsBag)) {
                        throw new IOException("Nested PKCS12 safe bags are not supported");
                    }
                }
            }
            return result;
        } catch (PKCSException exception) {
            throw new GeneralSecurityException("Incorrect PKCS12 password or unsupported key store encryption", exception);
        } catch (IllegalArgumentException | ClassCastException exception) {
            throw new IOException("Malformed or unsupported PKCS12 key store", exception);
        }
    }

    private static void checkEncryption(AlgorithmIdentifier algorithm) throws IOException {
        if (algorithm.getAlgorithm().on(PKCSObjectIdentifiers.pkcs_12PbeIds)) {
            PKCS12PBEParams parameters = PKCS12PBEParams.getInstance(algorithm.getParameters());
            checkIterations(parameters.getIterations());
            if (parameters.getIV().length > 1024) throw new IOException("Excessive PKCS12 salt length");
        } else if (algorithm.getAlgorithm().equals(PKCSObjectIdentifiers.id_PBES2)) {
            PBES2Parameters parameters = PBES2Parameters.getInstance(algorithm.getParameters());
            if (!parameters.getKeyDerivationFunc().getAlgorithm().equals(PKCSObjectIdentifiers.id_PBKDF2)) {
                throw new IOException("Only PBKDF2-based PKCS12 PBES2 encryption is supported");
            }
            PBKDF2Params kdf = PBKDF2Params.getInstance(parameters.getKeyDerivationFunc().getParameters());
            checkIterations(kdf.getIterationCount());
            if (kdf.getSalt().length > 1024 || (kdf.getKeyLength() != null
                    && (kdf.getKeyLength().signum() <= 0 || kdf.getKeyLength().compareTo(BigInteger.valueOf(64)) > 0))) {
                throw new IOException("PKCS12 key derivation parameters exceed safety limits");
            }
        } else throw new IOException("Unsupported PKCS12 password-based encryption algorithm");
    }

    private static void checkIterations(BigInteger iterations) throws IOException {
        if (iterations.signum() <= 0 || iterations.compareTo(MAX_ITERATIONS) > 0) {
            throw new IOException("PKCS12 iteration count exceeds the supported safety limit (2,000,000)");
        }
    }

    private static Attributes attributes(PKCS12SafeBag bag) throws IOException {
        String name = null;
        byte[] localId = null;
        Attribute[] attributes = bag.getAttributes();
        if (attributes == null) return new Attributes(null, null);
        for (Attribute attribute : attributes) {
            if (attribute.getAttrValues().size() != 1) throw new IOException("Ambiguous PKCS12 bag attribute");
            if (attribute.getAttrType().equals(PKCS12SafeBag.friendlyNameAttribute)) {
                if (name != null) throw new IOException("Duplicate PKCS12 friendly name");
                Object value = attribute.getAttrValues().getObjectAt(0);
                if (!(value instanceof ASN1String)) throw new IOException("Invalid PKCS12 friendly name");
                name = ((ASN1String) value).getString();
            } else if (attribute.getAttrType().equals(PKCS12SafeBag.localKeyIdAttribute)) {
                if (localId != null) throw new IOException("Duplicate PKCS12 local key identifier");
                localId = ASN1OctetString.getInstance(attribute.getAttrValues().getObjectAt(0)).getOctets();
                if (localId.length > 1024) throw new IOException("Excessive PKCS12 local key identifier");
            }
        }
        return new Attributes(name, localId);
    }

    private static X509Certificate findCertificate(KeyBag key, List<CertificateBag> certificates)
            throws GeneralSecurityException {
        X509Certificate selected = null;
        for (CertificateBag certificate : certificates) {
            boolean matches = key.localId != null && Arrays.equals(key.localId, certificate.localId);
            if (!matches && certificate.alias != null) matches = key.alias.equalsIgnoreCase(certificate.alias);
            if (matches) {
                if (selected != null && !selected.equals(certificate.certificate)) {
                    throw new GeneralSecurityException("PKCS12 alias is associated with more than one certificate");
                }
                selected = certificate.certificate;
            }
        }
        if (selected == null && certificates.size() == 1) selected = certificates.get(0).certificate;
        if (selected == null) throw new GeneralSecurityException("Cannot identify the X.509 certificate for this PKCS12 key");
        return selected;
    }

    private static List<X509Certificate> buildChain(X509Certificate leaf, List<CertificateBag> certificates) {
        List<X509Certificate> chain = new ArrayList<>();
        X509Certificate current = leaf;
        for (int depth = 0; depth < 16 && current != null && !chain.contains(current); ++depth) {
            chain.add(current);
            if (current.getIssuerX500Principal().equals(current.getSubjectX500Principal())) break;
            X509Certificate parent = null;
            for (CertificateBag candidate : certificates) {
                if (!candidate.certificate.getSubjectX500Principal().equals(current.getIssuerX500Principal())) continue;
                try {
                    // These certificates came from BC's certificate factory. Its API-1 verify
                    // overload selects the BC helper without the API-24 Provider overload.
                    current.verify(candidate.certificate.getPublicKey());
                    parent = candidate.certificate;
                    break;
                } catch (GeneralSecurityException ignored) {
                    // A same-name certificate is not necessarily this certificate's issuer.
                }
            }
            current = parent;
        }
        return chain;
    }

    private static final class Material {
        final Map<String, KeyBag> keys = new LinkedHashMap<>();
        final List<CertificateBag> certificates = new ArrayList<>();
    }

    private static final class Attributes {
        final String alias;
        final byte[] localId;
        Attributes(String alias, byte[] localId) { this.alias = alias; this.localId = localId; }
    }

    private static final class KeyBag {
        final String alias;
        final byte[] localId;
        final Object value;
        KeyBag(String alias, byte[] localId, Object value) {
            this.alias = alias;
            this.localId = localId;
            this.value = value;
        }
    }

    private static final class CertificateBag {
        final String alias;
        final byte[] localId;
        final X509Certificate certificate;
        CertificateBag(String alias, byte[] localId, X509Certificate certificate) {
            this.alias = alias;
            this.localId = localId;
            this.certificate = certificate;
        }
    }
}
