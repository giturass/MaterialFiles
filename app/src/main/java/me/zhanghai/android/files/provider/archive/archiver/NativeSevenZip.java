/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver;

import androidx.annotation.Keep;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;

import java8.nio.channels.SeekableByteChannel;

/** The official 7-Zip archive handler. Channels remain owned by the caller. */
@Keep
public final class NativeSevenZip implements Closeable {
    static {
        System.loadLibrary("sevenzip");
    }

    /** Missing timestamps are represented by {@link Long#MIN_VALUE}. */
    @Keep
    public static final class Entry {
        public int index;
        public String name;
        public boolean directory;
        public long size;
        public boolean encrypted;
        public boolean hasAttributes;
        public int attributes;
        public long lastModifiedTime = Long.MIN_VALUE;
        public long lastAccessTime = Long.MIN_VALUE;
        public long creationTime = Long.MIN_VALUE;
    }

    @Keep
    public interface ExtractCallback {
        /** Return null to skip an entry. Not called when testMode is true. */
        OutputStream openOutput(int index) throws IOException;

        /** Owns closing the output; failure is null only after successful CRC verification. */
        void onResult(int index, IOException failure) throws IOException;
    }

    @Keep
    public interface CreateCallback {
        /** The engine closes each input after it has been consumed. */
        InputStream openInput(int index) throws IOException;

        void onProgress(long completed) throws IOException;

        void onResult(int index, IOException failure) throws IOException;
    }

    private long handle;

    public NativeSevenZip(SeekableByteChannel channel, String password) throws IOException {
        this(channel, password, 128L * 1024 * 1024);
    }

    public NativeSevenZip(SeekableByteChannel channel, String password, long memoryLimitBytes)
            throws IOException {
        if (memoryLimitBytes <= 0) {
            throw new IllegalArgumentException("memoryLimitBytes must be positive");
        }
        handle = nativeOpen(new Channel(channel), password, memoryLimitBytes);
    }

    public synchronized Entry[] readEntries() throws IOException {
        return nativeReadEntries(requireOpen());
    }

    /** Indices must be strictly increasing; one native call decodes solid blocks only once. */
    public synchronized void extract(int[] indices, ExtractCallback callback, boolean testMode)
            throws IOException {
        for (int i = 0; i < indices.length; ++i) {
            if (indices[i] < 0 || (i > 0 && indices[i - 1] >= indices[i])) {
                throw new IllegalArgumentException("7z indices must be strictly increasing");
            }
        }
        nativeExtract(requireOpen(), indices, callback, testMode);
    }

    @Override
    public synchronized void close() throws IOException {
        if (handle != 0) {
            long previousHandle = handle;
            handle = 0;
            nativeClose(previousHandle);
        }
    }

    public static void create(SeekableByteChannel channel, Entry[] entries,
            CreateCallback callback, String password, boolean encryptHeaders) throws IOException {
        create(channel, entries, callback, password, encryptHeaders, 5, 16 * 1024 * 1024, 2);
    }

    public static void create(SeekableByteChannel channel, Entry[] entries,
            CreateCallback callback, String password, boolean encryptHeaders, int compressionLevel,
            int dictionarySizeBytes, int numThreads) throws IOException {
        if (encryptHeaders && password == null) {
            throw new IllegalArgumentException("Encrypted file names require a password");
        }
        if (compressionLevel < 0 || compressionLevel > 9 || dictionarySizeBytes < 64 * 1024
                || dictionarySizeBytes > 32 * 1024 * 1024 || numThreads < 1 || numThreads > 2) {
            throw new IllegalArgumentException("Invalid 7z compression settings");
        }
        nativeCreate(new Channel(channel), entries, callback, password, encryptHeaders,
                compressionLevel, dictionarySizeBytes, numThreads);
    }

    private long requireOpen() throws IOException {
        if (handle == 0) {
            throw new IOException("The 7z archive is closed");
        }
        return handle;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
        }
    }

    private static InterruptedIOException interrupted(ClosedByInterruptException cause) {
        InterruptedIOException exception = new InterruptedIOException();
        exception.initCause(cause);
        return exception;
    }

    /** Keeps java8 channels usable on Android 6 and propagates original provider exceptions. */
    @Keep
    private static final class Channel {
        private final SeekableByteChannel channel;

        Channel(SeekableByteChannel channel) {
            if (channel == null) {
                throw new NullPointerException("channel");
            }
            this.channel = channel;
        }

        int read(ByteBuffer buffer) throws IOException {
            checkInterrupted();
            int count;
            try {
                count = channel.read(buffer);
            } catch (ClosedByInterruptException exception) {
                throw interrupted(exception);
            }
            if (count == 0 && buffer.hasRemaining()) {
                throw new IOException("The archive channel made no read progress");
            }
            return count;
        }

        int write(ByteBuffer buffer) throws IOException {
            checkInterrupted();
            int count;
            try {
                count = channel.write(buffer);
            } catch (ClosedByInterruptException exception) {
                throw interrupted(exception);
            }
            if (count <= 0 && buffer.hasRemaining()) {
                throw new IOException("The archive channel made no write progress");
            }
            return count;
        }

        long seek(long offset, int origin) throws IOException {
            checkInterrupted();
            try {
                return seekInternal(offset, origin);
            } catch (ClosedByInterruptException exception) {
                throw interrupted(exception);
            }
        }

        private long seekInternal(long offset, int origin) throws IOException {
            long base;
            switch (origin) {
                case 0:
                    base = 0;
                    break;
                case 1:
                    base = channel.position();
                    break;
                case 2:
                    base = channel.size();
                    break;
                default:
                    throw new IOException("Invalid archive seek origin");
            }
            if ((offset > 0 && base > Long.MAX_VALUE - offset)
                    || (offset < 0 && base < Long.MIN_VALUE - offset)
                    || base + offset < 0) {
                throw new IOException("Invalid archive seek offset");
            }
            channel.position(base + offset);
            return base + offset;
        }

        void setSize(long size) throws IOException {
            checkInterrupted();
            try {
                channel.truncate(size);
            } catch (ClosedByInterruptException exception) {
                throw interrupted(exception);
            }
        }
    }

    private static native long nativeOpen(Channel channel, String password, long memoryLimitBytes)
            throws IOException;
    private static native Entry[] nativeReadEntries(long handle) throws IOException;
    private static native void nativeExtract(long handle, int[] indices, ExtractCallback callback,
            boolean testMode) throws IOException;
    private static native void nativeClose(long handle) throws IOException;
    private static native void nativeCreate(Channel channel, Entry[] entries,
            CreateCallback callback, String password, boolean encryptHeaders, int compressionLevel,
            int dictionarySizeBytes, int numThreads) throws IOException;
}
