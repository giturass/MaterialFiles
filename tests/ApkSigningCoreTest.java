/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkUtils;
import com.android.apksig.util.DataSources;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import me.zhanghai.android.files.apk.core.ApkCertificateInfo;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;
import me.zhanghai.android.files.apk.core.ApkVerificationService;
import me.zhanghai.android.files.apk.core.SigningKeyStoreService;
import me.zhanghai.android.files.apk.ApkManifestReader;
import org.bouncycastle.asn1.DERBMPString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBag;
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;

/**
 * Host checks against real AOSP apksig fixture APKs and the published apksig/BC artifacts.
 * These are signature, ZIP, and key-management tests, not installation or application-start tests.
 * All keys/passwords below are disposable public test data generated under the supplied work path.
 */
public final class ApkSigningCoreTest {
    private static final String PASSWORD = "disposable-test-password-123";
    private static final String PRIVATE_PASSWORD = "separate-private-password-456";
    private static final SigningKeyStoreService KEYS = new SigningKeyStoreService();
    private static final ApkSigningService SIGNER = new ApkSigningService();
    private static final ApkVerificationService VERIFIER = new ApkVerificationService();
    private static final ApkOperationCallback NONE = ApkOperationCallback.NONE;
    private static final List<String> FAILURES = new ArrayList<>();
    private static File work;
    private static File cache;
    private static File input;
    private static File generatedStore;
    private static SigningKeyStoreService.SigningKey signingKey;
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) throw new IllegalArgumentException("Pass workDirectory and AOSP fixtureDirectory");
        work = new File(arguments[0], "run-" + UUID.randomUUID()).getCanonicalFile();
        mkdir(work);
        cache = new File(work, "cache");
        mkdir(cache);
        File fixtures = new File(arguments[1]);
        input = new File(work, "原始 APK with spaces.apk");
        Files.copy(new File(fixtures, "golden-aligned-in.apk").toPath(), input.toPath());
        generatedStore = new File(work, "签名 key store.p12");
        char[] password = PASSWORD.toCharArray();
        signingKey = KEYS.generate(generatedStore, "release", password, "APK signing test", NONE);
        require(erased(password), "Generation retained the caller password");
        try {
            check("real unsigned AOSP APK has no valid signature", () -> {
                SIGNER.validateInput(input, NONE);
                ApkManifestReader metadata = ApkManifestReader.read(input);
                require(!metadata.requiresSplits && metadata.minSdk > 0 && !metadata.packageName.isEmpty(), "Standalone AOSP fixture manifest was not parsed");
                try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
                    java.nio.ByteBuffer manifest = ApkUtils.getAndroidManifest(DataSources.asDataSource(file));
                    require(metadata.packageName.equals(ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest.duplicate())), "Manifest package differs from independent parser");
                    require(metadata.versionCode == ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(manifest.duplicate()), "Manifest version differs from independent parser");
                    require(metadata.minSdk == ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.duplicate()), "Manifest SDK differs from independent parser");
                }
                require(!VERIFIER.verify(input, 23, NONE).verified, "Unsigned APK verified");
            });
            check("read and independently hash an existing AOSP signing certificate", () -> {
                File fixture = new File(fixtures, "golden-aligned-v1v2v3-out.apk");
                ApkVerificationService.Result result = VERIFIER.verify(fixture, 23, NONE);
                require(result.verified && result.schemes.containsAll(Arrays.asList("v1", "v2", "v3")), "Known good AOSP signatures did not verify: " + result.errors);
                ApkVerifier.Result independent = new ApkVerifier.Builder(fixture).setMinCheckedPlatformVersion(23).build().verify();
                require(independent.isVerified(), "Independent official verifier rejected the AOSP fixture");
                require(result.certificates.get(0).sha256Fingerprint.equals(fingerprint(independent.getSignerCertificates().get(0))), "Certificate SHA-256 differs from independent digest");
            });
            check("sign unsigned APK with v1 v2 v3 and preserve all original payloads", () -> {
                File output = sign(input, "signed all schemes.apk", ApkSigningService.Options.forMinSdk(23));
                ApkVerificationService.Result result = VERIFIER.verify(output, 23, NONE);
                require(result.verified && result.schemes.containsAll(Arrays.asList("v1", "v2", "v3")), "Missing verified schemes: " + result.schemes);
                require(result.certificates.get(0).sha256Fingerprint.equals(signingKey.certificates.get(0).sha256Fingerprint), "Wrong signing identity");
                require(payloads(input).equals(payloads(output)), "Signing changed original entry contents");
                try (RandomAccessFile in = new RandomAccessFile(input, "r"); RandomAccessFile out = new RandomAccessFile(output, "r")) {
                    require(ApkUtils.getPackageNameFromBinaryAndroidManifest(ApkUtils.getAndroidManifest(DataSources.asDataSource(in)))
                            .equals(ApkUtils.getPackageNameFromBinaryAndroidManifest(ApkUtils.getAndroidManifest(DataSources.asDataSource(out)))), "Signing changed package name");
                }
                require(new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(23).setMaxCheckedPlatformVersion(23).build().verify().isVerified(), "Output signature is not valid for Android 6.0");
                require(new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(36).setMaxCheckedPlatformVersion(36).build().verify().isVerified(), "Output signature is not valid for Android 16");
                requireAligned(output);
            });
            check("re-sign an already signed AOSP APK with a different certificate", () -> {
                File fixture = new File(fixtures, "golden-aligned-v1v2v3-out.apk");
                byte[] before = hash(fixture);
                String oldFingerprint = VERIFIER.verify(fixture, 23, NONE).certificates.get(0).sha256Fingerprint;
                File output = sign(fixture, "re-signed.apk", ApkSigningService.Options.forMinSdk(23));
                ApkVerificationService.Result result = VERIFIER.verify(output, 23, NONE);
                require(result.verified && !result.certificates.get(0).sha256Fingerprint.equals(oldFingerprint), "Re-signing retained old identity");
                require(payloads(fixture).equals(payloads(output)), "Re-signing changed payloads");
                require(Arrays.equals(before, hash(fixture)), "Re-signing changed input");
                requireAligned(output);
            });
            check("v2-only and v3-only selections verify for their selected minimum SDK", () -> {
                File v2 = sign(input, "v2.apk", new ApkSigningService.Options(24, false, true, false));
                ApkVerificationService.Result result = VERIFIER.verify(v2, 24, NONE);
                require(result.verified && result.schemes.equals(Collections.singletonList("v2")), "Unexpected v2 selection: " + result.schemes);
                File v3 = sign(input, "v3.apk", new ApkSigningService.Options(28, false, false, true));
                result = VERIFIER.verify(v3, 28, NONE);
                require(result.verified && result.schemes.equals(Collections.singletonList("v3")), "Unexpected v3 selection: " + result.schemes);
            });
            check("incompatible or empty signing scheme selections are rejected", () -> {
                expect(IllegalArgumentException.class, () -> new ApkSigningService.Options(23, false, true, true));
                expect(IllegalArgumentException.class, () -> new ApkSigningService.Options(27, false, false, true));
                expect(IllegalArgumentException.class, () -> new ApkSigningService.Options(35, false, false, false));
            });
            check("existing output and original input are never overwritten", () -> {
                File occupied = new File(work, "occupied.apk");
                Files.write(occupied.toPath(), new byte[]{11, 12, 13});
                byte[] original = hash(input);
                byte[] saved = hash(occupied);
                expect(IOException.class, () -> SIGNER.sign(input, occupied, cache, signingKey, ApkSigningService.Options.forMinSdk(23), NONE));
                expect(IOException.class, () -> SIGNER.sign(input, input, cache, signingKey, ApkSigningService.Options.forMinSdk(23), NONE));
                require(Arrays.equals(saved, hash(occupied)) && Arrays.equals(original, hash(input)), "Input or pre-existing output was changed");
                emptyCache();
            });
            check("password-protected PKCS12 imports and password arrays are cleared", () -> {
                char[] listPassword = PASSWORD.toCharArray();
                require(KEYS.aliases(generatedStore, "AUTO", listPassword).equals(Collections.singletonList("release")), "Wrong PKCS12 aliases");
                require(erased(listPassword), "Alias listing retained password");
                char[] storePassword = PASSWORD.toCharArray();
                char[] keyPassword = PASSWORD.toCharArray();
                try (SigningKeyStoreService.SigningKey imported = KEYS.load(generatedStore, "PKCS12", storePassword, "release", keyPassword)) {
                    require(imported.certificates.get(0).sha256Fingerprint.equals(signingKey.certificates.get(0).sha256Fingerprint), "PKCS12 import changed key identity");
                }
                require(erased(storePassword) && erased(keyPassword), "Import retained passwords");
                require(!new String(Files.readAllBytes(generatedStore.toPath()), StandardCharsets.ISO_8859_1).contains(PASSWORD), "PKCS12 contains plaintext password");
            });
            check("PKCS12 wrong store/key password and wrong alias fail without retaining secrets", () -> {
                for (int mode = 0; mode < 3; ++mode) {
                    char[] storePassword = (mode == 0 ? "wrong-store-password" : PASSWORD).toCharArray();
                    char[] keyPassword = (mode == 1 ? "wrong-private-password" : PASSWORD).toCharArray();
                    String alias = mode == 2 ? "missing" : "release";
                    expect(GeneralSecurityException.class, () -> KEYS.load(generatedStore, "PKCS12", storePassword, alias, keyPassword));
                    require(erased(storePassword) && erased(keyPassword), "Failed import retained a password");
                }
            });
            check("PKCS12 supports genuinely different store and private-key passwords", ApkSigningCoreTest::separatePkcs12Passwords);
            check("keytool JKS with different store/key passwords imports and signs", ApkSigningCoreTest::keytoolJks);
            check("new key store refuses weak passwords and existing destinations", () -> {
                char[] weak = "short".toCharArray();
                expect(IllegalArgumentException.class, () -> KEYS.generate(new File(work, "weak.p12"), "release", weak, "test"));
                require(erased(weak), "Weak password was retained");
                byte[] original = hash(generatedStore);
                char[] overwrite = PASSWORD.toCharArray();
                expect(IOException.class, () -> KEYS.generate(generatedStore, "release", overwrite, "test"));
                require(erased(overwrite) && Arrays.equals(original, hash(generatedStore)), "Key store overwritten or password retained");
            });
            check("closed private keys cannot be used to sign", () -> {
                SigningKeyStoreService.SigningKey key = KEYS.load(generatedStore, "AUTO", PASSWORD.toCharArray(), "release", PASSWORD.toCharArray());
                key.close();
                File output = new File(work, "closed-key.apk");
                expect(IllegalStateException.class, () -> SIGNER.sign(input, output, cache, key, ApkSigningService.Options.forMinSdk(23), NONE));
                require(!output.exists(), "Closed key created output");
                emptyCache();
            });
            check("malformed and tampered APKs fail verification", () -> {
                File damaged = new File(work, "damaged.apk");
                Files.write(damaged.toPath(), new byte[]{0, 1, 2, 3, 4});
                require(!VERIFIER.verify(damaged, 23, NONE).verified, "Malformed data verified");
                File signed = sign(input, "before-tamper.apk", ApkSigningService.Options.forMinSdk(23));
                flipEntryByte(signed, "AndroidManifest.xml");
                require(!VERIFIER.verify(signed, 23, NONE).verified, "Modified signed payload verified");
            });
            check("APK signing preserves ZIP keys while container extraction rejects unsafe paths", () -> {
                for (String path : Arrays.asList("../escape", "/absolute", "folder/../../escape", "C:/windows", "back\\slash", "res///file")) {
                    File source = new File(work, "unsafe-" + Math.abs(path.hashCode()) + ".apk");
                    derive(input, source, path, new byte[]{1, 2, 3}, false);
                    File output = new File(work, source.getName() + ".signed.apk");
                    expect(IOException.class, () -> SIGNER.validateContainer(source, NONE));
                    require(SIGNER.sign(source, output, cache, signingKey,
                            ApkSigningService.Options.forMinSdk(23), NONE).verified,
                            "APK resource ZIP key could not be signed");
                    try (ZipFile zip = new ZipFile(output)) {
                        require(zip.getEntry(path) != null, "Signing renamed the APK entry");
                        try (InputStream contents = zip.getInputStream(zip.getEntry(path))) {
                            require(Arrays.equals(contents.readAllBytes(), new byte[]{1, 2, 3}),
                                    "Signing changed the APK entry bytes");
                        }
                    }
                }
                emptyCache();
            });
            check("CRC damage is rejected before signing even when signing v2 only", () -> {
                File source = new File(work, "crc-corrupt.apk");
                derive(input, source, "assets/crc-test", new byte[]{1, 2, 3, 4, 5}, true);
                flipEntryByte(source, "assets/crc-test");
                File output = new File(work, "crc-corrupt-output.apk");
                expect(IOException.class, () -> SIGNER.sign(source, output, cache, signingKey, new ApkSigningService.Options(24, false, true, false), NONE));
                require(!output.exists(), "Corrupt payload created output");
                emptyCache();
            });
            check("native ZIP alignment is upgraded from an existing 4 KiB field to 16 KiB", () -> {
                File source = new File(work, "native alignment.apk");
                derive(input, source, "lib/arm64-v8a/libalignment.so", new byte[21817], true);
                File output = sign(source, "native aligned output.apk", ApkSigningService.Options.forMinSdk(23));
                requireAligned(output);
                require(payloads(source).equals(payloads(output)), "Alignment changed entry data");
            });
            check("cancel before processing, during alignment, signing, verification and commit", () -> {
                for (String phase : Arrays.asList("before", "inspect", "align", "sign", "verify", "save")) cancelInPhase(phase);
            });
            check("a late output collision preserves the other task's path and cleans temporary APKs", () -> {
                File output = new File(work, "write-failure.apk");
                AtomicBoolean inserted = new AtomicBoolean();
                ApkOperationCallback callback = new ApkOperationCallback() {
                    @Override public void checkCancelled() {}
                    @Override public void onProgress(String phase, long completed, long total) {
                        if (phase.equals("verify") && completed > 0 && inserted.compareAndSet(false, true)) mkdir(output);
                    }
                };
                expect(IOException.class, () -> SIGNER.sign(input, output, cache, signingKey, ApkSigningService.Options.forMinSdk(23), callback));
                require(inserted.get() && output.isDirectory(), "Output reservation test did not run");
                emptyCache();
            });
            check("large APK data is streamed without changing payloads", () -> {
                File large = new File(work, "大 APK with spaces.apk");
                deriveLarge(input, large, 64L * 1024 * 1024);
                File output = sign(large, "large signed.apk", ApkSigningService.Options.forMinSdk(23));
                require(VERIFIER.verify(output, 23, NONE).verified, "Large APK signature failed");
                require(payloads(large).equals(payloads(output)), "Large payload changed");
                emptyCache();
            });
        } finally {
            signingKey.close();
        }
        System.out.println("APK signing core: " + (checks - FAILURES.size()) + "/" + checks + " checks passed");
        System.out.println("Signature checks do not assert successful installation, launch, or app functionality.");
        if (!FAILURES.isEmpty()) throw new AssertionError("Failed checks: " + FAILURES);
    }

    private static File sign(File source, String filename, ApkSigningService.Options options) throws Exception {
        File output = new File(work, filename);
        byte[] before = hash(source);
        ApkVerificationService.Result result = SIGNER.sign(source, output, cache, signingKey, options, NONE);
        require(result.verified && output.isFile(), "Signing did not produce a verified file: " + result.errors);
        require(Arrays.equals(before, hash(source)), "Source was modified");
        emptyCache();
        return output;
    }

    private static void separatePkcs12Passwords() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream stream = new FileInputStream(generatedStore)) { source.load(stream, PASSWORD.toCharArray()); }
        PrivateKey key = (PrivateKey) source.getKey("release", PASSWORD.toCharArray());
        X509Certificate certificate = (X509Certificate) source.getCertificate("release");
        Provider provider = new BouncyCastleProvider();
        byte[] keyId = MessageDigest.getInstance("SHA-1").digest(certificate.getPublicKey().getEncoded());
        PKCS12SafeBagBuilder keyBag = new JcaPKCS12SafeBagBuilder(key,
                new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
                        .setProvider(provider).build(PRIVATE_PASSWORD.toCharArray()))
                .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("separate"))
                .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, new DEROctetString(keyId));
        PKCS12SafeBagBuilder certificateBag = new JcaPKCS12SafeBagBuilder(certificate)
                .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("separate"))
                .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, new DEROctetString(keyId));
        byte[] encoded = new PKCS12PfxPduBuilder().addData(keyBag.build())
                .addEncryptedData(new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
                        .setProvider(provider).build(PASSWORD.toCharArray()), certificateBag.build())
                .build(new JcePKCS12MacCalculatorBuilder().setProvider(provider), PASSWORD.toCharArray()).getEncoded();
        File file = new File(work, "separate-passwords.p12");
        Files.write(file.toPath(), encoded);
        try (SigningKeyStoreService.SigningKey imported = KEYS.load(file, "PKCS12", PASSWORD.toCharArray(), "separate", PRIVATE_PASSWORD.toCharArray())) {
            require(imported.certificates.get(0).sha256Fingerprint.equals(fingerprint(certificate)), "Separate-password PKCS12 certificate mismatch");
            require(SIGNER.sign(input, new File(work, "separate-p12-signed.apk"), cache, imported, ApkSigningService.Options.forMinSdk(23), NONE).verified, "Separate-password PKCS12 could not sign");
        }
        expect(GeneralSecurityException.class, () -> KEYS.load(file, "PKCS12", PASSWORD.toCharArray(), "separate", PASSWORD.toCharArray()));
        emptyCache();
    }

    private static void keytoolJks() throws Exception {
        File file = new File(work, "external keytool.jks");
        List<String> command = Arrays.asList("keytool", "-genkeypair", "-alias", "imported", "-keystore", file.toString(),
                "-storetype", "JKS", "-storepass:env", "APK_TEST_STORE_PASSWORD", "-keypass:env", "APK_TEST_KEY_PASSWORD",
                "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "3650", "-dname", "CN=Disposable APK signing test", "-noprompt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("APK_TEST_STORE_PASSWORD", PASSWORD);
        builder.environment().put("APK_TEST_KEY_PASSWORD", PRIVATE_PASSWORD);
        Process process = builder.start();
        byte[] diagnostic;
        try (InputStream stream = process.getInputStream()) { diagnostic = stream.readAllBytes(); }
        require(process.waitFor() == 0, "keytool fixture generation failed: " + new String(diagnostic, StandardCharsets.UTF_8));
        require(KEYS.aliases(file, "AUTO", PASSWORD.toCharArray()).equals(Collections.singletonList("imported")), "JKS alias listing failed");
        KeyStore independent = KeyStore.getInstance("JKS");
        try (InputStream stream = new FileInputStream(file)) { independent.load(stream, PASSWORD.toCharArray()); }
        X509Certificate certificate = (X509Certificate) independent.getCertificate("imported");
        try (SigningKeyStoreService.SigningKey imported = KEYS.load(file, "JKS", PASSWORD.toCharArray(), "IMPORTED", PRIVATE_PASSWORD.toCharArray())) {
            require(imported.certificates.get(0).sha256Fingerprint.equals(fingerprint(certificate)), "JKS certificate disagrees with JDK provider");
            File signed = new File(work, "jks signed.apk");
            require(SIGNER.sign(input, signed, cache, imported, ApkSigningService.Options.forMinSdk(23), NONE).verified, "JKS key could not sign");
        }
        expect(GeneralSecurityException.class, () -> KEYS.load(file, "JKS", "incorrect".toCharArray(), "imported", PRIVATE_PASSWORD.toCharArray()));
        expect(GeneralSecurityException.class, () -> KEYS.load(file, "JKS", PASSWORD.toCharArray(), "imported", "incorrect".toCharArray()));
        expect(GeneralSecurityException.class, () -> KEYS.load(file, "JKS", PASSWORD.toCharArray(), "missing", PRIVATE_PASSWORD.toCharArray()));
        emptyCache();
    }

    private static void cancelInPhase(String phase) throws Exception {
        AtomicBoolean reached = new AtomicBoolean(phase.equals("before"));
        File output = new File(work, "cancel-" + phase + ".apk");
        byte[] before = hash(input);
        ApkOperationCallback callback = new ApkOperationCallback() {
            @Override public void checkCancelled() throws InterruptedIOException {
                if (reached.get()) throw new InterruptedIOException("Injected user cancellation");
            }
            @Override public void onProgress(String current, long completed, long total) {
                if (current.equals(phase) && (completed > 0 || phase.equals("verify"))) reached.set(true);
            }
        };
        expect(InterruptedIOException.class, () -> SIGNER.sign(input, output, cache, signingKey, ApkSigningService.Options.forMinSdk(23), callback));
        require(reached.get() && !output.exists(), "Cancellation left output or was never exercised: " + phase);
        require(Arrays.equals(before, hash(input)), "Cancellation changed original APK");
        emptyCache();
    }

    private static void derive(File source, File destination, String extraName, byte[] extraData, boolean stored) throws IOException {
        try (ZipFile zip = new ZipFile(source); ZipOutputStream output = new ZipOutputStream(new FileOutputStream(destination))) {
            copyEntries(zip, output);
            ZipEntry entry = new ZipEntry(extraName);
            if (stored) {
                CRC32 crc = new CRC32();
                crc.update(extraData);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(extraData.length);
                entry.setCompressedSize(extraData.length);
                entry.setCrc(crc.getValue());
                if (extraName.endsWith(".so")) entry.setExtra(new byte[]{0x35, (byte) 0xd9, 2, 0, 0, 0x10});
            }
            output.putNextEntry(entry);
            output.write(extraData);
            output.closeEntry();
        }
    }

    private static void deriveLarge(File source, File destination, long size) throws IOException {
        try (ZipFile zip = new ZipFile(source); ZipOutputStream output = new ZipOutputStream(new FileOutputStream(destination))) {
            copyEntries(zip, output);
            output.putNextEntry(new ZipEntry("assets/large random payload.bin"));
            byte[] buffer = new byte[64 * 1024];
            Random random = new Random(127);
            long remaining = size;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int count = (int) Math.min(remaining, buffer.length);
                output.write(buffer, 0, count);
                remaining -= count;
            }
            output.closeEntry();
        }
    }

    private static void copyEntries(ZipFile source, ZipOutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        Enumeration<? extends ZipEntry> entries = source.entries();
        while (entries.hasMoreElements()) {
            ZipEntry sourceEntry = entries.nextElement();
            if (signatureEntry(sourceEntry.getName())) continue;
            ZipEntry entry = new ZipEntry(sourceEntry.getName());
            entry.setMethod(sourceEntry.getMethod());
            if (entry.getMethod() == ZipEntry.STORED) {
                entry.setSize(sourceEntry.getSize());
                entry.setCompressedSize(sourceEntry.getSize());
                entry.setCrc(sourceEntry.getCrc());
            }
            output.putNextEntry(entry);
            try (InputStream input = source.getInputStream(sourceEntry)) {
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            output.closeEntry();
        }
    }

    private static Map<String, String> payloads(File file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (signatureEntry(entry.getName())) continue;
                try (InputStream input = zip.getInputStream(entry)) { result.put(entry.getName(), hex(hash(input))); }
            }
        }
        return result;
    }

    private static boolean signatureEntry(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.equals("META-INF/") || upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static void requireAligned(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            while (true) {
                long offset = input.getFilePointer();
                byte[] header = new byte[30];
                input.readFully(header);
                if (u32(header, 0) != 0x04034b50L) break;
                int nameLength = u16(header, 26);
                int extraLength = u16(header, 28);
                byte[] name = new byte[nameLength];
                input.readFully(name);
                String path = new String(name, StandardCharsets.UTF_8);
                long dataOffset = offset + 30 + nameLength + extraLength;
                if (u16(header, 8) == ZipEntry.STORED) require(dataOffset % (path.endsWith(".so") ? 16384 : 4) == 0, "Misaligned entry: " + path + " at " + dataOffset);
                require((u16(header, 6) & 8) == 0, "Unexpected data descriptor in canonical output");
                input.seek(dataOffset + u32(header, 18));
            }
        }
    }

    private static void flipEntryByte(File file, String wanted) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "rw")) {
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    long offset = input.getFilePointer();
                    byte[] header = new byte[30];
                    input.readFully(header);
                    if (u32(header, 0) != 0x04034b50L) throw new IOException("Fixture entry layout unsupported");
                    byte[] name = new byte[u16(header, 26)];
                    input.readFully(name);
                    String path = new String(name, StandardCharsets.UTF_8);
                    long dataOffset = offset + 30 + name.length + u16(header, 28);
                    long compressed = zip.getEntry(path).getCompressedSize();
                    if (path.equals(wanted)) {
                        input.seek(dataOffset + compressed / 2);
                        int value = input.read();
                        input.seek(dataOffset + compressed / 2);
                        input.write(value ^ 1);
                        return;
                    }
                    input.seek(dataOffset + compressed + (((u16(header, 6) & 8) != 0) ? 16 : 0));
                }
            }
        }
        throw new IOException("Missing fixture entry " + wanted);
    }

    private static long u32(byte[] data, int offset) { return u16(data, offset) | ((long) u16(data, offset + 2) << 16); }
    private static int u16(byte[] data, int offset) { return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }

    private static byte[] hash(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return hash(input); }
    }

    private static byte[] hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        return digest.digest();
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        String hex = hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i != 0) result.append(':');
            result.append(hex, i, i + 2);
        }
        return result.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 255));
        return result.toString();
    }

    private static boolean erased(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static void emptyCache() {
        String[] names = cache.list();
        require(names != null && names.length == 0, "Temporary APKs leaked: " + Arrays.toString(names));
    }

    private static void mkdir(File directory) { require(directory.isDirectory() || directory.mkdirs(), "Cannot create test directory: " + directory); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void expect(Class<? extends Throwable> expected, CheckedRunnable runnable) throws Exception {
        try { runnable.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Expected " + expected.getName() + " but received " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    private static void check(String name, CheckedRunnable runnable) {
        ++checks;
        try {
            runnable.run();
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            FAILURES.add(name);
            System.out.println("FAIL " + name);
            failure.printStackTrace(System.out);
        } finally {
            Thread.interrupted();
        }
    }

    private interface CheckedRunnable { void run() throws Exception; }
}
