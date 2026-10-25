/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
import com.reandroid.apk.ApkModule;
import com.reandroid.apkeditor.protect.Protector;
import com.reandroid.apkeditor.protect.ProtectorOptions;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.DataType;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import me.zhanghai.android.files.apk.*;
import me.zhanghai.android.files.apk.core.*;

/** Real binary APKs, shared split merging, resource/Smali round trips and independent signatures. */
public final class ApkEditorTest {
    private static final String PACKAGE = "com.example.editor";
    private static final ApkOperationCallback NONE = ApkOperationCallback.NONE;
    private static final ApkEditorService EDITOR = new ApkEditorService();
    private static File work;
    private static int checks;
    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        File golden = new File(work, "resource-fixture.apk");
        try (ApkModule module = ApkModule.loadApkFile(new File(args[1], "golden-aligned-in.apk"))) {
            String path = "res/raw/payload.bin";
            module.getTableBlock().pickOne().getOrCreate("", "raw", "payload").setDataAsString(path);
            module.add(new com.reandroid.archive.ByteInputSource("resource payload".getBytes(), path));
            module.refreshTable();
            module.writeApk(golden);
        }
        File original = new File(work, "original.apk");
        fixture(golden, original, null, false);
        File clone = new File(work, "clone.apk");
        ApkCoexistenceRewriter.Report report = new ApkCoexistenceService().rebuild(original, clone, work,
                PACKAGE + ".clone", "A 'quoted' \\ title", reference -> PACKAGE + ".resource", NONE);
        require(report.renamedAuthorities == 3, "all authorities reported");
        require(report.renamedPermissions == 1, "permission reported");
        require(ApkManifestReader.read(clone).packageName.equals(PACKAGE + ".clone"), "binary package changed");
        try (ApkModule module = ApkModule.loadApkFile(clone)) {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            ResXmlElement application = manifest.getApplicationElement();
            require(manifest.getManifestElement().searchAttributeByName("sharedUserId") == null, "shared UID removed");
            ResXmlElement provider = application.getElement("provider");
            require((PACKAGE + ".Provider").equals(AndroidManifestBlock.getAndroidNameValue(provider)), "relative class preserved");
            require((PACKAGE + ".clone.files;" + PACKAGE + ".clone.fixed.authority").equals(
                    provider.searchAttributeByName("authorities").getDataAsString()), "semicolon authorities renamed");
            require("external.authority".equals(manifest.getManifestElement().getElement("queries")
                    .getElement("provider").searchAttributeByName("authorities").getDataAsString()), "queries preserved");
            require((PACKAGE + ".clone.permission").equals(AndroidManifestBlock.getAndroidNameValue(
                    manifest.getManifestElement().getElement("permission"))), "permission declaration renamed");
        }
        File noLabel = new File(work, "no-label.apk"), labeled = new File(work, "labeled.apk");
        try (ApkModule module = ApkModule.loadApkFile(original)) {
            module.getAndroidManifest().getApplicationElement().removeAttributesWithId(0x01010001);
            module.writeApk(noLabel);
        }
        String displayName = "A 'quoted' \\ title";
        new ApkCoexistenceService().rebuild(noLabel, labeled, work, PACKAGE + ".labeled",
                displayName, reference -> PACKAGE + ".resource", NONE);
        try (ApkModule module = ApkModule.loadApkFile(labeled)) {
            require(displayName.equals(module.getAndroidManifest().getApplicationLabelString()),
                    "binary fallback label has no text-resource escapes");
        }
        File obfuscated = new File(work,"obfuscated.apk"), named = new File(work,"named.apk");
        try (ApkModule module = ApkModule.loadApkFile(original)) {
            module.getAndroidManifest().getApplicationElement().getElement("provider")
                    .searchAttributeByResourceId(0x01010018).setName("hidden",0x01010018);
            module.writeApk(obfuscated);
        }
        new ApkCoexistenceService().rebuild(obfuscated,named,work,PACKAGE + ".named",null,
                reference -> PACKAGE + ".resource",NONE);
        try (ApkModule module = ApkModule.loadApkFile(named)) {
            require(module.getAndroidManifest().getApplicationElement().getElement("provider")
                    .searchAttributeByResourceId(0x01010018).getDataAsString().startsWith(PACKAGE + ".named."),
                    "obfuscated attribute names follow framework IDs");
        }
        samePayloads(original, clone);
        fail(() -> new ApkCoexistenceService().rebuild(original, new File(work,"same.apk"), work, PACKAGE, NONE));
        File cancelled = new File(work, "cancelled.apk");
        try {
            fail(() -> new ApkCoexistenceService().rebuild(original, cancelled, work, PACKAGE + ".cancel",
                    null, reference -> PACKAGE + ".resource", new ApkOperationCallback() {
                public void onProgress(String stage, long completed, long total) {
                    if (stage.equals("rebuild")) Thread.currentThread().interrupt();
                }
                public void checkCancelled() throws InterruptedIOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
                }
            }));
        } finally { Thread.interrupted(); }
        require(!cancelled.exists(), "cancelled edit has no output");

        File base = new File(work, "base.apk"), split = new File(work, "config.apk");
        fixture(golden, base, null, true);
        fixture(golden, split, "config.en", false);
        File merged = new File(work, "merged.apk");
        EDITOR.merge(Arrays.asList(base, split), merged, work, NONE);
        require(!ApkManifestReader.read(merged).requiresSplits, "merge removes split requirements");
        try (ZipFile zip = new ZipFile(merged)) { require(zip.getEntry("assets/from-split.txt") != null, "split payload retained"); }
        try (ApkModule module = ApkModule.loadApkFile(merged)) {
            require(module.getAndroidManifest().getApplicationElement().getElement("service") != null,
                    "feature component merged");
        }
        File mergedClone = new File(work, "merged-clone.apk");
        new ApkCoexistenceService().rebuild(merged, mergedClone, work, PACKAGE + ".clone", null,
                reference -> PACKAGE + ".resource", NONE);
        require(ApkManifestReader.read(mergedClone).packageName.endsWith(".clone"), "merged coexistence");
        fail(() -> EDITOR.merge(Arrays.asList(split), new File(work,"no-base.apk"), work, NONE));
        fail(() -> EDITOR.merge(Arrays.asList(base, base), new File(work,"duplicate.apk"), work, NONE));
        File foreign = new File(work,"foreign.apk");
        try (ApkModule module = ApkModule.loadApkFile(split)) {
            module.getAndroidManifest().setPackageName("com.other.app"); module.writeApk(foreign);
        }
        fail(() -> EDITOR.merge(Arrays.asList(base, foreign), new File(work,"mixed.apk"), work, NONE));
        File wrongVersion = new File(work,"wrong-version.apk");
        try (ApkModule module = ApkModule.loadApkFile(split)) {
            module.getAndroidManifest().setVersionCode(999); module.writeApk(wrongVersion);
        }
        fail(() -> EDITOR.merge(Arrays.asList(base, wrongVersion), new File(work,"versions.apk"), work, NONE));
        File missingDependency = new File(work,"missing-dependency.apk");
        try (ApkModule module = ApkModule.loadApkFile(split)) {
            ResXmlElement uses = module.getAndroidManifest().getManifestElement().newElement("uses-split");
            attribute(uses, "name", 0x01010003, "feature.missing"); module.writeApk(missingDependency);
        }
        fail(() -> EDITOR.merge(Arrays.asList(base, missingDependency), new File(work,"missing.apk"), work, NONE));
        File typedBase = new File(work,"typed-base.apk");
        try (ApkModule module = ApkModule.loadApkFile(base)) {
            attribute(module.getAndroidManifest().getManifestElement(),"requiredSplitTypes",
                    com.reandroid.app.AndroidManifest.ID_requiredSplitTypes,"base__abi");
            module.writeApk(typedBase);
        }
        fail(() -> EDITOR.merge(Arrays.asList(typedBase, split),new File(work,"missing-type.apk"),work,NONE));
        File typedSplit = new File(work,"typed-split.apk");
        try (ApkModule module = ApkModule.loadApkFile(split)) {
            attribute(module.getAndroidManifest().getManifestElement(),"splitTypes",
                    com.reandroid.app.AndroidManifest.ID_splitTypes,"base__abi");
            module.writeApk(typedSplit);
        }
        File typedMerged = new File(work,"typed-merged.apk");
        EDITOR.merge(Arrays.asList(typedBase,typedSplit),typedMerged,work,NONE);
        require(!ApkManifestReader.read(typedMerged).requiresSplits,"required split types satisfied");
        File archive = new File(work, "splits.apks");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            add(zip, "nested/base.apk", Files.readAllBytes(base.toPath()));
            add(zip, "nested/split.apk", Files.readAllBytes(split.toPath()));
        }
        List<File> extracted = EDITOR.extractContainer(archive, new File(work,"extracted"), NONE);
        require(extracted.size() == 2, "nested container APKs extracted");
        File archiveMerged = new File(work,"container-merged.apk");
        EDITOR.merge(extracted, archiveMerged, work, NONE);
        require(!ApkManifestReader.read(archiveMerged).requiresSplits, "container merge uses same worker");
        File malicious = new File(work, "unsafe.apks");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(malicious))) {
            add(zip, "../escaped.apk", Files.readAllBytes(base.toPath()));
        }
        fail(() -> EDITOR.extractContainer(malicious, new File(work,"unsafe"), NONE));

        // AOSP's valid DEX and compiled resources exercise real XML/Smali encoding APIs.
        File project = new File(work,"decoded");
        EDITOR.decode(golden, project, NONE);
        require(new File(project,"AndroidManifest.xml").isFile(), "Manifest decoded");
        require(EDITOR.validateTree(project, NONE).stream().anyMatch(f -> f.getName().endsWith(".smali")), "DEX decompiled");
        File built = new File(work,"built.apk");
        EDITOR.build(project, built, NONE);
        require(ApkManifestReader.read(built).packageName.equals(ApkManifestReader.read(golden).packageName), "round-trip package");
        File refactored = new File(work,"refactored.apk"), protectedApk = new File(work,"protected.apk");
        EDITOR.refactor(golden, refactored, NONE);
        protection(golden, protectedApk);
        require(EDITOR.info(refactored,NONE).contains(ApkManifestReader.read(golden).packageName), "APKEditor info");
        File projectZip = new File(work,"project.zip");
        EDITOR.zipProject(project, projectZip, NONE);
        try (ZipFile zip = new ZipFile(projectZip)) { require(zip.getEntry("AndroidManifest.xml") != null, "project archive"); }
        File unsafeProject = new File(work,"unsafe-project");
        require(unsafeProject.mkdir(),"prepare hostile project fixture");
        Files.writeString(new File(unsafeProject,"AndroidManifest.xml").toPath(),
                "<!DOCTYPE manifest [<!ENTITY file SYSTEM 'file:///etc/passwd'>]>"
                + "<manifest package='com.example.test'>&file;</manifest>");
        fail(() -> EDITOR.build(unsafeProject,new File(work,"unsafe-project.apk"),NONE));
        File linkedProject = new File(work,"linked-project");
        require(linkedProject.mkdir(),"prepare symlink project fixture");
        Files.createSymbolicLink(new File(linkedProject,"outside").toPath(),golden.toPath());
        fail(() -> EDITOR.validateTree(linkedProject,NONE));
        File keyFile = new File(work,"key.p12");
        char[] password = "public-test-password".toCharArray();
        try (SigningKeyStoreService.SigningKey key = new SigningKeyStoreService().generate(keyFile,
                "test", password, "Public disposable test key")) {
            int index = 0;
            for (File apk : Arrays.asList(clone, merged, mergedClone, built, refactored, protectedApk)) {
                File signed = new File(work,"signed-" + index++ + ".apk");
                int minSdk = ApkManifestReader.read(apk).minSdk;
                new ApkSigningService().sign(apk, signed, work, key,
                        ApkSigningService.Options.forMinSdk(minSdk), NONE);
                require(new ApkVerificationService().verify(signed,minSdk,NONE).verified, "independent output signature");
                if (apk.equals(protectedApk)) {
                    try (ZipFile before = new ZipFile(apk); ZipFile after = new ZipFile(signed)) {
                        for (ZipEntry entry : Collections.list(before.entries())) {
                            if (entry.getName().startsWith("META-INF/")) continue;
                            require(Arrays.equals(payload(before, entry.getName()), payload(after, entry.getName())),
                                    "signing preserves protected bytes and names: " + entry.getName());
                        }
                    }
                    ApkManifestReader.read(signed);
                    ApkCoexistenceService.validateInput(signed, work, NONE);
                }
            }
        }
        System.out.println("APKEditor integration: " + checks + " checks passed");
    }

    private static void protection(File golden, File protectedApk) throws Exception {
        File input = new File(work, "protection-input.apk");
        try (ApkModule module = ApkModule.loadApkFile(golden)) {
            // Exercise the full upstream dictionaries, including absolute paths and backslashes.
            for (int i = 0; i < 32; ++i) {
                String name = "payload_" + i;
                String path = "res/raw/" + name + ".bin";
                module.getTableBlock().pickOne().getOrCreate("", "raw", name).setDataAsString(path);
                module.add(new com.reandroid.archive.ByteInputSource(new byte[]{(byte) i}, path));
            }
            module.refreshTable();
            module.writeApk(input);
        }
        File upstream = new File(work, "upstream-protected.apk");
        ProtectorOptions options = new ProtectorOptions();
        options.inputFile = input;
        options.outputFile = upstream;
        options.validate();
        Protector protector = new Protector(options);
        protector.setEnableLog(false);
        protector.runCommand();
        EDITOR.protect(input, protectedApk, NONE);
        try (ZipFile original = new ZipFile(input); ZipFile expected = new ZipFile(upstream);
                ZipFile actual = new ZipFile(protectedApk)) {
            Set<String> expectedNames = new HashSet<>(), actualNames = new HashSet<>();
            for (ZipEntry entry : Collections.list(expected.entries())) expectedNames.add(entry.getName());
            for (ZipEntry entry : Collections.list(actual.entries())) actualNames.add(entry.getName());
            require(expectedNames.equals(actualNames), "resource names match upstream default protection");
            require(actualNames.stream().anyMatch(name -> name.startsWith("/")), "upstream absolute paths retained");
            require(actualNames.stream().anyMatch(name -> name.contains("\\")), "upstream backslashes retained");
            require(Arrays.equals(payload(expected, "resources.arsc"), payload(actual, "resources.arsc")),
                    "resource table protection matches upstream byte for byte");
            require(!Arrays.equals(payload(original, "AndroidManifest.xml"), payload(actual, "AndroidManifest.xml")),
                    "upstream Manifest protection enabled");
            for (String name : expectedNames) {
                // Upstream intentionally randomizes Manifest protection.
                if (!name.equals("AndroidManifest.xml")) {
                    require(Arrays.equals(payload(expected, name), payload(actual, name)),
                            "upstream protected payload preserved: " + name);
                }
            }
            require(Arrays.equals(payload(original, "classes.dex"), payload(actual, "classes.dex")),
                    "DEX protection remains off by default");
        }
        ApkManifestReader before = ApkManifestReader.read(input), after = ApkManifestReader.read(protectedApk);
        require(before.packageName.equals(after.packageName) && before.versionCode == after.versionCode
                && before.minSdk == after.minSdk && before.targetSdk == after.targetSdk
                && before.requiresSplits == after.requiresSplits, "protected Manifest identity preserved");
        ApkCoexistenceService.validateInput(protectedApk, work, NONE);
        new ApkSigningService().validateInput(protectedApk, NONE);
        File repeated = new File(work, "repeated-protection.apk");
        fail(() -> EDITOR.protect(protectedApk, repeated, NONE));
        require(!repeated.exists(), "upstream protected-input guard produces no output");
        File cancelled = new File(work, "cancelled-protection.apk");
        fail(() -> EDITOR.protect(input, cancelled, new ApkOperationCallback() {
            private int checks;
            @Override public void checkCancelled() throws InterruptedIOException {
                if (++checks >= 4) throw new InterruptedIOException("test cancellation");
            }
            @Override public void onProgress(String phase, long completed, long total) { }
        }));
        require(!cancelled.exists(), "cancelled upstream protection produces no output");
    }

    private static byte[] payload(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing test entry: " + name);
        try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
    }
    private static void fixture(File source, File target, String split, boolean requiresSplits) throws Exception {
        try (ApkModule module = ApkModule.loadApkFile(source)) {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            manifest.setPackageName(PACKAGE); manifest.setVersionCode(17);
            manifest.setMinSdkVersion(30); manifest.setTargetSdkVersion(36);
            ResXmlElement root = manifest.getManifestElement();
            ResXmlElement app = manifest.getApplicationElement();
            if (split != null) {
                manifest.setSplit(split, true);
                app.removeElementsIf(element -> true);
                ResXmlElement service = app.newElement("service");
                attribute(service,"name",0x01010003,".FeatureService");
                for (String name : new String[]{"classes.dex","resources.arsc"}) module.getZipEntryMap().remove(name);
                module.add(new com.reandroid.archive.ByteInputSource("from split".getBytes(), "assets/from-split.txt"));
            } else {
                attribute(root,"sharedUserId",0x0101000b,PACKAGE + ".shared");
                if (requiresSplits) root.getOrCreateAndroidAttribute("isSplitRequired",0x01010591).setValueAsBoolean(true);
                ResXmlElement permission = root.newElement("permission");
                attribute(permission,"name",0x01010003,PACKAGE + ".permission");
                ResXmlElement provider = app.newElement("provider");
                attribute(provider,"name",0x01010003,".Provider");
                attribute(provider,"permission",0x01010006,PACKAGE + ".permission");
                attribute(provider,"authorities",0x01010018,PACKAGE + ".files;fixed.authority");
                ResXmlElement resource = app.newElement("provider");
                attribute(resource,"name",0x01010003,".ResourceProvider");
                ResXmlAttribute authority = resource.getOrCreateAndroidAttribute("authorities",0x01010018);
                authority.setDataType(DataType.REFERENCE); authority.setData(0x7f090001);
                ResXmlElement query = root.newElement("queries").newElement("provider");
                attribute(query,"authorities",0x01010018,"external.authority");
            }
            manifest.refresh(); module.writeApk(target);
        }
    }
    private static void attribute(ResXmlElement element,String name,int id,String value) {
        element.getOrCreateAndroidAttribute(name,id).setDataAsString(value);
    }
    private static void samePayloads(File source,File target) throws Exception {
        try (ZipFile before = new ZipFile(source); ZipFile after = new ZipFile(target)) {
            require(before.size() == after.size(),"entry names preserved");
            Enumeration<? extends ZipEntry> entries = before.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if(entry.getName().equals("AndroidManifest.xml")) continue;
                try(InputStream a=before.getInputStream(entry); InputStream b=after.getInputStream(after.getEntry(entry.getName()))) {
                    require(Arrays.equals(a.readAllBytes(),b.readAllBytes()),"unchanged payload " + entry.getName());
                }
            }
        }
    }
    private static void add(ZipOutputStream zip,String name,byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }
    private static void require(boolean condition,String name) { if(!condition)throw new AssertionError(name);++checks; }
    private interface Action {void run() throws Exception;}
    private static void fail(Action action) throws Exception {
        try {action.run();} catch(IOException expected) {++checks;return;}
        throw new AssertionError("Expected rejection");
    }
}
