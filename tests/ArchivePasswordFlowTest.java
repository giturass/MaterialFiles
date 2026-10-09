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
import java.io.IOException;
import java.io.InputStream;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import java8.nio.channels.SeekableByteChannel;
import java8.nio.file.AccessMode;
import java8.nio.file.CopyOption;
import java8.nio.file.DirectoryStream;
import java8.nio.file.FileStore;
import java8.nio.file.FileSystem;
import java8.nio.file.LinkOption;
import java8.nio.file.OpenOption;
import java8.nio.file.Path;
import java8.nio.file.PathMatcher;
import java8.nio.file.WatchService;
import java8.nio.file.attribute.BasicFileAttributes;
import java8.nio.file.attribute.FileAttribute;
import java8.nio.file.attribute.FileAttributeView;
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
            require(Build.VERSION.SDK_INT >= 24, "Encrypted 7z requires Android API 24 or newer");
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
            if (!FAILURES.isEmpty()) {
                throw new AssertionError(FAILURES.size() + " / " + checks
                        + " password-flow checks failed: " + FAILURES);
            }
            System.out.println("PASS: " + checks + " APK password-flow checks on Android API "
                    + Build.VERSION.SDK_INT + " (encrypted headers, contents, late COPY CRC, "
                    + "real filesystem/Intent, password storage and retry).");
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

    /** The APK's CommonsSeekableByteChannel adapts this java8 provider to its desugared NIO API. */
    private static final class FixtureFileSystem extends FileSystem {
        final File file;
        final FixtureProvider provider = new FixtureProvider(this);
        final Path path;
        int openChannels;

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
            ++filesystem.openChannels;
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
                        --filesystem.openChannels;
                        file.close();
                    }
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
        @Override public void delete(Path path) { throw unsupported(); }
        @Override public void copy(Path source, Path target, CopyOption... options) { throw unsupported(); }
        @Override public void move(Path source, Path target, CopyOption... options) { throw unsupported(); }
        @Override public boolean isSameFile(Path first, Path second) { return first == second; }
        @Override public boolean isHidden(Path path) { return false; }
        @Override public FileStore getFileStore(Path path) { throw unsupported(); }
        @Override public void checkAccess(Path path, AccessMode... modes) { throw unsupported(); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                LinkOption... options) { throw unsupported(); }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                LinkOption... options) { throw unsupported(); }
        @Override public Map<String, Object> readAttributes(Path path, String attributes,
                LinkOption... options) { throw unsupported(); }
        @Override public void setAttribute(Path path, String attribute, Object value,
                LinkOption... options) { throw unsupported(); }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Outside the injected physical-provider boundary");
    }
}
