/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;
import com.android.apksig.ApkVerifier;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import dalvik.system.PathClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import me.zhanghai.android.files.apk.ApkCoexistenceService;
import me.zhanghai.android.files.apk.ApkManifestReader;
import me.zhanghai.android.files.apk.core.ApkCertificateInfo;
import me.zhanghai.android.files.apk.core.ApkCoexistenceRewriter;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;
import me.zhanghai.android.files.apk.core.SigningKeyStoreService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads production code from the actual app APK. Only this harness is separately compiled to DEX.
 * Uses disposable public test keys. Resource inspection and DEX constant checks are not a substitute
 * for the runner's separately reported, optional real installation/startup/provider checks.
 */
public final class ApkCoexistenceAndroidTest {
    private static final String ORIGINAL = "com.example.materialfiles.coexistence.fixture";
    private static final String CLONE = ORIGINAL + ".clone";
    private static final String PASSWORD = "public-disposable-coexistence-fixture-password";
    private static final ApkSigningService SIGNER = new ApkSigningService();
    private static final ApkOperationCallback NONE = ApkOperationCallback.NONE;
    private static final JSONArray CHECKS = new JSONArray();
    private static final JSONObject REPORT = new JSONObject();
    private static File work;
    private static int passed;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.exit(1);
        });
        boolean success = false;
        try {
            require(arguments.length == 4, "Pass work, unsigned fixture, actual app APK, and title ID");
            work = new File(arguments[0]).getCanonicalFile();
            require(work.isDirectory(), "Missing runner workspace");
            require(Build.VERSION.SDK_INT >= 30 && "Dalvik".equals(System.getProperty("java.vm.name")),
                    "Android ART API 30 or later is required");
            REPORT.put("android_api", Build.VERSION.SDK_INT).put("android_release", Build.VERSION.RELEASE)
                    .put("supported_abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                    .put("installation", "not-run").put("startup", "not-run").put("provider_self_test", "not-run")
                    .put("engine", "APKEditor / ARSCLib");
            System.out.println("ANDROID_RUNTIME api=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE);
            run(new File(arguments[1]), new File(arguments[2]), Integer.decode(arguments[3]));
            success = true;
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            try { REPORT.put("error", error.toString()); } catch (Exception ignored) {}
        } finally {
            try {
                REPORT.put("status", success ? "passed" : "failed").put("passed", passed)
                        .put("checks", CHECKS.length()).put("results", CHECKS);
                if (work != null) Files.write(new File(work, "art-summary.json").toPath(),
                        REPORT.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                error.printStackTrace(System.err);
                success = false;
            }
        }
        System.out.println("ART APK coexistence: " + passed + "/" + CHECKS.length() + " checks passed");
        System.out.println("Scope: Manifest edit/build, signatures and unchanged APK payloads; installation is separate.");
        if (!success) System.exit(1);
    }

    private static void run(File unsigned, File appApk, int expectedTitle) throws Exception {
        check("production APK contains the linked UI resources and ARSCLib resources", () -> {
            int actualTitle = Class.forName("me.zhanghai.android.files.R$string")
                    .getField("apk_coexistence_title").getInt(null);
            require(actualTitle == expectedTitle && (actualTitle >>> 24) == 0x7f, "Stale or placeholder R ID");
            try (ResourceFile resources = new ResourceFile(appApk)) {
                require("apk_coexistence_title".equals(resources.resources.getResourceEntryName(actualTitle))
                        && !resources.resources.getString(actualTitle).trim().isEmpty(),
                        "Packaged UI resource mismatch");
            }
            require(ApkCoexistenceService.class.getClassLoader().getResource("arsclib.properties") != null,
                    "ARSCLib resources is missing from the actual APK classpath");
            REPORT.put("apk_sha256", digest(appApk)).put("title_resource_id", actualTitle);
        });
        check("real compiled fixture is a valid standalone API 30 APK", () -> {
            SIGNER.validateInput(unsigned, NONE);
            assertManifest(unsigned, ORIGINAL);
            require(!new ApkVerifier.Builder(unsigned).setMinCheckedPlatformVersion(30).build().verify().isVerified(),
                    "Fixture was unexpectedly already signed");
        });
        File keys = directory(work, "disposable keys");
        File artifacts = directory(work, "artifacts");
        File signing = directory(work, "signing scratch");
        File original = new File(artifacts, "original.apk");
        File clone = new File(artifacts, "clone.apk");
        SigningKeyStoreService stores = new SigningKeyStoreService();
        try (SigningKeyStoreService.SigningKey originalKey = stores.generate(
                    new File(keys, "original.p12"), "original", PASSWORD.toCharArray(), "Public fixture original");
             SigningKeyStoreService.SigningKey cloneKey = stores.generate(
                    new File(keys, "clone.p12"), "clone", PASSWORD.toCharArray(), "Public fixture clone")) {
            check("sign the original fixture with a disposable PKCS12 key", () -> {
                SIGNER.sign(unsigned, original, signing, originalKey, ApkSigningService.Options.forMinSdk(30), NONE);
                verify(original, originalKey);
            });
            String originalDigest = digest(original);
            File job = directory(directory(work, "中文 路径"), "rebuild with spaces");
            File input = new File(job, "原始 input.apk");
            copy(original, input);
            File rebuilt = new File(job, "重建 output.apk");
            check("production ARSCLib service decodes, rewrites and rebuilds the real fixture", () -> {
                ApkCoexistenceRewriter.Report report = new ApkCoexistenceService().rebuild(
                        input, rebuilt, job, CLONE, progress());
                assertManifest(rebuilt, CLONE);
                require(originalDigest.equals(digest(input)) && originalDigest.equals(digest(original)),
                        "Rebuild changed original APK bytes");
                REPORT.put("build_complete", true).put("rewrite_report", report.summary);
                require(report.renamedAuthorities == 5, "Not every provider authority was reported as rewritten");
            });
            check("sign rebuilt APK with a different key and independently verify v2/v3", () -> {
                SIGNER.sign(rebuilt, clone, signing, cloneKey, ApkSigningService.Options.forMinSdk(30), NONE);
                verify(clone, cloneKey);
                require(!originalKey.certificates.get(0).sha256Fingerprint.equals(
                        cloneKey.certificates.get(0).sha256Fingerprint), "Test keys unexpectedly have one identity");
                assertManifest(clone, CLONE);
                REPORT.put("signature_verified", true)
                        .put("original_certificate_sha256", originalKey.certificates.get(0).sha256Fingerprint)
                        .put("clone_certificate_sha256", cloneKey.certificates.get(0).sha256Fingerprint)
                        .put("original", original.getPath()).put("original_sha256", digest(original))
                        .put("clone", clone.getPath()).put("clone_sha256", digest(clone))
                        .put("original_package", ORIGINAL).put("clone_package", CLONE);
            });
            check("all provider authorities in rebuilt and signed binary Manifests are independent of the original", () -> {
                String sharedAuthority = "com.example.materialfiles.coexistence.shared.documents";
                String fixedAuthority = "org.example.materialfiles.coexistence.fixed";
                List<String> expectedOriginal = Arrays.asList(ORIGINAL + ".provider",
                        ORIGINAL + ".files;" + ORIGINAL + ".cache;" + sharedAuthority, fixedAuthority);
                List<String> expectedClone = Arrays.asList(CLONE + ".provider",
                        CLONE + ".files;" + CLONE + ".cache;" + CLONE + "." + sharedAuthority,
                        CLONE + "." + fixedAuthority);
                List<String> expectedComponents = Arrays.asList(ORIGINAL + ".FixtureProvider",
                        ORIGINAL + ".MultiAuthorityFixtureProvider", ORIGINAL + ".FixedAuthorityFixtureProvider");
                List<String> expectedQueries = Collections.singletonList(ORIGINAL + ".provider");
                Set<String> originalAuthorities;
                try (ResourceFile resources = new ResourceFile(original)) {
                    ManifestValues manifest = new ManifestValues(original);
                    List<String> declarations = manifest.strings("application/provider/authorities", resources.resources);
                    require(expectedOriginal.equals(declarations), "Original provider coverage is incomplete: " + declarations);
                    originalAuthorities = authoritySet(declarations);
                    require(expectedQueries.equals(manifest.strings("queries/provider/authorities", resources.resources)),
                            "Original query coverage is missing");
                }
                for (File output : new File[]{rebuilt, clone}) {
                    try (ResourceFile resources = new ResourceFile(output)) {
                        ManifestValues manifest = new ManifestValues(output);
                        List<String> declarations = manifest.strings("application/provider/authorities", resources.resources);
                        require(expectedClone.equals(declarations), "Provider authorities were not all rewritten in "
                                + output.getName() + ": " + declarations);
                        Set<String> cloneAuthorities = authoritySet(declarations);
                        require(Collections.disjoint(originalAuthorities, cloneAuthorities),
                                "Original and cloned APKs still register a shared provider authority: " + output.getName());
                        require(expectedComponents.equals(manifest.strings("application/provider/name", resources.resources)),
                                "Provider components no longer address the original DEX classes");
                        require(expectedQueries.equals(manifest.strings("queries/provider/authorities", resources.resources)),
                                "Provider query target was rewritten as a registered authority");
                    }
                }
                REPORT.put("provider_authorities_verified", true).put("provider_count", expectedClone.size())
                        .put("original_provider_authorities", new JSONArray(new ArrayList<>(originalAuthorities)))
                        .put("clone_provider_authorities", new JSONArray(new ArrayList<>(authoritySet(expectedClone))))
                        .put("query_authorities_preserved", true);
            });
            check("rebuilt and signed APKs preserve every non-Manifest payload entry", () -> {
                JSONArray entries = assertPayloadPreserved(original, rebuilt);
                require(assertPayloadPreserved(original, clone).length() == entries.length(),
                        "Signing changed the payload entry set");
                REPORT.put("payload_verified", true).put("payload_entry_count", entries.length())
                        .put("payload_entries", entries)
                        .put("native_payload_scope", "ELF library bytes preserved; libraries were not loaded")
                        .put("native_payload_execution", "not-run");
            });
            check("Manifest identities change while resource namespace, IDs and content remain original", () -> {
                try (ResourceFile first = new ResourceFile(original); ResourceFile second = new ResourceFile(clone)) {
                    Resources before = first.resources;
                    Resources after = second.resources;
                    for (String[] resource : new String[][]{{"string", "app_name"}, {"string", "fixture_message"},
                            {"string", "fixture_authority"}, {"drawable", "ic_fixture"},
                            {"layout", "activity_fixture"}, {"id", "self_test_status"}}) {
                        int oldId = before.getIdentifier(resource[1], resource[0], ORIGINAL);
                        int newId = after.getIdentifier(resource[1], resource[0], ORIGINAL);
                        require(oldId != 0 && oldId == newId, "Resource ID changed: " + resource[1]);
                        require(ORIGINAL.equals(after.getResourcePackageName(newId)), "Resource namespace changed");
                        require(after.getIdentifier(resource[1], resource[0], CLONE) == 0,
                                "Unexpected resource entry under the clone package: " + resource[1]);
                    }
                    int label = after.getIdentifier("app_name", "string", ORIGINAL);
                    require("共存验证夹具".equals(after.getString(label)), "Application label changed");
                    int message = after.getIdentifier("fixture_message", "string", ORIGINAL);
                    require("单体 APK 资源与组件验证".equals(after.getString(message)), "Resource text changed");
                    require((ORIGINAL + ".provider").equals(after.getString(
                            after.getIdentifier("fixture_authority", "string", ORIGINAL))),
                            "Original resource identity was changed");
                    int icon = after.getIdentifier("ic_fixture", "drawable", ORIGINAL);
                    require(after.getDrawable(icon, null).getIntrinsicWidth() > 0, "Icon could not be inflated");
                    try (android.content.res.XmlResourceParser xml = after.getLayout(
                            after.getIdentifier("activity_fixture", "layout", ORIGINAL))) {
                        require(xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT, "Layout is empty");
                    }
                    ManifestValues manifest = new ManifestValues(clone);
                    require("1.0-fixture".equals(manifest.string("manifest/versionName", after)), "Version name changed");
                    require(label == manifest.reference("application/label")
                            && icon == manifest.reference("application/icon"), "Manifest resource reference changed");
                    require((CLONE + ".permission.FIXTURE").equals(manifest.string("permission/name", after)), "Permission conflict remains");
                    require((CLONE + ".permission.FIXTURE").equals(manifest.string("uses-permission/name", after)), "Permission use not rewritten");
                    List<String> actions = manifest.strings("application/activity/intent-filter/action/name", after);
                    require(actions.contains(ORIGINAL + ".action.SELF_TEST")
                            && !actions.contains(CLONE + ".action.SELF_TEST"), "Custom action changed");
                    require((ORIGINAL + ".FixtureActivity").equals(manifest.string("application/activity/name", after)),
                            "Relative component names no longer address original DEX classes");
                }
                REPORT.put("resources_verified", true).put("resource_namespace", ORIGINAL);
            });
            check("both APKs retain the original hardcoded BuildConfig identities in DEX", () -> {
                require(original.setReadOnly() && clone.setReadOnly(), "Cannot protect DEX files before loading");
                assertConstants(original, ORIGINAL);
                assertConstants(clone, ORIGINAL);
                REPORT.put("dex_constants_verified", true).put("hardcoded_references_migrated", false);
            });
            check("invalid and unchanged target package names fail without an output", () -> {
                for (String target : new String[]{ORIGINAL, "invalid package"}) {
                    File rejected = directory(work, target.equals(ORIGINAL) ? "same package" : "invalid package");
                    File source = new File(rejected, "input.apk");
                    copy(original, source);
                    File output = new File(rejected, "output.apk");
                    expectIOException(() -> new ApkCoexistenceService().rebuild(source, output, rejected, target, NONE));
                    require(!output.exists() && originalDigest.equals(digest(source)), "Rejected request changed APK bytes");
                }
            });
            check("cancellation before binary rebuild returns without a partial output", () -> {
                File canceled = directory(work, "cancel before build");
                File source = new File(canceled, "input.apk");
                copy(original, source);
                File output = new File(canceled, "output.apk");
                final boolean[] observed = {false};
                try {
                    expectIOException(() -> new ApkCoexistenceService().rebuild(source, output, canceled,
                            CLONE, new ApkOperationCallback() {
                                @Override public void checkCancelled() throws InterruptedIOException {
                                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
                                }
                                @Override public void onProgress(String phase, long completed, long total) {
                                    if ("rebuild".equals(phase)) { observed[0] = true; Thread.currentThread().interrupt(); }
                                }
                            }));
                } finally { Thread.interrupted(); }
                require(observed[0] && !output.exists() && originalDigest.equals(digest(source)), "Cancellation left output or changed input");
            });
            REPORT.put("original", original.getPath()).put("original_sha256", digest(original))
                    .put("clone", clone.getPath()).put("clone_sha256", digest(clone))
                    .put("original_package", ORIGINAL).put("clone_package", CLONE);
        }
    }

    private static void assertManifest(File apk, String packageName) throws Exception {
        ApkManifestReader manifest = ApkManifestReader.read(apk);
        require(packageName.equals(manifest.packageName) && !manifest.requiresSplits
                && manifest.versionCode == 17 && manifest.minSdk == 30 && manifest.targetSdk == 36,
                "Unexpected package/version/SDK or split metadata");
    }

    private static Set<String> authoritySet(List<String> declarations) {
        Set<String> result = new LinkedHashSet<>();
        for (String declaration : declarations) {
            for (String authority : declaration.split(";", -1)) {
                require(!authority.isEmpty() && authority.equals(authority.trim()) && result.add(authority),
                        "Empty or duplicate provider authority: " + declaration);
            }
        }
        return result;
    }

    private static void verify(File apk, SigningKeyStoreService.SigningKey key) throws Exception {
        ApkVerifier.Result v3 = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(30).build().verify();
        assertVerified(v3, key, "API 30+ v3", v3.isVerifiedUsingV3Scheme());
        // API 30+ chooses v3 when available, so verify the v2 signature in its own platform range.
        ApkVerifier.Result v2 = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24)
                .setMaxCheckedPlatformVersion(27).build().verify();
        assertVerified(v2, key, "API 24-27 v2", v2.isVerifiedUsingV2Scheme());
    }

    private static void assertVerified(ApkVerifier.Result result,
            SigningKeyStoreService.SigningKey key, String scope, boolean expectedSchemeVerified) throws Exception {
        String diagnostics = "verified=" + result.isVerified()
                + ", v1=" + result.isVerifiedUsingV1Scheme()
                + ", v2=" + result.isVerifiedUsingV2Scheme()
                + ", v3=" + result.isVerifiedUsingV3Scheme()
                + ", certificates=" + result.getSignerCertificates().size()
                + ", errors=" + result.getErrors() + ", warnings=" + result.getWarnings();
        require(result.isVerified() && expectedSchemeVerified && result.getSignerCertificates().size() == 1,
                "Official verifier rejected " + scope + " fixture signature: " + diagnostics);
        String expected = key.certificates.get(0).sha256Fingerprint;
        String actual = new ApkCertificateInfo(result.getSignerCertificates().get(0)).sha256Fingerprint;
        require(expected.equals(actual), "Unexpected " + scope + " signing identity: expected="
                + expected + ", actual=" + actual + "; " + diagnostics);
    }

    private static void assertConstants(File apk, String packageName) throws Exception {
        ClassLoader loader = new PathClassLoader(apk.getPath(), ApkCoexistenceAndroidTest.class.getClassLoader());
        Class<?> config = Class.forName(ORIGINAL + ".BuildConfig", true, loader);
        for (String[] value : new String[][]{{"APPLICATION_ID", packageName}, {"AUTHORITY", packageName + ".provider"},
                {"PERMISSION", packageName + ".permission.FIXTURE"}, {"SELF_TEST_ACTION", packageName + ".action.SELF_TEST"},
                {"CONTENT_URI", "content://" + packageName + ".provider/data"}}) {
            require(value[1].equals(config.getField(value[0]).get(null)), "DEX constant mismatch: " + value[0]);
        }
    }

    private static JSONArray assertPayloadPreserved(File original, File rebuilt) throws Exception {
        JSONArray result = new JSONArray();
        try (ZipFile before = new ZipFile(original); ZipFile after = new ZipFile(rebuilt)) {
            Map<String, ZipEntry> source = payloadEntries(before);
            Map<String, ZipEntry> target = payloadEntries(after);
            require(source.keySet().equals(target.keySet()), "APK payload entry names changed");
            require(source.containsKey("resources.arsc") && source.containsKey("classes.dex")
                    && source.containsKey("assets/retained_payload.txt"), "Fixture payload coverage is incomplete");
            boolean hasResources = false;
            boolean hasNativeLibrary = false;
            for (Map.Entry<String, ZipEntry> entry : source.entrySet()) {
                String name = entry.getKey();
                byte[] originalBytes = entryBytes(before, entry.getValue());
                byte[] rebuiltBytes = entryBytes(after, target.get(name));
                require(Arrays.equals(originalBytes, rebuiltBytes), "APK payload bytes changed: " + name);
                hasResources |= name.startsWith("res/");
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    require(originalBytes.length > 4 && originalBytes[0] == 0x7f
                            && originalBytes[1] == 'E' && originalBytes[2] == 'L' && originalBytes[3] == 'F',
                            "Fixture native payload is not ELF: " + name);
                    hasNativeLibrary = true;
                }
                result.put(new JSONObject().put("name", name).put("bytes", originalBytes.length)
                        .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(originalBytes))));
            }
            require(hasResources && hasNativeLibrary, "Fixture must exercise both compiled resources and native payloads");
        }
        return result;
    }

    private static Map<String, ZipEntry> payloadEntries(ZipFile zip) {
        Map<String, ZipEntry> result = new TreeMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || name.equals("AndroidManifest.xml") || isSignatureMetadata(name)) continue;
            require(result.put(name, entry) == null, "Duplicate payload entry: " + name);
        }
        return result;
    }

    private static boolean isSignatureMetadata(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || upper.equals("STAMP-CERT-SHA256")
                || (upper.startsWith("META-INF/") && upper.indexOf('/', 9) < 0
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")));
    }

    private static byte[] entryBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transfer(input, output);
            return output.toByteArray();
        }
    }

    private static ApkOperationCallback progress() {
        return new ApkOperationCallback() {
            @Override public void checkCancelled() throws InterruptedIOException {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
            }
            @Override public void onProgress(String phase, long completed, long total) {
                if (total < 0) System.out.println("COEXISTENCE_PHASE " + phase);
            }
        };
    }

    private static final class ResourceFile implements AutoCloseable {
        final AssetManager assets;
        final Resources resources;
        ResourceFile(File apk) throws Exception {
            // Test-only platform reflection; no mock Resources or placeholder resource table.
            assets = AssetManager.class.getDeclaredConstructor().newInstance();
            try {
                int cookie = (Integer) AssetManager.class.getDeclaredMethod("addAssetPath", String.class)
                        .invoke(assets, apk.getPath());
                require(cookie != 0, "Android could not load APK resources: " + apk);
                DisplayMetrics metrics = new DisplayMetrics();
                metrics.setToDefaults();
                resources = new Resources(assets, metrics, new Configuration());
            } catch (Throwable error) { assets.close(); throw error; }
        }
        @Override public void close() { assets.close(); }
    }

    private static final class ManifestValues {
        final Map<String, List<Object>> values = new LinkedHashMap<>();
        ManifestValues(File apk) throws Exception {
            byte[] data;
            try (ZipFile zip = new ZipFile(apk); InputStream input = zip.getInputStream(zip.getEntry("AndroidManifest.xml"));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                transfer(input, output);
                data = output.toByteArray();
            }
            AndroidBinXmlParser parser = new AndroidBinXmlParser(ByteBuffer.wrap(data));
            List<String> path = new ArrayList<>();
            while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (parser.getEventType() == AndroidBinXmlParser.EVENT_END_ELEMENT) {
                    path.remove(path.size() - 1);
                    continue;
                }
                if (parser.getEventType() != AndroidBinXmlParser.EVENT_START_ELEMENT) continue;
                path.add(parser.getName());
                String elementPath = path.size() == 1 ? path.get(0)
                        : String.join("/", path.subList(1, path.size()));
                for (int index = 0; index < parser.getAttributeCount(); ++index) {
                    int type = parser.getAttributeValueType(index);
                    if (type != AndroidBinXmlParser.VALUE_TYPE_STRING && type != AndroidBinXmlParser.VALUE_TYPE_REFERENCE) continue;
                    Object value = type == AndroidBinXmlParser.VALUE_TYPE_STRING
                            ? parser.getAttributeStringValue(index) : Integer.valueOf(parser.getAttributeIntValue(index));
                    String key = elementPath + "/" + parser.getAttributeName(index);
                    values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                }
            }
        }
        int reference(String key) { return (Integer) values.get(key).get(0); }
        String string(String key, Resources resources) { return strings(key, resources).get(0); }
        List<String> strings(String key, Resources resources) {
            List<String> result = new ArrayList<>();
            for (Object value : values.get(key)) result.add(value instanceof Integer
                    ? resources.getString((Integer) value) : (String) value);
            return result;
        }
    }

    private static void check(String name, CheckedAction action) throws Exception {
        JSONObject result = new JSONObject().put("name", name);
        CHECKS.put(result);
        try {
            action.run();
            result.put("status", "passed");
            ++passed;
            System.out.println("PASS " + passed + " " + name);
        } catch (Exception | Error error) {
            result.put("status", "failed").put("error", error.toString());
            throw error;
        }
    }

    private static void expectIOException(CheckedAction action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }

    private static File directory(File parent, String name) throws IOException {
        File file = new File(parent, name);
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file);
        return file;
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) { transfer(input, output); }
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedAction { void run() throws Exception; }
}
