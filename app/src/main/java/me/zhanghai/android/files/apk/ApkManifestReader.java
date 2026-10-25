/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk;

import com.android.apksig.apk.ApkUtils;
import com.android.apksig.internal.apk.AndroidBinXmlParser;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import me.zhanghai.android.files.apk.core.ApkOperationCallback;
import me.zhanghai.android.files.apk.core.ApkZipArchive;

/** Reads the binary manifest without loading the APK or resolving arbitrary resource references. */
public final class ApkManifestReader {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    // Framework IDs are stable even when an APK's resource-name strings have been obfuscated.
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_VALUE = 0x01010024;
    private static final int ATTR_IS_FEATURE_SPLIT = 0x0101055b;
    private static final int ATTR_IS_SPLIT_REQUIRED = 0x01010591;
    private static final int ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e;

    public final String packageName;
    public final long versionCode;
    public final int minSdk;
    public final int targetSdk;
    public final boolean requiresSplits;

    private ApkManifestReader(String packageName, long versionCode, int minSdk, int targetSdk,
                              boolean requiresSplits) {
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.minSdk = minSdk;
        this.targetSdk = targetSdk;
        this.requiresSplits = requiresSplits;
    }

    public static ApkManifestReader read(File apk) throws IOException {
        byte[] manifest = ApkZipArchive.inspect(apk, ApkOperationCallback.NONE).readEntry(
                apk, "AndroidManifest.xml", MAX_MANIFEST_BYTES, ApkOperationCallback.NONE);
        try {
            ByteBuffer xml = ByteBuffer.wrap(manifest);
            String packageName = ApkUtils.getPackageNameFromBinaryAndroidManifest(xml.duplicate());
            long versionCode = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(xml.duplicate());
            int minSdk = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(xml.duplicate());
            int targetSdk = ApkUtils.getTargetSdkVersionFromBinaryAndroidManifest(xml.duplicate());
            if (packageName == null || packageName.isEmpty() || minSdk < 1) {
                throw new IOException("Invalid APK package name or minimum SDK");
            }
            AndroidBinXmlParser parser = new AndroidBinXmlParser(xml.duplicate());
            boolean requiresSplits = false;
            boolean sawManifest = false;
            ArrayDeque<String> elements = new ArrayDeque<>();
            while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                checkInterrupted();
                if (parser.getEventType() == AndroidBinXmlParser.EVENT_END_ELEMENT) {
                    if (elements.isEmpty() || !elements.removeLast().equals(elementName(parser))) {
                        throw new IOException("APK manifest has mismatched closing elements");
                    }
                    continue;
                }
                if (parser.getEventType() != AndroidBinXmlParser.EVENT_START_ELEMENT) continue;
                String tag = parser.getName();
                if (elements.size() >= 256 || empty(tag)) {
                    throw new IOException("APK manifest has an invalid element or excessive nesting");
                }
                elements.addLast(elementName(parser));
                requireUniqueAttributes(parser);
                if (parser.getDepth() == 1) {
                    if (sawManifest || !"manifest".equals(tag) || !empty(parser.getNamespace())) {
                        throw new IOException("Invalid APK manifest root");
                    }
                    sawManifest = true;
                    for (int i = 0; i < parser.getAttributeCount(); ++i) {
                        String name = parser.getAttributeName(i);
                        String namespace = parser.getAttributeNamespace(i);
                        if (empty(namespace) && ("split".equals(name) || "configForSplit".equals(name))) {
                            // configForSplit is deliberately unnamespaced in ApkLiteParseUtils.
                            requiresSplits |= nonEmptyValue(parser, i);
                        } else if (attribute(parser, i, ATTR_IS_FEATURE_SPLIT, "isFeatureSplit")
                                || attribute(parser, i, ATTR_IS_SPLIT_REQUIRED, "isSplitRequired")) {
                            requiresSplits |= booleanValue(parser, i);
                        } else if (attribute(parser, i, ATTR_REQUIRED_SPLIT_TYPES, "requiredSplitTypes")
                                || (ANDROID.equals(namespace) && "configForSplit".equals(name))) {
                            requiresSplits |= nonEmptyValue(parser, i);
                        }
                    }
                }
                if (parser.getDepth() == 2 && "uses-split".equals(tag)) {
                    requiresSplits = true;
                }
                if ("meta-data".equals(tag)) {
                    String name = null;
                    int valueIndex = -1;
                    for (int i = 0; i < parser.getAttributeCount(); ++i) {
                        if (attribute(parser, i, ATTR_NAME, "name")) {
                            name = parser.getAttributeStringValue(i);
                        } else if (attribute(parser, i, ATTR_VALUE, "value")) {
                            valueIndex = i;
                        }
                    }
                    if ("com.android.vending.splits.required".equals(name)) {
                        // A resource reference cannot safely be treated as a literal false.
                        requiresSplits |= valueIndex < 0 || booleanValue(parser, valueIndex);
                    }
                }
            }
            if (!sawManifest || !elements.isEmpty()) throw new IOException("APK has no complete manifest root");
            return new ApkManifestReader(packageName, versionCode, minSdk,
                    targetSdk > 0 ? targetSdk : minSdk, requiresSplits);
        } catch (InterruptedIOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Cannot read binary APK manifest: " + exception.getMessage(),
                    exception);
        }
    }

    private static String elementName(AndroidBinXmlParser parser) {
        return parser.getNamespace() + "\0" + parser.getName();
    }

    private static void requireUniqueAttributes(AndroidBinXmlParser parser) throws Exception {
        Set<String> names = new HashSet<>();
        Set<Integer> resourceIds = new HashSet<>();
        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            String name = parser.getAttributeNamespace(i) + "\0" + parser.getAttributeName(i);
            int resourceId = parser.getAttributeNameResourceId(i);
            if (!names.add(name) || (resourceId != 0 && !resourceIds.add(resourceId))) {
                throw new IOException("APK manifest contains ambiguous duplicate attributes");
            }
        }
    }

    private static boolean attribute(AndroidBinXmlParser parser, int index, int resourceId,
                                     String name) throws Exception {
        return parser.getAttributeNameResourceId(index) == resourceId
                || (ANDROID.equals(parser.getAttributeNamespace(index))
                && name.equals(parser.getAttributeName(index)));
    }

    private static boolean nonEmptyValue(AndroidBinXmlParser parser, int index) throws Exception {
        // A reference to a split name/type is not safely resolvable without resource configuration.
        return parser.getAttributeValueType(index) != AndroidBinXmlParser.VALUE_TYPE_STRING
                || !empty(parser.getAttributeStringValue(index));
    }

    private static boolean booleanValue(AndroidBinXmlParser parser, int index) throws Exception {
        switch (parser.getAttributeValueType(index)) {
            case AndroidBinXmlParser.VALUE_TYPE_BOOLEAN:
                return parser.getAttributeBooleanValue(index);
            case AndroidBinXmlParser.VALUE_TYPE_INT:
                return parser.getAttributeIntValue(index) != 0;
            case AndroidBinXmlParser.VALUE_TYPE_STRING:
                String value = parser.getAttributeStringValue(index);
                return !"false".equals(value) && !"0".equals(value);
            default:
                return true;
        }
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
    }
}
