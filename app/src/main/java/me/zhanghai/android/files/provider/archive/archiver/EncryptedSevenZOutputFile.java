/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.sevenz.AndroidSevenZEncryption;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.tukaani.xz.LZMA2Options;

/**
 * Streaming 7z AES writer, including optional encryption of the archive's header.
 * Commons Compress writes entries and their checksums. Only the final metadata is buffered,
 * then wrapped in the standard 7z encoded-header structure; plaintext names never reach disk.
 * The caller owns the channel. See docs/7z.md for the format specification and test command.
 */
public final class EncryptedSevenZOutputFile implements Closeable {
    private static final int SIGNATURE_SIZE = 32;
    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    private final HeaderChannel channel;
    private final SevenZOutputFile archive;
    private final char[] password;
    private final boolean encryptFileNames;
    private final SevenZMethodConfiguration compression;
    private boolean entryOpen;
    private boolean failed;
    private boolean closed;

    public EncryptedSevenZOutputFile(SeekableByteChannel channel, char[] password,
                                    boolean encryptFileNames) throws IOException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("An encryption password is required");
        }
        this.channel = new HeaderChannel(channel);
        this.archive = new SevenZOutputFile(this.channel);
        this.password = password.clone();
        this.encryptFileNames = encryptFileNames;
        // Limit per-entry LZMA2 memory on mobile devices (4 MiB dictionary).
        this.compression = new SevenZMethodConfiguration(SevenZMethod.LZMA2, new LZMA2Options(3));
    }

    public void putArchiveEntry(SevenZArchiveEntry entry) throws IOException {
        checkOpen();
        if (entryOpen) {
            throw new IOException("The previous archive entry is still open");
        }
        try {
            // Entry methods are in decoder order: decrypt, then decompress. Empty streams
            // contain no data; when requested, their names are protected by the header cipher.
            if (!entry.isDirectory()) {
                entry.setContentMethods(Arrays.asList(
                        AndroidSevenZEncryption.newEncryption(password), compression));
            }
            archive.putArchiveEntry(entry);
            entryOpen = true;
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    public void write(byte[] bytes, int offset, int length) throws IOException {
        checkOpen();
        if (!entryOpen) {
            throw new IOException("No archive entry is open");
        }
        try {
            checkInterrupted();
            archive.write(bytes, offset, length);
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    public void closeArchiveEntry() throws IOException {
        checkOpen();
        if (!entryOpen) {
            return;
        }
        try {
            checkInterrupted();
            archive.closeArchiveEntry();
            entryOpen = false;
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** Prevent finalizing a partial archive after its source stream has failed. */
    public void abort() {
        failed = true;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (failed) {
                return;
            }
            checkInterrupted();
            closeArchiveEntry();
            if (encryptFileNames) {
                channel.bufferHeader();
            }
            archive.finish();
            if (encryptFileNames) {
                writeEncryptedHeader();
            }
        } finally {
            closed = true;
            Arrays.fill(password, '\0');
            channel.clearHeader();
        }
    }

    private void checkOpen() throws IOException {
        if (closed || failed) {
            throw new IOException("The archive writer is closed or has failed");
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
        }
    }

    private void writeEncryptedHeader() throws IOException {
        byte[] header = channel.header.toByteArray();
        try {
            AndroidSevenZEncryption.EncryptedHeader encrypted =
                    AndroidSevenZEncryption.encryptHeader(header, password);
            long dataOffset = channel.headerPosition - SIGNATURE_SIZE;
            channel.endHeaderBuffering();
            channel.write(ByteBuffer.wrap(encrypted.data));

            ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
            descriptor.write(0x17); // kEncodedHeader
            descriptor.write(0x06); // kPackInfo
            writeUint64(descriptor, dataOffset);
            writeUint64(descriptor, 1);
            descriptor.write(0x09); // kSize
            writeUint64(descriptor, encrypted.data.length);
            descriptor.write(0); // kEnd (PackInfo)
            descriptor.write(0x07); // kUnpackInfo
            descriptor.write(0x0B); // kFolder
            writeUint64(descriptor, 1);
            descriptor.write(0); // External = false
            writeUint64(descriptor, 1); // One AES coder
            descriptor.write(0x24); // Four-byte codec ID, with properties
            descriptor.write(new byte[]{0x06, (byte) 0xF1, 0x07, 0x01}); // AES-256/SHA-256
            writeUint64(descriptor, encrypted.properties.length);
            descriptor.write(encrypted.properties);
            descriptor.write(0x0C); // kCodersUnpackSize
            writeUint64(descriptor, header.length);
            descriptor.write(0x0A); // kCRC
            descriptor.write(1); // All digests defined
            writeInt32(descriptor, checksum(header));
            descriptor.write(0); // kEnd (UnpackInfo)
            descriptor.write(0); // kEnd (StreamsInfo)

            byte[] nextHeader = descriptor.toByteArray();
            long nextHeaderOffset = channel.position() - SIGNATURE_SIZE;
            channel.write(ByteBuffer.wrap(nextHeader));
            long archiveSize = channel.position();
            ByteBuffer signature = ByteBuffer.allocate(SIGNATURE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            signature.put(SIGNATURE).put((byte) 0).put((byte) 4).putInt(0);
            signature.putLong(nextHeaderOffset).putLong(nextHeader.length).putInt(checksum(nextHeader));
            CRC32 startCrc = new CRC32();
            startCrc.update(signature.array(), 12, 20);
            signature.putInt(8, (int) startCrc.getValue());
            signature.flip();
            channel.position(0);
            channel.write(signature);
            channel.position(archiveSize);
        } finally {
            Arrays.fill(header, (byte) 0);
        }
    }

    private static int checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static void writeInt32(ByteArrayOutputStream output, int value) {
        for (int shift = 0; shift < 32; shift += 8) {
            output.write(value >>> shift & 0xFF);
        }
    }

    private static void writeUint64(ByteArrayOutputStream output, long value) {
        int extraBytes = 0;
        while (extraBytes < 8 && (value >>> (7 * (extraBytes + 1))) != 0) {
            ++extraBytes;
        }
        int first = extraBytes == 8 ? 0xFF
                : (0xFF << (8 - extraBytes)) | (int) (value >>> (extraBytes * 8));
        output.write(first);
        for (int i = 0; i < extraBytes; ++i) {
            output.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }

    /** Captures only finish() metadata, also making short provider writes safe. */
    private static final class HeaderChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private WipingByteArrayOutputStream header;
        private long headerPosition;
        private long bufferedPosition;

        HeaderChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        void bufferHeader() throws IOException {
            headerPosition = delegate.position();
            bufferedPosition = headerPosition;
            header = new WipingByteArrayOutputStream();
        }

        void endHeaderBuffering() throws IOException {
            delegate.position(headerPosition);
            clearHeader();
        }

        void clearHeader() {
            if (header != null) {
                header.wipe();
                header = null;
            }
        }

        @Override
        public int write(ByteBuffer bytes) throws IOException {
            checkInterrupted();
            int count = bytes.remaining();
            if (header != null) {
                if (bufferedPosition == headerPosition + header.size()) {
                    byte[] part = new byte[count];
                    bytes.get(part);
                    header.write(part);
                    Arrays.fill(part, (byte) 0);
                } else if (bufferedPosition < SIGNATURE_SIZE
                        && bufferedPosition + count <= SIGNATURE_SIZE) {
                    // Commons Compress's original signature is replaced after encryption.
                    bytes.position(bytes.limit());
                } else {
                    throw new IOException("Unexpected seek while buffering the 7z header");
                }
                bufferedPosition += count;
            } else {
                while (bytes.hasRemaining()) {
                    checkInterrupted();
                    if (delegate.write(bytes) <= 0) {
                        throw new IOException("The archive destination made no write progress");
                    }
                }
            }
            return count;
        }

        @Override
        public int read(ByteBuffer bytes) {
            throw new NonReadableChannelException();
        }

        @Override
        public long position() throws IOException {
            return header == null ? delegate.position() : bufferedPosition;
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            if (header == null) {
                delegate.position(position);
            } else {
                bufferedPosition = position;
            }
            return this;
        }

        @Override
        public long size() throws IOException {
            return header == null ? delegate.size() : headerPosition + header.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() {
            // ArchiveWriter/FileJob owns this channel, including force() and error cleanup.
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
