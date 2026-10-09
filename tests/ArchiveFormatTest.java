/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java8.nio.channels.SeekableByteChannel;
import java8.nio.file.DirectoryNotEmptyException;
import java8.nio.file.FileSystem;
import java8.nio.file.LinkOption;
import java8.nio.file.Path;
import java8.nio.file.attribute.BasicFileAttributes;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Creates archives through the APK's ArchiveWriter, reads them through its official 7-Zip or
 * libarchive reader, and leaves a manifest for independent official 7zz interoperability checks.
 * The physical provider comes from ArchiveVolumeTest; no Activity or application preferences are
 * needed. ArchiveSourceSnapshot checks exercise the actual post-commit source-deletion guard.
 */
public final class ArchiveFormatTest {
    private static final String PACKAGE = "me.zhanghai.android.files.";
    private static final String ARCHIVER = PACKAGE + "provider.archive.archiver.";
    private static final String PASSWORD = "测试-password-🔐";
    private static final List<String> FAILURES = new ArrayList<>();
    private static final JSONArray MANIFEST = new JSONArray();
    private static File work;
    private static FileSystem filesystem;
    private static Class<?> constants;
    private static Class<?> presetType;
    private static Class<?> functionType;
    private static Class<?> writerType;
    private static Class<?> readerType;
    private static Class<?> readArchiveType;
    private static Class<?> entryType;
    private static Class<?> outputType;
    private static Class<?> splitType;
    private static Object unit;
    private static int checks;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> fail(failure));
        try {
            require(arguments.length == 1, "Pass a directory for generated archive-format files");
            work = new File(arguments[0]).getCanonicalFile();
            mkdir(work);
            mkdir(new File(work, "output"));
            mkdir(new File(work, "stage"));
            loadTypes();
            try (FileSystem owned = ArchiveVolumeTest.newPhysicalFileSystem(work)) {
                filesystem = owned;
                List<Entry> entries = createSources(new File(work, "source"));
                List<Entry> rawEntries = Collections.singletonList(new Entry("payload.bin",
                        new File(work, "source/payload.bin"), payload(24577)));
                putNew(rawEntries.get(0).file, rawEntries.get(0).bytes);
                for (String preset : new String[]{"SPEED", "STANDARD", "QUALITY"}) {
                    String suffix = preset.toLowerCase(Locale.ROOT);
                    verify(new Spec("zip-aes-" + suffix + ".zip", "zip", "ZIP", "NONE",
                            preset, PASSWORD, false, false, 0), entries);
                    verify(new Spec("7z-aes-" + suffix + ".7z", "7z", "7ZIP", "NONE",
                            preset, PASSWORD, true, false, 0), entries);
                    verify(new Spec("tar-xz-" + suffix + ".tar.xz", "tar.xz", "TAR", "XZ",
                            preset, null, false, false, 0), entries);
                }
                verify(standard("zip-plain.zip", "zip", "ZIP", "NONE"), entries);
                verify(standard("7z-plain.7z", "7z", "7ZIP", "NONE"), entries);
                verify(standard("plain.tar", "tar", "TAR", "NONE"), entries);
                verify(standard("tar-gzip.tar.gz", "tar.gz", "TAR", "GZIP"), entries);
                verify(standard("tar-bzip2.tar.bz2", "tar.bz2", "TAR", "BZIP2"), entries);
                verify(new Spec("payload.bin.gz", "gzip", "RAW", "GZIP", "STANDARD",
                        null, false, true, 0), rawEntries);
                verify(new Spec("payload.bin.bz2", "bzip2", "RAW", "BZIP2", "STANDARD",
                        null, false, true, 0), rawEntries);
                verify(new Spec("payload.bin.xz", "xz", "RAW", "XZ", "STANDARD",
                        null, false, true, 0), rawEntries);
                verify(new Spec("split.zip", "zip", "ZIP", "NONE", "STANDARD",
                        PASSWORD, false, false, 37), entries);
                verify(new Spec("split.7z", "7z", "7ZIP", "NONE", "STANDARD",
                        PASSWORD, true, false, 37), entries);
                verify(new Spec("split.tar.gz", "tar.gz", "TAR", "GZIP", "STANDARD",
                        null, false, false, 37), entries);
                check("unfiltered ordinary input is rejected instead of exposed as a raw archive",
                        ArchiveFormatTest::verifyUnfilteredInputRejected);
                for (int mode = 0; mode < 3; ++mode) {
                    final int currentMode = mode;
                    check(new String[]{"unchanged archived sources delete after output commit",
                            "changed source bytes survive post-archive deletion",
                            "new child survives deletion of the previously archived tree"}[mode],
                            () -> verifySourceDeletion(currentMode));
                }
            }
            JSONObject manifest = new JSONObject();
            manifest.put("archives", MANIFEST);
            try (FileOutputStream output = new FileOutputStream(new File(work, "format-manifest.json"))) {
                output.write((manifest.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!FAILURES.isEmpty()) {
                throw new AssertionError(FAILURES.size() + " / " + checks
                        + " format checks failed: " + FAILURES);
            }
            System.out.println("PASS: " + checks + " archive format checks");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void loadTypes() throws ClassNotFoundException, ReflectiveOperationException {
        constants = Class.forName("me.zhanghai.android.libarchive.Archive");
        presetType = Class.forName(ARCHIVER + "ArchiveCompressionPreset");
        functionType = Class.forName("kotlin.jvm.functions.Function1");
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        writerType = Class.forName(ARCHIVER + "ArchiveWriter");
        readerType = Class.forName(ARCHIVER + "ArchiveReader");
        readArchiveType = Class.forName(ARCHIVER + "ReadArchive");
        entryType = Class.forName(ARCHIVER + "ReadArchive$Entry");
        outputType = Class.forName(ARCHIVER + "ArchiveOutput");
        splitType = Class.forName(ARCHIVER + "SplitArchiveKt");
    }

    private static List<Entry> createSources(File directory) throws IOException {
        mkdir(directory);
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("hello.txt", new File(directory, "hello.txt"),
                "Native archive engines preserve UTF-8: 测试 🔐\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new Entry("文档", new File(directory, "文档"), null));
        entries.add(new Entry("文档/数据.bin", new File(directory, "文档/数据.bin"), payload(32771)));
        entries.add(new Entry("empty.bin", new File(directory, "empty.bin"), new byte[0]));
        entries.add(new Entry("empty-directory", new File(directory, "empty-directory"), null));
        for (Entry entry : entries) {
            if (entry.directory()) mkdir(entry.file);
            else putNew(entry.file, entry.bytes);
        }
        return entries;
    }

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; ++index) {
            bytes[index] = (byte) ((index * 37 + (index / 251) % 13) & 255);
        }
        return bytes;
    }

    private static Spec standard(String filename, String format, String container, String filter) {
        return new Spec(filename, format, container, filter, "STANDARD", null, false, false, 0);
    }

    private static void verify(Spec spec, List<Entry> entries) {
        check(spec.filename + ": write, progress, close, reopen and compare every entry", () -> {
            Path archive = create(spec, entries);
            readAndCompare(spec, archive, entries);
            addManifest(spec, entries);
        });
    }

    private static Path create(Spec spec, List<Entry> entries) throws Throwable {
        File outputFile = new File(work, "output/" + spec.filename);
        File stage = spec.splitSize == 0 ? outputFile : new File(work, "stage/" + spec.filename);
        writeArchive(spec, path(stage), entries);
        if (spec.splitSize > 0) {
            // The complete archive is staged before sequential volume creation, just as in
            // ArchiveFileJob: the 7z writer must be free to rewrite its leading header.
            try (ArchiveOutput output = new ArchiveOutput(path(outputFile), spec.splitSize);
                 InputStream input = new FileInputStream(stage)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) {
                        require(output.channel.write(buffer) > 0, "Volume output made no progress");
                    }
                }
                output.commit();
            }
            assertVolumes(outputFile, spec.splitSize, stage.length());
        }
        return path(new File(work, spec.archiveName()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeArchive(Spec spec, Path target, List<Entry> entries) throws Throwable {
        List<Object> pairs = new ArrayList<>();
        Map<String, Integer> completed = new LinkedHashMap<>();
        Set<String> started = new HashSet<>();
        Constructor<?> pair = Class.forName("kotlin.Pair").getConstructor(Object.class, Object.class);
        long expectedProgress = 0;
        for (Entry entry : entries) {
            Path source = path(entry.file);
            pairs.add(construct(pair, source, filesystem.getPath(entry.name)));
            completed.put(source.toString(), 0);
            expectedProgress += attributes(source).size();
        }
        long[] progress = {0};
        Object listener = function(value -> {
            long amount = (Long) value;
            require(amount >= 0, "Archive progress moved backwards");
            progress[0] += amount;
        });
        Object onEntry = function(value -> {
            String source = value.toString();
            require(completed.containsKey(source), "Started an unselected source: " + source);
            started.add(source);
        });
        Object onComplete = function(value -> {
            String source = value.toString();
            require(completed.containsKey(source), "Completed an unselected source: " + source);
            completed.put(source, completed.get(source) + 1);
        });
        Object preset = Enum.valueOf((Class) presetType, spec.preset);
        try (ArchiveOutput output = new ArchiveOutput(target, 0)) {
            try (Closeable writer = (Closeable) construct(writerType.getConstructor(
                    SeekableByteChannel.class, int.class, int.class, String.class, boolean.class,
                    presetType), output.channel, constant("FORMAT_" + spec.container),
                    constant("FILTER_" + spec.filter), spec.password, spec.encryptNames, preset)) {
                invoke(writerType.getMethod("writeEntries", List.class, long.class, functionType,
                        functionType, functionType), writer, pairs, 0L, listener, onEntry, onComplete);
            }
            output.commit();
        }
        require(progress[0] == expectedProgress,
                "Progress mismatch: " + progress[0] + " != " + expectedProgress);
        require(started.equals(completed.keySet()), "Some selected inputs were never started");
        for (Map.Entry<String, Integer> completedEntry : completed.entrySet()) {
            require(completedEntry.getValue() == 1,
                    "Entry completion was missing or repeated: " + completedEntry);
        }
    }

    private static void readAndCompare(Spec spec, Path archive, List<Entry> entries) throws Throwable {
        Map<String, Entry> expected = new LinkedHashMap<>();
        for (Entry entry : entries) expected.put(entry.name, entry);
        Set<String> seen = new HashSet<>();
        List<String> passwords = spec.password == null ? Collections.emptyList()
                : Collections.singletonList(spec.password);
        if (spec.container.equals("7ZIP")) {
            // Exercise ArchiveReader's signature-based routing; its non-7z path needs Android
            // preferences, while the official reader is deliberately independent of them.
            Object reader = readerType.getField("INSTANCE").get(null);
            Method readEntries = readerType.getDeclaredMethod("readEntries", Path.class, List.class);
            readEntries.setAccessible(true);
            for (Object entry : (List<?>) invoke(readEntries, reader, archive, passwords)) {
                Entry original = compareMetadata(entry, spec, expected, seen);
                if (!original.directory()) {
                    InputStream input = (InputStream) invoke(readerType.getMethod("newInputStream",
                            Path.class, List.class, entryType), reader, archive, passwords, entry);
                    compareStream(input, original);
                }
            }
        } else {
            try (SeekableByteChannel channel = openChannel(archive);
                 Closeable reader = (Closeable) construct(readArchiveType.getConstructor(
                         SeekableByteChannel.class, List.class), channel, passwords)) {
                Method readEntry = readArchiveType.getMethod("readEntry", Charset.class, String.class);
                Object entry;
                while ((entry = invoke(readEntry, reader, StandardCharsets.UTF_8,
                        spec.raw ? entries.get(0).name : null)) != null) {
                    Entry original = compareMetadata(entry, spec, expected, seen);
                    if (!original.directory()) {
                        compareStream((InputStream) call(reader, "newDataInputStream"), original);
                    }
                }
            }
        }
        require(seen.equals(expected.keySet()), "Reopening lost archive entries: " + expected.keySet()
                + " != " + seen);
    }

    private static Entry compareMetadata(Object actual, Spec spec, Map<String, Entry> expected,
            Set<String> seen) throws Throwable {
        String name = (String) call(actual, "getName");
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        Entry original = expected.get(name);
        require(original != null, "Unexpected archive entry: " + name);
        require(seen.add(name), "Duplicate archive entry: " + name);
        require((boolean) call(actual, "isDirectory") == original.directory(),
                "Directory type was not preserved: " + name);
        require(!(boolean) call(actual, "isSymbolicLink"), "Regular source became a symbolic link");
        if (!original.directory() && !spec.raw) {
            require((long) call(actual, "getSize") == original.bytes.length,
                    "Incorrect stored entry size: " + name);
            if (original.bytes.length > 0) {
                require((boolean) call(actual, "isEncrypted") == (spec.password != null),
                        "Unexpected encryption flag: " + name);
            }
        }
        return original;
    }

    private static void compareStream(InputStream input, Entry original) throws IOException {
        require(input != null, "No input stream for " + original.name);
        try (InputStream owned = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4110];
            require(owned.read(buffer, 7, 0) == 0, "A zero-length read consumed archive data");
            int first = owned.read();
            if (first >= 0) bytes.write(first);
            while (true) {
                Arrays.fill(buffer, (byte) 0xA5);
                int count = owned.read(buffer, 7, 4096);
                for (int index = 0; index < 7; ++index) {
                    require(buffer[index] == (byte) 0xA5 && buffer[4103 + index] == (byte) 0xA5,
                            "Archive read wrote outside the requested buffer range");
                }
                if (count < 0) break;
                require(count > 0 && count <= 4096, "Invalid archive stream read length: " + count);
                bytes.write(buffer, 7, count);
                require(bytes.size() <= original.bytes.length, "Extra decompressed bytes: " + original.name);
            }
            require(owned.read(buffer, 7, 0) == 0, "Zero-length EOF read did not return zero");
            require(Arrays.equals(original.bytes, bytes.toByteArray()),
                    "Decompressed bytes differ: " + original.name);
        }
    }

    private static void assertVolumes(File base, long splitSize, long length) {
        long count = Math.max(1, (length + splitSize - 1) / splitSize);
        require(count > 1, "The format fixture did not cross a split boundary");
        require(!base.exists(), "Split output left an extra unsplit archive");
        for (int index = 1; index <= count; ++index) {
            File part = new File(base.getPath() + String.format(Locale.ROOT, ".%03d", index));
            require(part.isFile() && part.length() == Math.min(splitSize,
                            length - (index - 1) * splitSize), "Incorrect archive volume: " + part);
        }
        require(!new File(base.getPath() + String.format(Locale.ROOT, ".%03d", count + 1)).exists(),
                "Split archive has an extra trailing part");
    }

    private static void verifyUnfilteredInputRejected() throws Throwable {
        File file = new File(work, "source/plain-input.txt");
        putNew(file, "This ordinary text is not a compressed stream.\n".getBytes(StandardCharsets.UTF_8));
        try (SeekableByteChannel channel = openChannel(path(file))) {
            expect(IOException.class, () -> {
                try (Closeable reader = (Closeable) construct(readArchiveType.getConstructor(
                        SeekableByteChannel.class, List.class), channel, Collections.emptyList())) {
                    invoke(readArchiveType.getMethod("readEntry", Charset.class, String.class),
                            reader, StandardCharsets.UTF_8, "plain-input.txt");
                }
            });
        }
    }

    private static void verifySourceDeletion(int mode) throws Throwable {
        String label = new String[]{"unchanged", "changed", "new-child"}[mode];
        File directory = new File(work, "delete-" + label + "/source-tree");
        mkdir(directory);
        File originalFile = new File(directory, "original.txt");
        byte[] saved = "These bytes belong to the completed archive.\n".getBytes(StandardCharsets.UTF_8);
        putNew(originalFile, saved);
        List<Entry> entries = Arrays.asList(new Entry("source-tree", directory, null),
                new Entry("source-tree/original.txt", originalFile, saved));
        Object directorySnapshot = snapshot(path(directory));
        Object fileSnapshot = snapshot(path(originalFile));
        Spec spec = standard("delete-" + label + ".zip", "zip", "ZIP", "NONE");
        Path archive = create(spec, entries);
        // create() only returns after writer close, output commit and output close succeed.
        readAndCompare(spec, archive, entries);
        if (mode == 1) {
            try (FileOutputStream output = new FileOutputStream(originalFile, true)) {
                output.write("New unarchived bytes.\n".getBytes(StandardCharsets.UTF_8));
            }
            expect(IOException.class, () -> call(fileSnapshot, "delete"));
            require(originalFile.length() > saved.length, "Changed source bytes were removed");
            require(directory.isDirectory(), "The changed source's parent was removed");
        } else if (mode == 2) {
            File added = new File(directory, "new.txt");
            byte[] addedBytes = "Added after the archive committed.\n".getBytes(StandardCharsets.UTF_8);
            putNew(added, addedBytes);
            call(fileSnapshot, "delete");
            require(!originalFile.exists(), "An unchanged archived file was not deleted");
            expect(DirectoryNotEmptyException.class, () -> call(directorySnapshot, "delete"));
            require(directory.isDirectory() && Arrays.equals(addedBytes, readFile(added)),
                    "Post-archive deletion removed newly added source data");
        } else {
            call(fileSnapshot, "delete");
            call(directorySnapshot, "delete");
            require(!originalFile.exists() && !directory.exists(), "Unchanged archived sources remain");
        }
        readAndCompare(spec, archive, entries);
        addManifest(spec, entries);
    }

    private static Object snapshot(Path source) throws Throwable {
        return construct(Class.forName(PACKAGE + "filejob.ArchiveSourceSnapshot")
                .getConstructor(Path.class, BasicFileAttributes.class), source, attributes(source));
    }

    private static void addManifest(Spec spec, List<Entry> entries) throws Throwable {
        JSONObject item = new JSONObject();
        item.put("archive", spec.archiveName());
        item.put("format", spec.format);
        item.put("preset", spec.preset);
        item.put("password", spec.password == null ? JSONObject.NULL : spec.password);
        item.put("raw", spec.raw);
        item.put("splitSize", spec.splitSize);
        item.put("encryptFileNames", spec.encryptNames);
        JSONObject members = new JSONObject();
        JSONArray directories = new JSONArray();
        for (Entry entry : entries) {
            if (entry.directory()) {
                directories.put(entry.name);
            } else {
                JSONObject member = new JSONObject();
                member.put("sha256", sha256(entry.bytes));
                member.put("size", entry.bytes.length);
                members.put(entry.name, member);
            }
        }
        item.put("members", members);
        item.put("directories", directories);
        MANIFEST.put(item);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            text.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }

    private static Object function(CheckedConsumer body) {
        return Proxy.newProxyInstance(functionType.getClassLoader(), new Class<?>[]{functionType},
                (proxy, method, args) -> {
                    if (method.getName().equals("invoke")) {
                        body.accept(args[0]);
                        return unit;
                    }
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("toString")) return "Archive format test callback";
                    throw new UnsupportedOperationException(method.toString());
                });
    }

    private static int constant(String name) throws ReflectiveOperationException {
        return constants.getField(name).getInt(null);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return filesystem.provider().readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path path(File file) { return filesystem.getPath(file.getAbsolutePath()); }

    private static SeekableByteChannel openChannel(Path archive) throws Throwable {
        return (SeekableByteChannel) invoke(splitType.getMethod("newArchiveByteChannel", Path.class),
                null, archive);
    }

    private static Object call(Object target, String name) throws Throwable {
        return invoke(target.getClass().getMethod(name), target);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Object construct(Constructor<?> constructor, Object... arguments) throws Throwable {
        try { return constructor.newInstance(arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void mkdir(File directory) {
        require(directory.isDirectory() || directory.mkdirs(), "Unable to create directory: " + directory);
    }

    private static void putNew(File file, byte[] bytes) throws IOException {
        mkdir(file.getParentFile());
        require(file.createNewFile(), "A fixture file already exists: " + file);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private static void expect(Class<? extends Throwable> type, CheckedRunnable body) throws Throwable {
        try { body.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(String name, CheckedRunnable body) {
        ++checks;
        try {
            body.run();
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            FAILURES.add(name);
            System.out.println("FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable failure) {
        failure.printStackTrace(System.err);
        System.exit(1);
    }

    private static final class Entry {
        final String name;
        final File file;
        final byte[] bytes;
        Entry(String name, File file, byte[] bytes) {
            this.name = name;
            this.file = file;
            this.bytes = bytes;
        }
        boolean directory() { return bytes == null; }
    }

    private static final class Spec {
        final String filename;
        final String format;
        final String container;
        final String filter;
        final String preset;
        final String password;
        final boolean encryptNames;
        final boolean raw;
        final long splitSize;
        Spec(String filename, String format, String container, String filter, String preset,
                String password, boolean encryptNames, boolean raw, long splitSize) {
            this.filename = filename;
            this.format = format;
            this.container = container;
            this.filter = filter;
            this.preset = preset;
            this.password = password;
            this.encryptNames = encryptNames;
            this.raw = raw;
            this.splitSize = splitSize;
        }
        String archiveName() { return "output/" + filename + (splitSize == 0 ? "" : ".001"); }
    }

    private static final class ArchiveOutput implements Closeable {
        final Object delegate;
        final SeekableByteChannel channel;
        ArchiveOutput(Path file, long splitSize) throws Throwable {
            delegate = construct(outputType.getConstructor(Path.class, long.class), file, splitSize);
            channel = (SeekableByteChannel) call(delegate, "getChannel");
        }
        void commit() throws Throwable { call(delegate, "commit"); }
        @Override public void close() throws IOException { ((Closeable) delegate).close(); }
    }

    private interface CheckedRunnable { void run() throws Throwable; }
    private interface CheckedConsumer { void accept(Object value) throws Throwable; }
}
