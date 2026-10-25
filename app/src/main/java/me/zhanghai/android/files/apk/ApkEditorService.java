/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
package me.zhanghai.android.files.apk;

import com.reandroid.apk.*;
import com.reandroid.apkeditor.compile.BuildOptions;
import com.reandroid.apkeditor.decompile.DecompileOptions;
import com.reandroid.apkeditor.refactor.*;
import com.reandroid.apkeditor.protect.*;
import com.reandroid.apkeditor.info.InfoWriterText;
import com.reandroid.apkeditor.smali.SmaliCompiler;
import com.reandroid.apkeditor.smali.SmaliDecompiler;
import com.reandroid.arsc.chunk.xml.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;

/** In-process APKEditor workers. The caller serializes jobs and owns the disposable workspace. */
public final class ApkEditorService {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final long MAX_EXPANDED = 1024L * 1024 * 1024;
    private static final int MAX_FILES = 50000;
    private static final int MAX_SPLITS = 256;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    public List<File> extractContainer(File archive, File directory, ApkOperationCallback callback)
            throws IOException {
        if (archive.length() > MAX_BYTES) throw new IOException("分包容器超过 512 MiB。");
        new ApkSigningService().validateContainer(archive, callback);
        if (!directory.mkdir()) throw new IOException("无法创建分包工作目录。");
        List<File> files = new ArrayList<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                check(callback);
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".obb")) throw new IOException("此 XAPK 包含 OBB 数据，请使用支持 OBB 的安装方式。");
                if (!name.endsWith(".apk") || entry.isDirectory()) continue;
                if (files.size() >= MAX_SPLITS || entry.getSize() < 0
                        || (total += entry.getSize()) > MAX_BYTES) {
                    throw new IOException("分包数量或总体积超过处理上限。");
                }
                // Never use archive-controlled paths for extraction.
                File output = new File(directory, "split-" + files.size() + ".apk");
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(output)) {
                    copy(input, out, entry.getSize(), callback);
                }
                files.add(output);
            }
        }
        if (files.isEmpty()) throw new IOException("容器中未找到 APK 分包。");
        return files;
    }

    public void merge(List<File> files, File output, File workspace, ApkOperationCallback callback)
            throws IOException {
        if (files.size() < 2 || files.size() > MAX_SPLITS) {
            throw new IOException("请提供一个 base APK 和同一版本所需的分包。");
        }
        callback.onProgress("merge", 0, files.size());
        boolean complete = false;
        try (ApkBundle bundle = new ApkBundle()) {
            bundle.setAPKLogger(logger(callback));
            bundle.setManifestMerger(new AndroidManifestBlockMerger().setEnabled(true));
            String packageName = null;
            long version = -1, total = 0;
            int bases = 0;
            Set<String> names = new HashSet<>();
            Set<String> requiredTypes = new HashSet<>(), providedTypes = new HashSet<>();
            long resourceBytes = 0, expandedBytes = 0;
            List<String> dependencies = new ArrayList<>();
            ApkManifestReader baseInfo = null;
            for (File file : files) {
                check(callback);
                if ((total += file.length()) > MAX_BYTES) throw new IOException("分包总大小超过 512 MiB。");
                ApkCoexistenceService.validateInput(file, workspace, callback);
                new ApkSigningService().validateInput(file, callback);
                try (ZipFile zip = new ZipFile(file)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        expandedBytes += entry.getSize();
                        if (entry.getName().equals("resources.arsc")) resourceBytes += entry.getSize();
                    }
                }
                if (expandedBytes > MAX_EXPANDED || resourceBytes > 32L * 1024 * 1024) {
                    throw new IOException("分包的总展开体积或资源表超过合并上限。");
                }
                ApkManifestReader info = ApkManifestReader.read(file);
                if (packageName == null) { packageName = info.packageName; version = info.versionCode; }
                if (!packageName.equals(info.packageName) || version != info.versionCode) {
                    throw new IOException("只能合并同一包名、同一版本的分包。");
                }
                ApkModule module = ApkModule.loadApkFile(file);
                boolean added = false;
                try {
                    AndroidManifestBlock manifest = module.getAndroidManifest();
                    String split = manifest.getSplit();
                    if (split == null || split.isEmpty()) { ++bases; baseInfo = info; split = ""; }
                    if (!names.add(split)) throw new IOException("发现重复 base 或 split；请仅选择一个设备变体的分包。");
                    module.setModuleName(split.isEmpty() ? "base" : "split-" + names.size());
                    ResXmlElement root = manifest.getManifestElement();
                    splitTypes(root, "requiredSplitTypes", requiredTypes);
                    splitTypes(root, "splitTypes", providedTypes);
                    ResXmlAttribute config = root.searchAttributeByName("configForSplit");
                    if (config != null && config.getDataAsString() != null) dependencies.add(config.getDataAsString());
                    Iterator<ResXmlElement> uses = root.getElements("uses-split");
                    while (uses.hasNext()) {
                        String dependency = AndroidManifestBlock.getAndroidNameValue(uses.next());
                        if (dependency == null) throw new IOException("分包依赖缺少名称。");
                        dependencies.add(dependency);
                    }
                    bundle.addModule(module);
                    added = true;
                } finally {
                    if (!added) module.close();
                }
            }
            if (bases != 1) throw new IOException("合并必须包含且仅包含一个 base APK。");
            for (String dependency : dependencies) {
                if (!dependency.isEmpty() && !dependency.equals("base") && !names.contains(dependency)) {
                    throw new IOException("缺少依赖分包：" + dependency);
                }
            }
            requiredTypes.removeAll(providedTypes);
            if (!requiredTypes.isEmpty()) throw new IOException("缺少所需类型的分包：" + requiredTypes);
            check(callback);
            try (ApkModule merged = bundle.mergeModules(false)) {
                // ARSCLib sanitizes standard split flags; additionally remove component split names
                // and configForSplit, which must not survive conversion to a standalone APK.
                Iterator<ResXmlElement> elements = merged.getAndroidManifest().recursiveElements();
                while (elements.hasNext()) {
                    ResXmlElement element = elements.next();
                    element.removeAttributesWithName("configForSplit");
                    element.removeAttributesWithName("splitName");
                    element.removeAttributesWithId(0x01010549);
                }
                merged.refreshTable();
                merged.refreshManifest();
                write(merged, output, callback);
            }
            ApkCoexistenceService.validateInput(output, workspace, callback);
            new ApkSigningService().validateInput(output, callback);
            ApkManifestReader result = ApkManifestReader.read(output);
            if (result.requiresSplits || !packageName.equals(result.packageName)
                    || result.versionCode != version || result.minSdk != baseInfo.minSdk
                    || result.targetSdk != baseInfo.targetSdk) {
                throw new IOException("合并输出的 Manifest 校验失败。");
            }
            check(callback);
            complete = true;
        } catch (Cancelled error) { throw error.io; }
        catch (OutOfMemoryError error) { throw new IOException("内存不足，无法合并这些分包。", error); }
        finally { if (!complete) output.delete(); }
    }

    private static void splitTypes(ResXmlElement root, String name, Set<String> types) throws IOException {
        ResXmlAttribute attribute = root.searchAttributeByName(name);
        if (attribute == null) return;
        String value = attribute.getDataAsString();
        if (value == null) throw new IOException("无法解析分包类型：" + name);
        for (String type : value.split(",")) if (!type.trim().isEmpty()) types.add(type.trim());
    }

    public void decode(File input, File directory, ApkOperationCallback callback) throws IOException {
        callback.onProgress("decode", 0, -1);
        try (ApkModule module = ApkModule.loadApkFile(input)) {
            module.setAPKLogger(logger(callback));
            DecompileOptions options = new DecompileOptions();
            options.noCache = true;
            options.loadDex = 1;
            ApkModuleXmlDecoder decoder = new ApkModuleXmlDecoder(module);
            decoder.setSanitizePaths(true);
            decoder.setLogErrors(false);
            SmaliDecompiler smali = new SmaliDecompiler(module.hasTableBlock() ? module.getTableBlock() : null, options);
            smali.setApkLogger(logger(callback));
            decoder.setDexDecoder(smali);
            decoder.decode(directory);
            check(callback);
            validateTree(directory, callback);
        } catch (Cancelled error) { throw error.io; }
        catch (OutOfMemoryError error) { throw new IOException("内存不足，无法反编译此 APK。", error); }
    }

    public void build(File directory, File output, ApkOperationCallback callback) throws IOException {
        validateTree(directory, callback);
        if (!new File(directory, "AndroidManifest.xml").isFile()) {
            throw new IOException("请选择 APKEditor 反编译得到、包含 AndroidManifest.xml 的项目目录。");
        }
        callback.onProgress("rebuild", 0, -1);
        ApkModuleXmlEncoder encoder = new ApkModuleXmlEncoder();
        try (ApkModule module = encoder.getApkModule()) {
            module.setAPKLogger(logger(callback));
            encoder.setApkLogger(logger(callback));
            BuildOptions options = new BuildOptions();
            options.noCache = true;
            SmaliCompiler compiler = new SmaliCompiler(options);
            compiler.setApkLogger(logger(callback));
            encoder.setDexEncoder(compiler);
            encoder.scanDirectory(directory);
            write(module, output, callback);
        } catch (Cancelled error) { throw error.io; }
        catch (OutOfMemoryError error) { throw new IOException("内存不足，无法回编译此项目。", error); }
    }

    public void refactor(File input, File output, ApkOperationCallback callback) throws IOException {
        callback.onProgress("refactor", 0, -1);
        try (ApkModule module = ApkModule.loadApkFile(input)) {
            if (!module.hasTableBlock()) throw new IOException("此 APK 没有可重构的资源表。");
            module.setAPKLogger(logger(callback));
            TypeNameRefactor types = new TypeNameRefactor(module);
            types.setApkLogger(logger(callback));
            types.refactor();
            new AutoRefactor(module).refactor();
            new StringValueNameGenerator(module.getTableBlock()).refactor();
            module.refreshTable();
            write(module, output, callback);
        } catch (Cancelled error) { throw error.io; }
        catch (OutOfMemoryError error) { throw new IOException("内存不足，无法重构此 APK。", error); }
    }

    public void protect(File input, File output, ApkOperationCallback callback) throws IOException {
        callback.onProgress("protect", 0, -1);
        try (ApkModule module = ApkModule.loadApkFile(input)) {
            if (!module.hasTableBlock()) throw new IOException("此 APK 没有可保护的资源表。");
            module.setAPKLogger(logger(callback));
            ProtectorOptions options = new ProtectorOptions();
            options.skipManifest = true;
            options.keepTypes.add("all-types");
            Protector protector = new Protector(options) {
                @Override public void logMessage(String message) { checkpoint(callback); }
                @Override public void logVerbose(String message) { checkpoint(callback); }
            };
            protector.setApkModule(module);
            new DirectoryConfuser(protector).confuse();
            new FileNameConfuser(protector).confuse();
            new TableConfuser(protector).confuse();
            module.refreshTable();
            write(module, output, callback);
        } catch (Cancelled error) { throw error.io; }
        catch (OutOfMemoryError error) { throw new IOException("内存不足，无法保护此 APK。", error); }
    }

    public String info(File input, ApkOperationCallback callback) throws IOException {
        check(callback);
        ApkManifestReader info = ApkManifestReader.read(input);
        try (ApkModule module = ApkModule.loadApkFile(input)) {
            StringWriter text = new StringWriter();
            InfoWriterText writer = new InfoWriterText(text);
            AndroidManifestBlock manifest = module.getAndroidManifest();
            writer.writeNameValue("包名", info.packageName);
            writer.writeNameValue("版本", manifest.getVersionName());
            writer.writeNameValue("版本号", info.versionCode);
            writer.writeNameValue("最低 Android API", info.minSdk);
            writer.writeNameValue("目标 Android API", info.targetSdk);
            writer.writeNameValue("分包", manifest.getSplit());
            writer.writeNameValue("依赖分包", info.requiresSplits);
            writer.writeNameValue("大小（字节）", input.length());
            writer.writeNameValue("应用类", manifest.getApplicationClassName());
            writer.writeNameValue("启动 Activity", manifest.getMainActivityClassName());
            writer.writeArray("权限", manifest.getUsesPermissions().toArray());
            if (module.hasTableBlock()) {
                List<com.reandroid.arsc.chunk.PackageBlock> packages = new ArrayList<>();
                module.getTableBlock().listPackages().forEach(packages::add);
                writer.writePackageNames(packages);
            }
            writer.flush();
            check(callback);
            return text.toString();
        } catch (OutOfMemoryError error) { throw new IOException("内存不足，无法读取此 APK。", error); }
    }

    private static void write(ApkModule module, File output, ApkOperationCallback callback) throws IOException {
        check(callback);
        module.setApkSignatureBlock(null);
        module.getZipEntryMap().remove("stamp-cert-sha256");
        module.writeApk(output, (name, method, size) -> checkpoint(callback));
        check(callback);
    }

    public static List<File> validateTree(File directory, ApkOperationCallback callback) throws IOException {
        List<File> files = new ArrayList<>();
        long total = 0;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory.toPath())) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                check(callback);
                Path path = iterator.next();
                if (Files.isSymbolicLink(path) || directory.toPath().relativize(path).getNameCount() > 32
                        || files.size() >= MAX_FILES) throw new IOException("项目含符号链接或超过目录/文件数量上限。");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(path);
                    if (total > MAX_EXPANDED) throw new IOException("项目展开体积超过 1 GiB。");
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("项目中包含非普通文件。");
                }
                files.add(path.toFile());
            }
        }
        return files;
    }

    public static void zipProject(File directory, File output, ApkOperationCallback callback) throws IOException {
        List<File> files = validateTree(directory, callback);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            for (File file : files) {
                check(callback);
                if (file.isDirectory()) continue;
                zip.putNextEntry(new ZipEntry(directory.toPath().relativize(file.toPath()).toString()));
                try (InputStream input = new FileInputStream(file)) { copy(input, zip, file.length(), callback); }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream input, OutputStream output, long limit, ApkOperationCallback callback)
            throws IOException {
        byte[] buffer = new byte[65536];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            check(callback);
            if ((size += count) > limit) throw new IOException("文件内容超过声明大小。");
            output.write(buffer, 0, count);
        }
        if (size != limit) throw new IOException("文件内容不完整。");
    }
    private static APKLogger logger(ApkOperationCallback callback) {
        return new APKLogger() {
            public void logMessage(String message) { checkpoint(callback); }
            public void logVerbose(String message) { checkpoint(callback); }
            public void logError(String message, Throwable error) { checkpoint(callback); }
        };
    }
    private static void checkpoint(ApkOperationCallback callback) {
        try { check(callback); } catch (InterruptedIOException error) { throw new Cancelled(error); }
    }
    private static void check(ApkOperationCallback callback) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
        callback.checkCancelled();
    }
    private static final class Cancelled extends RuntimeException {
        final InterruptedIOException io;
        Cancelled(InterruptedIOException io) { super(io); this.io = io; }
    }
}
