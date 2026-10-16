/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilderFactory;
import me.zhanghai.android.files.apk.core.ApkCoexistenceRewriter;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Host tests for the Manifest-only operation; DEX and resource bytes are deliberately opaque. */
public final class ApkCoexistenceRewriterTest {
    private static final String OLD = "com.example.fixture";
    private static final String NEW = "com.example.fixture.clone";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final ApkOperationCallback NONE = ApkOperationCallback.NONE;
    private static Path work;
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        work = new File(arguments[0], "coexistence-rewriter-" + UUID.randomUUID()).toPath();
        Files.createDirectories(work);
        check("only Manifest changes and report states the limitation", ApkCoexistenceRewriterTest::ordinary);
        check("all component class attributes retain the original code package", ApkCoexistenceRewriterTest::classes);
        check("action, process, task affinity and unrelated metadata stay unchanged", ApkCoexistenceRewriterTest::unrelatedValues);
        check("declared permissions, groups, trees and references are renamed", ApkCoexistenceRewriterTest::permissions);
        check("semicolon-separated Provider authorities receive independent names", ApkCoexistenceRewriterTest::authorities);
        check("all declared Providers use renamed authorities with namespace aliases while queries stay unchanged", ApkCoexistenceRewriterTest::multipleProviders);
        check("authority and permission collisions fail before writing", ApkCoexistenceRewriterTest::collisions);
        check("new authorities cannot overlap original authorities in either declaration order", ApkCoexistenceRewriterTest::originalAuthorityCollisions);
        check("shared UID attributes are removed", ApkCoexistenceRewriterTest::sharedUid);
        check("existing label, original label and package fallback are preserved", ApkCoexistenceRewriterTest::labels);
        check("resource resolver materializes identifiers only in Manifest", ApkCoexistenceRewriterTest::resources);
        check("resource-backed identifiers require an explicit resolver", ApkCoexistenceRewriterTest::missingResolver);
        check("unresolved resources fail without writes", ApkCoexistenceRewriterTest::unresolvedResources);
        check("external instrumentation is preserved", ApkCoexistenceRewriterTest::externalInstrumentation);
        check("split declarations are refused before writing", ApkCoexistenceRewriterTest::splits);
        check("false split flags are accepted", ApkCoexistenceRewriterTest::falseSplitFlags);
        check("native code, reflection and protected metadata are not scanned or refused", ApkCoexistenceRewriterTest::opaqueFiles);
        check("Manifest package and application structure are validated", ApkCoexistenceRewriterTest::structure);
        check("DTD and external entities are refused", ApkCoexistenceRewriterTest::entities);
        check("malformed UTF-8 and XML fail without writes", ApkCoexistenceRewriterTest::malformed);
        check("XML depth and attributes are bounded", ApkCoexistenceRewriterTest::xmlLimits);
        check("Manifest size is bounded before parsing", ApkCoexistenceRewriterTest::oversize);
        check("Manifest and root symlinks cannot redirect writes", ApkCoexistenceRewriterTest::symlinks);
        check("Manifest replacement during preparation is detected", ApkCoexistenceRewriterTest::replacement);
        check("analysis cancellation leaves the tree unchanged", () -> cancellation(false));
        check("cancellation at write boundary leaves the tree unchanged", () -> cancellation(true));
        check("thread interruption is preserved", ApkCoexistenceRewriterTest::interruption);
        check("package validation enforces the documented syntax and length", ApkCoexistenceRewriterTest::packageValidation);
        System.out.println("PASS " + checks + " Manifest-only coexistence checks");
    }

    private static void ordinary() throws Exception {
        Fixture fixture = fixture("ordinary");
        Map<String, byte[]> before = fixture.snapshot();
        ApkCoexistenceRewriter.Report report = fixture.rewrite(NONE);
        require(report.rewrittenFiles == 1 && report.rewrittenConstants > 0
                && report.renamedAuthorities == 1 && report.renamedPermissions == 1, "Incorrect report");
        require(report.summary.contains("仅修改 Manifest；代码硬编码引用未迁移"), "Limitation missing");
        Document manifest = fixture.manifest();
        require(manifest.getDocumentElement().getAttribute("package").equals(NEW), "Package unchanged");
        require(android(first(manifest, "provider"), "authorities").equals(NEW + ".provider"), "Provider unchanged");
        require(android(first(manifest, "uses-permission"), "name").equals(NEW + ".permission.READ"), "Permission unchanged");
        require(android(first(manifest, "application"), "label").equals("@string/title"), "Label changed");
        fixture.assertOnlyManifestChanged(before);
    }

    private static void classes() throws Exception {
        Fixture fixture = fixture("classes");
        fixture.replace("android:name=\".Receiver\"", "android:name=\".接收器$Inner\"");
        fixture.rewrite(NONE);
        Document manifest = fixture.manifest();
        for (String[] entry : new String[][]{
                {"application", "name", "App"}, {"application", "backupAgent", "Backup"},
                {"application", "manageSpaceActivity", "ManageSpace"},
                {"application", "zygotePreloadName", "Preload"}, {"activity", "name", "Main"},
                {"activity", "parentActivityName", "Parent"}, {"activity-alias", "name", "Alias"},
                {"activity-alias", "targetActivity", "Main"},
                {"activity-alias", "parentActivityName", "Parent"},
                {"provider", "name", "Provider"}, {"service", "name", "Service"},
                {"receiver", "name", "接收器$Inner"}, {"instrumentation", "name", "Runner"}}) {
            require(android(first(manifest, entry[0]), entry[1]).equals(OLD + "." + entry[2]),
                    "Class moved with install package: " + Arrays.toString(entry));
        }
        require(android(first(manifest, "application"), "appComponentFactory").equals("android.app.AppComponentFactory"),
                "Fully qualified framework class changed");
        require(android(first(manifest, "instrumentation"), "targetPackage").equals(NEW), "Own instrumentation target unchanged");
        require(android(named(manifest, "meta-data", "android.support.PARENT_ACTIVITY"), "value")
                .equals(OLD + ".Parent"), "Support parent class changed");
        require(android(named(manifest, "meta-data", "android.app.default_searchable"), "value")
                .equals(OLD + ".Search"), "Search class changed");
    }

    private static void unrelatedValues() throws Exception {
        Fixture fixture = fixture("unrelated");
        fixture.rewrite(NONE);
        Document manifest = fixture.manifest();
        require(android(named(manifest, "action", OLD + ".action.SELF"), "name").equals(OLD + ".action.SELF"), "Action rewritten");
        require(android(first(manifest, "activity"), "process").equals(OLD + ".worker"), "Process rewritten");
        require(android(first(manifest, "receiver"), "process").equals(":remote"), "Private process rewritten");
        require(android(first(manifest, "activity"), "taskAffinity").equals(OLD + ".task"), "Task affinity rewritten");
        require(android(named(manifest, "meta-data", OLD + ".metadata"), "value").equals(OLD + ".constant"), "Unknown metadata rewritten");
        require(android(first(manifest, "package"), "name").equals(OLD), "Package query rewritten");
        require(android(first(manifest, "uses-sdk"), "minSdkVersion").equals("26"), "SDK changed");
        require(manifest.getDocumentElement().getAttributeNS(ANDROID, "versionName").equals("1.2"), "Version changed");
    }

    private static void permissions() throws Exception {
        Fixture fixture = fixture("permissions");
        fixture.replace("<application", "<permission-group android:name=\"vendor.GROUP\"/>"
                + "<permission-tree android:name=\"" + OLD + ".tree\"/>"
                + "<permission android:name=\"vendor.ACCESS\" android:permissionGroup=\"vendor.GROUP\""
                + " android:backgroundPermission=\"" + OLD + ".permission.READ\"/>"
                + "<uses-permission-sdk-23 android:name=\"vendor.ACCESS\"/>"
                + "<uses-permission-sdk-m android:name=\"" + OLD + ".permission.READ\"/>"
                + "<uses-permission android:name=\"" + OLD + ".undeclared\"/>"
                + "<application android:permission=\"vendor.ACCESS\"");
        fixture.replace("android:authorities=", "android:readPermission=\"vendor.ACCESS\" android:writePermission=\""
                + OLD + ".permission.READ\" android:authorities=");
        ApkCoexistenceRewriter.Report report = fixture.rewrite(NONE);
        Document manifest = fixture.manifest();
        require(report.renamedPermissions == 4, "Permission/group/tree count differs");
        require(android(first(manifest, "permission-group"), "name").equals(NEW + ".vendor.GROUP"), "Group unchanged");
        require(android(first(manifest, "permission-tree"), "name").equals(NEW + ".tree"), "Tree unchanged");
        Element permission = named(manifest, "permission", NEW + ".vendor.ACCESS");
        require(android(permission, "permissionGroup").equals(NEW + ".vendor.GROUP"), "Group reference unchanged");
        require(android(permission, "backgroundPermission").equals(NEW + ".permission.READ"), "Background permission unchanged");
        require(android(first(manifest, "uses-permission-sdk-23"), "name").equals(NEW + ".vendor.ACCESS"), "SDK permission unchanged");
        require(android(first(manifest, "uses-permission-sdk-m"), "name").equals(NEW + ".permission.READ"), "Legacy SDK permission unchanged");
        require(android(first(manifest, "application"), "permission").equals(NEW + ".vendor.ACCESS"), "Application permission unchanged");
        require(android(first(manifest, "provider"), "readPermission").equals(NEW + ".vendor.ACCESS"), "Provider read permission unchanged");
        require(android(first(manifest, "provider"), "writePermission").equals(NEW + ".permission.READ"), "Provider write permission unchanged");
        require(named(manifest, "uses-permission", OLD + ".undeclared") != null, "Undeclared permission rewritten");
        require(android(first(manifest, "service"), "permission").equals("android.permission.BIND_JOB_SERVICE"), "Platform permission rewritten");
    }

    private static void authorities() throws Exception {
        Fixture fixture = fixture("authorities");
        fixture.replace(OLD + ".provider\"", OLD + ".provider;vendor.external;" + OLD + "\"");
        ApkCoexistenceRewriter.Report report = fixture.rewrite(NONE);
        require(android(first(fixture.manifest(), "provider"), "authorities")
                .equals(NEW + ".provider;" + NEW + ".vendor.external;" + NEW), "Composite authorities differ");
        require(report.renamedAuthorities == 3, "Authority count differs");
    }

    private static void multipleProviders() throws Exception {
        Fixture fixture = fixture("multiple-providers");
        String queryAuthorities = OLD + ".provider;vendor.external";
        fixture.replace("</queries>", "<provider android:authorities=\"" + queryAuthorities + "\"/></queries>");
        fixture.replace("</application>", "<provider android:name=\".OtherProvider\""
                + " android:authorities=\"vendor.external;" + OLD + "extra.provider\""
                + " android:exported=\"false\"/></application>");
        fixture.replace("xmlns:android=", "xmlns:apk=");
        fixture.replace("android:", "apk:");
        Map<String, byte[]> before = fixture.snapshot();
        ApkCoexistenceRewriter.Report report = fixture.rewrite(NONE);
        Document manifest = fixture.manifest();
        require(android(named(manifest, "provider", OLD + ".Provider"), "authorities")
                .equals(NEW + ".provider"), "First declared Provider was not rewritten");
        require(android(named(manifest, "provider", OLD + ".OtherProvider"), "authorities")
                .equals(NEW + ".vendor.external;" + NEW + "." + OLD + "extra.provider"),
                "Other Provider authorities or namespace alias were not handled");
        Element query = (Element) first(manifest, "queries").getElementsByTagName("provider").item(0);
        require(android(query, "authorities").equals(queryAuthorities), "Provider visibility query rewritten");
        require(report.renamedAuthorities == 3, "Declared Provider authority count includes a query");
        fixture.assertOnlyManifestChanged(before);
    }

    private static void collisions() throws Exception {
        Fixture provider = fixture("authority-collision");
        provider.replace(OLD + ".provider\"", "extra;" + OLD + ".extra\"");
        rejected(provider, "collide", () -> provider.rewrite(NONE));
        Fixture permission = fixture("permission-collision");
        permission.replace("<application", "<permission android:name=\"extra\"/>"
                + "<permission android:name=\"" + OLD + ".extra\"/><application");
        rejected(permission, "collide", () -> permission.rewrite(NONE));
    }

    private static void originalAuthorityCollisions() throws Exception {
        for (boolean reverse : new boolean[]{false, true}) {
            for (boolean composite : new boolean[]{false, true}) {
                Fixture fixture = fixture("original-authority-collision-" + reverse + "-" + composite);
                String firstAuthority = (reverse ? NEW : OLD) + ".provider";
                String secondAuthority = (reverse ? OLD : NEW) + ".provider";
                fixture.replace("android:authorities=\"" + OLD + ".provider\"",
                        "android:authorities=\"" + firstAuthority
                                + (composite ? ";" + secondAuthority : "") + "\"");
                if (!composite) {
                    fixture.replace("</application>", "<provider android:name=\".OtherProvider\""
                            + " android:authorities=\"" + secondAuthority + "\"/></application>");
                }
                rejected(fixture, "authority", () -> fixture.rewrite(NONE));
            }
        }
    }

    private static void sharedUid() throws Exception {
        Fixture fixture = fixture("shared-uid");
        fixture.replace("package=\"" + OLD + "\"", "package=\"" + OLD + "\" android:sharedUserId=\"false\""
                + " android:sharedUserLabel=\"@string/shared\" android:sharedUserMaxSdkVersion=\"32\"");
        fixture.rewrite(NONE);
        Element manifest = fixture.manifest().getDocumentElement();
        for (String name : new String[]{"sharedUserId", "sharedUserLabel", "sharedUserMaxSdkVersion"}) {
            require(!manifest.hasAttributeNS(ANDROID, name), "Shared UID attribute left behind: " + name);
        }
    }

    private static void labels() throws Exception {
        Fixture existing = fixture("existing-label");
        new ApkCoexistenceRewriter().rewrite(existing.root.toFile(), OLD, NEW, "Unused label", NONE);
        require(android(first(existing.manifest(), "application"), "label").equals("@string/title"), "Existing label replaced");
        for (String label : new String[]{null, " \t\n", "Original label", "@literal's \\ \"label\n"}) {
            Fixture fixture = fixture("label-" + UUID.randomUUID());
            fixture.replace(" android:label=\"@string/title\"", "");
            new ApkCoexistenceRewriter().rewrite(fixture.root.toFile(), OLD, NEW, label, NONE);
            String actual = android(first(fixture.manifest(), "application"), "label");
            if (label == null || label.trim().isEmpty()) require(actual.equals(OLD), "Package label fallback changed");
            else if (label.equals("Original label")) require(actual.equals(label), "Original label not used");
            else require(actual.startsWith("\"\\@literal\\'s") && actual.contains("\\\\")
                    && actual.contains("\\\"label\\n"), "Fallback label lost Android string escaping");
        }
    }

    private static void resources() throws Exception {
        Fixture fixture = fixture("resources");
        fixture.replace(OLD + ".provider\"", "@string/provider\"");
        fixture.replace(OLD + ".permission.READ", "@string/permission");
        fixture.replace("android:name=\".App\"", "android:name=\"@0x7f010005\"");
        fixture.replace("android:targetPackage=\"" + OLD + "\"", "android:targetPackage=\"@string/package\"");
        Map<String, byte[]> before = fixture.snapshot();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("@string/provider", OLD + ".provider;external.provider");
        values.put("@string/permission", OLD + ".permission.READ");
        values.put("@0x7f010005", ".App");
        values.put("@string/package", OLD);
        new ApkCoexistenceRewriter().rewrite(fixture.root.toFile(), OLD, NEW, null, reference -> {
            if (!values.containsKey(reference)) throw new IOException("Unexpected reference: " + reference);
            return values.get(reference);
        }, NONE);
        Document manifest = fixture.manifest();
        require(android(first(manifest, "application"), "name").equals(OLD + ".App"), "Resource class not resolved");
        require(android(first(manifest, "provider"), "authorities").equals(NEW + ".provider;" + NEW + ".external.provider"), "Resource authority not materialized");
        require(android(first(manifest, "provider"), "permission").equals(NEW + ".permission.READ"), "Resource permission not materialized");
        require(android(first(manifest, "instrumentation"), "targetPackage").equals(NEW), "Resource target not changed");
        require(android(first(manifest, "application"), "label").equals("@string/title"), "Label reference resolved unnecessarily");
        fixture.assertOnlyManifestChanged(before);
    }

    private static void missingResolver() throws Exception {
        for (String[] edit : new String[][]{
                {OLD + ".provider\"", "@string/provider\""},
                {"android:name=\".App\"", "android:name=\"@string/app\""},
                {OLD + ".permission.READ", "@string/permission"}}) {
            Fixture fixture = fixture("missing-resolver-" + UUID.randomUUID());
            fixture.replace(edit[0], edit[1]);
            rejected(fixture, "ManifestValueResolver", () -> fixture.rewrite(NONE));
        }
    }

    private static void unresolvedResources() throws Exception {
        for (String result : new String[]{null, "@string/again", ""}) {
            Fixture fixture = fixture("unresolved-" + UUID.randomUUID());
            fixture.replace(OLD + ".provider\"", "@string/provider\"");
            rejected(fixture, result != null && result.isEmpty() ? "blank" : "resolve",
                    () -> new ApkCoexistenceRewriter().rewrite(fixture.root.toFile(), OLD, NEW,
                            null, reference -> result, NONE));
        }
    }

    private static void externalInstrumentation() throws Exception {
        Fixture fixture = fixture("external-instrumentation");
        fixture.replace("android:targetPackage=\"" + OLD + "\"", "android:targetPackage=\"another.package\"");
        fixture.rewrite(NONE);
        require(android(first(fixture.manifest(), "instrumentation"), "targetPackage").equals("another.package"),
                "External instrumentation target rewritten");
    }

    private static void splits() throws Exception {
        for (String attribute : new String[]{"split=\"feature\"", "configForSplit=\"base\"",
                "android:isFeatureSplit=\"true\"", "android:isSplitRequired=\"true\"",
                "android:requiredSplitTypes=\"abi\"", "android:splitTypes=\"density\""}) {
            Fixture fixture = fixture("split-" + UUID.randomUUID());
            fixture.replace("package=\"" + OLD + "\"", "package=\"" + OLD + "\" " + attribute);
            rejected(fixture, "split", () -> fixture.rewrite(NONE));
        }
        for (String extra : new String[]{"<uses-split android:name=\"feature\"/>",
                "<meta-data android:name=\"com.android.vending.splits.required\" android:value=\"true\"/>",
                "<meta-data android:name=\"com.android.vending.splits.required\" android:resource=\"@bool/required\"/>"}) {
            Fixture fixture = fixture("split-child-" + UUID.randomUUID());
            fixture.replace("</application>", extra + "</application>");
            rejected(fixture, "split", () -> fixture.rewrite(NONE));
        }
        Fixture application = fixture("application-split");
        application.replace("<application", "<application android:isSplitRequired=\"true\"");
        rejected(application, "split", () -> application.rewrite(NONE));
    }

    private static void falseSplitFlags() throws Exception {
        Fixture fixture = fixture("false-splits");
        fixture.replace("package=\"" + OLD + "\"", "package=\"" + OLD + "\" android:isFeatureSplit=\"false\"");
        fixture.replace("<application", "<application android:isSplitRequired=\"false\"");
        fixture.replace("</application>", "<meta-data android:name=\"com.android.vending.splits.required\" android:value=\"false\"/></application>");
        fixture.rewrite(NONE);
    }

    private static void opaqueFiles() throws Exception {
        Fixture fixture = fixture("opaque");
        fixture.write("smali/Unknown.smali", "not parsed\nLjava/lang/Class;->forName\nLdalvik/system/DexClassLoader;\n"
                + "Landroid/content/pm/PackageInfo;->signatures\nconst-string v0, \"" + OLD + "\"\n");
        fixture.write("lib/arm64-v8a/libpayload.so", "\u007fELF" + OLD);
        fixture.write("assets/hidden.dat", "dex\n035\0" + OLD);
        fixture.write("res/xml/not_decoded.xml", "\0not XML");
        fixture.replace("</application>", "<meta-data android:name=\"android.accounts.AccountAuthenticator\" android:resource=\"@xml/account\"/>"
                + "<meta-data android:name=\"com.google.android.geo.API_KEY\" android:value=\"key\"/></application>");
        fixture.replace("<application", "<protected-broadcast android:name=\"" + OLD + ".ACTION\"/><application");
        Map<String, byte[]> before = fixture.snapshot();
        fixture.rewrite(NONE);
        fixture.assertOnlyManifestChanged(before);
    }

    private static void structure() throws Exception {
        Fixture mismatch = fixture("wrong-package");
        mismatch.replace("package=\"" + OLD + "\"", "package=\"different.package\"");
        rejected(mismatch, "package", () -> mismatch.rewrite(NONE));
        Fixture duplicate = fixture("duplicate-application");
        duplicate.replace("</manifest>", "<application/></manifest>");
        rejected(duplicate, "one application", () -> duplicate.rewrite(NONE));
        Fixture missing = fixture("missing-manifest");
        Files.delete(missing.root.resolve("AndroidManifest.xml"));
        rejected(missing, "regular file", () -> missing.rewrite(NONE));
    }

    private static void entities() throws Exception {
        Fixture fixture = fixture("entities");
        Path outside = work.resolve("entity.txt");
        Files.write(outside, "external content".getBytes(StandardCharsets.UTF_8));
        fixture.write("AndroidManifest.xml", "<!DOCTYPE manifest [<!ENTITY xxe SYSTEM \""
                + outside.toUri() + "\">]>" + fixture.read("AndroidManifest.xml").replace("@string/title", "&xxe;"));
        rejected(fixture, "DTD/entity", () -> fixture.rewrite(NONE));
        require(Files.readString(outside).equals("external content"), "External content changed");
    }

    private static void malformed() throws Exception {
        Fixture utf8 = fixture("utf8");
        Files.write(utf8.root.resolve("AndroidManifest.xml"), new byte[]{(byte) 0xc3, (byte) 0x28});
        rejected(utf8, "UTF-8", () -> utf8.rewrite(NONE));
        Fixture xml = fixture("malformed");
        xml.replace("</application>", "</not-application>");
        rejected(xml, "parse", () -> xml.rewrite(NONE));
    }

    private static void xmlLimits() throws Exception {
        Fixture deep = fixture("deep");
        deep.replace("</application>", "<x>".repeat(65) + "</x>".repeat(65) + "</application>");
        rejected(deep, "depth", () -> deep.rewrite(NONE));
        Fixture attributes = fixture("attributes");
        StringBuilder extra = new StringBuilder("<x");
        for (int i = 0; i < 257; ++i) extra.append(" a").append(i).append("=\"x\"");
        attributes.replace("</application>", extra + "/></application>");
        rejected(attributes, "attribute", () -> attributes.rewrite(NONE));
    }

    private static void oversize() throws Exception {
        Fixture fixture = fixture("oversize");
        try (RandomAccessFile file = new RandomAccessFile(fixture.root.resolve("AndroidManifest.xml").toFile(), "rw")) {
            file.setLength(4L * 1024 * 1024 + 1);
        }
        rejected(fixture, "size limit", () -> fixture.rewrite(NONE));
    }

    private static void symlinks() throws Exception {
        Fixture fixture = fixture("manifest-link");
        Path outside = work.resolve("outside-manifest.xml");
        Files.move(fixture.root.resolve("AndroidManifest.xml"), outside);
        byte[] before = Files.readAllBytes(outside);
        Files.createSymbolicLink(fixture.root.resolve("AndroidManifest.xml"), outside);
        rejected(fixture, "symbolic link", () -> fixture.rewrite(NONE));
        require(Arrays.equals(before, Files.readAllBytes(outside)), "Symlink target modified");
        Fixture real = fixture("real-directory");
        Path link = work.resolve("root-link");
        Files.createSymbolicLink(link, real.root);
        rejected(real, "symbolic link", () -> new ApkCoexistenceRewriter().rewrite(link.toFile(), OLD, NEW, NONE));
    }

    private static void replacement() throws Exception {
        Fixture fixture = fixture("replacement");
        byte[] replacement = "<replacement/>".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean injected = new AtomicBoolean();
        try {
            fixture.rewrite(new ApkOperationCallback() {
                @Override public void checkCancelled() {}
                @Override public void onProgress(String phase, long completed, long total) {
                    if (phase.equals("coexist_rewrite") && injected.compareAndSet(false, true)) {
                        try { Files.write(fixture.root.resolve("AndroidManifest.xml"), replacement); }
                        catch (IOException exception) { throw new AssertionError(exception); }
                    }
                }
            });
            throw new AssertionError("Concurrent replacement ignored");
        } catch (IOException expected) {
            require(expected.getMessage().contains("changed"), "Unexpected replacement error");
        }
        require(injected.get() && Arrays.equals(replacement, Files.readAllBytes(fixture.root.resolve("AndroidManifest.xml"))),
                "Rewriter overwrote a changed Manifest");
    }

    private static void cancellation(boolean atWrite) throws Exception {
        Fixture fixture = fixture(atWrite ? "cancel-write" : "cancel-analysis");
        Map<String, byte[]> before = fixture.snapshot();
        AtomicBoolean reached = new AtomicBoolean(!atWrite);
        try {
            fixture.rewrite(new ApkOperationCallback() {
                @Override public void checkCancelled() throws InterruptedIOException {
                    if (reached.get()) throw new InterruptedIOException("injected cancellation");
                }
                @Override public void onProgress(String phase, long completed, long total) {
                    if (phase.equals("coexist_rewrite")) reached.set(true);
                }
            });
            throw new AssertionError("Cancellation ignored");
        } catch (InterruptedIOException expected) { require(reached.get(), "Write boundary not reached"); }
        fixture.assertUnchanged(before);
    }

    private static void interruption() throws Exception {
        Fixture fixture = fixture("interrupt");
        Thread.currentThread().interrupt();
        try {
            try { fixture.rewrite(NONE); throw new AssertionError("Thread interrupt ignored"); }
            catch (InterruptedIOException expected) {
                require(Thread.currentThread().isInterrupted(), "Interrupt flag cleared");
            }
        } finally { Thread.interrupted(); }
    }

    private static void packageValidation() {
        ApkCoexistenceRewriter.validateTargetPackage(OLD, "A_1.b2");
        ApkCoexistenceRewriter.validateTargetPackage(OLD, "a." + "b".repeat(253));
        for (String name : new String[]{null, OLD, "", "single", "1bad.name", "good.2bad",
                "a..b", "a.b-c", "a.中文", ".a.b", "a.b.", "a." + "b".repeat(254)}) {
            try {
                ApkCoexistenceRewriter.validateTargetPackage(OLD, name);
                throw new AssertionError("Invalid package accepted: " + name);
            } catch (IllegalArgumentException expected) { /* expected */ }
        }
    }

    private static Fixture fixture(String name) throws Exception {
        Fixture fixture = new Fixture(work.resolve(name));
        Files.createDirectories(fixture.root);
        fixture.write("AndroidManifest.xml", "<manifest xmlns:android=\"" + ANDROID + "\" package=\"" + OLD + "\""
                + " android:versionCode=\"12\" android:versionName=\"1.2\">"
                + "<uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"36\"/>"
                + "<permission android:name=\"" + OLD + ".permission.READ\" android:protectionLevel=\"normal\"/>"
                + "<uses-permission android:name=\"" + OLD + ".permission.READ\"/>"
                + "<queries><package android:name=\"" + OLD + "\"/></queries>"
                + "<application android:name=\".App\" android:label=\"@string/title\" android:backupAgent=\"Backup\""
                + " android:appComponentFactory=\"android.app.AppComponentFactory\" android:manageSpaceActivity=\".ManageSpace\""
                + " android:zygotePreloadName=\"Preload\">"
                + "<activity android:name=\".Main\" android:parentActivityName=\"Parent\" android:process=\"" + OLD + ".worker\""
                + " android:taskAffinity=\"" + OLD + ".task\"><intent-filter>"
                + "<action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/>"
                + "<action android:name=\"" + OLD + ".action.SELF\"/></intent-filter>"
                + "<meta-data android:name=\"android.support.PARENT_ACTIVITY\" android:value=\".Parent\"/>"
                + "<meta-data android:name=\"android.app.default_searchable\" android:value=\"Search\"/></activity>"
                + "<activity-alias android:name=\".Alias\" android:targetActivity=\".Main\" android:parentActivityName=\".Parent\"/>"
                + "<provider android:name=\".Provider\" android:authorities=\"" + OLD + ".provider\""
                + " android:permission=\"" + OLD + ".permission.READ\" android:exported=\"false\"/>"
                + "<receiver android:name=\".Receiver\" android:process=\":remote\"/>"
                + "<service android:name=\"Service\" android:permission=\"android.permission.BIND_JOB_SERVICE\"/>"
                + "<meta-data android:name=\"" + OLD + ".metadata\" android:value=\"" + OLD + ".constant\"/>"
                + "</application><instrumentation android:name=\".Runner\" android:targetPackage=\"" + OLD + "\"/></manifest>");
        fixture.write("resources.arsc", "\0original resource table " + OLD);
        fixture.write("classes.dex", "dex\n035\0original DEX " + OLD);
        fixture.write("classes2.dex", "dex\n035\0second DEX " + OLD);
        fixture.write("res/values/strings.xml", "<resources><string name=\"title\">Fixture</string>"
                + "<string name=\"provider\">" + OLD + ".provider</string></resources>");
        fixture.write("res/layout/main.xml", "<" + OLD + ".Widget xmlns:app=\"http://schemas.android.com/apk/res/" + OLD + "\"/>");
        fixture.write("assets/config.json", "{\"package\":\"" + OLD + "\"}");
        fixture.write("apktool.yml", "resourcesInfo:\n  packageName: " + OLD + "\n");
        return fixture;
    }

    private static final class Fixture {
        final Path root;
        Fixture(Path root) { this.root = root; }
        void write(String name, String content) throws IOException {
            Path path = root.resolve(name);
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        }
        String read(String name) throws IOException { return Files.readString(root.resolve(name)); }
        void replace(String from, String to) throws IOException {
            String manifest = read("AndroidManifest.xml");
            require(manifest.contains(from), "Fixture edit did not match: " + from);
            write("AndroidManifest.xml", manifest.replace(from, to));
        }
        Document manifest() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(root.resolve("AndroidManifest.xml").toFile());
        }
        ApkCoexistenceRewriter.Report rewrite(ApkOperationCallback callback) throws IOException {
            return new ApkCoexistenceRewriter().rewrite(root.toFile(), OLD, NEW, callback);
        }
        Map<String, byte[]> snapshot() throws IOException {
            Map<String, byte[]> result = new LinkedHashMap<>();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))::iterator) {
                    result.put(root.relativize(path).toString(), Files.readAllBytes(path));
                }
            }
            return result;
        }
        void assertUnchanged(Map<String, byte[]> before) throws IOException {
            Map<String, byte[]> after = snapshot();
            require(after.keySet().equals(before.keySet()), "Operation changed tree entries");
            for (String name : before.keySet()) {
                require(Arrays.equals(before.get(name), after.get(name)), "Failure modified " + name);
            }
        }
        void assertOnlyManifestChanged(Map<String, byte[]> before) throws IOException {
            Map<String, byte[]> after = snapshot();
            require(after.keySet().equals(before.keySet()), "Operation changed tree entries");
            require(!Arrays.equals(before.get("AndroidManifest.xml"), after.get("AndroidManifest.xml")), "Manifest unchanged");
            for (String name : before.keySet()) {
                if (name.equals("AndroidManifest.xml")) continue;
                require(Arrays.equals(before.get(name), after.get(name)), "Non-Manifest file modified: " + name);
            }
        }
    }

    private static void rejected(Fixture fixture, String message, Checked operation) throws Exception {
        Map<String, byte[]> before = fixture.snapshot();
        try { operation.run(); throw new AssertionError("Invalid fixture accepted: " + fixture.root); }
        catch (IOException expected) {
            require(expected.getMessage().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),
                    "Wanted '" + message + "', got: " + expected);
        }
        fixture.assertUnchanged(before);
    }

    private static Element first(Document document, String tag) {
        return (Element) document.getElementsByTagName(tag).item(0);
    }
    private static Element named(Document document, String tag, String name) {
        org.w3c.dom.NodeList elements = document.getElementsByTagName(tag);
        for (int i = 0; i < elements.getLength(); ++i) {
            Element element = (Element) elements.item(i);
            if (android(element, "name").equals(name)) return element;
        }
        throw new AssertionError("Missing " + tag + " named " + name);
    }
    private static String android(Element element, String name) {
        return element.getAttributeNS(ANDROID, name);
    }
    private interface Checked { void run() throws Exception; }
    private static void check(String name, Checked check) throws Exception {
        check.run(); ++checks; System.out.println("PASS " + name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
