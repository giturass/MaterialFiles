/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import java8.nio.channels.SeekableByteChannel;
import java8.nio.file.DirectoryStream;
import java8.nio.file.DirectoryNotEmptyException;
import java8.nio.file.FileSystem;
import java8.nio.file.LinkOption;
import java8.nio.file.Path;
import java8.nio.file.attribute.BasicFileAttributes;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Creates archives through the APK's JBinding ArchiveWriter and reads them through ArchiveReader.
 * Leaves a manifest for independent official 7zz interoperability checks.
 * The physical provider comes from ArchiveVolumeTest; no Activity or application preferences are
 * needed. ArchiveSourceSnapshot checks exercise the actual post-commit source-deletion guard.
 */
public final class ArchiveFormatTest {
    private static final String PACKAGE = "me.zhanghai.android.files.";
    private static final String ARCHIVER = PACKAGE + "provider.archive.archiver.";
    private static final String PASSWORD = "测试-password-🔐";
    private static final String ZIP_PASSWORD = "zip-password-123!";
    private static final List<String> FAILURES = new ArrayList<>();
    private static final JSONArray MANIFEST = new JSONArray();
    private static File work;
    private static FileSystem filesystem;
    private static Class<?> creationType;
    private static Class<?> presetType;
    private static Class<?> functionType;
    private static Class<?> writerType;
    private static Class<?> readerType;
    private static Class<?> entryType;
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
            File cache = new File(work, "cache");
            ArchiveTestSupport.installApplication(cache);
            loadTypes();
            try (FileSystem owned = ArchiveVolumeTest.newPhysicalFileSystem(work)) {
                filesystem = owned;
                ArchiveVolumeTest.installDefaultProvider(filesystem);
                // App-private staging must remain independent of the active file/root provider.
                ArchiveVolumeTest.rejectPrivateCacheAccess(filesystem, true);
                List<Entry> entries = createSources(new File(work, "source"));
                List<Entry> rawEntries = Collections.singletonList(new Entry("payload.bin",
                        new File(work, "source/payload.bin"), payload(24577)));
                putNew(rawEntries.get(0).file, rawEntries.get(0).bytes);
                for (String preset : new String[]{"SPEED", "STANDARD", "QUALITY"}) {
                    String suffix = preset.toLowerCase(Locale.ROOT);
                    verify(new Spec("zip-password-" + suffix + ".zip", "zip", "ZIP",
                            preset, ZIP_PASSWORD, false, false, 0), entries);
                    verify(new Spec("7z-headers-" + suffix + ".7z", "7z", "SEVEN_Z",
                            preset, PASSWORD, true, false, 0), entries);
                    verify(new Spec("7z-contents-" + suffix + ".7z", "7z", "SEVEN_Z",
                            preset, PASSWORD, false, false, 0), entries);
                    verify(new Spec("payload-" + suffix + ".bin.bz2", "bzip2", "BZIP2",
                            preset, null, false, true, 0), Collections.singletonList(
                                    new Entry("payload-" + suffix + ".bin", rawEntries.get(0).file,
                                            rawEntries.get(0).bytes)));
                }
                verify(standard("zip-plain.zip", "zip", "ZIP"), entries);
                verify(standard("7z-plain.7z", "7z", "SEVEN_Z"), entries);
                verify(standard("plain.tar", "tar", "TAR"), entries);
                verify(new Spec("zip-password-99.zip", "zip", "ZIP", "STANDARD",
                        String.join("", Collections.nCopies(99, "x")), false, false, 0), entries);
                verify(new Spec("payload.bin.gz", "gzip", "GZIP", "STANDARD",
                        null, false, true, 0), rawEntries);
                verify(new Spec("payload.bin.bz2", "bzip2", "BZIP2", "STANDARD",
                        null, false, true, 0), rawEntries);
                verify(new Spec("split.zip", "zip", "ZIP", "STANDARD",
                        ZIP_PASSWORD, false, false, 37), entries);
                verify(new Spec("split.7z", "7z", "SEVEN_Z", "STANDARD",
                        PASSWORD, true, false, 37), entries);
                verify(new Spec("split.tar", "tar", "TAR", "STANDARD",
                        null, false, false, 4093), entries);
                check("new split encrypted 7z roots request a password and retry without cached metadata",
                        () -> verifySplitPasswordRetry(entries.subList(0, 5)));
                for (String creation : new String[]{"ZIP", "SEVEN_Z", "TAR"}) {
                    verify(standard("empty-" + creation + ".archive", creation.toLowerCase(Locale.ROOT),
                            creation), Collections.emptyList());
                }
                check("symbolic links and executable modes survive ZIP/7z/TAR creation",
                        ArchiveFormatTest::verifySymlinks);
                check("short provider reads and writes preserve archive contents",
                        ArchiveFormatTest::verifyShortIo);
                check("stream-only providers read encrypted 7z through temporary storage",
                        ArchiveFormatTest::verifyStreamOnly);
                check("ZIP caches a stream-only source without provider cache access or double-counting progress",
                        ArchiveFormatTest::verifyZipStreamOnlySource);
                check("7z reports encoded work instead of read-ahead and advances before completion",
                        ArchiveFormatTest::verifySevenZipProgress);
                check("a failed ZIP seek probe and source close cannot fall back or publish output",
                        ArchiveFormatTest::verifyProbeCloseFailure);
                for (String failure : new String[]{"source-close", "output-close", "grow", "shrink",
                        "zero-write", "cancel", "header-zero-write"}) {
                    check("failed creation rolls back output and retains sources: " + failure,
                            () -> verifyCreationFailure(failure));
                }
                check("raw formats reject multiple inputs and directories",
                        () -> verifyRawInputsRejected(entries));
                check("ZIP rejects Unicode, control characters and passwords over 99 bytes",
                        () -> verifyZipPasswordsRejected(entries));
                check("unfiltered ordinary input is rejected instead of exposed as a raw archive",
                        ArchiveFormatTest::verifyUnfilteredInputRejected);
                for (int mode = 0; mode < 3; ++mode) {
                    final int currentMode = mode;
                    check(new String[]{"unchanged archived sources delete after output commit",
                            "changed source bytes survive post-archive deletion",
                            "new child survives deletion of the previously archived tree"}[mode],
                            () -> verifySourceDeletion(currentMode));
                }
                ArchiveTestSupport.requireEmptyCache(cache);
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
        creationType = Class.forName(ARCHIVER + "ArchiveCreationFormat");
        presetType = Class.forName(ARCHIVER + "ArchiveCompressionPreset");
        functionType = Class.forName("kotlin.jvm.functions.Function1");
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        writerType = Class.forName(ARCHIVER + "ArchiveWriter");
        readerType = Class.forName(ARCHIVER + "ArchiveReader");
        entryType = Class.forName(ARCHIVER + "ArchiveEntry");
        splitType = Class.forName(ARCHIVER + "SplitArchiveKt");
    }

