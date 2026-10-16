/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk;

import brut.androlib.ApkBuilder;
import brut.androlib.ApkDecoder;
import brut.androlib.Config;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.meta.ApkInfo;
import brut.androlib.res.table.ResId;
import brut.util.OS;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import me.zhanghai.android.files.apk.core.ApkCoexistenceRewriter;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;

/**
 * Synchronous, bounded Manifest edit/build worker. DEX and resources are copied unchanged.
 * The caller owns the private workspace and must
 * serialize Apktool operations, sign the returned APK, verify it, and publish a separate output.
 */
public final class ApkCoexistenceService {
    public static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_RESOURCES_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;
    private static final long SPACE_MARGIN = 64L * 1024 * 1024;

    public ApkCoexistenceRewriter.Report rebuild(File input, File output, File workspace,
            Config config, String targetPackage, ApkOperationCallback callback) throws IOException {
        return rebuild(input, output, workspace, config, targetPackage, null, callback);
    }

    public ApkCoexistenceRewriter.Report rebuild(File input, File output, File workspace,
            Config config, String targetPackage, String originalLabel,
            ApkOperationCallback callback) throws IOException {
        return rebuild(input, output, workspace, config, targetPackage, originalLabel, null, callback);
    }

    public ApkCoexistenceRewriter.Report rebuild(File input, File output, File workspace,
            Config config, String targetPackage, String originalLabel,
            ApkCoexistenceRewriter.ManifestValueResolver resolver,
            ApkOperationCallback callback) throws IOException {
        if (callback == null) callback = ApkOperationCallback.NONE;
        checkCancelled(callback);
        input = input.getCanonicalFile();
        workspace = workspace.getCanonicalFile();
        if (!workspace.isDirectory() || !input.isFile()
                || !input.toPath().startsWith(workspace.toPath())) {
            throw new IOException("共存输入必须位于独立的私有工作目录中。");
        }
        if (!output.getAbsoluteFile().equals(output.getCanonicalFile()) || output.exists()
                || !workspace.equals(output.getParentFile().getCanonicalFile())) {
            throw new IOException("共存重建必须使用工作目录中不存在的新输出文件。");
        }
        File decoded = new File(workspace, "decoded");
        File temporary = new File(workspace, "apktool-tmp");
        if (decoded.exists() || !temporary.mkdir()) {
            throw new IOException("共存工作目录已被占用，请重新开始操作。");
        }
        ApkManifestReader original = ApkManifestReader.read(input);
        if (original.requiresSplits) {
            throw new IOException("仅支持完整的单体 APK；拆分包和依赖拆分包的 base APK 暂不支持共存。");
        }
        try {
            ApkCoexistenceRewriter.validateTargetPackage(original.packageName, targetPackage);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        if (config.getAaptBinary() == null) throw new IOException("未配置 APK 资源编译器。");
        File aapt = new File(config.getAaptBinary());
        if (!aapt.isFile() || !aapt.canExecute()) {
            throw new IOException("当前安装包缺少可执行的 arm64-v8a APK 资源编译器，请使用完整安装包。");
        }
        callback.onProgress("analyze", 0, -1);
        validateInput(input, workspace, callback);
        // Validate the raw ZIP before Apktool extracts any untrusted entry.
        new ApkSigningService().validateInput(input, callback);
        config.setJobs(1);
        config.setForced(false);
        config.setAnalysisMode(true);
        config.setDecodeResources(Config.DecodeResources.ONLY_MANIFEST);
        config.setDecodeSources(Config.DecodeSources.NONE);
        config.setKeepBrokenResources(false);
        config.setCopyOriginal(false);
        config.setTempDirectory(temporary);
        boolean complete = false;
        try {
            callback.onProgress("decode", 0, -1);
            ApkDecoder decoder = new ApkDecoder(input, config);
            decoder.decode(decoded);
            checkCancelled(callback);
            ApkInfo apkInfo = decoder.getApkInfo();
            if (!apkInfo.getUsesFramework().getIds().contains(1)) {
                apkInfo.getUsesFramework().getIds().add(1);
            }
            String resourcePackage = apkInfo.getResourcesInfo().getPackageName();
            if (resourcePackage == null) resourcePackage = original.packageName;
            if (new File(decoded, "resources.arsc").isFile()) {
                // Link only the new Manifest against the original resource table. This keeps
                // icon/label/layout IDs and the compiled table byte-for-byte unchanged.
                apkInfo.getResourcesInfo().setPackageName(resourcePackage);
                if (!apkInfo.getUsesLibrary().contains(resourcePackage)) {
                    apkInfo.getUsesLibrary().add(resourcePackage);
                }
                config.getLibraryFiles().put(resourcePackage, new String[] {input.getPath()});
            }
            final String originalResourcePackage = resourcePackage;
            ApkCoexistenceRewriter.ManifestValueResolver manifestResolver = resolver == null
                    ? null : reference -> resolver.resolve(
                            reference.startsWith("@") && reference.indexOf(':') < 0
                                    && reference.indexOf('/') >= 0
                                    ? "@" + originalResourcePackage + ":" + reference.substring(1)
                                    : reference);
            callback.onProgress("rewrite", 0, -1);
            ApkCoexistenceRewriter.Report report = new ApkCoexistenceRewriter().rewrite(
                    decoded, original.packageName, targetPackage, originalLabel,
                    manifestResolver, callback);
            apkInfo.save(decoded);
            checkCancelled(callback);
            callback.onProgress("rebuild", 0, -1);
            new ApkBuilder(decoded, config).build(output);
            checkCancelled(callback);
            new ApkSigningService().validateInput(output, callback);
            ApkManifestReader rebuilt = ApkManifestReader.read(output);
            if (!targetPackage.equals(rebuilt.packageName) || rebuilt.requiresSplits
                    || original.versionCode != rebuilt.versionCode
                    || original.minSdk != rebuilt.minSdk || original.targetSdk != rebuilt.targetSdk) {
                throw new IOException("重建后的包名、版本或 SDK 信息与预期不一致，已拒绝输出。");
            }
            complete = true;
            return report;
        } catch (AndrolibException | RuntimeException exception) {
            checkCancelled(callback);
            throw new IOException("APK 共存处理失败：" + diagnostic(exception), exception);
        } catch (OutOfMemoryError error) {
            throw new IOException("内存不足，无法解码或重建此 APK。请处理较小的单体 APK。", error);
        } finally {
            // No future job may inherit this job's temporary directory after it has been removed.
            OS.setDefaultTempDirectory(null);
            ResId.clearCache();
            if (!complete) {
                // The caller also retries cleanup of the whole workspace; nothing is published.
                output.delete();
            }
        }
    }

    /** Validate archive paths and reserve bounded disk space before configuring a signing key. */
    public static void validateInput(File input, File workspace, ApkOperationCallback callback)
            throws IOException {
        if (callback == null) callback = ApkOperationCallback.NONE;
        if (input.length() > MAX_INPUT_BYTES) {
            throw new IOException("共存暂支持不超过 512 MiB 的单体 APK。");
        }
        new ApkSigningService().validateArchiveStructure(input, callback);
        long expanded = 0;
        int count = 0;
        Set<String> directories = new HashSet<>();
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(callback);
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                int depth = 0;
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    if (++depth > 32) throw new IOException("APK 的目录层级过深，暂不支持制作共存版。");
                    directories.add(name.substring(0, slash));
                    if (directories.size() + count > MAX_ENTRIES) {
                        throw new IOException("APK 的文件和目录数量超过共存处理上限。");
                    }
                }
                long size = entry.getSize();
                if (++count + directories.size() > MAX_ENTRIES || size < 0 || size > MAX_EXPANDED_BYTES
                        || expanded > MAX_EXPANDED_BYTES - size) {
                    throw new IOException("APK 的文件数量或展开体积超过共存处理上限。");
                }
                expanded += size;
                if (name.equals("resources.arsc") && size > MAX_RESOURCES_BYTES) {
                    throw new IOException("资源表超过 16 MiB，暂不能在可控内存内制作共存版。");
                }
            }
        }
        // Manifest-only decoding keeps the original code and resource payloads.
        long estimate = expanded * 2 + input.length() * 4 + SPACE_MARGIN;
        if (workspace.getUsableSpace() < estimate) {
            throw new IOException("可用空间不足，解码、重建和签名预计至少需要 "
                    + ((estimate + 1024 * 1024 - 1) / (1024 * 1024)) + " MiB 临时空间。");
        }
    }

    private static void checkCancelled(ApkOperationCallback callback) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
        callback.checkCancelled();
    }

    private static String diagnostic(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isEmpty()) text = error.getClass().getSimpleName();
        return text.length() <= 4096 ? text : text.substring(0, 4096);
    }
}
