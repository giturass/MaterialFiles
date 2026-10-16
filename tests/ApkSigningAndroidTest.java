/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.os.Build;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import me.zhanghai.android.files.apk.ApkManifestReader;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;
import me.zhanghai.android.files.apk.core.ApkVerificationService;
import me.zhanghai.android.files.apk.core.SigningKeyStoreService;

/**
 * Real ART checks. The runner either loads the actual app APK, or explicitly marks a source-DEX
 * preliminary run. No Android API or signing implementation is mocked. These checks do not install
 * or launch an application and do not exercise a UI, APK cloning, or an Android 6.0 device.
 * Every password/key used here is disposable public test data.
 */
public final class ApkSigningAndroidTest {
    private static final String PASSWORD = "disposable-art-password-123";
    private static final String ALIAS = "测试签名";
    private static final SigningKeyStoreService KEYS = new SigningKeyStoreService();
    private static final ApkSigningService SIGNER = new ApkSigningService();
    private static final ApkVerificationService VERIFIER = new ApkVerificationService();
    private static final ApkOperationCallback NONE = ApkOperationCallback.NONE;
    private static final List<String> FAILURES = new ArrayList<>();
    private static File work;
    private static File cache;
    private static File input;
    private static SigningKeyStoreService.SigningKey key;
    private static int checks;

