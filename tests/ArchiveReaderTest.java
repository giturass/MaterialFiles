/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java8.nio.file.FileSystem;
import java8.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the APK's real JBinding readers against independently decoded upstream fixtures. */
public final class ArchiveReaderTest {
    private static final String PACKAGE = "me.zhanghai.android.files.";
    private static final String ARCHIVER = PACKAGE + "provider.archive.archiver.";
    private static final List<String> FAILURES = new ArrayList<>();
    private static Class<?> readerType;
    private static Class<?> callbackType;
    private static Object companion;
    private static File fixtures;
    private static File cache;
    private static FileSystem filesystem;
    private static int checks;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            if (arguments.length != 2) throw new AssertionError("Pass a reader manifest and work directory");
            File manifestFile = new File(arguments[0]).getCanonicalFile();
            fixtures = manifestFile.getParentFile();
            JSONObject manifest = new JSONObject(new String(readAll(new FileInputStream(manifestFile)), StandardCharsets.UTF_8));
            cache = new File(arguments[1], "cache");
            ArchiveTestSupport.installApplication(cache);
            readerType = Class.forName(ARCHIVER + "SevenZipArchiveReader");
            callbackType = Class.forName(ARCHIVER + "SevenZipArchiveReader$ExtractionCallback");
            companion = readerType.getField("Companion").get(null);
            try (FileSystem physical = ArchiveVolumeTest.newPhysicalFileSystem(fixtures)) {
                filesystem = physical;
                JSONArray archives = manifest.getJSONArray("archives");
                check("reader fixtures cover the upgraded 7-Zip engine", () -> {
                    for (String format : Arrays.asList("ZIP_ZSTANDARD", "TAR_PAX_SIZE", "TAR_GNU_LONG_RECORDS")) {
                        find(archives, format);
                    }
                });
                for (int index = 0; index < archives.length(); ++index) {
                    JSONObject archive = archives.getJSONObject(index);
                    check(archive.getString("format") + ": metadata, streams and selected batch ("
                            + archive.getString("archive") + ")", () -> verifyArchive(archive));
                }
                check("short provider reads preserve decoded contents", () -> {
                    ArchiveVolumeTest.configureIo(filesystem, 3, Integer.MAX_VALUE, false, null, null);
                    try { verifyArchive(find(archives, "ZIP")); }
                    finally { ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null); }
                });
                check("stream-only provider spooling preserves XZ contents", () -> {
                    ArchiveVolumeTest.configureIo(filesystem, 13, Integer.MAX_VALUE, true, null, null);
                    try { verifyArchive(find(archives, "XZ")); }
                    finally { ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null); }
                });
                check("untrusted entry paths cannot escape the archive root", ArchiveReaderTest::verifyPaths);
                check("folded GNU and PAX paths cannot escape the archive root",
                        () -> verifyPaths("generated/unsafe-paths.tar"));
                check("CRC failure is delivered after streamed ZIP data", ArchiveReaderTest::verifyCrcFailure);
                check("a truncated 7z header fails without leaking source handles", () -> reject("generated/bad-header.7z"));
                check("a missing 7z volume cannot masquerade as a raw split", () -> reject("generated/broken-volume/archive.7z.001"));
                check("a corrupt named 7z split cannot masquerade as raw data", () -> reject("generated/damaged.7z.001"));
                check("cyclic TAR hard links fail", () -> reject("generated/hardlinks-cycle.tar"));
                check("missing TAR hard-link targets fail", () -> reject("generated/hardlinks-missing.tar"));
                check("TAR hard-link traversal cannot reference external data", () -> reject("generated/hardlinks-unsafe.tar"));
                check("folded GNU hard links cannot reference external data", () -> reject("generated/gnu-hardlink-unsafe.tar"));
                check("conflicting PAX sizes fail instead of exposing padding", () -> reject("generated/pax-size-mismatch.tar"));
                check("unsupported PAX sparse layouts cannot produce incomplete files", () -> reject("generated/pax-sparse.tar"));
                check("unsupported PAX character encodings cannot produce wrong names", () -> reject("generated/pax-non-utf8.tar"));
                check("reader cancellation retains the interrupt", ArchiveReaderTest::verifyCancellation);
                check("supported extensions route correctly despite generic provider MIME", ArchiveReaderTest::verifyMime);
                ArchiveTestSupport.requireEmptyCache(cache);
            }
            if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " / " + checks + " reader checks failed: " + FAILURES);
            System.out.println("PASS: " + checks + " JBinding reader checks");
        } catch (Throwable failure) { fail(failure); }
    }

    private static JSONObject find(JSONArray archives, String format) throws Exception {
        for (int index = 0; index < archives.length(); ++index) {
            JSONObject archive = archives.getJSONObject(index);
            if (format.equals(archive.getString("format"))) return archive;
        }
        throw new AssertionError("No fixture for " + format);
    }

    private static void verifyArchive(JSONObject archive) throws Throwable {
        List<String> passwords = archive.has("password") ? Collections.singletonList(archive.getString("password")) : Collections.emptyList();
        try (Closeable reader = open(path(archive.getString("archive")), passwords)) {
            Map<String, Object> entries = new LinkedHashMap<>();
            for (Object entry : (List<?>) call(reader, "readEntries")) {
                String name = (String) call(entry, "getName");
                require(entries.put(name, entry) == null, "Duplicate fixture entry: " + name);
            }
            JSONArray expected = archive.getJSONArray("members");
            List<Object> selected = new ArrayList<>();
            Map<String, JSONObject> members = new LinkedHashMap<>();
            for (int index = 0; index < expected.length(); ++index) {
                JSONObject member = expected.getJSONObject(index);
                String name = member.getString("name");
                Object entry = entries.get(name);
                require(entry != null, "The reader lost a fixture entry: " + name + " from " + entries.keySet());
                require(!(boolean) call(entry, "isDirectory"), "A regular member became a directory: " + name);
                if (member.has("modified_millis")) {
                    Object modified = call(entry, "getLastModifiedTime");
                    require(modified != null && (long) call(modified, "toMillis") == member.getLong("modified_millis"),
                            "PAX modification time lost its local/global scope: " + name);
                }
                if (member.has("symlink")) {
                    require((boolean) call(entry, "isSymbolicLink"), "The reader lost symbolic-link metadata: " + name);
                    require(member.getString("symlink").equals(call(entry, "getSymbolicLinkTarget")), "Incorrect symbolic-link target: " + name);
                    continue;
                }
                if (archive.optBoolean("check_size")) {
                    require((long) call(entry, "getSize") == member.getLong("size"), "Incorrect resolved hard-link size: " + name);
                }
                InputStream input = (InputStream) invoke(readerType.getMethod("newInputStream", String.class), reader, name);
                require(input != null, "No content stream: " + name);
                compare(input, member);
                selected.add(entry);
                members.put(name, member);
            }
            Collections.reverse(selected);
            verifyBatch(reader, selected, members);
            if (archive.has("alias_only")) {
                String alias = archive.getString("alias_only");
                verifyBatch(reader, Collections.singletonList(entries.get(alias)), Collections.singletonMap(alias, members.get(alias)));
            }
        }
        ArchiveTestSupport.requireEmptyCache(cache);
    }

    private static void verifyBatch(Object reader, List<Object> selected, Map<String, JSONObject> expected) throws Throwable {
        Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        Set<String> completed = new HashSet<>();
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return "ArchiveReaderTest.ExtractionCallback";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == arguments[0];
                            default: throw new UnsupportedOperationException(method.toString());
                        }
                    }
                    String name = (String) call(arguments[0], "getName");
                    switch (method.getName()) {
                        case "openOutput": {
                            require(expected.containsKey(name), "Batch opened an unselected member: " + name);
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            require(outputs.put(name, output) == null, "Batch opened a member twice: " + name);
                            return output;
                        }
                        case "onResult": {
                            if (arguments[1] != null) throw (Throwable) arguments[1];
                            require(completed.add(name), "Batch completed a member twice: " + name);
                            return null;
                        }
                        default: throw new UnsupportedOperationException(method.toString());
                    }
                });
        invoke(readerType.getMethod("extract", List.class, callbackType), reader, selected, callback);
        require(completed.equals(expected.keySet()), "A selected batch member never completed");
        for (Map.Entry<String, JSONObject> entry : expected.entrySet()) {
            ByteArrayOutputStream output = outputs.get(entry.getKey());
            require(output != null, "Missing selected output: " + entry.getKey());
            require(sha256(output.toByteArray()).equals(entry.getValue().getString("sha256")), "Batch content differs: " + entry.getKey());
        }
    }

    private static void compare(InputStream source, JSONObject expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = source) {
            byte[] buffer = new byte[1038];
            require(input.read(buffer, 7, 0) == 0, "Zero-length read consumed data");
            int first = input.read();
            if (first >= 0) { digest.update((byte) first); ++size; }
            while (true) {
                Arrays.fill(buffer, (byte) 0xC3);
                int count = input.read(buffer, 7, 1024);
                for (int index = 0; index < 7; ++index) {
                    require(buffer[index] == (byte) 0xC3 && buffer[1031 + index] == (byte) 0xC3, "Read modified bytes outside its requested range");
                }
                if (count < 0) break;
                require(count > 0 && count <= 1024, "Invalid stream progress");
                digest.update(buffer, 7, count);
                size += count;
                require(size <= expected.getLong("size"), "Reader produced extra content");
            }
            require(input.read(buffer, 7, 0) == 0, "Empty read at EOF was not zero");
        }
        require(size == expected.getLong("size"), "Reader truncated a member: " + expected.getString("name"));
        require(hex(digest.digest()).equals(expected.getString("sha256")), "Decompressed bytes differ: " + expected.getString("name"));
    }

    private static void verifyPaths() throws Throwable {
        verifyPaths("generated/traversal.zip");
    }

    private static void verifyPaths(String archiveName) throws Throwable {
        Path physical = path(archiveName);
        Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
        Path root = (Path) invoke(paths.getMethod("createArchiveRootPath", Path.class), null, physical);
        try (FileSystem archive = root.getFileSystem()) {
            Class<?> providerReader = Class.forName(ARCHIVER + "ArchiveReader");
            Object pair = invoke(providerReader.getMethod("readEntries", Path.class, List.class, Path.class),
                    providerReader.getField("INSTANCE").get(null), physical, Collections.emptyList(), root);
            Map<?, ?> entries = (Map<?, ?>) call(pair, "getFirst");
            Set<String> names = new HashSet<>();
            for (Object candidate : entries.keySet()) {
                Path entry = (Path) candidate;
                require(entry.startsWith(root), "An untrusted member escaped the archive root");
                names.add(root.relativize(entry).toString());
            }
            require(names.equals(new HashSet<>(Arrays.asList("", "safe", "safe/backslash.txt", "normalized.txt", "ok.txt", "collision", "collision/child.txt"))),
                    "Unsafe paths leaked or safe paths disappeared: " + names);
            require((boolean) call(entries.get(root.resolve("collision")), "isDirectory"), "A conflicting parent file blocked safe child extraction");
        }
    }

    private static void verifyCrcFailure() throws Throwable {
        try (Closeable reader = open(path("generated/bad-crc.zip"), Collections.emptyList())) {
            boolean failed = false;
            try (InputStream input = (InputStream) invoke(readerType.getMethod("newInputStream", String.class), reader, "payload.bin")) {
                readAll(input);
            } catch (IOException error) {
                require(!Class.forName(ARCHIVER + "ArchivePassphraseException").isInstance(error), "An unencrypted CRC failure was reported as a password error");
                failed = true;
            }
            require(failed, "CRC failure was reported as successful extraction");
        }
    }

    private static void reject(String name) throws Throwable {
        try (Closeable reader = open(path(name), Collections.emptyList())) {
            call(reader, "readEntries");
        } catch (IOException expected) {
            ArchiveTestSupport.requireEmptyCache(cache);
            return;
        }
        throw new AssertionError("Invalid archive accepted: " + name);
    }

    private static void verifyCancellation() throws Throwable {
        Thread.currentThread().interrupt();
        try {
            try (Closeable ignored = open(path("generated/unicode.zip"), Collections.emptyList())) {
                throw new AssertionError("Interrupted reader opened an archive");
            } catch (InterruptedIOException | ClosedByInterruptException expected) {
                require(Thread.currentThread().isInterrupted(), "Reader cleared the interrupt");
            }
        } finally { Thread.interrupted(); }
    }

    private static void verifyMime() throws Throwable {
        Class<?> conversion = Class.forName(PACKAGE + "file.MimeTypeConversionExtensionsKt");
        Object mimeCompanion = Class.forName(PACKAGE + "file.MimeType").getField("Companion").get(null);
        Method guess = prefixMethod(conversion, "guessFromPath");
        for (String[] spec : new String[][]{{"help.CHM", "application/vnd.ms-htmlhelp"}, {"disk.XAR", "application/x-xar"},
                {"archive.7Z", "application/x-7z-compressed"}, {"archive.RAR", "application/vnd.rar"},
                {"raw.txt.001", "application/x-split"}, {"archive.7z.001", "application/x-7z-compressed"}}) {
            Object mime = invoke(guess, null, mimeCompanion, spec[0]);
            String value = mime instanceof String ? (String) mime : (String) call(mime, "getValue");
            require(spec[1].equals(value), "Wrong archive MIME for " + spec[0] + ": " + value);
        }
        Method isArchive = prefixMethod(Class.forName(PACKAGE + "filelist.PathExtensionsKt"), "isArchiveFile");
        for (String name : new String[]{"help.chm", "disk.IMG", "archive.RAR", "data.001", "archive.XAR", "archive.lzma"}) {
            require((boolean) invoke(isArchive, null, filesystem.getPath(name), "application/octet-stream"), "Generic provider MIME hid supported archive " + name);
        }
        for (String name : new String[]{"setup.exe", "unsupported.zst", "photo.jpg"}) {
            require(!(boolean) invoke(isArchive, null, filesystem.getPath(name), "application/octet-stream"), "Unsupported filename opened automatically as an archive: " + name);
        }
        require(!(boolean) invoke(isArchive, null, filesystem.getPath("directory.zip"), "vnd.android.document/directory"), "A directory was classified as an archive");
    }

    private static Method prefixMethod(Class<?> type, String prefix) {
        for (Method method : type.getDeclaredMethods()) if (method.getName().startsWith(prefix)) return method;
        throw new AssertionError("Missing method: " + prefix);
    }
    private static Closeable open(Path file, List<String> passwords) throws Throwable {
        return (Closeable) invoke(companion.getClass().getMethod("open", Path.class, List.class), companion, file, passwords);
    }
    private static Path path(String name) { return filesystem.getPath(new File(fixtures, name).getAbsolutePath()); }
    private static Object call(Object target, String method) throws Throwable { return invoke(target.getClass().getMethod(method), target); }
    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static byte[] readAll(InputStream source) throws IOException {
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
            return output.toByteArray();
        }
    }
    private static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, CheckedRunnable body) {
        ++checks;
        try { body.run(); System.out.println("PASS " + name); }
        catch (Throwable error) { FAILURES.add(name); System.out.println("FAIL " + name); error.printStackTrace(System.out); }
    }
    private static void fail(Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    private interface CheckedRunnable { void run() throws Throwable; }
}
