/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java8.nio.channels.SeekableByteChannel;
import java8.nio.file.AccessMode;
import java8.nio.file.CopyOption;
import java8.nio.file.DirectoryStream;
import java8.nio.file.DirectoryNotEmptyException;
import java8.nio.file.FileAlreadyExistsException;
import java8.nio.file.FileStore;
import java8.nio.file.FileSystem;
import java8.nio.file.LinkOption;
import java8.nio.file.NoSuchFileException;
import java8.nio.file.OpenOption;
import java8.nio.file.Path;
import java8.nio.file.PathMatcher;
import java8.nio.file.StandardOpenOption;
import java8.nio.file.WatchService;
import java8.nio.file.attribute.BasicFileAttributes;
import java8.nio.file.attribute.FileAttribute;
import java8.nio.file.attribute.FileAttributeView;
import java8.nio.file.attribute.FileTime;
import java8.nio.file.attribute.UserPrincipalLookupService;
import java8.nio.file.spi.FileSystemProvider;

/** Runs the APK's split-volume and output-transaction code, without loading any native library. */
public final class ArchiveVolumeTest {
    private static final String PACKAGE = "me.zhanghai.android.files.provider.archive.archiver.";
    private static final byte[] SOURCE = "An original source must never participate in output cleanup."
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD_VOLUME = "Existing volume: preserve these bytes."
            .getBytes(StandardCharsets.UTF_8);
    private static final List<String> FAILURES = new ArrayList<>();
    private static File work;
    private static Class<?> outputType;
    private static Class<?> extensions;
    private static int checks;

    /** Reusable physical provider for APK ArchiveWriter tests, with no application/native setup. */
    public static FileSystem newPhysicalFileSystem(File directory) throws IOException {
        return new Fixture(directory);
    }

