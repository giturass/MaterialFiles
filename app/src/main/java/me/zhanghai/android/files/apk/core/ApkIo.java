/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import com.android.apksig.util.DataSink;
import com.android.apksig.util.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;

final class ApkIo {
    static final int BUFFER_SIZE = 64 * 1024;
    static final int MAX_METADATA_SIZE = 32 * 1024 * 1024;

    private ApkIo() {}

    static ApkOperationCallback callback(ApkOperationCallback callback) {
        return callback != null ? callback : ApkOperationCallback.NONE;
    }

    static void check(ApkOperationCallback callback) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("APK operation cancelled");
        }
        callback.checkCancelled();
    }

    static File input(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.isFile() || !canonical.canRead()) {
            throw new IOException("Input is not a readable regular file: " + file);
        }
        return canonical;
    }

    static File newOutput(File file, File input) throws IOException {
        File canonical = file.getCanonicalFile();
        if (canonical.equals(input) || canonical.exists()) {
            throw new IOException("Output already exists; choose a new file name: " + file);
        }
        File parent = canonical.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Output directory does not exist: " + file);
        }
        return canonical;
    }

    static void createPrivate(File file) throws IOException {
        if (!file.createNewFile()) {
            throw new IOException("Output already exists; choose a new file name: " + file);
        }
        boolean ready = false;
        try {
            if (!file.setReadable(false, false) || !file.setWritable(false, false)
                    || !file.setExecutable(false, false) || !file.setReadable(true, true)
                    || !file.setWritable(true, true)) {
                throw new IOException("Cannot protect the output file; use app private storage");
            }
            ready = true;
        } finally {
            if (!ready && !file.delete()) {
                throw new IOException("Cannot remove an unprotected empty output file: " + file);
            }
        }
    }

    static void copy(File source, File destination, ApkOperationCallback callback) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long completed = 0;
        try (FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(callback);
                output.write(buffer, 0, count);
                completed += count;
                callback.onProgress("save", completed, source.length());
            }
            output.getFD().sync();
        }
        check(callback);
    }

    static IOException delete(File file, Throwable failure) {
        if (file == null || !file.exists() || file.delete()) return null;
        IOException cleanup = new IOException("Unable to remove temporary APK file: " + file);
        if (failure != null) failure.addSuppressed(cleanup);
        return cleanup;
    }

    /** Keeps apksig's random access and digest reads cancellable, including slices and parallel reads. */
    static final class Source implements DataSource {
        private final DataSource delegate;
        private final ApkOperationCallback callback;
        private final String phase;

        Source(DataSource delegate, ApkOperationCallback callback, String phase) {
            this.delegate = delegate;
            this.callback = callback;
            this.phase = phase;
        }

        @Override public long size() { return delegate.size(); }

        @Override public void feed(long offset, long size, DataSink sink) throws IOException {
            if (offset < 0 || size < 0 || offset > size() || size > size() - offset) {
                throw new IndexOutOfBoundsException("Invalid APK data range");
            }
            long remaining = size;
            while (remaining > 0) {
                check(callback);
                int count = (int) Math.min(BUFFER_SIZE, remaining);
                delegate.feed(offset, count, sink);
                offset += count;
                remaining -= count;
            }
            check(callback);
        }

        @Override public ByteBuffer getByteBuffer(long offset, int size) throws IOException {
            check(callback);
            if (size > MAX_METADATA_SIZE) {
                throw new IOException("APK metadata exceeds the supported 32 MiB safety limit");
            }
            ByteBuffer result = delegate.getByteBuffer(offset, size);
            check(callback);
            return result;
        }

        @Override public void copyTo(long offset, int size, ByteBuffer destination) throws IOException {
            if (offset < 0 || size < 0 || offset > size() || size > size() - offset
                    || size > destination.remaining()) {
                throw new IndexOutOfBoundsException("Invalid APK data range");
            }
            int remaining = size;
            while (remaining > 0) {
                check(callback);
                int count = Math.min(BUFFER_SIZE, remaining);
                delegate.copyTo(offset, count, destination);
                remaining -= count;
                offset += count;
            }
        }

        @Override public DataSource slice(long offset, long size) {
            return new Source(delegate.slice(offset, size), callback, phase);
        }
    }

    static final class Sink implements DataSink {
        private final DataSink delegate;
        private final ApkOperationCallback callback;
        private final String phase;
        private long completed;

        Sink(DataSink delegate, ApkOperationCallback callback, String phase) {
            this.delegate = delegate;
            this.callback = callback;
            this.phase = phase;
        }

        @Override public void consume(byte[] buffer, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > buffer.length || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            while (length > 0) {
                check(callback);
                int count = Math.min(length, BUFFER_SIZE);
                delegate.consume(buffer, offset, count);
                offset += count;
                length -= count;
                completed += count;
                callback.onProgress(phase, completed, -1);
            }
        }

        @Override public void consume(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) {
                check(callback);
                int count = Math.min(buffer.remaining(), BUFFER_SIZE);
                ByteBuffer chunk = buffer.slice();
                chunk.limit(count);
                delegate.consume(chunk);
                buffer.position(buffer.position() + count);
                completed += count;
                callback.onProgress(phase, completed, -1);
            }
        }
    }
}
