/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
package me.zhanghai.android.files.apk;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.DataType;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import me.zhanghai.android.files.apk.core.ApkCoexistenceRewriter;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkSigningService;

/** Edits binary Manifest attributes with ARSCLib; all other entry contents stay original. */
public final class ApkCoexistenceService {
    public static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_RESOURCES_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;
    private static final long SPACE_MARGIN = 64L * 1024 * 1024;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    public ApkCoexistenceRewriter.Report rebuild(File input, File output, File workspace,
            String targetPackage, ApkOperationCallback callback) throws IOException {
        return rebuild(input, output, workspace, targetPackage, null, null, callback);
    }

    public ApkCoexistenceRewriter.Report rebuild(File input, File output, File workspace,
            String targetPackage, String originalLabel,
            ApkCoexistenceRewriter.ManifestValueResolver resolver,
            ApkOperationCallback callback) throws IOException {
        if (callback == null) callback = ApkOperationCallback.NONE;
        checkCancelled(callback);
        input = input.getCanonicalFile();
        workspace = workspace.getCanonicalFile();
        if (!workspace.isDirectory() || !input.isFile()
                || !input.toPath().startsWith(workspace.toPath()) || output.exists()
                || !output.getCanonicalFile().toPath().startsWith(workspace.toPath())) {
            throw new IOException("共存处理必须使用私有工作目录及新的输出文件。");
        }
        validateInput(input, workspace, callback);
        new ApkSigningService().validateInput(input, callback);
        ApkManifestReader original = ApkManifestReader.read(input);
        if (original.requiresSplits) throw new IOException("请同时选择 base 和所需分包，或选择完整分包容器。");
        boolean complete = false;
        try (ZipFile zip = new ZipFile(input)) {
            callback.onProgress("decode", 0, -1);
            AndroidManifestBlock manifest;
            try (InputStream stream = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
                manifest = AndroidManifestBlock.load(stream);
            }
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Map<Element, ResXmlElement> elements = new LinkedHashMap<>();
            Map<Attr, ResXmlAttribute> attributes = new LinkedHashMap<>();
            Map<Attr, String> before = new IdentityHashMap<>();
            document.appendChild(project(manifest.getManifestElement(), document, elements,
                    attributes, before, callback, 0));
            callback.onProgress("rewrite", 0, -1);
            ApkCoexistenceRewriter.Report report = new ApkCoexistenceRewriter().rewriteManifest(
                    document, original.packageName, targetPackage, originalLabel, resolver, callback);
            for (Map.Entry<Attr, ResXmlAttribute> entry : attributes.entrySet()) {
                checkCancelled(callback);
                Attr attr = entry.getKey();
                if (attr.getOwnerElement() == null) entry.getValue().removeSelf();
                else if (!attr.getValue().equals(before.get(attr))) {
                    entry.getValue().setDataAsString(attr.getValue());
                }
            }
            for (Map.Entry<Element, ResXmlElement> entry : elements.entrySet()) {
                Element element = entry.getKey();
                Attr label = element.getAttributeNodeNS(ANDROID, "label");
                if (label != null && !attributes.containsKey(label)) {
                    entry.getValue().getOrCreateAndroidAttribute("label", 0x01010001)
                            .setDataAsString(label.getValue());
                }
            }
            manifest.refresh();
            byte[] binary = manifest.getBytes();
            callback.onProgress("rebuild", 0, -1);
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                byte[] buffer = new byte[65536];
                while (entries.hasMoreElements()) {
                    checkCancelled(callback);
                    ZipEntry old = entries.nextElement();
                    ZipEntry next = new ZipEntry(old.getName());
                    next.setTime(old.getTime());
                    boolean changed = old.getName().equals("AndroidManifest.xml");
                    if (!changed && old.getMethod() == ZipEntry.STORED) {
                        next.setMethod(ZipEntry.STORED);
                        next.setSize(old.getSize());
                        next.setCrc(old.getCrc());
                    }
                    out.putNextEntry(next);
                    if (changed) out.write(binary);
                    else try (InputStream stream = zip.getInputStream(old)) {
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            checkCancelled(callback);
                            out.write(buffer, 0, count);
                        }
                    }
                    out.closeEntry();
                }
            }
            checkCancelled(callback);
            new ApkSigningService().validateInput(output, callback);
            ApkManifestReader rebuilt = ApkManifestReader.read(output);
            if (!targetPackage.equals(rebuilt.packageName) || rebuilt.requiresSplits
                    || original.versionCode != rebuilt.versionCode || original.minSdk != rebuilt.minSdk
                    || original.targetSdk != rebuilt.targetSdk) {
                throw new IOException("重建后的包名、版本或 SDK 信息与预期不一致。");
            }
            complete = true;
            return report;
        } catch (RuntimeException | javax.xml.parsers.ParserConfigurationException exception) {
            checkCancelled(callback);
            throw new IOException("APK 共存处理失败：" + diagnostic(exception), exception);
        } catch (OutOfMemoryError error) {
            throw new IOException("内存不足，无法处理此 APK。", error);
        } finally {
            if (!complete) output.delete();
        }
    }

    private static Element project(ResXmlElement source, Document document,
            Map<Element, ResXmlElement> elements, Map<Attr, ResXmlAttribute> attributes,
            Map<Attr, String> before, ApkOperationCallback callback, int depth) throws IOException {
        checkCancelled(callback);
        if (source == null || depth > 128 || elements.size() >= 50000) {
            throw new IOException("Manifest 的结构超过处理上限。");
        }
        Element element = document.createElement(source.getName());
        elements.put(element, source);
        Iterator<ResXmlAttribute> iterator = source.getAttributes();
        while (iterator.hasNext()) {
            ResXmlAttribute value = iterator.next();
            String uri = value.getUri();
            String name = value.getName();
            // Attribute IDs are authoritative even when the string name has been obfuscated.
            if (value.getNameId() >>> 24 == 1) {
                String canonical = frameworkName(value.getNameId());
                if (canonical != null) { name = canonical; uri = ANDROID; }
            }
            String prefix = uri == null || uri.isEmpty() ? "" : "a:";
            Attr attr = document.createAttributeNS(uri == null || uri.isEmpty() ? null : uri, prefix + name);
            String text;
            DataType type = value.getDataType();
            if (type == DataType.STRING) text = value.getDataAsString();
            else if (type == DataType.REFERENCE) text = value.getData() == 0 ? "@null"
                    : String.format(java.util.Locale.ROOT, "@0x%08x", value.getData());
            else if (type == DataType.ATTRIBUTE) text = "?0x" + Integer.toHexString(value.getData());
            else if (type == DataType.NULL) text = "@null";
            else text = Integer.toString(value.getData());
            attr.setValue(text == null ? "" : text);
            element.setAttributeNodeNS(attr);
            attributes.put(attr, value);
            before.put(attr, attr.getValue());
        }
        Iterator<ResXmlElement> children = source.getElements();
        while (children.hasNext()) element.appendChild(project(children.next(), document,
                elements, attributes, before, callback, depth + 1));
        return element;
    }

    private static String frameworkName(int id) {
        switch (id) {
            case 0x01010001: return "label";
            case 0x01010003: return "name";
            case 0x01010006: return "permission";
            case 0x01010007: return "readPermission";
            case 0x01010008: return "writePermission";
            case 0x0101000b: return "sharedUserId";
            case 0x01010004: return "manageSpaceActivity";
            case 0x01010018: return "authorities";
            case 0x01010021: return "targetPackage";
            case 0x01010024: return "value";
            case 0x0101000a: return "permissionGroup";
            case 0x01010202: return "targetActivity";
            case 0x01010261: return "sharedUserLabel";
            case 0x0101027f: return "backupAgent";
            case 0x010103a7: return "parentActivityName";
            case 0x0101057a: return "appComponentFactory";
            case 0x0101059d: return "zygotePreloadName";
            case 0x0101064d: return "sharedUserMaxSdkVersion";
            default: return null;
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
                if (name.equals("AndroidManifest.xml") && size > 4L * 1024 * 1024) {
                    throw new IOException("Manifest 超过 4 MiB。 ");
                }
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
