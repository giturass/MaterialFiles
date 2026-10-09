/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java8.nio.file.FileSystem;
import java8.nio.file.Path;

/**
 * Exercises the APK's real extraction copy/conflict branch against physical destinations.
 * The official 7z fixture is named a.zip to verify destination naming independently of the
 * archive signature. Archive metadata is cached using the real reader; file data is decoded
 * normally. Notification throttling and preset skip decisions avoid launching any Activity.
 */
public final class ArchiveExtractionTest {
    private static final String PACKAGE = "me.zhanghai.android.files.";
    private static final byte[] EXISTING = "Preserve this existing destination."
            .getBytes(StandardCharsets.UTF_8);
    private static final List<String> FAILURES = new ArrayList<>();
    private static File fixtures;
    private static File work;
    private static Class<?> jobType;
    private static Class<?> copyJobType;
    private static Class<?> scanType;
    private static Class<?> transferType;
    private static Class<?> decisionsType;
    private static Class<?> functionType;
    private static Object unit;
    private static Method copy;
    private static Method getTargetFileName;
    private static Method archiveRoot;
    private static int checks;

    public static void main(String[] arguments) {
        try {
            require(arguments.length == 2, "Pass the official fixtures and output directories");
            fixtures = new File(arguments[0]);
            work = new File(arguments[1]);
            require(work.isDirectory() || work.mkdirs(), "Unable to create test directory");
            loadTypes();
            check("a.zip creates a/ and extracts its contents", () -> extract(0));
            check("an existing empty a/ needs no merge decision", () -> extract(1));
            check("unrelated existing contents survive extraction into a/", () -> extract(2));
            check("the archive's existing parent can be reused without replacing its source",
                    ArchiveExtractionTest::sourceParent);
            check("a real destination file conflict retains the existing bytes", () -> extract(3));
            check("an existing file at the container path still conflicts", ArchiveExtractionTest::containerFile);
            check("a directory inside the archive retains ordinary merge decisions", ArchiveExtractionTest::memberDirectory);
            check("ordinary copying retains ordinary merge decisions", ArchiveExtractionTest::ordinaryCopy);
            check("the physical archive cannot be replaced by an extracted root", () -> protectSource(true));
            check("the physical archive cannot be replaced by an extracted file", () -> protectSource(false));
            check("compound and split archive names produce the expected container", ArchiveExtractionTest::names);
            if (!FAILURES.isEmpty()) {
                throw new AssertionError(FAILURES.size() + " / " + checks
                        + " extraction checks failed: " + FAILURES);
            }
            System.out.println("PASS: " + checks + " archive extraction destination checks");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void loadTypes() throws ReflectiveOperationException {
        jobType = Class.forName(PACKAGE + "filejob.FileJob");
        copyJobType = Class.forName(PACKAGE + "filejob.CopyFileJob");
        scanType = Class.forName(PACKAGE + "filejob.ScanInfo");
        transferType = Class.forName(PACKAGE + "filejob.TransferInfo");
        decisionsType = Class.forName(PACKAGE + "filejob.ActionAllInfo");
        functionType = Class.forName("kotlin.jvm.functions.Function1");
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Class<?> functions = Class.forName(PACKAGE + "filejob.FileJobsKt");
        copy = functions.getDeclaredMethod("copy", jobType, Path.class, Path.class,
                boolean.class, transferType, decisionsType, functionType);
        copy.setAccessible(true);
        getTargetFileName = functions.getDeclaredMethod("getTargetFileName", jobType, Path.class);
        getTargetFileName.setAccessible(true);
        archiveRoot = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt")
                .getMethod("createArchiveRootPath", Path.class);
    }

    private static void extract(int state) throws Throwable {
        try (Fixture fixture = new Fixture("contents-" + state)) {
            if (state != 0) require(fixture.directory.mkdir(), "Unable to create existing a/");
            File previous = new File(fixture.directory, "previous.txt");
            File extracted = new File(fixture.directory, "large.bin");
            if (state == 2) put(previous, EXISTING);
            if (state == 3) put(extracted, EXISTING);
            require(fixture.copy(fixture.root, fixture.target, true), "The extraction container was skipped");
            require(fixture.directory.isDirectory(), "The extraction container was not created");
            boolean copied = fixture.copy(fixture.root.resolve("large.bin"), fixture.path(extracted), true);
            require(copied == (state != 3), "Incorrect decision for the actual extracted file");
            require(Arrays.equals(state == 3 ? EXISTING : read(new File(fixtures, "source/large.bin")),
                    read(extracted)), "Extracted or pre-existing file bytes changed");
            if (state == 2) require(Arrays.equals(EXISTING, read(previous)), "Unrelated contents were changed");
            fixture.assertSourceIntact();
        }
    }

    private static void containerFile() throws Throwable {
        try (Fixture fixture = new Fixture("container-file")) {
            put(fixture.directory, EXISTING);
            require(!fixture.copy(fixture.root, fixture.target, true), "A regular file was treated as a container");
            require(Arrays.equals(EXISTING, read(fixture.directory)), "The conflicting container file was overwritten");
        }
    }

    private static void sourceParent() throws Throwable {
        try (Fixture fixture = new Fixture("source-parent")) {
            require(fixture.copy(fixture.root, fixture.path(fixture.base), true),
                    "Reusing the source parent was mistaken for overwriting the source archive");
            fixture.assertSourceIntact();
        }
    }

    private static void memberDirectory() throws Throwable {
        try (Fixture fixture = new Fixture("member-directory")) {
            require(fixture.copy(fixture.root, fixture.target, true), "The extraction container was skipped");
            File directory = new File(fixture.directory, "empty-directory");
            require(directory.mkdir(), "Unable to create existing member directory");
            require(!fixture.copy(fixture.root.resolve("empty-directory"), fixture.path(directory), true),
                    "The container exception leaked into an archived directory");
        }
    }

    private static void ordinaryCopy() throws Throwable {
        try (Fixture fixture = new Fixture("ordinary-copy")) {
            require(fixture.directory.mkdir(), "Unable to create existing destination");
            require(!fixture.copy(fixture.root, fixture.target, false),
                    "The extraction exception changed ordinary copy conflict handling");
        }
    }

    private static void protectSource(boolean root) throws Throwable {
        try (Fixture fixture = new Fixture("source-" + root)) {
            set(fixture.decisions, "replace", true);
            set(fixture.decisions, "skipCopyMoveOverItself", true);
            require(!fixture.copy(root ? fixture.root : fixture.root.resolve("large.bin"),
                    fixture.archive, true), "Extraction was allowed to replace its backing archive");
            fixture.assertSourceIntact();
        }
    }

    private static void names() throws Throwable {
        try (Fixture fixture = new Fixture("names")) {
            for (String[] names : new String[][]{{"a.zip", "a"}, {"backup.7z", "backup"},
                    {"backup.tar", "backup"}, {"backup.tar.gz", "backup"},
                    {"backup.tar.bz2", "backup"}, {"backup.tar.xz", "backup"},
                    {"backup.tar.gzip", "backup"}, {"backup.tar.bzip2", "backup"},
                    {"backup.tar.lzma", "backup"}, {"backup.tar.lz", "backup"},
                    {"backup.tar.zst", "backup"}, {"backup.TAR.ZST", "backup"},
                    {"backup.v1.tar.zst", "backup.v1"}, {"backup.tar.tar.gz", "backup.tar"},
                    {"backup.gz", "backup"}, {"backup.bz2", "backup"}, {"backup.xz", "backup"},
                    {"backup.zip.001", "backup"}, {"backup.7z.001", "backup"},
                    {"backup.tar.xz.001", "backup"}, {"backup.TAR.GZ", "backup"},
                    {"backup.v1.tar.zst.001", "backup.v1"},
                    {"backup.TAR.BZ2.001", "backup"}, {"backup.v2.zip", "backup.v2"},
                    {"a", "a"}, {".zip", ".zip"}}) {
                Path root = (Path) invoke(archiveRoot, null, fixture.path(new File(fixture.base, names[0])));
                require(names[1].equals(invoke(getTargetFileName, null, fixture.job, root).toString()),
                        "Unexpected extraction container for " + names[0]);
                root.getFileSystem().close();
            }
        }
    }

    private static final class Fixture implements Closeable {
        final File base;
        final File archiveFile;
        final File directory;
        final FileSystem physical;
        final Path archive;
        final Path root;
        final Path target;
        final Object job;
        final Object transfer;
        final Object decisions;

        Fixture(String name) throws Throwable {
            base = new File(work, name);
            require(base.mkdir(), "Test output already exists: " + base);
            physical = ArchiveVolumeTest.newPhysicalFileSystem(base);
            archiveFile = new File(base, "a.zip");
            put(archiveFile, read(new File(fixtures, "official-copy.7z")));
            archive = path(archiveFile);
            root = (Path) invoke(archiveRoot, null, archive);
            cacheEntries();
            job = copyJobType.getConstructor(List.class, Path.class, boolean.class)
                    .newInstance(Collections.singletonList(root), path(base), false);
            String nameInTarget = invoke(getTargetFileName, null, job, root).toString();
            require(nameInTarget.equals("a"), "a.zip did not select the a/ container");
            directory = new File(base, nameInTarget);
            target = path(directory);
            Object scan = construct(scanType);
            set(scan, "fileCount", 10);
            set(scan, "size", 16 * 1024 * 1024L);
            Constructor<?> constructor = transferType.getDeclaredConstructor(scanType, Path.class);
            constructor.setAccessible(true);
            transfer = constructor.newInstance(scan, path(base));
            set(transfer, "lastNotificationTimeMillis", System.currentTimeMillis() + 86400000L);
            decisions = construct(decisionsType);
            // Real conflicts must return false; accepting the synthetic root must return true.
            set(decisions, "skipMerge", true);
            set(decisions, "skipReplace", true);
        }

        private void cacheEntries() throws Throwable {
            Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
            invoke(paths.getMethod("archiveAddPassword", Path.class, String.class), null,
                    root, "测试-password-🔐");
            Class<?> readerType = Class.forName(PACKAGE + "provider.archive.archiver.SevenZArchiveReader");
            Object companion = readerType.getField("Companion").get(null);
            Method open = companion.getClass().getMethod("openOrNull", Path.class, List.class);
            Map<Path, Object> entries = new LinkedHashMap<>();
            try (Closeable reader = (Closeable) invoke(open, companion, archive,
                    Collections.singletonList("测试-password-🔐"))) {
                require(reader != null, "The official archive fixture was not recognized");
                for (Object entry : (List<?>) invoke(readerType.getMethod("readEntries"), reader)) {
                    String name = (String) invoke(entry.getClass().getMethod("getName"), entry);
                    entries.put(root.resolve(name), entry);
                }
            }
            Class<?> archiveReader = Class.forName(PACKAGE + "provider.archive.archiver.ArchiveReader");
            Method directoryEntry = archiveReader.getDeclaredMethod("createDirectoryEntry", String.class);
            directoryEntry.setAccessible(true);
            entries.put(root, invoke(directoryEntry, archiveReader.getField("INSTANCE").get(null), ""));
            set(root.getFileSystem(), "entries", entries);
            set(root.getFileSystem(), "isRefreshNeeded", false);
        }

        Path path(File file) { return physical.getPath(file.getAbsolutePath()); }

        boolean copy(Path source, Path destination, boolean extract) throws Throwable {
            List<Path> resolvedTargets = new ArrayList<>();
            Object resolved = Proxy.newProxyInstance(functionType.getClassLoader(),
                    new Class<?>[]{functionType}, (proxy, method, arguments) -> {
                        require(method.getName().equals("invoke"), "Unexpected target callback");
                        resolvedTargets.add((Path) arguments[0]);
                        return unit;
                    });
            boolean copied = (boolean) invoke(ArchiveExtractionTest.copy, null, job, source, destination,
                    extract, transfer, decisions, resolved);
            require(resolvedTargets.equals(copied ? Collections.singletonList(destination)
                    : Collections.emptyList()), "The resolved target was not passed to child extraction");
            return copied;
        }

        void assertSourceIntact() throws IOException {
            require(Arrays.equals(read(new File(fixtures, "official-copy.7z")), read(archiveFile)),
                    "Extraction changed its source archive");
        }

        @Override public void close() throws IOException {
            root.getFileSystem().close();
            physical.close();
        }
    }

    private static Object construct(Class<?> type) throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void put(File file, byte[] bytes) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static void check(String name, CheckedRunnable body) {
        ++checks;
        try {
            body.run();
            System.out.println("ok - " + name);
        } catch (Throwable failure) {
            FAILURES.add(name);
            failure.printStackTrace(System.err);
            System.out.println("FAIL - " + name);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedRunnable { void run() throws Throwable; }
}
