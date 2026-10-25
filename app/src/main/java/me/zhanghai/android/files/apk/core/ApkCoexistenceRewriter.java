/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Applies the shared coexistence rules to a Manifest document.
 *
 * <p>The caller must rebuild with the original resources and DEX, then sign the result. Component
 * classes remain in the original code package; authorities and custom permissions get independent
 * Manifest names. Code and resource references to the old application identity are not migrated,
 * so this basic operation does not establish that the resulting app will work.</p>
 *
 * <p>Use a private disposable directory. Validation and serialization finish before writing;
 * discard the directory after any failure or cancellation.</p>
 */
public final class ApkCoexistenceRewriter {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IDENTIFIER_LENGTH = 8192;
    private static final Pattern PACKAGE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern DTD = Pattern.compile(
            "<!\\s*(?:DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);

    public static void validateTargetPackage(String originalPackage, String targetPackage) {
        if (!validPackage(originalPackage)) {
            throw new IllegalArgumentException("Invalid original APK package name");
        }
        if (!validPackage(targetPackage)) {
            throw new IllegalArgumentException("Package name must have at least two ASCII segments, "
                    + "each starting with a letter and containing letters, digits or underscores; "
                    + "maximum 255 characters");
        }
        if (originalPackage.equals(targetPackage)) {
            throw new IllegalArgumentException(
                    "The coexistence package name must differ from the original");
        }
    }

    private static boolean validPackage(String value) {
        return value != null && value.length() <= 255 && PACKAGE.matcher(value).matches();
    }

    /** Resolves a Manifest resource reference against the original APK to its literal value. */
    public interface ManifestValueResolver {
        String resolve(String reference) throws IOException;
    }

    public Report rewrite(File decodedDirectory, String originalPackage, String targetPackage,
            ApkOperationCallback callback) throws IOException {
        return rewrite(decodedDirectory, originalPackage, targetPackage, null, null, callback);
    }

    /** The original label is used only if the application has no Manifest label attribute. */
    public Report rewrite(File decodedDirectory, String originalPackage, String targetPackage,
            String originalLabel, ApkOperationCallback callback) throws IOException {
        return rewrite(decodedDirectory, originalPackage, targetPackage, originalLabel, null,
                callback);
    }

    public Report rewrite(File decodedDirectory, String originalPackage, String targetPackage,
            String originalLabel, ManifestValueResolver resolver, ApkOperationCallback callback)
            throws IOException {
        validateTargetPackage(originalPackage, targetPackage);
        if (decodedDirectory == null) throw new IOException("Missing decoded APK directory");
        return new Plan(decodedDirectory, originalPackage, targetPackage, originalLabel, resolver,
                callback != null ? callback : ApkOperationCallback.NONE).run();
    }

    public static final class Report {
        public final String summary;
        public final int rewrittenFiles;
        /** Number of changed Manifest attributes, including added labels and removed UID fields. */
        public final int rewrittenConstants;
        public final int renamedAuthorities;
        public final int renamedPermissions;

        private Report(String original, String target, int attributes, int authorities,
                int permissions) {
            rewrittenFiles = 1;
            rewrittenConstants = attributes;
            renamedAuthorities = authorities;
            renamedPermissions = permissions;
            summary = original + " → " + target + "；已更新 " + attributes + " 处属性、"
                    + authorities + " 个 Provider 标识和 " + permissions
                    + " 项自定义权限；仅修改 Manifest；代码硬编码引用未迁移";
        }
    }

    /** Binary editors project attributes into a DOM, then apply only the changed attributes. */
    public Report rewriteManifest(Document document, String original, String target,
            String label, ManifestValueResolver resolver, ApkOperationCallback callback)
            throws IOException {
        validateTargetPackage(original, target);
        Plan plan = new Plan(null, original, target, label, resolver,
                callback != null ? callback : ApkOperationCallback.NONE);
        plan.binary = true;
        List<Element> elements = allElements(document.getDocumentElement());
        plan.analyze(document.getDocumentElement(), elements);
        plan.rewrite(document.getDocumentElement(), elements);
        return new Report(original, target, plan.changedAttributes, plan.authorities.size(),
                plan.permissions.size());
    }

    private static final class Plan {
        final Path requestedRoot;
        final Path root;
        final Path manifest;
        final String original;
        final String target;
        final String originalLabel;
        final ManifestValueResolver resolver;
        final ApkOperationCallback callback;
        final Map<String, String> resolvedValues = new LinkedHashMap<>();
        final Map<String, String> authorities = new LinkedHashMap<>();
        final Map<String, String> authoritySources = new LinkedHashMap<>();
        final Map<String, String> permissions = new LinkedHashMap<>();
        final Map<String, String> permissionSources = new LinkedHashMap<>();
        int changedAttributes;
        boolean binary;

        Plan(File directory, String original, String target, String originalLabel,
                ManifestValueResolver resolver, ApkOperationCallback callback) throws IOException {
            this.original = original;
            this.target = target;
            this.originalLabel = originalLabel;
            this.resolver = resolver;
            this.callback = callback;
            check();
            if (directory == null) {
                requestedRoot = root = manifest = null;
                return;
            }
            requestedRoot = directory.toPath().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(requestedRoot)
                    || !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw fail("Decoded APK must be a real directory, not a symbolic link");
            }
            root = requestedRoot.toRealPath();
            manifest = root.resolve("AndroidManifest.xml");
        }

        Report run() throws IOException {
            callback.onProgress("coexist_analyze", 0, 1);
            byte[] before = readManifest();
            Document document = parseXml(decodeUtf8(before));
            List<Element> elements = allElements(document.getDocumentElement());
            analyze(document.getDocumentElement(), elements);
            rewrite(document.getDocumentElement(), elements);
            byte[] after = serialize(document);
            callback.onProgress("coexist_analyze", 1, 1);
            callback.onProgress("coexist_rewrite", 0, 1);
            check();
            if (!Arrays.equals(before, readManifest())) {
                throw fail("Decoded Manifest changed while coexistence was being prepared");
            }
            check();
            safeManifestPath();
            try (SeekableByteChannel channel = Files.newByteChannel(manifest,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(after);
                while (buffer.hasRemaining()) {
                    check();
                    channel.write(buffer);
                }
            }
            callback.onProgress("coexist_rewrite", 1, 1);
            return new Report(original, target, changedAttributes, authorities.size(),
                    permissions.size());
        }

        void analyze(Element manifestElement, List<Element> elements) throws IOException {
            if (!tag(manifestElement).equals("manifest")
                    || !manifestElement.getAttribute("package").equals(original)) {
                throw fail("Decoded Manifest package does not match the original APK");
            }
            for (String name : Arrays.asList("split", "configForSplit")) {
                if (!manifestElement.getAttribute(name).isEmpty()) {
                    throw fail("Only standalone APKs are supported; Manifest contains " + name);
                }
            }
            int applications = 0;
            for (Element element : elements) {
                check();
                String tag = tag(element);
                if (tag.equals("manifest") || tag.equals("application")) {
                    for (String name : Arrays.asList("isFeatureSplit", "isSplitRequired")) {
                        String value = value(element.getAttributeNodeNS(ANDROID, name));
                        if (!isFalse(value)) throw fail("APK requires a split: " + name);
                    }
                    for (String name : Arrays.asList("configForSplit", "requiredSplitTypes",
                            "splitTypes")) {
                        if (!value(element.getAttributeNodeNS(ANDROID, name)).isEmpty()) {
                            throw fail("APK contains split information: " + name);
                        }
                    }
                }
                if (tag.equals("uses-split")) throw fail("APK requires a uses-split dependency");
                if (tag.equals("application")) {
                    if (++applications != 1 || element.getParentNode() != manifestElement) {
                        throw fail("APK must contain one application declaration");
                    }
                }
                if (tag.equals("meta-data")
                        && element.getAttributeNS(ANDROID, "name")
                        .equals("com.android.vending.splits.required")
                        && (!element.hasAttributeNS(ANDROID, "value")
                        || !isFalse(value(element.getAttributeNodeNS(ANDROID, "value"))))) {
                    throw fail("APK requires splits");
                }
                if (tag.equals("provider") && parentTag(element).equals("application")) {
                    String value = value(element.getAttributeNodeNS(ANDROID, "authorities"));
                    for (String authority : value.split(";", -1)) {
                        register(authorities, authoritySources, authority, "Provider authority");
                    }
                }
                if (isPermissionDeclaration(tag) && parentTag(element).equals("manifest")) {
                    register(permissions, permissionSources,
                            value(element.getAttributeNodeNS(ANDROID, "name")),
                            "custom permission");
                }
            }
            if (applications != 1) throw fail("APK must contain one application declaration");
            // The original APK keeps every authority it declared. Check after collecting all
            // providers: a renamed authority may otherwise reuse another original authority.
            for (String authority : authorities.values()) {
                check();
                if (authorities.containsKey(authority)) {
                    throw fail("Provider authority would collide with the original APK after "
                            + "rewriting: " + authority + "; choose a different target package name");
                }
            }
        }

        void register(Map<String, String> names, Map<String, String> sources, String name,
                String kind) throws IOException {
            if (name.isEmpty() || !name.equals(name.trim())) {
                throw fail("Missing or blank " + kind);
            }
            String replacement = name.equals(original) ? target
                    : name.startsWith(original + ".")
                    ? target + name.substring(original.length()) : target + "." + name;
            if (replacement.length() > MAX_IDENTIFIER_LENGTH || names.size() >= 4096) {
                throw fail("Too many or excessively long Manifest identifiers");
            }
            String source = sources.get(replacement);
            if (source != null && !source.equals(name)) {
                throw fail("Different " + kind + " identifiers would collide after rewriting: "
                        + source + " and " + name);
            }
            names.put(name, replacement);
            sources.put(replacement, name);
        }

        void rewrite(Element manifestElement, List<Element> elements) throws IOException {
            set(manifestElement.getAttributeNode("package"), target);
            for (String name : Arrays.asList("sharedUserId", "sharedUserLabel",
                    "sharedUserMaxSdkVersion")) {
                if (manifestElement.hasAttributeNS(ANDROID, name)) {
                    manifestElement.removeAttributeNS(ANDROID, name);
                    ++changedAttributes;
                }
            }
            for (Element element : elements) {
                check();
                String tag = tag(element);
                if (tag.equals("application") && !element.hasAttributeNS(ANDROID, "label")) {
                    String label = originalLabel == null || originalLabel.trim().isEmpty()
                            ? original : originalLabel;
                    if (label.length() > 4096) throw fail("Original application label is too long");
                    element.setAttributeNS(ANDROID, "android:label", binary ? label : attributeString(label));
                    ++changedAttributes;
                }
                for (Attr attribute : attributes(element)) {
                    if (!ANDROID.equals(attribute.getNamespaceURI())) continue;
                    String name = attribute.getLocalName();
                    boolean metadataClass = tag.equals("meta-data") && name.equals("value")
                            && Arrays.asList("android.support.PARENT_ACTIVITY",
                            "android.app.default_searchable")
                            .contains(element.getAttributeNS(ANDROID, "name"));
                    if (isManifestClass(tag, name) || metadataClass) {
                        String value = value(attribute);
                        if (value.isEmpty() || metadataClass && value.equals("*")) continue;
                        set(attribute, value.charAt(0) == '.' ? original + value
                                : value.indexOf('.') < 0 ? original + "." + value : value);
                    } else if (tag.equals("provider") && parentTag(element).equals("application")
                            && name.equals("authorities")) {
                        StringBuilder joined = new StringBuilder();
                        for (String authority : value(attribute).split(";", -1)) {
                            if (joined.length() != 0) joined.append(';');
                            joined.append(authorities.get(authority));
                        }
                        set(attribute, joined.toString());
                    } else if (!permissions.isEmpty() && (isPermissionReference(name)
                            || name.equals("name") && (isPermissionDeclaration(tag)
                            || Arrays.asList("uses-permission", "uses-permission-sdk-23",
                            "uses-permission-sdk-m").contains(tag)))) {
                        String replacement = permissions.get(value(attribute));
                        if (replacement != null) set(attribute, replacement);
                    } else if (tag.equals("instrumentation") && name.equals("targetPackage")
                            && value(attribute).equals(original)) {
                        set(attribute, target);
                    }
                }
            }
        }

        String value(Attr attribute) throws IOException {
            if (attribute == null) return "";
            String value = attribute.getValue();
            if (value.equals("@null") || value.equals("@empty")) return "";
            if (value.startsWith("@") || value.startsWith("?")) {
                if (resolver == null) {
                    throw fail("Manifest " + attribute.getName() + " uses resource reference "
                            + value + "; a ManifestValueResolver for the original APK is required");
                }
                if (!resolvedValues.containsKey(value)) {
                    check();
                    String resolved = resolver.resolve(value);
                    check();
                    if (resolved == null || resolved.startsWith("@") || resolved.startsWith("?")) {
                        throw fail("Cannot resolve Manifest resource reference to a literal: "
                                + value);
                    }
                    if (resolved.length() > MAX_IDENTIFIER_LENGTH) {
                        throw fail("Resolved Manifest identifier is too long: " + value);
                    }
                    resolvedValues.put(value, binary ? resolved : attributeString(resolved));
                }
                value = resolvedValues.get(value);
            }
            if (value.length() > MAX_IDENTIFIER_LENGTH || value.contains("$" + "{")
                    || value.indexOf('\0') >= 0) {
                throw fail("Invalid or unresolved Manifest identifier: " + attribute.getName());
            }
            return value;
        }

        void set(Attr attribute, String value) {
            if (!attribute.getValue().equals(value)) {
                attribute.setValue(value);
                ++changedAttributes;
            }
        }

        void check() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("APK coexistence cancelled");
            }
            callback.checkCancelled();
        }