    private static List<Entry> createSources(File directory) throws IOException {
        mkdir(directory);
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("hello.txt", new File(directory, "hello.txt"),
                "7-Zip-JBinding preserves UTF-8: 测试 🔐\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new Entry("文档", new File(directory, "文档"), null));
        entries.add(new Entry("文档/数据.bin", new File(directory, "文档/数据.bin"), payload(32771)));
        entries.add(new Entry("empty.bin", new File(directory, "empty.bin"), new byte[0]));
        entries.add(new Entry("empty-directory", new File(directory, "empty-directory"), null));
        byte[] random = new byte[131101];
        new Random(0x5EED7A1FL).nextBytes(random);
        entries.add(new Entry("incompressible.bin", new File(directory, "incompressible.bin"), random));
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

    private static Spec standard(String filename, String format, String creation) {
        return new Spec(filename, format, creation, "STANDARD", null, false, false, 0);
    }

    private static void verify(Spec spec, List<Entry> entries) {
        check(spec.filename + ": production output transaction, progress, reopen and compare every entry", () -> {
            Path archive = create(spec, entries);
            readAndCompare(spec, archive, entries);
            addManifest(spec, entries);
        });
    }

    private static Path create(Spec spec, List<Entry> entries) throws Throwable {
        File outputFile = new File(work, "output/" + spec.filename);
        long archiveSize = writeArchive(spec, path(outputFile), entries);
        if (spec.splitSize > 0) {
            assertVolumes(outputFile, spec.splitSize, archiveSize);
        }
        return path(new File(work, spec.archiveName()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static long writeArchive(Spec spec, Path target, List<Entry> entries) throws Throwable {
        return writeArchive(spec, target, entries, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static long writeArchive(Spec spec, Path target, List<Entry> entries,
            CheckedConsumer onProgress) throws Throwable {
        List<Object> pairs = new ArrayList<>();
        Map<String, Integer> completed = new LinkedHashMap<>();
        Set<String> started = new HashSet<>();
        Constructor<?> pair = Class.forName("kotlin.Pair").getConstructor(Object.class, Object.class);
        long expectedProgress = 0;
        for (Entry entry : entries) {
            Path source = entry.providerPath != null ? entry.providerPath : path(entry.file);
            pairs.add(construct(pair, source, filesystem.getPath(entry.name)));
            completed.put(source.toString(), 0);
            expectedProgress += attributes(source).size();
        }
        final long progressMax = expectedProgress;
        long[] progress = {0};
        long[] previousProgress = {0};
        Object listener = function(value -> {
            long amount = (Long) value;
            require(amount > 0, "Archive progress did not advance");
            previousProgress[0] = progress[0];
            progress[0] += amount;
            require(progress[0] <= progressMax, "Archive progress exceeded its total");
            if (onProgress != null) onProgress.accept(progress[0]);
        });
        Object onEntry = function(value -> {
            String source = value.toString();
            require(completed.containsKey(source), "Started an unselected source: " + source);
            require(started.add(source), "Started a selected source more than once: " + source);
        });
        Object onComplete = function(value -> {
            String source = value.toString();
            require(completed.containsKey(source), "Completed an unselected source: " + source);
            completed.put(source, completed.get(source) + 1);
        });
        Object preset = Enum.valueOf((Class) presetType, spec.preset);
        long[] archiveSize = {-1};
        Object write = function(value -> {
            SeekableByteChannel channel = (SeekableByteChannel) value;
            try (Closeable writer = (Closeable) construct(writerType.getConstructor(
                    SeekableByteChannel.class, creationType, String.class, boolean.class,
                    presetType), channel, Enum.valueOf((Class) creationType, spec.creation),
                    spec.password, spec.encryptNames, preset)) {
                invoke(writerType.getMethod("writeEntries", List.class, long.class, functionType,
                        functionType, functionType), writer, pairs, 0L, listener, onEntry, onComplete);
            }
            archiveSize[0] = channel.size();
        });
        File cache = new File(work, "cache");
        try {
            // This is the same transaction used by ArchiveFileJob, including its real staging,
            // sequential volume publication, commit, close and rollback paths.
            invoke(splitType.getMethod("writeArchiveOutput", Path.class, long.class, File.class,
                    functionType), null, target, spec.splitSize, cache, write);
        } finally {
            ArchiveTestSupport.requireEmptyCache(cache);
        }
        require(archiveSize[0] >= 0, "Output transaction never completed its archive writer");
        require(progress[0] == expectedProgress,
                "Progress mismatch: " + progress[0] + " != " + expectedProgress);
        require(expectedProgress == 0 || previousProgress[0] / (double) expectedProgress <= 0.99,
                "Archive progress could display 100% before the final completion callback");
        require(started.equals(completed.keySet()), "Some selected inputs were never started");
        for (Map.Entry<String, Integer> completedEntry : completed.entrySet()) {
            require(completedEntry.getValue() == 1,
                    "Entry completion was missing or repeated: " + completedEntry);
        }
        return archiveSize[0];
    }

    private static void readAndCompare(Spec spec, Path archive, List<Entry> entries) throws Throwable {
        Map<String, Entry> expected = new LinkedHashMap<>();
        for (Entry entry : entries) expected.put(entry.name, entry);
        Set<String> seen = new HashSet<>();
        List<String> passwords = spec.password == null ? Collections.emptyList()
                : Collections.singletonList(spec.password);
        Object reader = readerType.getField("INSTANCE").get(null);
        Method readEntries = readerType.getDeclaredMethod("readEntries", Path.class, List.class);
        readEntries.setAccessible(true);
        for (Object entry : (List<?>) invoke(readEntries, reader, archive, passwords)) {
            Entry original = compareMetadata(entry, spec, expected, seen);
            if (!original.directory() && !original.symbolicLink()) {
                InputStream input = (InputStream) invoke(readerType.getMethod("newInputStream",
                        Path.class, List.class, entryType), reader, archive, passwords, entry);
                compareStream(input, original);
            }
        }
        require(seen.equals(expected.keySet()), "Reopening lost archive entries: " + expected.keySet()
                + " != " + seen);
        if (spec.password != null) {
            if (spec.encryptNames) {
                for (List<String> invalid : Arrays.asList(Collections.<String>emptyList(),
                        Collections.singletonList("wrong-password"))) {
                    try {
                        invoke(readEntries, reader, archive, invalid);
                        throw new AssertionError("Encrypted file names can be listed without the correct password");
                    } catch (IOException failure) {
                        require(Class.forName(ARCHIVER + "ArchivePassphraseException").isInstance(failure),
                                "Encrypted-header listing did not request a password: " + failure);
                    }
                }
            } else {
                List<?> visible = (List<?>) invoke(readEntries, reader, archive, Collections.emptyList());
                require(visible.size() == expected.size(), "Content-only encryption unexpectedly hid file names");
                Object encryptedSample = null;
                for (Object entry : visible) {
                    if ((boolean) call(entry, "isEncrypted") && (long) call(entry, "getSize") > 0
                            && !(boolean) call(entry, "isDirectory")) {
                        encryptedSample = entry;
                        break;
                    }
                }
                require(encryptedSample != null, "Password fixture contains no encrypted file data");
                for (List<String> invalid : Arrays.asList(Collections.<String>emptyList(),
                        Collections.singletonList("wrong-password"))) {
                    try (InputStream input = (InputStream) invoke(readerType.getMethod("newInputStream",
                            Path.class, List.class, entryType), reader, archive, invalid, encryptedSample)) {
                        byte[] bytes = new byte[8192];
                        while (input.read(bytes) >= 0) {}
                        throw new AssertionError("Encrypted archive data accepted an absent or incorrect password");
                    } catch (IOException failure) {
                        require(Class.forName(ARCHIVER + "ArchivePassphraseException").isInstance(failure),
                                "Encrypted data extraction did not report a password error: " + failure);
                    }
                }
            }
        }
    }

    private static void verifySplitPasswordRetry(List<Entry> entries) throws Throwable {
        Spec spec = new Spec("retry-headers.7z", "7z", "SEVEN_Z", "STANDARD",
                PASSWORD, true, false, 173);
        // First touch the new archive through the same provider entry point used for browsing.
        // Pre-populating ArchiveFileSystem.entries would bypass the initial header-password failure.
        Path archiveFile = create(spec, entries);
        Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
        File cache = new File(work, "cache");
        for (boolean streamsOnly : new boolean[]{false, true}) {
            ArchiveVolumeTest.configureIo(filesystem, streamsOnly ? 19 : Integer.MAX_VALUE,
                    Integer.MAX_VALUE, streamsOnly, null, null);
            Path root = (Path) invoke(paths.getMethod("createArchiveRootPath", Path.class),
                    null, archiveFile);
            try (FileSystem archive = root.getFileSystem()) {
                retryPasswordPrompt(root, archiveFile, false, "wrong-password");
                ArchiveTestSupport.requireEmptyCache(cache);
                retryPasswordPrompt(root, archiveFile, true, PASSWORD);
                ArchiveTestSupport.requireEmptyCache(cache);
                Set<String> listed = new HashSet<>();
                collectArchiveChildren(root, root, listed);
                Set<String> expectedNames = new HashSet<>();
                Map<String, Entry> expected = new LinkedHashMap<>();
                for (Entry entry : entries) {
                    expectedNames.add(entry.name);
                    expected.put(entry.name, entry);
                }
                require(listed.equals(expectedNames), "Password retry lost the archive directory tree: "
                        + listed + " != " + expectedNames);
                Set<String> seen = new HashSet<>();
                for (Entry original : entries) {
                    Path member = root.resolve(original.name);
                    Object entry = invoke(archive.getClass().getMethod("getEntry", Path.class),
                            archive, member);
                    compareMetadata(entry, spec, expected, seen);
                    if (!original.directory() && !original.symbolicLink()) {
                        compareStream(archive.provider().newInputStream(member), original);
                    }
                }
                ArchiveTestSupport.requireEmptyCache(cache);
            } finally {
                ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        false, null, null);
            }
        }
        addManifest(spec, entries);
    }

    private static void collectArchiveChildren(Path root, Path directory, Set<String> names)
            throws IOException {
        try (DirectoryStream<Path> children = directory.getFileSystem().provider()
                .newDirectoryStream(directory, path -> true)) {
            for (Path child : children) {
                require(names.add(root.relativize(child).toString()), "Repeated archive child: " + child);
                if (child.getFileSystem().provider().readAttributes(child, BasicFileAttributes.class)
                        .isDirectory()) {
                    collectArchiveChildren(root, child, names);
                }
            }
        }
    }

    private static void retryPasswordPrompt(Path root, Path archiveFile, boolean incorrect,
            String nextPassword) throws Throwable {
        IOException rejected = null;
        try (DirectoryStream<Path> ignored = root.getFileSystem().provider()
                .newDirectoryStream(root, path -> true)) {
            throw new AssertionError("Encrypted split file names were visible without the correct password");
        } catch (IOException failure) {
            rejected = failure;
        }
        Class<?> errorType = Class.forName(PACKAGE + "provider.archive.ArchivePasswordRequiredException");
        require(errorType.isInstance(rejected), "First root listing lost its actionable password error: " + rejected);
        require(Boolean.valueOf(incorrect).equals(call(rejected, "isPasswordIncorrect")),
                "Root listing confused missing and incorrect passwords");
        Resources resources = new Resources(Resources.getSystem().getAssets(),
                Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration()) {
            @Override public String getString(int id) { return "Archive password"; }
            @Override public String getString(int id, Object... arguments) { return Arrays.toString(arguments); }
        };
        Context context = new Application() {
            @Override public Resources getResources() { return resources; }
        };
        Class<?> continuationType = Class.forName("kotlin.coroutines.Continuation");
        int[] resumed = {0};
        Object continuation = Proxy.newProxyInstance(continuationType.getClassLoader(),
                new Class<?>[]{continuationType}, (proxy, method, arguments) -> {
                    require(method.getName().equals("resumeWith") && Boolean.TRUE.equals(arguments[0]),
                            "Password retry did not resume successfully");
                    ++resumed[0];
                    return null;
                });
        Object action = invoke(errorType.getMethod("getUserAction", continuationType, Context.class),
                rejected, continuation, context);
        require(("[" + archiveFile.getFileName() + "]").equals(call(action, "getMessage")),
                "Split-password prompt did not identify its .001 volume");
        Intent intent = (Intent) call(action, "getIntent");
        Object args = intent.getParcelableExtra(PACKAGE + "fileaction.ArchivePasswordDialogFragment$Args");
        require(args != null && root.equals(call(args, "getPath")), "Password prompt lost the archive root");
        require(Boolean.valueOf(incorrect).equals(call(args, "isPasswordIncorrect")),
                "Password dialog lost its incorrect-password flag");
        Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
        require(archiveFile.equals(invoke(paths.getMethod("getArchiveFile", Path.class), null, root)),
                "Password prompt changed the physical split archive");
        invoke(paths.getMethod("archiveAddPassword", Path.class, String.class),
                null, call(args, "getPath"), nextPassword);
        invoke(Class.forName("kotlin.jvm.functions.Function1").getMethod("invoke", Object.class),
                call(args, "getListener"), Boolean.TRUE);
        require(resumed[0] == 1, "Password submission resumed listing more than once");
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
        require((boolean) call(actual, "isSymbolicLink") == original.symbolicLink(),
                "Symbolic link type was not preserved: " + name);
        if (original.symbolicLink()) {
            require(original.linkTarget.equals(call(actual, "getSymbolicLinkTarget")),
                    "Symbolic link target changed: " + name);
        }
        if (original.mode != null) {
            int mode = (int) invoke(Class.forName(PACKAGE + "provider.common.PosixFileModeKt")
                    .getMethod("toInt", Set.class), null, call(actual, "getMode"));
            require((mode & 0777) == original.mode, "File permissions changed: " + name);
        }
        if (!original.directory() && !original.symbolicLink() && !spec.raw) {
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
        Object reader = readerType.getField("INSTANCE").get(null);
        Method readEntries = readerType.getDeclaredMethod("readEntries", Path.class, List.class);
        readEntries.setAccessible(true);
        expect(IOException.class, () -> invoke(readEntries, reader, path(file), Collections.emptyList()));
    }

    private static void verifySymlinks() throws Throwable {
        File directory = new File(work, "symbolic-sources");
        mkdir(directory);
        File script = new File(directory, "executable.sh");
        byte[] scriptBytes = "#!/bin/sh\nprintf archive\n".getBytes(StandardCharsets.UTF_8);
        putNew(script, scriptBytes);
        Os.chmod(script.toString(), 0750);
        File link = new File(directory, "link");
        Os.symlink("executable.sh", link.toString());
        List<Entry> entries = Arrays.asList(new Entry("executable.sh", script, scriptBytes, null, 0750),
                new Entry("link", link, "executable.sh".getBytes(StandardCharsets.UTF_8), "executable.sh", 0777));
        ArchiveVolumeTest.usePosixAttributes(filesystem, true);
        try {
            for (String[] format : new String[][]{{"ZIP", "zip"}, {"SEVEN_Z", "7z"}, {"TAR", "tar"}}) {
                Spec spec = standard("symlinks." + format[1], format[1], format[0]);
                Path archive = create(spec, entries);
                readAndCompare(spec, archive, entries);
                addManifest(spec, entries);
            }
        } finally {
            ArchiveVolumeTest.usePosixAttributes(filesystem, false);
        }
    }

    private static void verifyShortIo() throws Throwable {
        Entry entry = new Entry("payload.bin", new File(work, "short-source.bin"), payload(33101));
        putNew(entry.file, entry.bytes);
        ArchiveVolumeTest.configureIo(filesystem, 5, 3, false, null, null);
        try {
            for (Spec spec : Arrays.asList(
                    new Spec("short-io.7z", "7z", "SEVEN_Z", "STANDARD", PASSWORD, true, false, 0),
                    new Spec("short-io.zip", "zip", "ZIP", "STANDARD", ZIP_PASSWORD, false, false, 0))) {
                Path archive = create(spec, Collections.singletonList(entry));
                readAndCompare(spec, archive, Collections.singletonList(entry));
                addManifest(spec, Collections.singletonList(entry));
            }
        } finally {
            ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null);
        }
    }

    private static void verifyStreamOnly() throws Throwable {
        Entry entry = new Entry("payload.bin", new File(work, "stream-source.bin"), payload(18001));
        putNew(entry.file, entry.bytes);
        Spec spec = new Spec("stream-provider.7z", "7z", "SEVEN_Z", "STANDARD",
                PASSWORD, true, false, 0);
        Path archive = create(spec, Collections.singletonList(entry));
        ArchiveVolumeTest.configureIo(filesystem, 17, Integer.MAX_VALUE, true, null, null);
        try {
            readAndCompare(spec, archive, Collections.singletonList(entry));
            addManifest(spec, Collections.singletonList(entry));
        } finally {
            ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null);
        }
    }

    private static void verifyZipStreamOnlySource() throws Throwable {
        File directory = new File(work, "zip-stream-sources");
        mkdir(directory);
        try (FileSystem sourceFilesystem = ArchiveVolumeTest.newPhysicalFileSystem(directory)) {
            byte[] random = new byte[131111];
            new Random(0x21757EADL).nextBytes(random);
            Entry entry = new Entry("source.bin", new File(directory, "source.bin"), random);
            putNew(entry.file, entry.bytes);
            entry.providerPath = sourceFilesystem.getPath(entry.file.toString());
            ArchiveVolumeTest.configureIo(sourceFilesystem, 19, Integer.MAX_VALUE, true, null, null);
            Spec spec = new Spec("zip-stream-source.zip", "zip", "ZIP", "STANDARD", ZIP_PASSWORD, false, false, 0);
            Path archive = create(spec, Collections.singletonList(entry));
            readAndCompare(spec, archive, Collections.singletonList(entry));
            addManifest(spec, Collections.singletonList(entry));
        }
    }

    private static void verifySevenZipProgress() throws Throwable {
        Entry entry = new Entry("progress.bin", new File(work, "progress-source.bin"),
                payload(8 * 1024 * 1024 + 37));
        putNew(entry.file, entry.bytes);
        Spec spec = standard("encoding-progress.7z", "7z", "SEVEN_Z");
        AtomicLong progress = new AtomicLong();
        AtomicLong intermediateUpdates = new AtomicLong();
        AtomicBoolean checkedReadAhead = new AtomicBoolean();
        // Shorten only the first read. LZMA2 must fill its input buffer before it can encode
        // a block, so consuming those first 17 bytes must not count as completed work.
        ArchiveVolumeTest.configureIo(filesystem, 17, Integer.MAX_VALUE, false, null, null);
        ArchiveVolumeTest.afterFirstSourceRead(filesystem, () -> {
            ArchiveVolumeTest.configureIo(filesystem, 64 * 1024, Integer.MAX_VALUE, false, null, null);
            ArchiveVolumeTest.afterFirstSourceRead(filesystem, () -> {
                require(progress.get() == 0, "7z reported buffered source bytes as encoded work");
                checkedReadAhead.set(true);
            });
        });
        Path output = path(new File(work, spec.archiveName()));
        try {
            writeArchive(spec, output, Collections.singletonList(entry), value -> {
                long completed = (Long) value;
                progress.set(completed);
                if (completed > 0 && completed < entry.bytes.length) {
                    intermediateUpdates.incrementAndGet();
                }
            });
            require(checkedReadAhead.get(), "7z did not exercise multiple source reads");
            require(intermediateUpdates.get() >= 2,
                    "7z did not report encoding progress before its final update");
        } finally {
            ArchiveVolumeTest.afterFirstSourceRead(filesystem, null);
            ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null);
        }
        readAndCompare(spec, output, Collections.singletonList(entry));
        addManifest(spec, Collections.singletonList(entry));
    }

    private static void verifyCreationFailure(String failure) throws Throwable {
        Thread ownerThread = Thread.currentThread();
        AtomicLong lastProgress = new AtomicLong();
        Entry entry = new Entry("payload.bin", new File(work, "failure-source-" + failure + ".bin"), payload(32779));
        putNew(entry.file, entry.bytes);
        File output = new File(work, "output/failure-" + failure + ".7z");
        Spec spec = failure.equals("header-zero-write")
                ? new Spec(output.getName(), "7z", "SEVEN_Z", "STANDARD", PASSWORD, true, false, 0)
                : standard(output.getName(), "7z", "SEVEN_Z");
        ArchiveVolumeTest.configureIo(filesystem, 11, failure.equals("zero-write") ? 0 : 127, false,
                failure.equals("source-close") ? entry.file.getName() : null,
                failure.equals("output-close") ? output.getName() : null);
        ArchiveVolumeTest.failOutputProgress(filesystem, failure.equals("header-zero-write") ? output.getName() : null);
        if (failure.equals("grow") || failure.equals("shrink")) {
            ArchiveVolumeTest.afterFirstSourceRead(filesystem, () -> {
                try (RandomAccessFile source = new RandomAccessFile(entry.file, "rw")) {
                    source.setLength(failure.equals("grow") ? entry.bytes.length + 100 : 13);
                } catch (IOException error) { throw new RuntimeException(error); }
            });
        }
        try {
            expect(failure.equals("cancel") ? InterruptedIOException.class : IOException.class,
                    () -> writeArchive(spec, path(output), Collections.singletonList(entry),
                            value -> {
                                lastProgress.set((Long) value);
                                if (failure.equals("cancel")) ownerThread.interrupt();
                            }));
            require(!output.exists(), "Failed writer left a partial archive: " + output);
            require(entry.file.isFile(), "Failed writer deleted its source");
            if (!failure.equals("grow") && !failure.equals("shrink")) {
                require(Arrays.equals(entry.bytes, readFile(entry.file)), "Failed writer changed source bytes");
            }
            if (failure.equals("cancel")) {
                require(Thread.currentThread().isInterrupted(), "Writer swallowed cancellation");
                require(lastProgress.get() > 0 && lastProgress.get() < entry.bytes.length,
                        "Cancelled 7z encoding reported full completion or never exercised progress");
            }
            if (failure.equals("header-zero-write")) ArchiveTestSupport.requireEmptyCache(new File(work, "cache"));
        } finally {
            Thread.interrupted();
            ArchiveVolumeTest.afterFirstSourceRead(filesystem, null);
            ArchiveVolumeTest.failOutputProgress(filesystem, null);
            ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null);
        }
    }

    private static void verifyProbeCloseFailure() throws Throwable {
        Entry entry = new Entry("payload.bin", new File(work, "probe-close-source.bin"), payload(1031));
        putNew(entry.file, entry.bytes);
        File output = new File(work, "output/failure-probe-close.zip");
        Spec spec = standard(output.getName(), "zip", "ZIP");
        ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false,
                entry.file.getName(), null);
        ArchiveVolumeTest.failSeekProbe(filesystem, entry.file.getName());
        int openedStreams = ArchiveVolumeTest.openedStreamCount(filesystem);
        try {
            try {
                writeArchive(spec, path(output), Collections.singletonList(entry));
                throw new AssertionError("A source probe and close failure published a ZIP archive");
            } catch (IOException expected) {
                require(hasFailureMessage(expected, "Injected source seek probe failure"),
                        "ZIP lost the original source seek failure: " + expected);
                require(hasFailureMessage(expected, "Injected close failure"),
                        "ZIP lost the source probe cleanup failure: " + expected);
            }
            require(ArchiveVolumeTest.openedStreamCount(filesystem) == openedStreams,
                    "ZIP fell back to an InputStream after the source probe could not close");
            require(!output.exists(), "Failed source probe left a partial archive");
            require(Arrays.equals(entry.bytes, readFile(entry.file)), "Failed source probe changed its source");
        } finally {
            ArchiveVolumeTest.failSeekProbe(filesystem, null);
            ArchiveVolumeTest.configureIo(filesystem, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null, null);
        }
    }

    private static boolean hasFailureMessage(Throwable failure, String message) {
        if (failure == null) return false;
        if (failure.getMessage() != null && failure.getMessage().contains(message)) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (hasFailureMessage(suppressed, message)) return true;
        }
        return hasFailureMessage(failure.getCause(), message);
    }

    private static void verifyRawInputsRejected(List<Entry> entries) throws Throwable {
        for (String creation : new String[]{"GZIP", "BZIP2"}) {
            for (List<Entry> selected : Arrays.asList(entries, Collections.singletonList(entries.get(1)))) {
                File output = new File(work, "output/invalid-" + creation + "-" + selected.size());
                Spec spec = standard(output.getName(), creation.toLowerCase(Locale.ROOT), creation);
                try {
                    writeArchive(spec, path(output), selected);
                    throw new AssertionError("Raw compression accepted multiple files or a directory");
                } catch (IOException | IllegalArgumentException expected) {
                    require(!output.exists(), "Rejected raw input left an archive behind");
                }
            }
        }
    }

    private static void verifyZipPasswordsRejected(List<Entry> entries) throws Throwable {
        for (String password : new String[]{PASSWORD, "line\nbreak", "tab\tpassword", "control\u007f",
                String.join("", Collections.nCopies(100, "x"))}) {
            File output = new File(work, "output/invalid-zip-password.zip");
            Spec spec = new Spec(output.getName(), "zip", "ZIP", "STANDARD", password, false, false, 0);
            try {
                writeArchive(spec, path(output), entries);
                throw new AssertionError("ZIP accepted an unsupported password");
            } catch (IOException | IllegalArgumentException expected) {
                require(!output.exists(), "Rejected ZIP password left an archive behind");
                String message = expected.getMessage();
                require(message != null && (message.toLowerCase(Locale.ROOT).contains("password")
                        || message.toLowerCase(Locale.ROOT).contains("ascii")), "ZIP password rejection has no useful explanation: " + expected);
            }
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
        Spec spec = standard("delete-" + label + ".zip", "zip", "ZIP");
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
                if (entry.symbolicLink()) member.put("symlink", entry.linkTarget);
                if (entry.mode != null) member.put("mode", entry.mode);
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

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return path.getFileSystem().provider().readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
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
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            // Kotlin callbacks declare no checked exceptions; a Java Proxy adds this wrapper
            // when the real archive writer throws IOException through the output transaction.
            while (cause instanceof UndeclaredThrowableException) {
                cause = ((UndeclaredThrowableException) cause).getUndeclaredThrowable();
            }
            throw cause;
        }
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
        final String linkTarget;
        final Integer mode;
        Path providerPath;
        Entry(String name, File file, byte[] bytes) {
            this(name, file, bytes, null, null);
        }
        Entry(String name, File file, byte[] bytes, String linkTarget, Integer mode) {
            this.name = name;
            this.file = file;
            this.bytes = bytes;
            this.linkTarget = linkTarget;
            this.mode = mode;
        }
        boolean directory() { return bytes == null; }
        boolean symbolicLink() { return linkTarget != null; }
    }

    private static final class Spec {
        final String filename;
        final String format;
        final String creation;
        final String preset;
        final String password;
        final boolean encryptNames;
        final boolean raw;
        final long splitSize;
        Spec(String filename, String format, String creation, String preset,
                String password, boolean encryptNames, boolean raw, long splitSize) {
            this.filename = filename;
            this.format = format;
            this.creation = creation;
            this.preset = preset;
            this.password = password;
            this.encryptNames = encryptNames;
            this.raw = raw;
            this.splitSize = splitSize;
        }
        String archiveName() { return "output/" + filename + (splitSize == 0 ? "" : ".001"); }
    }

    private interface CheckedRunnable { void run() throws Throwable; }
    private interface CheckedConsumer { void accept(Object value) throws Throwable; }
}
