/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.zhanghai.android.files.provider.archive.archiver.ReadArchive;
import me.zhanghai.android.files.provider.archive.archiver.SevenZArchiveReader;
import me.zhanghai.android.files.provider.common.PosixFileModeBit;
import me.zhanghai.android.files.provider.common.PosixFileType;
import me.zhanghai.android.libarchive.ArchiveException;

/** Exercises the compiled application reader, not a replacement or Commons Compress directly. */
public final class SevenZArchiveReaderInteropTest {
    private static final String PASSWORD = "测试-password-🔐";
    private static final String NAME = "文档/秘密名称.txt";
    private static final byte[] CONTENT = "加密压缩互操作测试\nhello 7z\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final List<String> CORRECT = Collections.singletonList(PASSWORD);
    private static final List<String> FALLBACK = Arrays.asList(PASSWORD, "wrong-latest");
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path fixtures = Paths.get(args[0]);
        for (String filename : new String[]{"official-headers.7z", "official-contents.7z",
                "official-copy.7z"}) {
            Path path = fixtures.resolve(filename);
            check(filename + ": no password", () -> rejectPassword(path, Collections.emptyList()));
            check(filename + ": incorrect password", () ->
                    rejectPassword(path, Collections.singletonList("wrong-password")));
            check(filename + ": metadata", () -> verifyMetadata(path, true));
            check(filename + ": content", () -> verifyContent(path, CORRECT, true));
            check(filename + ": older correct password", () -> verifyContent(path, FALLBACK, true));
            if (filename.equals("official-headers.7z")) {
                check(filename + ": protected names", () -> rejectListingPassword(path));
                check(filename + ": metadata password retry", () -> verifyMetadataFallback(path));
            }
        }
        Path encrypted = fixtures.resolve("official-headers.7z");
        check("interrupted before metadata", () -> verifyInterrupted(encrypted, false));
        check("interrupted during content", () -> verifyInterrupted(encrypted, true));
        check("I/O cancellation keeps its exception", () ->
                verifyIoFailure(encrypted, new InterruptedIOException("Injected cancellation")));
        check("ordinary I/O failure keeps its exception", () ->
                verifyIoFailure(encrypted, new IOException("Injected provider failure")));
        check("archive close failure still releases owner", () -> verifyCloseFailure(encrypted));
        if (args.length > 1) {
            Path writerFixtures = Paths.get(args[1]);
            for (String filename : new String[]{"headers.7z", "contents.7z"}) {
                Path path = writerFixtures.resolve(filename);
                check("app " + filename + ": metadata", () -> verifyMetadata(path, false));
                check("app " + filename + ": content and password retry", () ->
                        verifyContent(path, FALLBACK, false));
                check("app " + filename + ": no password", () ->
                        rejectPassword(path, Collections.emptyList()));
            }
            check("app encrypted empty entries", () -> verifyEmptyArchive(writerFixtures));
        }
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " / " + checks + " checks failed: " + FAILURES);
        }
        System.out.println("Actual SevenZArchiveReader: " + checks + " checks passed (official 7zz"
                + (args.length > 1 ? " and application" : "")
                + " fixtures, passwords, metadata, streams, cancellation, ownership).");
    }

    private static void rejectPassword(Path path, List<String> passwords) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, passwords)) {
            try (InputStream input = handle.reader.newInputStream(NAME)) {
                // COPY can detect an incorrect key only at the final content CRC.
                if (input != null) {
                    byte[] buffer = new byte[8192];
                    while (input.read(buffer) >= 0) {}
                }
                throw new AssertionError("Encrypted content accepted an absent or incorrect key");
            } catch (ArchiveException expected) {
                require(expected.getCause() instanceof IOException, "Password error lost its cause");
                require(expected.getCode() == -1, "Password error lost libarchive's error code");
                require(expected.getMessage().equals(passwords.isEmpty()
                        ? "Passphrase required for this entry" : "Incorrect passphrase"),
                        "Incorrect user-facing password error");
            }
            handle.assertStillOwned();
        }
    }

    private static void rejectListingPassword(Path path) throws Exception {
        for (List<String> passwords : Arrays.asList(Collections.<String>emptyList(),
                Collections.singletonList("wrong-password"))) {
            try (ReaderHandle handle = new ReaderHandle(path, passwords)) {
                try {
                    readEntries(handle.reader);
                    throw new AssertionError("Encrypted names accepted an absent or incorrect key");
                } catch (ArchiveException expected) {
                    require(expected.getCause() instanceof IOException, "Password error lost its cause");
                    require(expected.getCode() == -1, "Password error lost libarchive's error code");
                }
                handle.assertStillOwned();
            }
        }
    }

    private static void verifyMetadata(Path path, boolean official) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, CORRECT)) {
            Map<String, ReadArchive.Entry> entries = index(readEntries(handle.reader));
            require(entries.size() == (official ? 7 : 6), "Unexpected entry count: " + entries.keySet());
            ReadArchive.Entry file = entries.get(NAME);
            require(file != null && file.getType() == PosixFileType.REGULAR_FILE,
                    "Unicode regular file metadata missing");
            require(file.getSize() == CONTENT.length && file.isEncrypted(), "File size/encryption");
            require(file.getMode().contains(PosixFileModeBit.OWNER_WRITE), "Lost file owner mode");
            require(!file.getMode().contains(PosixFileModeBit.OWNER_EXECUTE), "File became executable");
            ReadArchive.Entry directory = entries.get("文档");
            require(directory != null && directory.isDirectory(), "Directory type");
            require(directory.getMode().contains(PosixFileModeBit.OWNER_EXECUTE), "Directory mode");
            ReadArchive.Entry empty = entries.get("empty");
            require(empty != null && empty.getType() == PosixFileType.REGULAR_FILE
                    && empty.getSize() == 0, "Empty file metadata");
            ReadArchive.Entry link = entries.get("link");
            require(link != null && link.isSymbolicLink() && NAME.equals(link.getSymbolicLinkTarget()),
                    "Symlink type/Unicode target");
            if (official) {
                require(file.getLastModifiedTime() != null
                        && file.getLastModifiedTime().toMillis() == 1700000000000L,
                        "Modification time not preserved");
                require(!file.getMode().contains(PosixFileModeBit.OTHERS_READ), "0640 became public");
                ReadArchive.Entry executable = entries.get("executable.sh");
                require(executable.getMode().contains(PosixFileModeBit.OWNER_EXECUTE)
                        && executable.getMode().contains(PosixFileModeBit.GROUP_EXECUTE)
                        && !executable.getMode().contains(PosixFileModeBit.OTHERS_EXECUTE),
                        "0750 executable permissions");
                require(entries.get("empty-directory").isDirectory(), "Empty directory metadata");
            }
            handle.assertStillOwned();
        }
    }

    private static void verifyMetadataFallback(Path path) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, FALLBACK)) {
            require(index(readEntries(handle.reader)).containsKey(NAME), "Metadata retry lost name");
            handle.assertStillOwned();
        }
    }

    private static void verifyContent(Path path, List<String> passwords, boolean official)
            throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, passwords)) {
            assertBytes(handle.reader, NAME, CONTENT);
            assertBytes(handle.reader, "empty", new byte[0]);
            assertBytes(handle.reader, "link", NAME.getBytes(StandardCharsets.UTF_8));
            if (official) {
                assertBytes(handle.reader, "executable.sh", "#!/bin/sh\nexit 0\n"
                        .getBytes(StandardCharsets.UTF_8));
            } else {
                assertBytes(handle.reader, "growing", CONTENT);
            }
            require(handle.reader.newInputStream("missing-entry") == null, "Missing entry not null");
            try (InputStream input = handle.reader.newInputStream("large.bin")) {
                require(input != null, "Large entry not found");
                long position = 0;
                byte[] buffer = new byte[8191];
                int count;
                while ((count = input.read(buffer, 0, buffer.length)) >= 0) {
                    for (int i = 0; i < count; ++i) {
                        require(buffer[i] == (byte) ((position + i) * 37), "Streamed content differs");
                    }
                    position += count;
                }
                require(position == (official ? 1024 * 1024 : 16 * 1024 * 1024),
                        "Streamed content length");
            }
            handle.assertStillOwned();
        }
    }

    private static void verifyEmptyArchive(Path directory) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(directory.resolve("empty.7z"), FALLBACK)) {
            Map<String, ReadArchive.Entry> entries = index(readEntries(handle.reader));
            require(entries.size() == 2 && entries.get("empty-directory").isDirectory(),
                    "Encrypted empty archive entries");
            assertBytes(handle.reader, "empty", new byte[0]);
        }
    }

    private static void assertBytes(SevenZArchiveReader reader, String name, byte[] expected)
            throws IOException {
        try (InputStream input = reader.newInputStream(name)) {
            require(input != null, "Missing " + name);
            // Both public read methods must use the real translating stream wrapper.
            for (byte value : expected) {
                require(input.read() == (value & 255), "Content differs: " + name);
            }
            require(input.read(new byte[3], 1, 2) == -1, "Unexpected trailing content: " + name);
        }
    }

    private static void verifyInterrupted(Path path, boolean content) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, CORRECT)) {
            InputStream input = content ? handle.reader.newInputStream(NAME) : null;
            Thread.currentThread().interrupt();
            try {
                if (content) {
                    input.read(new byte[2]);
                } else {
                    readEntries(handle.reader);
                }
                throw new AssertionError("Cancellation was ignored");
            } catch (InterruptedIOException expected) {
                require(Thread.currentThread().isInterrupted(), "Lost interruption flag");
            } finally {
                Thread.interrupted();
                if (input != null) {
                    input.close();
                }
            }
        }
    }

    private static void verifyIoFailure(Path path, IOException failure) throws Exception {
        try (ReaderHandle handle = new ReaderHandle(path, FALLBACK)) {
            handle.channel.failure = failure;
            try {
                readEntries(handle.reader);
                throw new AssertionError("I/O failure was ignored");
            } catch (IOException expected) {
                require(expected == failure, "I/O failure was translated to a password error");
            }
            handle.assertStillOwned();
        }
    }

    private static void verifyCloseFailure(Path path) throws Exception {
        ReaderHandle handle = new ReaderHandle(path, CORRECT);
        try {
            readEntries(handle.reader);
            IOException failure = new IOException("Injected archive close failure");
            handle.channel.closeFailure = failure;
            try {
                ((Closeable) handle.reader).close();
                throw new AssertionError("Archive close failure was ignored");
            } catch (IOException expected) {
                require(expected == failure, "Archive close failure was replaced");
            }
            require(handle.ownerCloseCount == 1 && !handle.file.isOpen(),
                    "Archive close failure leaked or repeatedly closed its owner");
        } finally {
            handle.file.close();
        }
    }

    // Kotlin methods need no checked-exception declaration, but Java catch blocks do.
    private static List<ReadArchive.Entry> readEntries(SevenZArchiveReader reader) throws IOException {
        return reader.readEntries();
    }

    private static Map<String, ReadArchive.Entry> index(List<ReadArchive.Entry> entries) {
        Map<String, ReadArchive.Entry> indexed = new HashMap<>();
        for (ReadArchive.Entry entry : entries) {
            require(indexed.put(entry.getName(), entry) == null, "Duplicate archive name");
        }
        return indexed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void check(String name, CheckedRunnable block) {
        ++checks;
        try {
            block.run();
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            FAILURES.add(name);
            System.out.println("FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    /** Only the provider boundary is injected; all reader/entry/exception logic is application code. */
    private static final class ReaderHandle implements Closeable {
        final FileChannel file;
        final BorrowedChannel channel;
        final SevenZArchiveReader reader;
        int ownerCloseCount;

        ReaderHandle(Path path, List<String> passwords) throws Exception {
            file = FileChannel.open(path, StandardOpenOption.READ);
            channel = new BorrowedChannel(file);
            Constructor<SevenZArchiveReader> constructor = SevenZArchiveReader.class
                    .getDeclaredConstructor(SeekableByteChannel.class,
                            Closeable.class, List.class);
            constructor.setAccessible(true);
            try {
                reader = constructor.newInstance(channel, (Closeable) () -> {
                    ++ownerCloseCount;
                    file.close();
                }, passwords);
            } catch (Exception failure) {
                file.close();
                throw failure;
            }
        }

        void assertStillOwned() {
            require(file.isOpen() && ownerCloseCount == 0, "Password retry/stream close released owner");
        }

        @Override
        public void close() throws IOException {
            reader.close();
            require(ownerCloseCount == 1 && !file.isOpen(), "Reader did not close its owner once");
        }
    }

    private static final class BorrowedChannel implements SeekableByteChannel {
        final FileChannel delegate;
        IOException failure;
        IOException closeFailure;

        BorrowedChannel(FileChannel delegate) { this.delegate = delegate; }
        @Override public int read(ByteBuffer buffer) throws IOException {
            if (failure != null) { throw failure; }
            return delegate.read(buffer);
        }
        @Override public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() throws IOException {
            if (closeFailure != null) { throw closeFailure; }
        }
    }
}