    public static void main(String[] arguments) {
        try {
            require(arguments.length == 1, "Pass a directory for generated volume-test files");
            work = new File(arguments[0]).getCanonicalFile();
            require(work.isDirectory() || work.mkdirs(), "Unable to create test directory: " + work);
            outputType = Class.forName(PACKAGE + "ArchiveOutput");
            extensions = Class.forName(PACKAGE + "SplitArchiveKt");
            for (int megabytes : new int[]{5, 10, 50}) {
                long size = megabytes * 1024L * 1024;
                check(megabytes + " MiB split boundary", () -> roundTrip(size, size + 37, false));
            }
            check("custom tiny volumes and random seeks", () -> roundTrip(7, 103, false));
            check("ordinary unsplit output", () -> roundTrip(0, 4099, false));
            check("exact boundary creates no empty trailing volume", ArchiveVolumeTest::exactBoundary);
            check("empty split and unsplit outputs", ArchiveVolumeTest::emptyOutputs);
            for (long splitSize : new long[]{0, 17}) {
                check("existing first output is retained, split=" + splitSize,
                        () -> existingFirst(splitSize));
                check("uncommitted output is rolled back, split=" + splitSize,
                        () -> uncommitted(splitSize));
                for (boolean streams : new boolean[]{false, true}) {
                    check("failed final close rolls back, split=" + splitSize + ", streams=" + streams,
                            () -> failedClose(splitSize, streams));
                }
            }
            check("existing later volume is retained while new volumes roll back",
                    ArchiveVolumeTest::existingLater);
            check("a boundary-close failure rolls back the completed first volume",
                    ArchiveVolumeTest::failedBoundaryClose);
            check("short provider reads and writes preserve every byte",
                    () -> roundTrip(11, 997, true));
            check("zero-progress provider writes fail and roll back", ArchiveVolumeTest::zeroWrite);
            check("cancelled writes roll back and retain the interrupt", () -> cancelled(false));
            check("cancelled commit rolls back and retains the interrupt", () -> cancelled(true));
            check("both split readers honor cancellation", ArchiveVolumeTest::cancelledReads);
            check("stream-only providers support split output and sequential input",
                    ArchiveVolumeTest::streamOnly);
            for (boolean stream : new boolean[]{false, true}) {
                check("a truncated volume fails after size discovery, stream=" + stream,
                        () -> changedVolume(stream, true));
                check("a growing volume cannot spill into the next volume, stream=" + stream,
                        () -> changedVolume(stream, false));
            }
            if (!FAILURES.isEmpty()) {
                throw new AssertionError(FAILURES.size() + " / " + checks + " volume checks failed: " + FAILURES);
            }
            System.out.println("PASS: " + checks + " archive volume checks");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void roundTrip(long splitSize, long length, boolean shortOperations) throws Throwable {
        try (Fixture fixture = new Fixture("round-trip")) {
            if (shortOperations) {
                fixture.maxWrite = 3;
                fixture.maxRead = 5;
            }
            try (ArchiveOutput output = fixture.output(splitSize)) {
                writePattern(output.channel, length);
                require(output.channel.position() == length, "Incorrect output position");
                require(output.channel.size() == length, "Incorrect output size");
                output.commit();
            }
            fixture.assertVolumeSizes(splitSize, length);
            Path first = fixture.first(splitSize);
            try (SeekableByteChannel input = openChannel(first)) {
                require(input.size() == length, "The joined channel has an incorrect size");
                long boundary = splitSize > 0 ? splitSize : length / 2;
                for (long position : new long[]{0, 1, boundary - 1, boundary, boundary + 1,
                        length / 2, length - 1, 0, length, length + 3}) {
                    checkRange(input, Math.max(0, position), length);
                }
                int state = 1729;
                for (int index = 0; index < 40; ++index) {
                    state = state * 1103515245 + 12345;
                    checkRange(input, (state & 0x7fffffffL) % length, length);
                }
            }
            try (InputStream input = openStream(first)) {
                checkStream(input, length);
            }
        }
    }

    private static void exactBoundary() throws Throwable {
        try (Fixture fixture = new Fixture("exact-boundary")) {
            try (ArchiveOutput output = fixture.output(31)) {
                writePattern(output.channel, 62);
                require(output.channel.write(ByteBuffer.allocate(0)) == 0,
                        "An empty write should return zero");
                output.commit();
            }
            fixture.assertVolumeSizes(31, 62);
            require(!fixture.file("archive.7z.003").exists(), "Created an unnecessary empty volume");
        }
    }

    private static void emptyOutputs() throws Throwable {
        for (long splitSize : new long[]{0, 17}) {
            try (Fixture fixture = new Fixture("empty-output")) {
                ArchiveOutput output = fixture.output(splitSize);
                output.commit();
                output.close();
                output.close();
                fixture.assertVolumeSizes(splitSize, 0);
                try (SeekableByteChannel input = openChannel(fixture.first(splitSize))) {
                    require(input.size() == 0, "Empty archive has nonzero size");
                    require(input.read(ByteBuffer.allocate(1)) == -1, "Empty archive has data");
                    require(input.read(ByteBuffer.allocate(0)) == 0, "Empty channel read is not zero");
                }
                try (InputStream input = openStream(fixture.first(splitSize))) {
                    require(input.read(new byte[0]) == 0, "Empty stream read is not zero");
                    require(input.read() == -1, "Empty volume stream has data");
                }
            }
        }
    }

    private static void existingFirst(long splitSize) throws Throwable {
        try (Fixture fixture = new Fixture("existing-first")) {
            File first = fixture.disk(fixture.first(splitSize));
            put(first, OLD_VOLUME);
            expect(FileAlreadyExistsException.class, () -> fixture.output(splitSize));
            require(Arrays.equals(OLD_VOLUME, readBytes(first)), "An existing output was overwritten");
            require(fixture.deleted.isEmpty(), "Failed CREATE_NEW deleted an existing file");
        }
    }

    private static void existingLater() throws Throwable {
        try (Fixture fixture = new Fixture("existing-later")) {
            put(fixture.file("archive.7z.002"), OLD_VOLUME);
            put(fixture.file("archive.7z"), SOURCE);
            expect(FileAlreadyExistsException.class, () -> {
                try (ArchiveOutput output = fixture.output(17)) {
                    writePattern(output.channel, 50);
                    output.commit();
                }
            });
            require(!fixture.file("archive.7z.001").exists(), "New first volume was not rolled back");
            require(Arrays.equals(OLD_VOLUME, readBytes(fixture.file("archive.7z.002"))),
                    "An existing later volume was changed");
            require(Arrays.equals(SOURCE, readBytes(fixture.file("archive.7z"))),
                    "Rollback changed an unrelated archive with the same base name");
            require(fixture.deleted.equals(Collections.singletonList("archive.7z.001")),
                    "Rollback deleted a volume that this transaction did not create");
        }
    }

    private static void uncommitted(long splitSize) throws Throwable {
        try (Fixture fixture = new Fixture("uncommitted")) {
            try (ArchiveOutput output = fixture.output(splitSize)) {
                writePattern(output.channel, 43);
            }
            fixture.assertNoOutput();
        }
    }

    private static void failedClose(long splitSize, boolean streams) throws Throwable {
        try (Fixture fixture = new Fixture("failed-close")) {
            fixture.streamsOnly = streams;
            fixture.failCloseName = splitSize == 0 ? "archive.7z" : "archive.7z.003";
            expect(IOException.class, () -> {
                try (ArchiveOutput output = fixture.output(splitSize)) {
                    writePattern(output.channel, 43);
                    output.commit();
                }
            });
            require(fixture.closeFailures == 1, "The fixture did not inject a final close failure");
            fixture.assertNoOutput();
        }
    }

    private static void failedBoundaryClose() throws Throwable {
        try (Fixture fixture = new Fixture("failed-boundary-close")) {
            fixture.failCloseName = "archive.7z.001";
            expect(IOException.class, () -> {
                try (ArchiveOutput output = fixture.output(17)) {
                    writePattern(output.channel, 43);
                    output.commit();
                }
            });
            require(fixture.closeFailures == 1, "The fixture did not fail on the volume boundary");
            fixture.assertNoOutput();
        }
    }

    private static void zeroWrite() throws Throwable {
        try (Fixture fixture = new Fixture("zero-write")) {
            fixture.zeroWrite = true;
            expect(IOException.class, () -> {
                try (ArchiveOutput output = fixture.output(17)) {
                    ByteBuffer bytes = ByteBuffer.allocate(80);
                    int originalLimit = bytes.limit();
                    try {
                        output.channel.write(bytes);
                    } finally {
                        require(bytes.limit() == originalLimit, "A failed write changed the buffer limit");
                    }
                    output.commit();
                }
            });
            fixture.assertNoOutput();
        }
    }

    private static void cancelled(boolean atCommit) throws Throwable {
        try (Fixture fixture = new Fixture("cancelled")) {
            try (ArchiveOutput output = fixture.output(17)) {
                writePattern(output.channel, 43);
                Thread.currentThread().interrupt();
                try {
                    expect(InterruptedIOException.class, () -> {
                        if (atCommit) output.commit();
                        else output.channel.write(ByteBuffer.wrap(new byte[]{1}));
                    });
                    output.close();
                    require(Thread.currentThread().isInterrupted(), "Output cleanup swallowed cancellation");
                } finally {
                    Thread.interrupted();
                }
            }
            fixture.assertNoOutput();
        }
    }

    private static void cancelledReads() throws Throwable {
        try (Fixture fixture = new Fixture("cancelled-read")) {
            try (ArchiveOutput output = fixture.output(17)) {
                writePattern(output.channel, 43);
                output.commit();
            }
            try (SeekableByteChannel input = openChannel(fixture.first(17))) {
                Thread.currentThread().interrupt();
                try {
                    expect(InterruptedIOException.class, () -> input.read(ByteBuffer.allocate(1)));
                } finally {
                    Thread.interrupted();
                }
                require(input.position() == 0, "A cancelled read advanced the source");
            }
            try (InputStream input = openStream(fixture.first(17))) {
                Thread.currentThread().interrupt();
                try {
                    expect(InterruptedIOException.class, input::read);
                } finally {
                    Thread.interrupted();
                }
            }
        }
    }

    private static void streamOnly() throws Throwable {
        try (Fixture fixture = new Fixture("stream-provider")) {
            fixture.streamsOnly = true;
            try (ArchiveOutput output = fixture.output(19)) {
                writePattern(output.channel, 4099);
                output.commit();
            }
            fixture.assertVolumeSizes(19, 4099);
            try (InputStream input = openStream(fixture.first(19))) {
                checkStream(input, 4099);
            }
        }
    }

    private static void changedVolume(boolean stream, boolean truncate) throws Throwable {
        try (Fixture fixture = new Fixture("changed-volume")) {
            try (ArchiveOutput output = fixture.output(17)) {
                writePattern(output.channel, 43);
                output.commit();
            }
            Path first = fixture.first(17);
            // Both constructors snapshot volume sizes. Change the actual file afterwards to
            // verify that a short read is an error and a growing volume stays within that bound.
            try (Closeable input = stream ? openStream(first) : openChannel(first)) {
                try (RandomAccessFile file = new RandomAccessFile(fixture.disk(first), "rw")) {
                    if (truncate) {
                        file.setLength(8);
                    } else {
                        file.seek(17);
                        file.write(new byte[]{99, 98, 97, 96, 95, 94, 93});
                    }
                }
                CheckedRunnable read = () -> {
                    if (stream) {
                        checkStream((InputStream) input, 43);
                    } else {
                        SeekableByteChannel channel = (SeekableByteChannel) input;
                        ByteBuffer bytes = ByteBuffer.allocate(43);
                        while (bytes.hasRemaining()) {
                            require(channel.read(bytes) > 0, "Unexpected joined-channel EOF");
                        }
                        for (int index = 0; index < bytes.capacity(); ++index) {
                            require(bytes.get(index) == pattern(index), "Growth changed a later volume's data");
                        }
                        require(channel.read(ByteBuffer.allocate(1)) == -1, "Read past the cached volume size");
                    }
                };
                if (truncate) expect(EOFException.class, read);
                else read.run();
            }
        }
    }

    private static void writePattern(SeekableByteChannel output, long length) throws IOException {
        byte[] bytes = new byte[64 * 1024];
        long position = 0;
        while (position < length) {
            int count = (int) Math.min(bytes.length, length - position);
            for (int index = 0; index < count; ++index) bytes[index] = pattern(position + index);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
            while (buffer.hasRemaining()) {
                int before = buffer.position();
                int written = output.write(buffer);
                require(written > 0 && written == buffer.position() - before, "Invalid channel write progress");
            }
            position += count;
        }
    }

    private static void checkRange(SeekableByteChannel input, long position, long length) throws IOException {
        input.position(position);
        ByteBuffer bytes = ByteBuffer.allocate(67);
        int expected = (int) Math.min(bytes.remaining(), Math.max(0, length - position));
        while (bytes.position() < expected) {
            int read = input.read(bytes);
            require(read > 0, "Unexpected EOF in a joined volume at " + input.position());
        }
        for (int index = 0; index < expected; ++index) {
            require(bytes.get(index) == pattern(position + index), "Incorrect byte after seeking to " + position);
        }
        require(input.position() == position + expected, "Incorrect joined-channel position");
        if (expected == 0) require(input.read(bytes) == -1, "Read beyond the last volume did not return EOF");
    }

    private static void checkStream(InputStream input, long length) throws IOException {
        byte[] bytes = new byte[64 * 1024];
        long position = 0;
        int count;
        while ((count = input.read(bytes)) >= 0) {
            require(count > 0, "The joined stream returned zero bytes");
            require(count <= length - position, "The joined stream returned extra data");
            for (int index = 0; index < count; ++index) {
                if (bytes[index] != pattern(position + index)) {
                    throw new AssertionError("Incorrect stream byte at " + (position + index));
                }
            }
            position += count;
        }
        require(position == length, "The joined stream lost bytes: " + position + " != " + length);
    }

    private static byte pattern(long position) { return (byte) (position * 31 + (position >>> 7)); }

    private static SeekableByteChannel openChannel(Path first) throws Throwable {
        return (SeekableByteChannel) invoke(extensions.getMethod("newArchiveByteChannel", Path.class), null, first);
    }

    private static InputStream openStream(Path first) throws Throwable {
        return (InputStream) invoke(extensions.getMethod("newArchiveInputStream", Path.class), null, first);
    }

    private static final class ArchiveOutput implements Closeable {
        final Object delegate;
        final SeekableByteChannel channel;

        ArchiveOutput(Path file, long splitSize) throws Throwable {
            Constructor<?> constructor = outputType.getConstructor(Path.class, long.class);
            try {
                delegate = constructor.newInstance(file, splitSize);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            channel = (SeekableByteChannel) invoke(outputType.getMethod("getChannel"), delegate);
        }

        void commit() throws Throwable { invoke(outputType.getMethod("commit"), delegate); }
        @Override public void close() throws IOException { ((Closeable) delegate).close(); }
    }

    private static final class Fixture extends FileSystem {
        final File directory;
        final Provider provider = new Provider(this);
        final List<String> deleted = new ArrayList<>();
        boolean streamsOnly;
        boolean zeroWrite;
        int maxWrite = Integer.MAX_VALUE;
        int maxRead = Integer.MAX_VALUE;
        String failCloseName;
        int closeFailures;
        int openChannels;
        int openStreams;

        Fixture(String name) throws IOException {
            this(newCaseDirectory(name));
        }

        Fixture(File directory) throws IOException {
            this.directory = directory.getCanonicalFile();
            require(this.directory.isDirectory() || this.directory.mkdirs(), "Unable to create fixture directory");
            File source = file("source.keep");
            if (source.exists()) {
                require(Arrays.equals(SOURCE, readBytes(source)), "An unrelated fixture marker already exists");
            } else {
                put(source, SOURCE);
            }
        }

        private static File newCaseDirectory(String name) throws IOException {
            File marker = File.createTempFile(name + "-", ".dir", work);
            require(marker.delete() && marker.mkdir(), "Unable to create fixture directory");
            return marker;
        }

        ArchiveOutput output(long splitSize) throws Throwable { return new ArchiveOutput(path("archive.7z"), splitSize); }
        Path first(long splitSize) { return path(splitSize == 0 ? "archive.7z" : "archive.7z.001"); }
        File file(String name) { return new File(directory, name); }
        Path path(String name) { return makePath(file(name).getAbsolutePath()); }

        File disk(Path path) throws IOException {
            require(path.getFileSystem() == this, "Unexpected filesystem");
            File file = new File(path.toString()).getCanonicalFile();
            require(file.equals(directory) || file.getPath().startsWith(directory.getPath() + File.separator),
                    "A provider operation escaped its fixture: " + file);
            return file;
        }

        private Path makePath(String text) {
            return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getFileSystem": return this;
                            case "toString": return text;
                            case "toUri": return new File(text).toURI();
                            case "toFile": return new File(text);
                            case "isAbsolute": return new File(text).isAbsolute();
                            case "getFileName": return makePath(new File(text).getName());
                            case "getParent": {
                                String parent = new File(text).getParent();
                                return parent == null ? null : makePath(parent);
                            }
                            case "resolveSibling": {
                                String sibling = args[0].toString();
                                return makePath(new File(sibling).isAbsolute() ? sibling
                                        : new File(new File(text).getParentFile(), sibling).getPath());
                            }
                            case "resolve": {
                                String child = args[0].toString();
                                return makePath(new File(child).isAbsolute() ? child : new File(text, child).getPath());
                            }
                            case "normalize": return makePath(new File(text).getCanonicalPath());
                            case "hashCode": return 31 * System.identityHashCode(this) + text.hashCode();
                            case "equals": return args[0] instanceof Path && ((Path) args[0]).getFileSystem() == this
                                    && text.equals(args[0].toString());
                            case "compareTo": return text.compareTo(args[0].toString());
                            default: throw unsupported(method.toString());
                        }
                    });
        }

        void assertVolumeSizes(long splitSize, long length) {
            long count = splitSize == 0 ? 1 : Math.max(1, (length + splitSize - 1) / splitSize);
            Set<String> expected = new HashSet<>(Collections.singleton("source.keep"));
            for (int index = 1; index <= count; ++index) {
                String name = splitSize == 0 ? "archive.7z" : String.format(java.util.Locale.ROOT, "archive.7z.%03d", index);
                expected.add(name);
                long expectedSize = splitSize == 0 ? length : Math.min(splitSize, length - (index - 1L) * splitSize);
                require(file(name).isFile() && file(name).length() == expectedSize, "Incorrect volume size: " + name);
            }
            require(expected.equals(new HashSet<>(Arrays.asList(directory.list()))), "Incorrect volume names");
        }

        void assertNoOutput() {
            require(Arrays.equals(new String[]{"source.keep"}, directory.list()), "Partial output remains: " + Arrays.toString(directory.list()));
        }

        @Override public void close() throws IOException {
            require(Arrays.equals(SOURCE, readBytes(file("source.keep"))), "Output cleanup changed the source");
            require(!deleted.contains("source.keep"), "Source participated in output cleanup");
            require(openChannels == 0 && openStreams == 0, "Provider handles leaked: " + openChannels + "/" + openStreams);
        }

        @Override public FileSystemProvider provider() { return provider; }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return false; }
        @Override public String getSeparator() { return "/"; }
        @Override public Iterable<Path> getRootDirectories() { throw unsupported("root directories"); }
        @Override public Iterable<FileStore> getFileStores() { throw unsupported("file stores"); }
        @Override public Set<String> supportedFileAttributeViews() { return Collections.singleton("basic"); }
        @Override public Path getPath(String first, String... more) {
            File file = new File(first);
            for (String part : more) file = new File(file, part);
            return makePath(file.getPath());
        }
        @Override public PathMatcher getPathMatcher(String pattern) { throw unsupported("path matching"); }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw unsupported("principals"); }
        @Override public WatchService newWatchService() { throw unsupported("watch service"); }
    }

    private static final class Provider extends FileSystemProvider {
        private final Fixture fixture;

        Provider(Fixture fixture) { this.fixture = fixture; }

        private File create(Path path, Set<? extends OpenOption> options) throws IOException {
            require(options.contains(StandardOpenOption.CREATE_NEW), "Output did not request CREATE_NEW");
            require(options.contains(StandardOpenOption.WRITE), "Output did not request WRITE");
            File file = fixture.disk(path);
            if (!file.createNewFile()) throw new FileAlreadyExistsException(path.toString());
            return file;
        }

        private File existing(Path path) throws IOException {
            File file = fixture.disk(path);
            if (!file.exists()) throw new NoSuchFileException(path.toString());
            return file;
        }

        private void afterClose(File file, boolean writing) throws IOException {
            if (writing && file.getName().equals(fixture.failCloseName)) {
                ++fixture.closeFailures;
                throw new IOException("Injected close failure: " + file.getName());
            }
        }

        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) throws IOException {
            if (fixture.streamsOnly) throw unsupported("byte channels");
            boolean writing = options.contains(StandardOpenOption.WRITE);
            File file = writing ? create(path, options) : existing(path);
            RandomAccessFile access = new RandomAccessFile(file, writing ? "rw" : "r");
            FileChannel channel = access.getChannel();
            ++fixture.openChannels;
            return new SeekableByteChannel() {
                private boolean open = true;
                @Override public int read(ByteBuffer buffer) throws IOException {
                    int limit = buffer.limit();
                    buffer.limit(buffer.position() + Math.min(buffer.remaining(), fixture.maxRead));
                    try { return channel.read(buffer); } finally { buffer.limit(limit); }
                }
                @Override public int write(ByteBuffer buffer) throws IOException {
                    if (fixture.zeroWrite) return 0;
                    int limit = buffer.limit();
                    buffer.limit(buffer.position() + Math.min(buffer.remaining(), fixture.maxWrite));
                    try { return channel.write(buffer); } finally { buffer.limit(limit); }
                }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public SeekableByteChannel position(long position) throws IOException { channel.position(position); return this; }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public SeekableByteChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
                @Override public boolean isOpen() { return open; }
                @Override public void close() throws IOException {
                    if (!open) return;
                    open = false;
                    --fixture.openChannels;
                    access.close();
                    afterClose(file, writing);
                }
            };
        }

        @Override public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
            FileInputStream input = new FileInputStream(existing(path));
            ++fixture.openStreams;
            return new InputStream() {
                private boolean closed;
                @Override public int read() throws IOException { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return input.read(bytes, offset, Math.min(length, fixture.maxRead));
                }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    --fixture.openStreams;
                    input.close();
                }
            };
        }

        @Override public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
            File file = create(path, new HashSet<>(Arrays.asList(options)));
            FileOutputStream output = new FileOutputStream(file);
            ++fixture.openStreams;
            return new OutputStream() {
                private boolean closed;
                @Override public void write(int value) throws IOException { output.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(bytes, offset, length); }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    --fixture.openStreams;
                    output.close();
                    afterClose(file, true);
                }
            };
        }

        @Override public void delete(Path path) throws IOException {
            File file = fixture.disk(path);
            fixture.deleted.add(file.getName());
            if (!file.exists()) throw new NoSuchFileException(path.toString());
            if (file.isDirectory() && file.list().length != 0) throw new DirectoryNotEmptyException(path.toString());
            if (!file.delete()) throw new IOException("Unable to delete fixture: " + path);
        }

        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { existing(path); }

        @SuppressWarnings("unchecked")
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                LinkOption... options) throws IOException {
            File file = existing(path);
            return (A) new BasicFileAttributes() {
                @Override public FileTime lastModifiedTime() { return FileTime.fromMillis(file.lastModified()); }
                @Override public FileTime lastAccessTime() { return lastModifiedTime(); }
                @Override public FileTime creationTime() { return lastModifiedTime(); }
                @Override public boolean isRegularFile() { return file.isFile(); }
                @Override public boolean isDirectory() { return file.isDirectory(); }
                @Override public boolean isSymbolicLink() { return false; }
                @Override public boolean isOther() { return false; }
                @Override public long size() { return file.length(); }
                @Override public Object fileKey() { return file.getPath(); }
            };
        }

        @Override public String getScheme() { return "volume-fixture"; }
        @Override public FileSystem newFileSystem(URI uri, java.util.Map<String, ?> env) { throw unsupported("new filesystem"); }
        @Override public FileSystem getFileSystem(URI uri) { return fixture; }
        @Override public Path getPath(URI uri) { return fixture.getPath(new File(uri).getPath()); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) { throw unsupported("directory stream"); }
        @Override public void createDirectory(Path path, FileAttribute<?>... attributes) { throw unsupported("create directory"); }
        @Override public void copy(Path source, Path target, CopyOption... options) { throw unsupported("copy"); }
        @Override public void move(Path source, Path target, CopyOption... options) { throw unsupported("move"); }
        @Override public boolean isSameFile(Path first, Path second) { return first.equals(second); }
        @Override public boolean isHidden(Path path) { return false; }
        @Override public FileStore getFileStore(Path path) { throw unsupported("file store"); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { throw unsupported("attribute view"); }
        @Override public java.util.Map<String, Object> readAttributes(Path path, String names, LinkOption... options) { throw unsupported("named attributes"); }
        @Override public void setAttribute(Path path, String name, Object value, LinkOption... options) { throw unsupported("set attribute"); }
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void put(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static byte[] readBytes(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream input = new FileInputStream(file)) {
            int position = 0;
            while (position < bytes.length) {
                int count = input.read(bytes, position, bytes.length - position);
                if (count < 0) throw new IOException("Unexpected EOF: " + file);
                position += count;
            }
        }
        return bytes;
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

    private static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("Outside the fake provider boundary: " + operation);
    }

    private interface CheckedRunnable { void run() throws Throwable; }
}
