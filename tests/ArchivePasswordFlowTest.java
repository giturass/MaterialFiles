/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import java8.nio.channels.SeekableByteChannel;
import java8.nio.file.AccessMode;
import java8.nio.file.CopyOption;
import java8.nio.file.DirectoryStream;
import java8.nio.file.FileStore;
import java8.nio.file.FileSystem;
import java8.nio.file.LinkOption;
import java8.nio.file.NoSuchFileException;
import java8.nio.file.OpenOption;
import java8.nio.file.Path;
import java8.nio.file.PathMatcher;
import java8.nio.file.WatchService;
import java8.nio.file.attribute.BasicFileAttributes;
import java8.nio.file.attribute.FileAttribute;
import java8.nio.file.attribute.FileAttributeView;
import java8.nio.file.attribute.FileTime;
import java8.nio.file.attribute.UserPrincipalLookupService;
import java8.nio.file.spi.FileSystemProvider;

/**
 * Runs the APK's reader, archive filesystem, exception wrapper and password Intent on Android.
 * Only the physical file provider and resource strings are supplied by this harness. The archive
 * directory cache is populated with the real reader's metadata, avoiding application startup and
 * native libarchive in app_process. No Activity is launched or password dialog rendered.
 */
public final class ArchivePasswordFlowTest {
    private static final String PACKAGE = "me.zhanghai.android.files.";
    private static final String PASSWORD = "测试-password-🔐";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> fail(failure));
        try {
            require(Build.VERSION.SDK_INT >= 23, "The application requires Android API 23 or newer");
            Application application = new TestApplication();
            setField(Class.forName(PACKAGE + "app.AppProviderKt"), null,
                    "application", application);
            File fixtures = new File(arguments[0]);
            for (String kind : new String[]{"headers", "contents", "copy"}) {
                for (boolean wrong : new boolean[]{false, true}) {
                    String name = kind + ": " + (wrong ? "incorrect" : "missing")
                            + " password, dialog path and successful retry";
                    check(name, () -> verifyFlow(fixtures, kind, wrong, application));
                }
            }
            check("encrypted-header root password action names its archive",
                    () -> verifyRootAction(fixtures, application));
            for (String kind : new String[]{"headers", "contents", "copy"}) {
                check(kind + ": all selected streams share one batch in archive order",
                        () -> verifyBatch(fixtures, kind));
            }
            check("skipping conflicts keeps the remaining streams in one batch",
                    () -> verifyBatchSkip(fixtures));
            check("skipping the last conflict still waits for batch completion",
                    () -> verifyBatchSkipLast(fixtures));
            check("a worker close failure after the last entry EOF is reported",
                    () -> verifyBatchFinalFailure(fixtures));
            check("skipping an early-closed last entry does not wait for its consumer",
                    () -> verifyBatchSkipFailedLast(fixtures, false));
            check("skipping a failed last entry does not report the handled failure twice",
                    () -> verifyBatchSkipFailedLast(fixtures, true));
            check("an early-closed stream can be retried without losing later files",
                    () -> verifyBatchRetry(fixtures));
            for (boolean wrong : new boolean[]{false, true}) {
                check("batch " + (wrong ? "incorrect" : "missing") + " password and retry",
                        () -> verifyBatchPasswordRetry(fixtures, wrong));
            }
            check("batch cancellation joins the decoder and preserves interruption",
                    () -> verifyBatchCancellation(fixtures));
            check("another thread can read the same archive without consuming the batch",
                    () -> verifyBatchIsolation(fixtures));
            check("failed destination close removes the extracted file",
                    () -> verifyFailedCopyCleanup(fixtures, false));
            check("late encrypted COPY CRC failure removes the partial extracted file",
                    () -> verifyFailedCopyCleanup(fixtures, true));
            if (!FAILURES.isEmpty()) {
                throw new AssertionError(FAILURES.size() + " / " + checks
                        + " password-flow checks failed: " + FAILURES);
            }
            System.out.println("PASS: " + checks + " APK password-flow checks on Android API "
                    + Build.VERSION.SDK_INT + " (encrypted headers, contents, late COPY CRC, "
                    + "real filesystem/Intent, batch/skip/retry/cancel and output cleanup).");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void verifyFlow(File fixtures, String kind, boolean wrong,
            Application application) throws Throwable {
        FixtureFileSystem physical = new FixtureFileSystem(
                new File(fixtures, "official-" + kind + ".7z"));
        Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
        Path root = (Path) invoke(paths.getMethod("createArchiveRootPath", Path.class),
                null, physical.path);
        FileSystem archive = root.getFileSystem();
        try {
            // The first nonempty stream ensures the Copy case reaches a late CRC failure rather
            // than failing while seeking past an earlier solid entry with an incorrect key.
            Object entry = firstStreamEntry(physical.path);
            String name = (String) call(entry, "getName");
            Path file = root.resolve(name);
            byte[] expected = (boolean) call(entry, "isSymbolicLink")
                    ? ((String) call(entry, "getSymbolicLinkTarget"))
                            .getBytes(StandardCharsets.UTF_8)
                    : readAll(new FileInputStream(new File(new File(fixtures, "source"), name)));
            setField(archive.getClass(), archive, "entries", Collections.singletonMap(file, entry));
            setField(archive.getClass(), archive, "isRefreshNeeded", false);
            if (wrong) {
                invoke(paths.getMethod("archiveAddPassword", Path.class, String.class),
                        null, file, WRONG_PASSWORD);
            }

            Method open = archive.getClass().getMethod("newInputStream", Path.class);
            IOException rejected = null;
            boolean streamOpened = false;
            try (InputStream input = (InputStream) invoke(open, archive, file)) {
                streamOpened = true;
                drain(input);
            } catch (IOException failure) {
                rejected = failure;
            }
            require(rejected != null, "Encrypted extraction accepted an absent/incorrect password");
            if (wrong && kind.equals("copy")) {
                require(streamOpened, "Copy fixture did not exercise the lazy stream CRC failure");
            }
            Class<?> passwordError = Class.forName(
                    PACKAGE + "provider.archive.ArchivePasswordRequiredException");
            require(passwordError.isInstance(rejected), "Lost password-required exception: " + rejected);
            require((wrong ? "Incorrect passphrase" : "Passphrase required for this entry")
                    .equals(call(rejected, "getReason")), "Incorrect password error message");

            Class<?> continuationClass = Class.forName("kotlin.coroutines.Continuation");
            int[] resumed = {0};
            Object continuation = Proxy.newProxyInstance(continuationClass.getClassLoader(),
                    new Class<?>[]{continuationClass}, (proxy, method, arguments) -> {
                        if (method.getName().equals("resumeWith")) {
                            require(Boolean.TRUE.equals(arguments[0]), "Password retry was cancelled");
                            ++resumed[0];
                            return null;
                        }
                        throw new UnsupportedOperationException(method.toString());
                    });
            Object action = invoke(passwordError.getMethod("getUserAction", continuationClass,
                    Context.class), rejected, continuation, application);
            require(("[" + physical.file.getName() + "]").equals(call(action, "getMessage")),
                    "Password notification does not identify the archive");
            Intent intent = (Intent) call(action, "getIntent");
            Class<?> argsClass = Class.forName(
                    PACKAGE + "fileaction.ArchivePasswordDialogFragment$Args");
            Object args = intent.getParcelableExtra(argsClass.getName());
            require(args != null, "Password Intent has no dialog arguments");
            Path dialogPath = (Path) call(args, "getPath");
            // These are the actual operations performed by onCreateDialog() and onOk(). Before
            // the fix getArchiveFile() throws ProviderMismatchException for the physical path.
            Path archiveFile = (Path) invoke(paths.getMethod("getArchiveFile", Path.class),
                    null, dialogPath);
            require(archiveFile == physical.path && dialogPath.equals(file),
                    "Password dialog lost the archive entry path");
            invoke(paths.getMethod("archiveAddPassword", Path.class, String.class),
                    null, dialogPath, PASSWORD);
            Object listener = call(args, "getListener");
            invoke(Class.forName("kotlin.jvm.functions.Function1").getMethod("invoke", Object.class),
                    listener, Boolean.TRUE);
            require(resumed[0] == 1, "Password dialog did not resume extraction exactly once");

            // Retry through the same filesystem so the dialog must have saved the password on
            // the correct ArchivePath. A separate reader with a supplied key cannot satisfy this.
            byte[] actual = readAll((InputStream) invoke(open, archive, file));
            require(Arrays.equals(expected, actual), "Extraction retry returned different content");
            require(physical.openChannels == 0, "Extraction/password retry leaked its file channel");
        } finally {
            archive.close();
            physical.close();
        }
    }

    private static void verifyRootAction(File fixtures, Application application) throws Throwable {
        FixtureFileSystem physical = new FixtureFileSystem(new File(fixtures, "official-headers.7z"));
        Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
        Path root = (Path) invoke(paths.getMethod("createArchiveRootPath", Path.class),
                null, physical.path);
        try (FileSystem archive = root.getFileSystem()) {
            Object reader = openReader(physical.path, Collections.emptyList());
            IOException rejected = null;
            try (Closeable ownedReader = (Closeable) reader) {
                call(reader, "readEntries");
            } catch (IOException failure) {
                rejected = failure;
            }
            Class<?> rawError = Class.forName("me.zhanghai.android.libarchive.ArchiveException");
            require(rawError.isInstance(rejected),
                    "Encrypted-header error bypasses the filesystem's path translation");
            Object error = invoke(Class.forName(PACKAGE + "provider.archive.ArchiveExceptionExtensionsKt")
                    .getMethod("toFileSystemOrInterruptedIOException", rawError, Path.class),
                    null, rejected, root);
            Class<?> continuationClass = Class.forName("kotlin.coroutines.Continuation");
            Object continuation = Proxy.newProxyInstance(continuationClass.getClassLoader(),
                    new Class<?>[]{continuationClass}, (proxy, method, arguments) -> {
                        throw new AssertionError("Root action should not resume during construction");
                    });
            Object action = invoke(error.getClass().getMethod("getUserAction", continuationClass,
                    Context.class), error, continuation, application);
            require(("[" + physical.file.getName() + "]").equals(call(action, "getMessage")),
                    "Root password notification shows a null/entry filename");
            Intent intent = (Intent) call(action, "getIntent");
            Object args = intent.getParcelableExtra(
                    PACKAGE + "fileaction.ArchivePasswordDialogFragment$Args");
            Path dialogPath = (Path) call(args, "getPath");
            require(dialogPath.equals(root), "Encrypted-header prompt lost its archive root path");
            require(invoke(paths.getMethod("getArchiveFile", Path.class), null, dialogPath)
                    == physical.path, "Encrypted-header prompt identifies a different archive");
            require(physical.openChannels == 0, "Encrypted-header failure leaked its file channel");
        } finally {
            physical.close();
        }
    }

    private static Object openReader(Path physical, List<String> passwords) throws Throwable {
        Class<?> readerClass = Class.forName(PACKAGE + "provider.archive.archiver.SevenZArchiveReader");
        Object companion = readerClass.getField("Companion").get(null);
        Object reader = invoke(companion.getClass().getMethod("openOrNull", Path.class, List.class),
                companion, physical, passwords);
        require(reader != null, "Fixture is not recognized as 7z");
        return reader;
    }

    private static void verifyBatch(File fixtures, String kind) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, kind,
                Collections.singletonList(PASSWORD))) {
            Object run = null;
            for (Path file : fixture.files) {
                fixture.verify(file);
                Object current = getField(fixture.session, "run");
                require(current != null, "The provider bypassed the batch session");
                if (run == null) run = current;
                require(run == current, "A successful entry restarted the native extraction");
            }
            fixture.finish();
        }
    }

    private static void verifyBatchSkip(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            require(fixture.files.size() >= 4, "The fixture needs four regular entries");
            fixture.skip(fixture.files.get(0));
            fixture.verify(fixture.files.get(1));
            Object run = getField(fixture.session, "run");
            fixture.skip(fixture.files.get(2));
            for (int index = 3; index < fixture.files.size(); ++index) {
                fixture.verify(fixture.files.get(index));
                require(run == getField(fixture.session, "run"),
                        "Skipping a conflict restarted the batch");
            }
            fixture.finish();
        }
    }

    private static void verifyBatchSkipLast(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            for (int index = 0; index < fixture.files.size() - 1; ++index) {
                fixture.verify(fixture.files.get(index));
            }
            fixture.skip(fixture.files.get(fixture.files.size() - 1));
            fixture.finish();
        }
    }

    private static void verifyBatchFinalFailure(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            // This fails in the real worker after the native decoder has published every
            // per-entry success. Like a late native DataAfterEnd result, it cannot reach an
            // already completed entry pipe and must be checked when the batch finishes.
            fixture.physical.failInputClose = true;
            for (Path file : fixture.files) fixture.verify(file);
            IOException rejected = null;
            try {
                fixture.finish();
            } catch (IOException failure) {
                rejected = failure;
            }
            require(rejected != null
                            && "Injected archive close failure".equals(rejected.getMessage()),
                    "The completed entry streams hid the final worker failure: " + rejected);
            Thread worker = (Thread) getField(getField(fixture.session, "run"), "thread");
            require(!worker.isAlive(), "Batch completion left the decoder running");
            require(fixture.physical.openChannels == 0, "Final failure leaked the archive channel");
        }
    }

    private static void verifyBatchSkipFailedLast(File fixtures, boolean wrongPassword)
            throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "copy", Collections.singletonList(
                wrongPassword ? WRONG_PASSWORD : PASSWORD))) {
            Path lastNonempty = null;
            for (Path file : fixture.files) {
                if (fixture.expected(file).length > 0) lastNonempty = file;
            }
            require(lastNonempty != null, "The fixture needs a nonempty entry");
            IOException rejected = null;
            try (InputStream input = fixture.open(lastNonempty)) {
                if (wrongPassword) {
                    drain(input);
                } else {
                    require(input.read() >= 0, "The test needs a nonempty entry");
                }
            } catch (IOException failure) {
                rejected = failure;
            }
            if (wrongPassword) {
                require(Class.forName(PACKAGE + "provider.archive.ArchivePasswordRequiredException")
                                .isInstance(rejected), "The fixture did not fail its password check");
            } else {
                require(rejected == null, "A valid source stream failed before the early close");
            }
            Thread worker = (Thread) getField(getField(fixture.session, "run"), "thread");
            for (int index = fixture.files.indexOf(lastNonempty); index < fixture.files.size(); ++index) {
                fixture.skip(fixture.files.get(index));
            }
            fixture.finish();
            require(!worker.isAlive(), "Skipping the last unfinished stream leaked its decoder");
        }
    }

    private static void verifyBatchRetry(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            Path file = fixture.largeFile();
            Object previous;
            try (InputStream input = fixture.open(file)) {
                require(input.read() >= 0, "The test needs a nonempty entry");
                previous = getField(fixture.session, "run");
                // This is what closing the source does when opening or writing a destination
                // fails. The decoder may be blocked on the bounded queue at this point.
            }
            fixture.verify(file);
            Object retried = getField(fixture.session, "run");
            require(retried != previous, "Retry reused an already consumed entry stream");
            fixture.verifyRemaining(file, retried);
            fixture.finish();
        }
    }

    private static void verifyBatchPasswordRetry(File fixtures, boolean wrong) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "copy", wrong
                ? Collections.singletonList(WRONG_PASSWORD) : Collections.emptyList())) {
            Path file = fixture.firstNonemptyFile();
            IOException rejected = null;
            try (InputStream input = fixture.open(file)) {
                drain(input);
            } catch (IOException failure) {
                rejected = failure;
            }
            require(Class.forName(PACKAGE + "provider.archive.ArchivePasswordRequiredException")
                    .isInstance(rejected), "The batch lost its password-required exception");
            require((wrong ? "Incorrect passphrase" : "Passphrase required for this entry")
                    .equals(call(rejected, "getReason")), "Incorrect batch password error");
            Object previous = getField(fixture.session, "run");
            fixture.addPassword(PASSWORD);
            fixture.verify(file);
            Object retried = getField(fixture.session, "run");
            require(retried != previous, "The password retry did not reopen the native reader");
            fixture.verifyRemaining(file, retried);
            fixture.finish();
        }
    }

    private static void verifyBatchCancellation(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            try (InputStream input = fixture.open(fixture.largeFile())) {
                require(input.read() >= 0, "The test needs a nonempty entry");
                Thread worker = (Thread) getField(getField(fixture.session, "run"), "thread");
                Thread.currentThread().interrupt();
                try {
                    ((Closeable) fixture.session).close();
                    require(Thread.currentThread().isInterrupted(),
                            "Batch cleanup swallowed the job's interruption");
                    require(!worker.isAlive(), "The cancelled native decoder is still running");
                    require(fixture.physical.openChannels == 0,
                            "Cancellation leaked the archive's file channel");
                } finally {
                    Thread.interrupted();
                }
            }
        }
    }

    private static void verifyBatchIsolation(File fixtures) throws Throwable {
        try (BatchFixture fixture = new BatchFixture(fixtures, "contents",
                Collections.singletonList(PASSWORD))) {
            Path file = fixture.largeFile();
            try (InputStream input = fixture.open(file)) {
                int first = input.read();
                require(first >= 0, "The test needs a nonempty entry");
                Object batch = getField(fixture.session, "run");
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread reader = new Thread(() -> {
                    try {
                        fixture.verify(file);
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                }, "independent-archive-reader");
                reader.start();
                reader.join(10000);
                require(!reader.isAlive(), "Another reader deadlocked behind the batch session");
                if (failure.get() != null) throw failure.get();
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    bytes.write(first);
                    bytes.write(readAll(input));
                    require(Arrays.equals(fixture.expected(file), bytes.toByteArray()),
                            "An independent reader consumed or changed the batch stream");
                }
                require(batch == getField(fixture.session, "run"),
                        "An independent reader replaced the batch");
                fixture.verifyRemaining(file, batch);
                fixture.finish();
            }
        }
    }

    private static void verifyFailedCopyCleanup(File fixtures, boolean wrongPassword) throws Throwable {
        File target = File.createTempFile("7z-extract-failure-", ".tmp", fixtures.getParentFile());
        require(target.delete(), "Unable to prepare the extraction destination");
        try (BatchFixture fixture = new BatchFixture(fixtures, "copy", Collections.singletonList(
                wrongPassword ? WRONG_PASSWORD : PASSWORD))) {
            FixtureFileSystem output = new FixtureFileSystem(target);
            output.failOutputClose = !wrongPassword;
            Path file = fixture.firstNonemptyFile();
            IOException rejected = null;
            try {
                invoke(Class.forName(PACKAGE + "provider.common.PathExtensionsKt")
                        .getMethod("copyTo", Path.class, Path.class, CopyOption[].class),
                        null, file, output.path, new CopyOption[]{LinkOption.NOFOLLOW_LINKS});
            } catch (IOException failure) {
                rejected = failure;
            }
            require(rejected != null, "A failed extraction was reported as successful");
            if (wrongPassword) {
                require(Class.forName(PACKAGE + "provider.archive.ArchivePasswordRequiredException")
                        .isInstance(rejected), "Late CRC failure lost its password exception");
            } else {
                require("Injected output close failure".equals(rejected.getMessage()),
                        "The output close failure was hidden: " + rejected);
            }
            require(output.outputBytes > 0, "The failure test did not write any partial content");
            require(!target.exists(), "A failed extraction left a partial target behind");
        } finally {
            target.delete();
        }
    }

    private static final class BatchFixture implements Closeable {
        final File fixtures;
        final FixtureFileSystem physical;
        final FileSystem filesystem;
        final Path root;
        final Object session;
        final List<Path> files;
        final Map<Path, String> names = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        BatchFixture(File fixtures, String kind, List<String> passwords) throws Throwable {
            this.fixtures = fixtures;
            physical = new FixtureFileSystem(new File(fixtures, "official-" + kind + ".7z"));
            Class<?> paths = Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt");
            root = (Path) invoke(paths.getMethod("createArchiveRootPath", Path.class), null, physical.path);
            filesystem = root.getFileSystem();
            Map<Path, Object> entries = new LinkedHashMap<>();
            Object reader = openReader(physical.path, Collections.singletonList(PASSWORD));
            try (Closeable ownedReader = (Closeable) reader) {
                for (Object entry : (List<?>) call(reader, "readEntries")) {
                    if ((boolean) call(entry, "isDirectory") || (boolean) call(entry, "isSymbolicLink")) {
                        continue;
                    }
                    String name = (String) call(entry, "getName");
                    Path path = root.resolve(name);
                    entries.put(path, entry);
                    names.put(path, name);
                }
            }
            require(entries.size() >= 4, "The fixture needs multiple regular entries");
            setField(filesystem.getClass(), filesystem, "entries", entries);
            setField(filesystem.getClass(), filesystem, "isRefreshNeeded", false);
            for (String password : passwords) addPassword(password);
            List<Path> selected = new ArrayList<>(entries.keySet());
            Collections.reverse(selected);
            session = invoke(filesystem.getClass().getMethod("newSevenZExtractionSession", List.class),
                    filesystem, selected);
            require(session != null, "The filesystem did not create a 7z batch session");
            files = (List<Path>) call(session, "getFiles");
            require(files.equals(new ArrayList<>(entries.keySet())),
                    "The batch did not restore archive order for out-of-order selections");
        }

        void addPassword(String password) throws Throwable {
            invoke(Class.forName(PACKAGE + "provider.archive.PathArchiveExtensionsKt")
                    .getMethod("archiveAddPassword", Path.class, String.class), null, root, password);
        }

        InputStream open(Path file) throws Throwable {
            return (InputStream) invoke(filesystem.getClass().getMethod("newInputStream", Path.class),
                    filesystem, file);
        }

        byte[] expected(Path file) throws IOException {
            return readAll(new FileInputStream(new File(new File(fixtures, "source"), names.get(file))));
        }

        void verify(Path file) throws Throwable {
            require(Arrays.equals(expected(file), readAll(open(file))),
                    "Unexpected extracted content: " + file);
        }

        void skip(Path file) throws Throwable {
            invoke(session.getClass().getMethod("skip", Path.class), session, file);
        }

        void finish() throws Throwable {
            call(session, "finish");
        }

        Path firstNonemptyFile() {
            for (Path file : files) {
                if (new File(new File(fixtures, "source"), names.get(file)).length() > 0) return file;
            }
            throw new AssertionError("The fixture has no nonempty entry");
        }

        Path largeFile() {
            for (Path file : files) {
                if (names.get(file).equals("large.bin")) return file;
            }
            throw new AssertionError("The fixture has no large entry");
        }

        void verifyRemaining(Path file, Object run) throws Throwable {
            for (int index = files.indexOf(file) + 1; index < files.size(); ++index) {
                verify(files.get(index));
                require(run == getField(session, "run"), "A later entry restarted extraction");
            }
        }

        @Override public void close() throws IOException {
            try {
                ((Closeable) session).close();
            } finally {
                filesystem.close();
                physical.close();
            }
            require(physical.openChannels == 0, "The batch leaked an archive file channel");
        }
    }

    private static Object firstStreamEntry(Path physical) throws Throwable {
        Object reader = openReader(physical, Collections.singletonList(PASSWORD));
        try (Closeable ownedReader = (Closeable) reader) {
            for (Object entry : (List<?>) call(reader, "readEntries")) {
                if ((long) call(entry, "getSize") > 1 && !(boolean) call(entry, "isDirectory")) {
                    return entry;
                }
            }
        }
        throw new AssertionError("Fixture has no nonempty stream");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream owned = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = owned.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) >= 0) {}
    }

    private static Object call(Object target, String name) throws Throwable {
        return invoke(target.getClass().getMethod(name), target);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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

    private static void fail(Throwable failure) {
        failure.printStackTrace(System.err);
        System.exit(1);
    }

    private interface CheckedRunnable {
        void run() throws Throwable;
    }

    private static final class TestApplication extends Application {
        private final Resources resources = new Resources(Resources.getSystem().getAssets(),
                Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration()) {
            @Override public String getString(int id) { return "Archive password"; }
            @Override public String getString(int id, Object... arguments) {
                return Arrays.toString(arguments);
            }
        };

        @Override public String getPackageName() { return "me.zhanghai.android.files"; }
        @Override public Resources getResources() { return resources; }
    }

    /** A physical provider shared by the APK's native reader and extraction-failure tests. */
    private static final class FixtureFileSystem extends FileSystem {
        final File file;
        final FixtureProvider provider = new FixtureProvider(this);
        final Path path;
        int openChannels;
        boolean failInputClose;
        boolean failOutputClose;
        long outputBytes;

        FixtureFileSystem(File file) {
            this.file = file.getAbsoluteFile();
            path = path(this.file.toString());
        }

        private Path path(String text) {
            return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getFileSystem": return this;
                            case "toString": return text;
                            case "toUri": return new File(text).toURI();
                            case "getFileName": return path(new File(text).getName());
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == arguments[0];
                            default: throw new UnsupportedOperationException(method.toString());
                        }
                    });
        }

        @Override public FileSystemProvider provider() { return provider; }
        @Override public void close() {}
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String getSeparator() { return "/"; }
        @Override public Iterable<Path> getRootDirectories() { throw unsupported(); }
        @Override public Iterable<FileStore> getFileStores() { throw unsupported(); }
        @Override public Set<String> supportedFileAttributeViews() { return Collections.emptySet(); }
        @Override public Path getPath(String first, String... more) { throw unsupported(); }
        @Override public PathMatcher getPathMatcher(String pattern) { throw unsupported(); }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw unsupported(); }
        @Override public WatchService newWatchService() { throw unsupported(); }
    }

    private static final class FixtureProvider extends FileSystemProvider {
        private final FixtureFileSystem filesystem;

        FixtureProvider(FixtureFileSystem filesystem) { this.filesystem = filesystem; }

        @Override public SeekableByteChannel newByteChannel(Path path,
                Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
            require(path == filesystem.path, "Unexpected provider path: " + path);
            RandomAccessFile file = new RandomAccessFile(filesystem.file, "r");
            synchronized (filesystem) { ++filesystem.openChannels; }
            return new SeekableByteChannel() {
                final FileChannel channel = file.getChannel();
                boolean open = true;
                @Override public int read(ByteBuffer buffer) throws IOException { return channel.read(buffer); }
                @Override public int write(ByteBuffer buffer) { throw unsupported(); }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public SeekableByteChannel position(long position) throws IOException {
                    channel.position(position);
                    return this;
                }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public SeekableByteChannel truncate(long size) { throw unsupported(); }
                @Override public boolean isOpen() { return open; }
                @Override public void close() throws IOException {
                    if (open) {
                        open = false;
                        synchronized (filesystem) { --filesystem.openChannels; }
                        file.close();
                        if (filesystem.failInputClose) {
                            throw new IOException("Injected archive close failure");
                        }
                    }
                }
            };
        }

        @Override public OutputStream newOutputStream(Path path, OpenOption... options)
                throws IOException {
            require(path == filesystem.path, "Unexpected output provider path: " + path);
            FileOutputStream output = new FileOutputStream(filesystem.file);
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    output.write(value);
                    ++filesystem.outputBytes;
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    output.write(bytes, offset, length);
                    filesystem.outputBytes += length;
                }
                @Override public void close() throws IOException {
                    output.close();
                    if (filesystem.failOutputClose) throw new IOException("Injected output close failure");
                }
            };
        }

        @Override public String getScheme() { return "fixture"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw unsupported(); }
        @Override public FileSystem getFileSystem(URI uri) { return filesystem; }
        @Override public Path getPath(URI uri) { throw unsupported(); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path directory,
                DirectoryStream.Filter<? super Path> filter) { throw unsupported(); }
        @Override public void createDirectory(Path path, FileAttribute<?>... attributes) { throw unsupported(); }
        @Override public void delete(Path path) throws IOException {
            require(path == filesystem.path, "Unexpected deleted provider path: " + path);
            if (!filesystem.file.delete()) throw new NoSuchFileException(path.toString());
        }
        @Override public void copy(Path source, Path target, CopyOption... options) { throw unsupported(); }
        @Override public void move(Path source, Path target, CopyOption... options) { throw unsupported(); }
        @Override public boolean isSameFile(Path first, Path second) { return first == second; }
        @Override public boolean isHidden(Path path) { return false; }
        @Override public FileStore getFileStore(Path path) { throw unsupported(); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
            if (!filesystem.file.exists()) throw new NoSuchFileException(path.toString());
        }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                LinkOption... options) { throw unsupported(); }
        @SuppressWarnings("unchecked")
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                LinkOption... options) throws IOException {
            require(path == filesystem.path, "Unexpected attribute provider path: " + path);
            File file = filesystem.file;
            if (!file.exists()) throw new NoSuchFileException(path.toString());
            long size = file.length();
            FileTime modified = FileTime.fromMillis(file.lastModified());
            boolean directory = file.isDirectory();
            return (A) new BasicFileAttributes() {
                @Override public FileTime lastModifiedTime() { return modified; }
                @Override public FileTime lastAccessTime() { return modified; }
                @Override public FileTime creationTime() { return modified; }
                @Override public boolean isRegularFile() { return !directory; }
                @Override public boolean isDirectory() { return directory; }
                @Override public boolean isSymbolicLink() { return false; }
                @Override public boolean isOther() { return false; }
                @Override public long size() { return size; }
                @Override public Object fileKey() { return file.getAbsolutePath(); }
            };
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes,
                LinkOption... options) { throw unsupported(); }
        @Override public void setAttribute(Path path, String attribute, Object value,
                LinkOption... options) { throw unsupported(); }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Outside the injected physical-provider boundary");
    }
}
