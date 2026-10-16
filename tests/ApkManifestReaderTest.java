/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.zhanghai.android.files.apk.ApkManifestReader;

/** Binary-XML fixtures exercise split-package recognition independently of PackageManager. */
public final class ApkManifestReaderTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static File work;
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        work = new File(arguments[0], "manifest-" + UUID.randomUUID());
        require(work.mkdirs(), "Cannot create test work directory");
        Xml normal = new Xml();
        ApkManifestReader metadata = read("normal", normal);
        require(metadata.packageName.equals("org.example.fixture") && metadata.versionCode == 42
                && metadata.minSdk == 23 && metadata.targetSdk == 36 && !metadata.requiresSplits,
                "Ordinary binary manifest metadata differs");
        ++checks;
        for (String name : Arrays.asList("split", "configForSplit")) {
            split(name, new Xml().root(new Attr(null, name, 0, "feature")), true);
        }
        split("feature", new Xml().root(new Attr(ANDROID, "isFeatureSplit", 0x0101055b, true)), true);
        split("required", new Xml().root(new Attr(ANDROID, "isSplitRequired", 0x01010591, true)), true);
        split("false", new Xml().root(new Attr(ANDROID, "isSplitRequired", 0x01010591, false)), false);
        split("obfuscated-feature", new Xml().root(new Attr(ANDROID, "a", 0x0101055b, true)), true);
        split("obfuscated-required", new Xml().root(new Attr(ANDROID, "b", 0x01010591, true)), true);
        split("required-types", new Xml().root(new Attr(ANDROID, "requiredSplitTypes", 0x0101064e, "abi,density")), true);
        split("obfuscated-types", new Xml().root(new Attr(ANDROID, "c", 0x0101064e, "abi")), true);
        split("empty-types", new Xml().root(new Attr(ANDROID, "requiredSplitTypes", 0x0101064e, "")), false);
        split("reference-types", new Xml().root(Attr.reference("requiredSplitTypes", 0x0101064e)), true);
        split("uses-split", new Xml().usesSplit(), true);
        split("play-required", new Xml().metadata(false, true), true);
        split("play-false", new Xml().metadata(false, false), false);
        split("obfuscated-play-required", new Xml().metadata(true, true), true);
        Xml duplicate = new Xml().root(new Attr(ANDROID, "isSplitRequired", 0x01010591, false))
                .root(new Attr(ANDROID, "isSplitRequired", 0x01010591, true));
        rejects("duplicate-attribute", duplicate.encode());
        Xml duplicateId = new Xml().root(new Attr(ANDROID, "a", 0x01010591, false))
                .root(new Attr(ANDROID, "b", 0x01010591, true));
        rejects("duplicate-resource-id", duplicateId.encode());
        byte[] encoded = normal.encode();
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 24);
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, truncated.length);
        rejects("unclosed-root", truncated);
        byte[] mismatched = encoded.clone();
        ByteBuffer.wrap(mismatched).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(mismatched.length - 4, normal.strings.get("application"));
        rejects("mismatched-closing-root", mismatched);
        rejects("not-xml", new byte[]{1, 2, 3});
        File cancelledInput = apk("cancel", new Xml().encode());
        Thread.currentThread().interrupt();
        try {
            try {
                ApkManifestReader.read(cancelledInput);
                throw new AssertionError("Interrupted manifest reader did not cancel");
            } catch (InterruptedIOException expected) {
                require(Thread.currentThread().isInterrupted(), "Manifest reader cleared cancellation");
                ++checks;
            }
        } finally {
            Thread.interrupted();
        }
        System.out.println("APK manifest reader: " + checks + " checks passed");
    }

    private static void split(String name, Xml xml, boolean expected) throws Exception {
        require(read(name, xml).requiresSplits == expected, "Split recognition failed: " + name);
        ++checks;
    }

    private static ApkManifestReader read(String name, Xml xml) throws Exception {
        return ApkManifestReader.read(apk(name, xml.encode()));
    }

    private static void rejects(String name, byte[] xml) throws Exception {
        File apk = apk(name, xml);
        try {
            ApkManifestReader.read(apk);
            throw new AssertionError("Malformed manifest was accepted: " + name);
        } catch (IOException expected) {
            ++checks;
        }
    }

    private static File apk(String name, byte[] xml) throws IOException {
        File file = new File(work, name + ".apk");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            output.write(xml);
            output.closeEntry();
        }
        return file;
    }

    private static final class Xml {
        final Map<String, Integer> strings = new LinkedHashMap<>();
        final Map<Integer, Integer> resources = new LinkedHashMap<>();
        final List<Attr> root = new ArrayList<>();
        final List<Node> children = new ArrayList<>();

        Xml() {
            root.add(new Attr(null, "package", 0, "org.example.fixture"));
            root.add(new Attr(ANDROID, "versionCode", 0x0101021b, 42));
            children.add(new Node("uses-sdk", Arrays.asList(
                    new Attr(ANDROID, "minSdkVersion", 0x0101020c, 23),
                    new Attr(ANDROID, "targetSdkVersion", 0x01010270, 36))));
            children.add(new Node("application", new ArrayList<>()));
        }

        Xml root(Attr value) { root.add(value); return this; }
        Xml usesSplit() {
            children.add(new Node("uses-split", Arrays.asList(new Attr(ANDROID, "name", 0x01010003, "feature"))));
            return this;
        }
        Xml metadata(boolean obfuscated, boolean value) {
            Node app = children.get(1);
            app.children.add(new Node("meta-data", Arrays.asList(
                    new Attr(ANDROID, obfuscated ? "renamed-name" : "name", 0x01010003, "com.android.vending.splits.required"),
                    new Attr(ANDROID, obfuscated ? "renamed-value" : "value", 0x01010024, value))));
            return this;
        }

        int string(String value) {
            if (value == null) return -1;
            Integer previous = strings.get(value);
            if (previous != null) return previous;
            int index = strings.size();
            strings.put(value, index);
            return index;
        }

        byte[] encode() throws IOException {
            strings.clear();
            resources.clear();
            Node manifest = new Node("manifest", root);
            manifest.children.addAll(children);
            ByteArrayOutputStream nodes = new ByteArrayOutputStream();
            node(nodes, manifest);
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            ByteBuffer offsets = buffer(strings.size() * 4);
            for (String string : strings.keySet()) {
                byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
                if (utf8.length > 127 || string.length() > 127) throw new AssertionError("Test string too long");
                offsets.putInt(text.size());
                text.write(string.length());
                text.write(utf8.length);
                text.write(utf8);
                text.write(0);
            }
            while (text.size() % 4 != 0) text.write(0);
            int stringStart = 28 + strings.size() * 4;
            ByteBuffer pool = buffer(stringStart + text.size());
            header(pool, 1, 28, pool.capacity());
            pool.putInt(strings.size()).putInt(0).putInt(0x100).putInt(stringStart).putInt(0);
            pool.put(offsets.array()).put(text.toByteArray());
            ByteBuffer map = buffer(8 + strings.size() * 4);
            header(map, 0x180, 8, map.capacity());
            for (int i = 0; i < strings.size(); ++i) map.putInt(resources.getOrDefault(i, 0));
            ByteBuffer xml = buffer(8 + pool.capacity() + map.capacity() + nodes.size());
            header(xml, 3, 8, xml.capacity());
            xml.put(pool.array()).put(map.array()).put(nodes.toByteArray());
            return xml.array();
        }

        void node(ByteArrayOutputStream output, Node node) throws IOException {
            int name = string(node.name);
            ByteBuffer start = buffer(36 + node.attributes.size() * 20);
            header(start, 0x102, 16, start.capacity());
            start.putInt(1).putInt(-1).putInt(-1).putInt(name);
            start.putShort((short) 20).putShort((short) 20).putShort((short) node.attributes.size());
            start.putShort((short) 0).putShort((short) 0).putShort((short) 0);
            for (Attr attribute : node.attributes) {
                int attributeName = string(attribute.name);
                if (attribute.resourceId != 0) resources.put(attributeName, attribute.resourceId);
                int type;
                int data;
                if (attribute.value instanceof String) { type = 3; data = string((String) attribute.value); }
                else if (attribute.value instanceof Boolean) { type = 0x12; data = (Boolean) attribute.value ? 1 : 0; }
                else { type = attribute.reference ? 1 : 0x10; data = (Integer) attribute.value; }
                start.putInt(string(attribute.namespace)).putInt(attributeName).putInt(type == 3 ? data : -1);
                start.putShort((short) 8).put((byte) 0).put((byte) type).putInt(data);
            }
            output.write(start.array());
            for (Node child : node.children) node(output, child);
            ByteBuffer end = buffer(24);
            header(end, 0x103, 16, 24);
            end.putInt(1).putInt(-1).putInt(-1).putInt(name);
            output.write(end.array());
        }
    }

    private static final class Node {
        final String name;
        final List<Attr> attributes;
        final List<Node> children = new ArrayList<>();
        Node(String name, List<Attr> attributes) { this.name = name; this.attributes = attributes; }
    }

    private static final class Attr {
        final String namespace;
        final String name;
        final int resourceId;
        final Object value;
        boolean reference;
        Attr(String namespace, String name, int resourceId, Object value) {
            this.namespace = namespace;
            this.name = name;
            this.resourceId = resourceId;
            this.value = value;
        }
        static Attr reference(String name, int resourceId) {
            Attr result = new Attr(ANDROID, name, resourceId, 0x7f010001);
            result.reference = true;
            return result;
        }
    }

    private static void header(ByteBuffer buffer, int type, int headerSize, int size) {
        buffer.putShort((short) type).putShort((short) headerSize).putInt(size);
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