    public static void main(String[] arguments) {
        // app_process normally routes an uncaught Java exception to logcat instead of stderr.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            run(arguments);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void run(String[] arguments) throws Exception {
        require(arguments.length == 4, "Pass work, fixtures, optional JKS, and optional APK paths");
        require("Dalvik".equals(System.getProperty("java.vm.name")), "This test requires Android ART");
        System.out.println("ANDROID_RUNTIME api=" + Build.VERSION.SDK_INT + " release="
                + Build.VERSION.RELEASE + " vm=" + System.getProperty("java.vm.name"));
        work = new File(arguments[0], "中文 路径");
        mkdir(work);
        cache = new File(work, "private cache");
        mkdir(cache);
        File fixtures = new File(arguments[1]);
        input = new File(work, "原始 APK with spaces.apk");
        copy(new File(fixtures, "golden-aligned-in.apk"), input);
        File signedFixture = new File(fixtures, "golden-aligned-v1v2v3-out.apk");

        check("unsigned APK and its real binary Manifest are readable on ART", () -> {
            SIGNER.validateInput(input, NONE);
            ApkManifestReader manifest = ApkManifestReader.read(input);
            require(!manifest.requiresSplits && !manifest.packageName.isEmpty(), "Invalid fixture Manifest");
            ApkVerificationService.Result result = VERIFIER.verify(input, 23, NONE);
            require(!result.verified && !result.errors.isEmpty(), "Unsigned fixture was accepted");
        });
        check("known AOSP v1/v2/v3 signatures and certificate SHA-256 verify on ART", () -> {
            ApkVerificationService.Result result = VERIFIER.verify(signedFixture, 23, NONE);
            require(result.verified && result.schemes.containsAll(Arrays.asList("v1", "v2", "v3")),
                    "Known signatures failed: " + result.errors);
            ApkVerifier.Result direct = new ApkVerifier.Builder(signedFixture)
                    .setMinCheckedPlatformVersion(23).build().verify();
            require(direct.isVerified(), "Direct official verifier rejected the fixture");
            require(result.certificates.get(0).sha256Fingerprint.equals(
                    fingerprint(direct.getSignerCertificates().get(0))), "Certificate digest mismatch");
        });

        File store = new File(work, "私有 signing key.p12");
        check("generate encrypted PKCS12 with a Unicode alias and erase the password", () -> {
            char[] password = PASSWORD.toCharArray();
            key = KEYS.generate(store, ALIAS, password, "Android 签名测试", NONE);
            require(erased(password) && store.isFile(), "Generation did not erase password/create PKCS12");
            require(key.certificates.get(0).subject.contains("Android"), "Missing generated certificate");
        });
        require(key != null, "Cannot continue without the generated test key");
        try {
            check("read aliases and load PKCS12 using actual Android crypto providers", () -> {
                char[] password = PASSWORD.toCharArray();
                require(KEYS.aliases(store, "AUTO", password).equals(Collections.singletonList(ALIAS)),
                        "Unicode key alias was not preserved");
                require(erased(password), "Alias enumeration retained its password");
                char[] storePassword = PASSWORD.toCharArray();
                char[] privatePassword = PASSWORD.toCharArray();
                try (SigningKeyStoreService.SigningKey loaded = KEYS.load(
                        store, "PKCS12", storePassword, ALIAS, privatePassword)) {
                    require(loaded.certificates.get(0).sha256Fingerprint.equals(
                            key.certificates.get(0).sha256Fingerprint), "Import changed signing identity");
                }
                require(erased(storePassword) && erased(privatePassword), "Key load retained passwords");
            });
            check("sign an unsigned APK at a Chinese/space path and preserve the input", () -> {
                File output = sign(input, "签名结果 with spaces.apk", ApkSigningService.Options.forMinSdk(23));
                ApkVerificationService.Result result = VERIFIER.verify(output, 23, NONE);
                require(result.verified && result.schemes.containsAll(Arrays.asList("v1", "v2", "v3")),
                        "Not all requested signatures verified");
                require(result.certificates.get(0).sha256Fingerprint.equals(key.certificates.get(0).sha256Fingerprint),
                        "Output has the wrong certificate");
                require(Arrays.equals(entryHash(input, "AndroidManifest.xml"),
                        entryHash(output, "AndroidManifest.xml")), "Signing modified the Manifest");
                require(new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(23)
                        .setMaxCheckedPlatformVersion(23).build().verify().isVerified(),
                        "API 23 signature compatibility rules failed (not an API 23 runtime test)");
                require(new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
                        .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify().isVerified(),
                        "Current Android signature compatibility rules failed");
            });
            check("re-sign an existing signature using a different identity", () -> {
                String original = VERIFIER.verify(signedFixture, 23, NONE).certificates.get(0).sha256Fingerprint;
                File output = sign(signedFixture, "重新签名.apk", ApkSigningService.Options.forMinSdk(23));
                ApkVerificationService.Result result = VERIFIER.verify(output, 23, NONE);
                require(result.verified && !result.certificates.get(0).sha256Fingerprint.equals(original),
                        "Re-signing did not change the old signing identity");
            });
            check("configured v2-only and v3-only schemes work on ART", () -> {
                File v2 = sign(input, "v2-only.apk", new ApkSigningService.Options(24, false, true, false));
                require(VERIFIER.verify(v2, 24, NONE).schemes.equals(Collections.singletonList("v2")),
                        "v2 configuration was not applied");
                File v3 = sign(input, "v3-only.apk", new ApkSigningService.Options(28, false, false, true));
                require(VERIFIER.verify(v3, 28, NONE).schemes.equals(Collections.singletonList("v3")),
                        "v3 configuration was not applied");
            });
            check("wrong passwords and aliases fail and erase all supplied password arrays", () -> {
                for (int mode = 0; mode < 3; ++mode) {
                    char[] storePassword = (mode == 0 ? "wrong-password" : PASSWORD).toCharArray();
                    char[] privatePassword = (mode == 1 ? "wrong-private-password" : PASSWORD).toCharArray();
                    String alias = mode == 2 ? "missing-alias" : ALIAS;
                    expect(Exception.class, () -> KEYS.load(store, "PKCS12", storePassword, alias, privatePassword));
                    require(erased(storePassword) && erased(privatePassword), "Failure retained passwords");
                }
            });
            check("malformed APKs and tampered signatures cannot verify", () -> {
                File bad = new File(work, "malformed.apk");
                try (OutputStream output = new FileOutputStream(bad)) {
                    output.write(new byte[]{0, 1, 2, 3, 4, 5});
                }
                require(!VERIFIER.verify(bad, 23, NONE).verified, "Malformed APK verified");
                File changed = sign(input, "tampered.apk", ApkSigningService.Options.forMinSdk(23));
                try (RandomAccessFile file = new RandomAccessFile(changed, "rw")) {
                    file.seek(64);
                    int value = file.read();
                    file.seek(64);
                    file.write(value ^ 0x40);
                }
                require(!VERIFIER.verify(changed, 23, NONE).verified, "Modified APK verified");
            });
            check("existing outputs and original inputs are never overwritten", () -> {
                File occupied = new File(work, "occupied.apk");
                try (OutputStream output = new FileOutputStream(occupied)) { output.write(new byte[]{4, 3, 2, 1}); }
                byte[] before = hash(occupied);
                byte[] sourceBefore = hash(input);
                expect(IOException.class, () -> SIGNER.sign(input, occupied, cache, key,
                        ApkSigningService.Options.forMinSdk(23), NONE));
                expect(IOException.class, () -> SIGNER.sign(input, input, cache, key,
                        ApkSigningService.Options.forMinSdk(23), NONE));
                require(Arrays.equals(before, hash(occupied)) && Arrays.equals(sourceBefore, hash(input)),
                        "Existing bytes were overwritten");
                emptyCache();
            });
            check("cancel during alignment/signing/verification/save removes partial files", () -> {
                for (String phase : Arrays.asList("align", "sign", "verify", "save")) cancel(phase);
            });
            check("an interrupted worker cancels before creating output", () -> {
                File output = new File(work, "interrupted.apk");
                Thread.currentThread().interrupt();
                try {
                    expect(InterruptedIOException.class, () -> SIGNER.sign(input, output, cache, key,
                            ApkSigningService.Options.forMinSdk(23), NONE));
                } finally {
                    Thread.interrupted();
                }
                require(!output.exists(), "Interrupted worker left an output");
                emptyCache();
            });
            check("a 32 MiB APK is signed with streaming IO on ART", () -> {
                File large = new File(work, "大 APK 32 MiB.apk");
                createLargeApk(input, large);
                File output = sign(large, "大 APK 签名结果.apk", ApkSigningService.Options.forMinSdk(23));
                require(VERIFIER.verify(output, 23, NONE).verified, "Large APK did not verify");
                require(Arrays.equals(entryHash(large, "assets/大文件 with spaces.bin"),
                        entryHash(output, "assets/大文件 with spaces.bin")), "Large payload changed");
            });
            if (!arguments[2].equals("-")) {
                check("a JKS fixture with different store/private passwords works on ART", () -> {
                    File jks = new File(arguments[2]);
                    char[] storePassword = "disposable-test-password-123".toCharArray();
                    char[] privatePassword = "separate-private-password-456".toCharArray();
                    try (SigningKeyStoreService.SigningKey imported = KEYS.load(
                            jks, "JKS", storePassword, "imported", privatePassword)) {
                        File output = new File(work, "JKS 签名结果.apk");
                        require(SIGNER.sign(input, output, cache, imported,
                                ApkSigningService.Options.forMinSdk(23), NONE).verified, "JKS signing failed");
                    }
                    require(erased(storePassword) && erased(privatePassword), "JKS load retained passwords");
                    emptyCache();
                });
            }
            if (!arguments[3].equals("-")) {
                check("packaged APK signing string/layout resource IDs are nonzero", () -> {
                    int title = Class.forName("me.zhanghai.android.files.R$string")
                            .getField("apk_signing_title").getInt(null);
                    int layout = Class.forName("me.zhanghai.android.files.R$layout")
                            .getField("apk_signing_dialog").getInt(null);
                    require((title >>> 24) == 0x7f && (layout >>> 24) == 0x7f,
                            "Packaged code retained placeholder resource IDs");
                    System.out.println("ART_RESOURCE_IDS title=" + Integer.toHexString(title)
                            + " layout=" + Integer.toHexString(layout));
                });
                check("re-sign the actual built Material Files APK without changing its Manifest", () -> {
                    File actual = new File(arguments[3]);
                    System.out.println("ACTUAL_APK_BYTES " + actual.length());
                    File output = sign(actual, "Material Files 重新签名.apk", ApkSigningService.Options.forMinSdk(23));
                    require(Arrays.equals(entryHash(actual, "AndroidManifest.xml"),
                            entryHash(output, "AndroidManifest.xml")), "Actual APK Manifest was modified");
                });
            }
        } finally {
            key.close();
        }
        System.out.println("ART APK signing: " + (checks - FAILURES.size()) + "/" + checks + " checks passed");
        System.out.println("Scope: ART signing/verification only; no installation, launch, UI, cloning, or API 23 device test.");
        if (!FAILURES.isEmpty()) throw new AssertionError("Failed ART checks: " + FAILURES);
    }