        IOException fail(String message) { return new IOException(message + " [AndroidManifest.xml]"); }

        void safeManifestPath() throws IOException {
            if (Files.isSymbolicLink(requestedRoot)
                    || !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)
                    || !requestedRoot.toRealPath().equals(root)
                    || Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw fail("Decoded APK directory changed or became a symbolic link");
            }
            if (Files.isSymbolicLink(manifest)
                    || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || !manifest.toRealPath().equals(manifest)) {
                throw fail("Manifest must be a regular file inside the decoded APK, not a symbolic link");
            }
        }

        byte[] readManifest() throws IOException {
            check();
            safeManifestPath();
            try (SeekableByteChannel channel = Files.newByteChannel(manifest,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (channel.size() > MAX_MANIFEST_BYTES) throw fail("Manifest exceeds size limit");
                ByteBuffer buffer = ByteBuffer.allocate(65536);
                int count;
                while ((count = channel.read(buffer)) != -1) {
                    check();
                    if (count == 0 || out.size() > MAX_MANIFEST_BYTES - count) {
                        throw fail("Manifest exceeds size limit");
                    }
                    out.write(buffer.array(), 0, count);
                    buffer.clear();
                }
                return out.toByteArray();
            }
        }

        String decodeUtf8(byte[] bytes) throws IOException {
            try {
                String value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                if (value.indexOf('\0') >= 0) throw fail("Decoded Manifest contains NUL bytes");
                return value.startsWith("\ufeff") ? value.substring(1) : value;
            } catch (CharacterCodingException exception) {
                throw fail("Decoded Manifest must use valid UTF-8");
            }
        }

        Document parseXml(String content) throws IOException {
            if (DTD.matcher(content).find()) throw fail("DTD/entity declarations are forbidden");
            try {
                DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
                documentFactory.setNamespaceAware(true);
                Document document = documentFactory.newDocumentBuilder().newDocument();
                SAXParserFactory saxFactory = SAXParserFactory.newInstance();
                saxFactory.setNamespaceAware(true);
                XMLReader reader = saxFactory.newSAXParser().getXMLReader();
                // Android and host XML providers expose different feature subsets. The lexical
                // DTD ban and refusing resolver below also apply when these features are absent.
                feature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
                feature(reader, "http://xml.org/sax/features/external-general-entities", false);
                feature(reader, "http://xml.org/sax/features/external-parameter-entities", false);
                feature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                feature(reader, XMLConstants.FEATURE_SECURE_PROCESSING, true);
                DefaultHandler handler = new DefaultHandler() {
                    final Deque<Element> stack = new ArrayDeque<>();
                    final Map<String, String> prefixes = new LinkedHashMap<>();
                    int nodes;
                    void limit() throws SAXException {
                        if (++nodes > 100000) throw new SAXException("XML has too many nodes");
                        try { check(); }
                        catch (InterruptedIOException exception) { throw new SAXException(exception); }
                    }
                    @Override public void startPrefixMapping(String prefix, String uri) {
                        prefixes.put(prefix, uri);
                    }
                    @Override public void startElement(String uri, String local, String qualified,
                            Attributes attrs) throws SAXException {
                        limit();
                        if (stack.size() >= 64 || attrs.getLength() > 256) {
                            throw new SAXException("XML depth/attribute limit exceeded");
                        }
                        Element element = document.createElementNS(uri.isEmpty() ? null : uri,
                                qualified.isEmpty() ? local : qualified);
                        for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
                            element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                                    prefix.getKey().isEmpty() ? "xmlns" : "xmlns:" + prefix.getKey(),
                                    prefix.getValue());
                        }
                        prefixes.clear();
                        for (int i = 0; i < attrs.getLength(); ++i) {
                            String attributeUri = attrs.getURI(i);
                            element.setAttributeNS(attributeUri.isEmpty() ? null : attributeUri,
                                    attrs.getQName(i).isEmpty() ? attrs.getLocalName(i)
                                    : attrs.getQName(i), attrs.getValue(i));
                        }
                        if (stack.isEmpty()) document.appendChild(element);
                        else stack.peek().appendChild(element);
                        stack.push(element);
                    }
                    @Override public void endElement(String uri, String local, String qualified) {
                        stack.pop();
                    }
                    @Override public void characters(char[] ch, int start, int length)
                            throws SAXException {
                        if (stack.isEmpty() || length == 0) return;
                        limit();
                        Node previous = stack.peek().getLastChild();
                        if (previous != null && previous.getNodeType() == Node.TEXT_NODE) {
                            ((org.w3c.dom.Text) previous).appendData(new String(ch, start, length));
                        } else {
                            stack.peek().appendChild(document.createTextNode(
                                    new String(ch, start, length)));
                        }
                    }
                    @Override public InputSource resolveEntity(String publicId, String systemId)
                            throws SAXException {
                        throw new SAXException("External XML entities are forbidden");
                    }
                    @Override public void error(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                    @Override public void fatalError(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                };
                reader.setContentHandler(handler);
                reader.setEntityResolver(handler);
                reader.setErrorHandler(handler);
                reader.parse(new InputSource(new StringReader(content)));
                if (document.getDocumentElement() == null) throw fail("Manifest XML is empty");
                return document;
            } catch (ParserConfigurationException | SAXException exception) {
                for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                    if (cause instanceof InterruptedIOException) throw (InterruptedIOException) cause;
                }
                throw new IOException("Cannot safely parse decoded Manifest: "
                        + exception.getMessage(), exception);
            }
        }

        byte[] serialize(Document document) throws IOException {
            check();
            try {
                TransformerFactory factory = TransformerFactory.newInstance();
                try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); }
                catch (TransformerException ignored) { /* Provider-dependent; input has no DTD. */ }
                try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); }
                catch (IllegalArgumentException ignored) { /* Android provider may not expose it. */ }
                try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", ""); }
                catch (IllegalArgumentException ignored) { /* No stylesheet is used. */ }
                Transformer transformer = factory.newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.INDENT, "no");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(document), new StreamResult(out));
                if (out.size() > MAX_MANIFEST_BYTES) throw fail("Rewritten Manifest exceeds size limit");
                check();
                return out.toByteArray();
            } catch (TransformerException exception) {
                throw new IOException("Cannot serialize rewritten Manifest", exception);
            }
        }
    }

    private static boolean isFalse(String value) {
        return value.isEmpty() || value.equals("false") || value.equals("0")
                || value.equals("0x0");
    }

    private static boolean isPermissionDeclaration(String tag) {
        return tag.equals("permission") || tag.equals("permission-tree")
                || tag.equals("permission-group");
    }

    private static boolean isPermissionReference(String attribute) {
        return Arrays.asList("permission", "readPermission", "writePermission", "permissionGroup",
                "backgroundPermission").contains(attribute);
    }

    private static boolean isManifestClass(String tag, String attribute) {
        if (attribute.equals("name") && Arrays.asList("application", "activity", "activity-alias",
                "provider", "service", "receiver", "instrumentation").contains(tag)) return true;
        if (tag.equals("application") && Arrays.asList("backupAgent", "appComponentFactory",
                "manageSpaceActivity", "zygotePreloadName").contains(attribute)) return true;
        return (tag.equals("activity") || tag.equals("activity-alias"))
                && attribute.equals("parentActivityName")
                || tag.equals("activity-alias") && attribute.equals("targetActivity");
    }

    /** Uses Android resource-string escaping in addition to the XML serializer's escaping. */
    private static String attributeString(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); ++i) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': result.append("\\\\"); break;
                case '\'': result.append("\\'"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default: result.append(ch);
            }
        }
        if (result.length() != 0 && (result.charAt(0) == '@' || result.charAt(0) == '?')) {
            result.insert(0, '\\');
        }
        if (!value.equals(value.trim())) { result.insert(0, '"'); result.append('"'); }
        return result.toString();
    }

    private static void feature(XMLReader reader, String feature, boolean value) {
        try { reader.setFeature(feature, value); }
        catch (SAXException ignored) { /* See parseXml(). */ }
    }

    private static String tag(Element element) {
        return element.getNamespaceURI() == null || element.getNamespaceURI().isEmpty()
                ? element.getTagName() : "";
    }

    private static String parentTag(Element element) {
        Node parent = element.getParentNode();
        return parent instanceof Element ? tag((Element) parent) : "";
    }

    private static List<Element> allElements(Element root) {
        List<Element> result = new ArrayList<>();
        Deque<Element> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Element element = pending.pop();
            result.add(element);
            for (Node child = element.getLastChild(); child != null;
                    child = child.getPreviousSibling()) {
                if (child instanceof Element) pending.push((Element) child);
            }
        }
        return result;
    }

    private static List<Attr> attributes(Element element) {
        List<Attr> result = new ArrayList<>();
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); ++i) result.add((Attr) attrs.item(i));
        return result;
    }
}
