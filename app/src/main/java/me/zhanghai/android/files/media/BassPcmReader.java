package me.zhanghai.android.files.media;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Handles arbitrary Media3 byte reads/seeks while the decoder works in complete PCM frames. */
public final class BassPcmReader implements Closeable {
    public interface Decoder extends Closeable {
        int sampleRate();
        int channels();
        long length();
        void seek(long alignedPcmBytePosition) throws IOException;
        int read(ByteBuffer buffer) throws IOException;
    }

    private final Decoder decoder;
    private final byte[] header;
    private final ByteBuffer buffer;
    private long position;
    private long remaining;
    private int skip;

    public BassPcmReader(Decoder decoder) {
        this.decoder = decoder;
        header = BassWaveHeader.create(decoder.sampleRate(), decoder.channels(), decoder.length());
        buffer = ByteBuffer.allocateDirect(4096 * decoder.channels() * 4);
        buffer.limit(0);
    }

    public long open(long position, long requestedLength) throws IOException {
        long total = header.length + decoder.length();
        if (position < 0 || position > total || requestedLength < -1) {
            throw new IOException("Audio position is outside the stream");
        }
        this.position = position;
        remaining = requestedLength == -1 ? total - position : Math.min(total - position, requestedLength);
        int frameBytes = decoder.channels() * 4;
        long pcmPosition = Math.max(0, position - header.length);
        skip = (int) (pcmPosition % frameBytes);
        if (pcmPosition < decoder.length()) {
            decoder.seek(pcmPosition - skip);
        }
        buffer.limit(0);
        return remaining;
    }

    public int read(byte[] target, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > target.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int count = (int) Math.min(length, remaining);
        if (position < header.length) {
            count = Math.min(count, header.length - (int) position);
            System.arraycopy(header, (int) position, target, offset, count);
        } else {
            if (!buffer.hasRemaining()) {
                buffer.clear();
                int decoded = decoder.read(buffer);
                if (decoded <= skip || decoded > buffer.capacity()) {
                    throw new EOFException("Decoder ended before the advertised audio length");
                }
                buffer.limit(decoded);
                buffer.position(skip);
                skip = 0;
            }
            count = Math.min(count, buffer.remaining());
            buffer.get(target, offset, count);
        }
        position += count;
        remaining -= count;
        return count;
    }

    @Override
    public void close() throws IOException { decoder.close(); }
}