    private static File sign(File source, String name, ApkSigningService.Options options) throws Exception {
        byte[] before = hash(source);
        File output = new File(work, name);
        ApkVerificationService.Result result = SIGNER.sign(source, output, cache, key, options, NONE);
        require(result.verified && output.isFile(), "No verified output: " + result.errors);
        require(Arrays.equals(before, hash(source)), "Signing changed the input APK");
        emptyCache();
        return output;
    }

    private static void cancel(String phase) throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        ApkOperationCallback callback = new ApkOperationCallback() {
            @Override public void checkCancelled() throws InterruptedIOException {
                if (reached.get()) throw new InterruptedIOException("Test user cancellation");
            }
            @Override public void onProgress(String current, long completed, long total) {
                if (phase.equals(current) && (completed > 0 || phase.equals("verify"))) reached.set(true);
            }
        };
        File output = new File(work, "canceled-" + phase + ".apk");
        byte[] before = hash(input);
        expect(InterruptedIOException.class, () -> SIGNER.sign(input, output, cache, key,
                ApkSigningService.Options.forMinSdk(23), callback));
        require(reached.get() && !output.exists(), "Cancellation missed its phase or left an output: " + phase);
        require(Arrays.equals(before, hash(input)), "Cancellation changed the input");
        emptyCache();
    }

    private static void createLargeApk(File source, File target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int index = 0; index < buffer.length; ++index) buffer[index] = (byte) (index * 37);
        CRC32 crc = new CRC32();
        for (int index = 0; index < 512; ++index) crc.update(buffer);
        try (ZipFile archive = new ZipFile(source);
                ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream input = archive.getInputStream(entry)) { transfer(input, output); }
                output.closeEntry();
            }
            ZipEntry entry = new ZipEntry("assets/大文件 with spaces.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(32L * 1024 * 1024);
            entry.setCompressedSize(entry.getSize());
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            for (int index = 0; index < 512; ++index) output.write(buffer);
            output.closeEntry();
        }
    }

    private static byte[] entryHash(File file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file); InputStream stream = zip.getInputStream(zip.getEntry(name))) {
            return digest(stream);
        }
    }

    private static byte[] hash(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return digest(input); }
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        return digest.digest();
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder value = new StringBuilder();
        for (byte octet : digest) {
            if (value.length() > 0) value.append(':');
            value.append("0123456789ABCDEF".charAt((octet >> 4) & 15));
            value.append("0123456789ABCDEF".charAt(octet & 15));
        }
        return value.toString();
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            transfer(input, output);
        }
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
    }

    private static boolean erased(char[] password) {
        for (char character : password) if (character != 0) return false;
        return true;
    }

    private static void emptyCache() {
        String[] names = cache.list();
        require(names != null && names.length == 0, "Operation left temporary files");
    }

    private static void mkdir(File directory) {
        require(directory.isDirectory() || directory.mkdirs(), "Cannot create test directory");
    }

    private static void check(String name, CheckedRunnable action) {
        ++checks;
        try {
            action.run();
            System.out.println("PASS " + checks + " " + name);
        } catch (Throwable error) {
            FAILURES.add(name);
            System.out.println("FAIL " + checks + " " + name);
            error.printStackTrace(System.out);
        }
    }

    private static void expect(Class<? extends Throwable> type, CheckedRunnable action) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable error) {
        error.printStackTrace(System.err);
        System.exit(1);
    }

    private interface CheckedRunnable { void run() throws Exception; }
}
