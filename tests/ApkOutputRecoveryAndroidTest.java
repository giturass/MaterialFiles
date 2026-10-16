/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Runs the APK's actual OwnedOutput recovery implementation against real Android file/inode APIs.
 * Reflection accesses the private helper without exposing a test hook in the production app.
 * No recovery logic, Android API, storage, or cryptographic operation is replaced by a mock.
 */
public final class ApkOutputRecoveryAndroidTest {
    private static final String TYPE = "me.zhanghai.android.files.apk.ApkOperations$OwnedOutput";
    private static final List<String> FAILURES = new ArrayList<>();
    private static final int SOURCE_SIZE = 512 * 1024 + 53;
    private static final int PREFIX_SIZE = 192 * 1024 + 7;
    private static Constructor<?> constructor;
    private static Method matchesIdentity;
    private static Method removeIfOwned;
    private static Method toJson;
    private static File work;
    private static int checks;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> fail(failure));
        try {
            run(arguments);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void run(String[] arguments) throws Exception {
        require(arguments.length == 1, "Pass a private work directory");
        require("Dalvik".equals(System.getProperty("java.vm.name")), "This test requires Android ART");
        work = new File(arguments[0], "恢复输出 中文 " + UUID.randomUUID()).getCanonicalFile();
        mkdir(work);
        Class<?> type = Class.forName(TYPE);
        constructor = type.getDeclaredConstructor(File.class, long.class, long.class,
                long.class, String.class, File.class);
        constructor.setAccessible(true);
        matchesIdentity = method(type, "matchesIdentity");
        removeIfOwned = method(type, "removeIfOwned");
        toJson = method(type, "toJson");

        check("remove an unchanged partial output after verifying its source and prefix", () -> {
            Fixture fixture = fixture("partial", PREFIX_SIZE);
            require(identity(fixture.owned), "New output's inode was not recognized");
            byte[] sourceBefore = digest(fixture.source);
            require(remove(fixture.owned), "Verified owned prefix was not removable");
            require(!fixture.output.exists(), "Partial APK remains after recovery");
            require(Arrays.equals(sourceBefore, digest(fixture.source)), "Recovery changed the source APK");
        });

        check("remove an owned empty output only when the verified source is available", () -> {
            Fixture fixture = fixture("empty", 0);
            require(remove(fixture.owned) && !fixture.output.exists(), "Owned empty APK was not removed");
        });

        check("preserve an output edited in place without changing its inode or length", () -> {
            Fixture fixture = fixture("edited", PREFIX_SIZE);
            StructStat before = Os.lstat(fixture.output.getPath());
            try (RandomAccessFile file = new RandomAccessFile(fixture.output, "rw")) {
                file.seek(70003);
                int value = file.read();
                file.seek(70003);
                file.write(value ^ 0x40);
            }
            StructStat after = Os.lstat(fixture.output.getPath());
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino
                    && before.st_size == after.st_size, "Fixture did not edit the same inode");
            byte[] edited = digest(fixture.output);
            require(identity(fixture.owned), "In-place fixture should retain the old inode identity");
            require(!remove(fixture.owned), "Recovery treated user-edited bytes as its own prefix");
            require(Arrays.equals(edited, digest(fixture.output)), "Recovery deleted or changed user-edited output");
        });

        check("preserve another inode replacing the target path", () -> {
            Fixture fixture = fixture("replacement", PREFIX_SIZE);
            File moved = new File(fixture.directory, "old inode.apk");
            Os.rename(fixture.output.getPath(), moved.getPath());
            // Keep the old inode allocated so a filesystem cannot immediately reuse its number.
            writeNew(fixture.output, new byte[]{9, 8, 7, 6, 5});
            byte[] replacement = digest(fixture.output);
            byte[] original = digest(moved);
            require(!identity(fixture.owned), "Replacement unexpectedly has the original inode");
            require(remove(fixture.owned), "A replaced path should retire its obsolete journal");
            require(Arrays.equals(replacement, digest(fixture.output)), "Recovery removed the replacement file");
            require(Arrays.equals(original, digest(moved)), "Recovery followed the original inode to another name");
        });

        check("preserve partial output when its verified source was removed", () -> {
            Fixture fixture = fixture("missing-source", PREFIX_SIZE);
            byte[] before = digest(fixture.output);
            require(fixture.source.delete(), "Cannot remove fixture source");
            require(!remove(fixture.owned), "Missing source was treated as proof of ownership");
            require(Arrays.equals(before, digest(fixture.output)), "Missing-source recovery removed output");
        });

        check("preserve output when the source has changed but retains the same size", () -> {
            Fixture fixture = fixture("changed-source", PREFIX_SIZE);
            long originalSize = fixture.source.length();
            try (RandomAccessFile file = new RandomAccessFile(fixture.source, "rw")) {
                // Change bytes beyond the copied prefix to ensure the full source hash is checked.
                file.seek(originalSize - 17);
                int value = file.read();
                file.seek(originalSize - 17);
                file.write(value ^ 0x20);
            }
            require(fixture.source.length() == originalSize, "Source size unexpectedly changed");
            byte[] before = digest(fixture.output);
            require(!remove(fixture.owned), "Recovery accepted a source with a different SHA-256");
            require(Arrays.equals(before, digest(fixture.output)), "Modified-source recovery removed output");
        });

        check("preserve output when the source length no longer matches the journal", () -> {
            Fixture fixture = fixture("short-source", PREFIX_SIZE);
            try (RandomAccessFile file = new RandomAccessFile(fixture.source, "rw")) {
                file.setLength(SOURCE_SIZE - 1);
            }
            byte[] before = digest(fixture.output);
            require(!remove(fixture.owned), "Recovery accepted a source with a different length");
            require(Arrays.equals(before, digest(fixture.output)), "Changed source length caused output deletion");
        });

        check("preserve legacy journal output without a verified source reference", () -> {
            Fixture fixture = fixture("no-source", PREFIX_SIZE);
            Object owned = owned(fixture.output, SOURCE_SIZE, digestHex(fixture.source), null);
            byte[] before = digest(fixture.output);
            require(!remove(owned), "A missing source reference was treated as trusted");
            require(Arrays.equals(before, digest(fixture.output)), "Legacy journal removed an unproven output");
        });

        check("preserve target symlinks and the file they reference", () -> {
            Fixture fixture = fixture("output-symlink", PREFIX_SIZE);
            File moved = new File(fixture.directory, "original partial.apk");
            Os.rename(fixture.output.getPath(), moved.getPath());
            File referenced = new File(fixture.directory, "unrelated.apk");
            writeNew(referenced, new byte[]{37, 38, 39, 40});
            byte[] before = digest(referenced);
            Os.symlink(referenced.getPath(), fixture.output.getPath());
            require(!identity(fixture.owned), "Symlink matched the original regular-file identity");
            require(remove(fixture.owned), "Symlink replacement should retire the obsolete journal");
            require(OsConstants.S_ISLNK(Os.lstat(fixture.output.getPath()).st_mode), "Recovery removed the target symlink");
            require(Arrays.equals(before, digest(referenced)), "Recovery followed a target symlink");
            require(moved.isFile(), "Recovery deleted the moved partial file");
        });

        check("preserve output when its source path was replaced by a symlink", () -> {
            Fixture fixture = fixture("source-symlink", PREFIX_SIZE);
            File moved = new File(fixture.directory, "real source.apk");
            Os.rename(fixture.source.getPath(), moved.getPath());
            Os.symlink(moved.getPath(), fixture.source.getPath());
            byte[] before = digest(fixture.output);
            require(!remove(fixture.owned), "Recovery trusted a symlink in the source path");
            require(Arrays.equals(before, digest(fixture.output)), "Source symlink caused output deletion");
            require(OsConstants.S_ISLNK(Os.lstat(fixture.source.getPath()).st_mode), "Source symlink was modified");
        });

        check("preserve output larger than the verified source", () -> {
            Fixture fixture = fixture("oversized-output", SOURCE_SIZE);
            try (FileOutputStream output = new FileOutputStream(fixture.output, true)) {
                output.write(7);
            }
            byte[] before = digest(fixture.output);
            require(!remove(fixture.owned), "Recovery accepted bytes beyond the verified source");
            require(Arrays.equals(before, digest(fixture.output)), "Recovery removed an enlarged output");
        });

        check("interrupted recovery preserves output and can safely retry after cancellation", () -> {
            Fixture fixture = fixture("cancel", PREFIX_SIZE);
            byte[] before = digest(fixture.output);
            Thread.currentThread().interrupt();
            try {
                require(!remove(fixture.owned), "Interrupted recovery reported deletion");
                require(Thread.currentThread().isInterrupted(), "Recovery swallowed the interruption flag");
            } finally {
                Thread.interrupted();
            }
            require(Arrays.equals(before, digest(fixture.output)), "Cancellation removed or modified output");
            require(remove(fixture.owned) && !fixture.output.exists(), "Recovery could not retry an unchanged owned prefix");
        });

        check("journal serialization records the verified source and original file identity", () -> {
            Fixture fixture = fixture("journal", PREFIX_SIZE);
            byte[] before = digest(fixture.output);
            JSONObject journal = new JSONObject((String) invoke(toJson, fixture.owned));
            StructStat stat = Os.lstat(fixture.output.getPath());
            require(journal.getString("path").equals(fixture.output.getPath())
                    && journal.getString("source").equals(fixture.source.getPath())
                    && journal.getLong("device") == stat.st_dev && journal.getLong("inode") == stat.st_ino
                    && journal.getLong("size") == SOURCE_SIZE
                    && journal.getString("sha256").equals(digestHex(fixture.source)),
                    "Serialized output ownership does not match the filesystem snapshot");
            require(Arrays.equals(before, digest(fixture.output)), "Serializing a journal changed its output");
        });

        System.out.println("ART output recovery: " + (checks - FAILURES.size()) + "/" + checks + " checks passed");
        System.out.println("Scope: real packaged OwnedOutput recovery and Android filesystem identity; no forced process death or storage namespace race.");
        if (!FAILURES.isEmpty()) throw new AssertionError("Failed output recovery checks: " + FAILURES);
    }

    private static Fixture fixture(String name, int prefixSize) throws Exception {
        File directory = new File(work, name).getCanonicalFile();
        mkdir(directory);
        byte[] sourceBytes = new byte[SOURCE_SIZE];
        new Random(893475L).nextBytes(sourceBytes);
        File source = new File(directory, "verified.apk");
        File output = new File(directory, "partial output.apk");
        writeNew(source, sourceBytes);
        writeNew(output, Arrays.copyOf(sourceBytes, prefixSize));
        Object owned = owned(output, sourceBytes.length, digestHex(source), source);
        return new Fixture(directory, source, output, owned);
    }

    private static Object owned(File output, long expectedSize, String sha256, File source) throws Exception {
        StructStat stat = Os.lstat(output.getPath());
        try {
            return constructor.newInstance(output, stat.st_dev, stat.st_ino, expectedSize, sha256, source);
        } catch (InvocationTargetException exception) {
            return rethrow(exception.getCause());
        }
    }

    private static boolean identity(Object owned) throws Exception { return (Boolean) invoke(matchesIdentity, owned); }
    private static boolean remove(Object owned) throws Exception { return (Boolean) invoke(removeIfOwned, owned); }

    private static Method method(Class<?> type, String name) throws Exception {
        Method result = type.getDeclaredMethod(name);
        result.setAccessible(true);
        return result;
    }

    private static Object invoke(Method method, Object target) throws Exception {
        try { return method.invoke(target); }
        catch (InvocationTargetException exception) { return rethrow(exception.getCause()); }
    }

    private static Object rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static String digestHex(File file) throws Exception {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest(file)) result.append(hex[(value >>> 4) & 15]).append(hex[value & 15]);
        return result.toString();
    }

    private static void writeNew(File file, byte[] bytes) throws IOException {
        require(file.createNewFile(), "Test attempted to overwrite an existing fixture");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void mkdir(File directory) { require(directory.isDirectory() || directory.mkdirs(), "Cannot create private test directory"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void check(String name, CheckedRunnable runnable) {
        ++checks;
        try {
            runnable.run();
            System.out.println("PASS " + checks + " " + name);
        } catch (Throwable failure) {
            FAILURES.add(name);
            System.out.println("FAIL " + checks + " " + name);
            failure.printStackTrace(System.out);
        } finally {
            Thread.interrupted();
        }
    }

    private static void fail(Throwable failure) {
        failure.printStackTrace(System.err);
        System.exit(1);
    }

    private static final class Fixture {
        final File directory;
        final File source;
        final File output;
        final Object owned;
        Fixture(File directory, File source, File output, Object owned) {
            this.directory = directory;
            this.source = source;
            this.output = output;
            this.owned = owned;
        }
    }

    private interface CheckedRunnable { void run() throws Exception; }
}
