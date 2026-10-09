/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import me.zhanghai.android.files.provider.archive.archiver.EncryptedSevenZOutputFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

/** Real archive interoperability fixtures; invoked by tools/verify_7z.py. */
public final class EncryptedSevenZInteropTest {
    public static final String PASSWORD = "测试-password-🔐";
    public static final String NAME = "文档/秘密名称.txt";
    public static final byte[] CONTENT = "加密压缩互操作测试\nhello 7z\n".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);
        Files.createDirectories(directory);
        for (boolean headers : new boolean[]{false, true}) {
            Path path = directory.resolve(headers ? "headers.7z" : "contents.7z");
            try (SeekableByteChannel channel = new TestChannel(Files.newByteChannel(path,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), 7, false)) {
                try (EncryptedSevenZOutputFile writer = new EncryptedSevenZOutputFile(
                        channel, PASSWORD.toCharArray(), headers)) {
                    entry(writer, "文档", null, 0040755);
                    entry(writer, NAME, CONTENT, 0100644);
                    entry(writer, "empty", new byte[0], 0100644);
                    entry(writer, "link", "文档/秘密名称.txt".getBytes(StandardCharsets.UTF_8), 0120777);
                    // Simulate a file growing after stat(): it must still be encrypted.
                    SevenZArchiveEntry growing = new SevenZArchiveEntry();
                    growing.setName("growing");
                    growing.setSize(0);
                    writer.putArchiveEntry(growing);
                    writer.write(CONTENT, 0, CONTENT.length);
                    writer.closeArchiveEntry();
                    SevenZArchiveEntry large = new SevenZArchiveEntry();
                    large.setName("large.bin");
                    large.setSize(16 * 1024 * 1024);
                    writer.putArchiveEntry(large);
                    byte[] block = new byte[8192];
                    for (int i = 0; i < block.length; ++i) {
                        block[i] = (byte) (i * 37);
                    }
                    for (int i = 0; i < 2048; ++i) {
                        writer.write(block, 0, block.length);
                    }
                    writer.closeArchiveEntry();
                }
                require(channel.isOpen(), "Writer took ownership of the provider channel");
            }
            verifyRead(path);
            byte[] bytes = Files.readAllBytes(path);
            boolean visible = contains(bytes, NAME.getBytes(StandardCharsets.UTF_16LE));
            require(visible != headers, "Filename encryption does not match the option");
            require(!contains(bytes, CONTENT), "Plaintext content leaked into the archive");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(directory.resolve("empty.7z"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
             EncryptedSevenZOutputFile writer = new EncryptedSevenZOutputFile(
                     channel, PASSWORD.toCharArray(), true)) {
            entry(writer, "empty", new byte[0], 0100644);
            entry(writer, "empty-directory", null, 0040755);
        }
        verifyFinalizationFailure(directory);
        verifyCancellation(directory);
        verifyAbort(directory);
        verifySequentialDestination(directory);
        System.out.println("Java checks passed: encrypted content/header, Unicode, empty entries, "
                + "symlink metadata, growth, streaming, short/sequential writes, close failure, cancellation.");
    }

    private static void entry(EncryptedSevenZOutputFile writer, String name, byte[] bytes, int mode)
            throws IOException {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setDirectory(bytes == null);
        entry.setSize(bytes == null ? 0 : bytes.length);
        entry.setHasWindowsAttributes(true);
        entry.setWindowsAttributes((mode << 16) | 0x8000 | (bytes == null ? 0x10 : 0x20));
        writer.putArchiveEntry(entry);
        if (bytes != null) {
            writer.write(bytes, 0, bytes.length);
        }
        writer.closeArchiveEntry();
    }

    private static void verifyRead(Path path) throws Exception {
        try (SevenZFile archive = SevenZFile.builder().setPath(path).setPassword(PASSWORD)
                .setMaxMemoryLimitKiB(64 * 1024).get()) {
            int count = 0;
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                long read = 0;
                byte[] bytes = new byte[8192];
                int size;
                while ((size = archive.read(bytes)) != -1) {
                    read += size;
                }
                require(read == entry.getSize(), "Decompressed size differs for " + entry.getName());
                ++count;
            }
            require(count == 6, "Wrong entry count");
        }
    }

    private static void verifyFinalizationFailure(Path directory) throws Exception {
        Path path = directory.resolve("close-failed.7z");
        try (SeekableByteChannel channel = new TestChannel(Files.newByteChannel(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE), 11, true)) {
            EncryptedSevenZOutputFile writer = new EncryptedSevenZOutputFile(
                    channel, PASSWORD.toCharArray(), true);
            entry(writer, NAME, CONTENT, 0100644);
            try {
                writer.close();
                throw new AssertionError("Signature write failure was swallowed");
            } catch (IOException expected) {
                require(!contains(Files.readAllBytes(path), NAME.getBytes(StandardCharsets.UTF_16LE)),
                        "Plaintext names leaked when close failed");
            }
        }
    }

    private static void verifyCancellation(Path directory) throws Exception {
        try (SeekableByteChannel channel = Files.newByteChannel(directory.resolve("cancelled.7z"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            EncryptedSevenZOutputFile writer = new EncryptedSevenZOutputFile(
                    channel, PASSWORD.toCharArray(), true);
            entry(writer, NAME, CONTENT, 0100644);
            Thread.currentThread().interrupt();
            try {
                writer.close();
                throw new AssertionError("Cancellation was ignored during finalization");
            } catch (InterruptedIOException expected) {
                require(Thread.currentThread().isInterrupted(), "Cancellation flag was lost");
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static void verifyAbort(Path directory) throws Exception {
        Path path = directory.resolve("aborted.7z");
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            EncryptedSevenZOutputFile writer = new EncryptedSevenZOutputFile(
                    channel, PASSWORD.toCharArray(), true);
            entry(writer, NAME, CONTENT, 0100644);
            long size = channel.size();
            writer.abort();
            writer.close();
            require(channel.size() == size, "Aborted archive was finalized");
        }
    }

    private static void verifySequentialDestination(Path directory) throws Exception {
        Path output = directory.resolve("sequential.7z");
        // This is the same Channels.newOutputStream copy used after FileJobs privately stages
        // an encrypted 7z for FTP, document, or WebDAV targets. Every seek/read would fail here.
        try (SeekableByteChannel channel = new SequentialChannel(Files.newByteChannel(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
             InputStream input = Files.newInputStream(directory.resolve("headers.7z"))) {
            OutputStream stream = Channels.newOutputStream(channel);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                stream.write(buffer, 0, count);
            }
        }
        require(Arrays.equals(Files.readAllBytes(output), Files.readAllBytes(directory.resolve("headers.7z"))),
                "Sequential destination differs from the completed encrypted archive");
        verifyRead(output);
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        for (int i = 0; i <= bytes.length - needle.length; ++i) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final int maximumWrite;
        private final boolean failSignature;

        TestChannel(SeekableByteChannel delegate, int maximumWrite, boolean failSignature) {
            this.delegate = delegate;
            this.maximumWrite = maximumWrite;
            this.failSignature = failSignature;
        }

        @Override
        public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
        @Override
        public int write(ByteBuffer buffer) throws IOException {
            int limit = buffer.limit();
            buffer.limit(Math.min(limit, buffer.position() + maximumWrite));
            try { return delegate.write(buffer); }
            finally { buffer.limit(limit); }
        }
        @Override
        public long position() throws IOException { return delegate.position(); }
        @Override
        public SeekableByteChannel position(long position) throws IOException {
            if (failSignature && position == 0) { throw new IOException("Injected signature failure"); }
            delegate.position(position);
            return this;
        }
        @Override
        public long size() throws IOException { return delegate.size(); }
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }
        @Override
        public boolean isOpen() { return delegate.isOpen(); }
        @Override
        public void close() throws IOException { delegate.close(); }
    }

    private static final class SequentialChannel implements SeekableByteChannel {
        private final TestChannel delegate;

        SequentialChannel(SeekableByteChannel delegate) {
            this.delegate = new TestChannel(delegate, 13, false);
        }

        @Override
        public int read(ByteBuffer bytes) throws IOException { throw new IOException("No reads"); }
        @Override
        public int write(ByteBuffer bytes) throws IOException { return delegate.write(bytes); }
        @Override
        public long position() throws IOException { throw new IOException("No seeking"); }
        @Override
        public SeekableByteChannel position(long position) throws IOException { throw new IOException("No seeking"); }
        @Override
        public long size() throws IOException { throw new IOException("No seeking"); }
        @Override
        public SeekableByteChannel truncate(long size) throws IOException { throw new IOException("No truncation"); }
        @Override
        public boolean isOpen() { return delegate.isOpen(); }
        @Override
        public void close() throws IOException { delegate.close(); }
    }
}
