package me.zhanghai.android.files.media;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;

public class BassPcmReaderTest {
    @Test public void floatWavHeaderDescribesStereoPcm() {
        ByteBuffer header = ByteBuffer.wrap(BassWaveHeader.create(48000, 2, 384000))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(header.array(), 0, 4));
        assertEquals(384036, header.getInt(4));
        assertEquals(3, header.getShort(20));
        assertEquals(2, header.getShort(22));
        assertEquals(48000, header.getInt(24));
        assertEquals(384000, header.getInt(28));
        assertEquals(8, header.getShort(32));
        assertEquals(32, header.getShort(34));
    }

    @Test public void rf64KeepsLongDecodedSizes() {
        long pcm = 5L * 1024 * 1024 * 1024;
        ByteBuffer header = ByteBuffer.wrap(BassWaveHeader.create(192000, 8, pcm))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(80, header.capacity());
        assertEquals("RF64", new String(header.array(), 0, 4));
        assertEquals(-1, header.getInt(4));
        assertEquals("ds64", new String(header.array(), 12, 4));
        assertEquals(pcm + 72, header.getLong(20));
        assertEquals(pcm, header.getLong(28));
        assertEquals(pcm / 32, header.getLong(36));
        assertEquals(-1, header.getInt(76));
    }

    @Test public void smallReadsPreserveEverySampleByte() throws Exception {
        FakeDecoder decoder = new FakeDecoder(6, 10000);
        BassPcmReader reader = new BassPcmReader(decoder);
        assertEquals(44 + decoder.data.length, reader.open(0, -1));
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        byte[] part = new byte[7];
        int count;
        while ((count = reader.read(part, 0, part.length)) != -1) actual.write(part, 0, count);
        byte[] expectedHeader = BassWaveHeader.create(48000, 6, decoder.data.length);
        byte[] expected = new byte[expectedHeader.length + decoder.data.length];
        System.arraycopy(expectedHeader, 0, expected, 0, expectedHeader.length);
        System.arraycopy(decoder.data, 0, expected, expectedHeader.length, decoder.data.length);
        assertArrayEquals(expected, actual.toByteArray());
        assertEquals(0, reader.read(part, 0, 0));
    }

    @Test public void seekInsideFrameAndBoundedRange() throws Exception {
        FakeDecoder decoder = new FakeDecoder(6, 100);
        BassPcmReader reader = new BassPcmReader(decoder);
        assertEquals(13, reader.open(44 + 49, 13));
        byte[] target = new byte[13];
        assertEquals(13, reader.read(target, 0, 13));
        assertArrayEquals(Arrays.copyOfRange(decoder.data, 49, 62), target);
        assertEquals(48, decoder.lastSeek);
        assertEquals(-1, reader.read(target, 0, 1));
    }

    @Test public void loopReopensAtHeaderAndFirstFrame() throws Exception {
        FakeDecoder decoder = new FakeDecoder(2, 32);
        BassPcmReader reader = new BassPcmReader(decoder);
        byte[] part = new byte[44];
        reader.open(44 + 16, -1);
        reader.read(part, 0, 13);
        reader.open(0, -1);
        assertEquals(44, reader.read(part, 0, part.length));
        assertArrayEquals(BassWaveHeader.create(48000, 2, decoder.data.length), part);
        assertEquals(8, reader.read(part, 0, 8));
        assertArrayEquals(Arrays.copyOf(decoder.data, 8), Arrays.copyOf(part, 8));
    }

    @Test public void eofSeekDoesNotCallDecoderSeek() throws Exception {
        FakeDecoder decoder = new FakeDecoder(2, 32);
        BassPcmReader reader = new BassPcmReader(decoder);
        assertEquals(0, reader.open(44 + decoder.data.length, -1));
        assertEquals(-1, reader.read(new byte[1], 0, 1));
        assertEquals(-1, decoder.lastSeek);
    }

    @Test(expected = EOFException.class) public void truncatedDecodeIsReported() throws Exception {
        FakeDecoder decoder = new FakeDecoder(2, 32) {
            @Override public int read(ByteBuffer buffer) { return -1; }
        };
        BassPcmReader reader = new BassPcmReader(decoder);
        reader.open(44, -1);
        reader.read(new byte[8], 0, 8);
    }

    @Test(expected = IOException.class) public void outOfRangeSeekIsRejected() throws Exception {
        new BassPcmReader(new FakeDecoder(2, 32)).open(Long.MAX_VALUE, -1);
    }

    @Test(expected = IllegalArgumentException.class) public void malformedFramesAreRejected() {
        BassWaveHeader.create(48000, 6, 25);
    }

    @Test public void closeReleasesDecoder() throws Exception {
        FakeDecoder decoder = new FakeDecoder(2, 32);
        new BassPcmReader(decoder).close();
        assertTrue(decoder.closed);
    }

    private static class FakeDecoder implements BassPcmReader.Decoder {
        final byte[] data;
        final int channels;
        int position;
        long lastSeek = -1;
        boolean closed;
        FakeDecoder(int channels, int frames) {
            this.channels = channels;
            data = new byte[channels * 4 * frames];
            for (int i = 0; i < data.length; ++i) data[i] = (byte) (i * 17 + 3);
        }
        @Override public int sampleRate() { return 48000; }
        @Override public int channels() { return channels; }
        @Override public long length() { return data.length; }
        @Override public void seek(long alignedPcmBytePosition) {
            assertEquals(0, alignedPcmBytePosition % (channels * 4));
            position = (int) alignedPcmBytePosition;
            lastSeek = position;
        }
        @Override public int read(ByteBuffer buffer) {
            int size = Math.min(buffer.remaining(), data.length - position);
            if (size == 0) return -1;
            buffer.put(data, position, size);
            position += size;
            return size;
        }
        @Override public void close() { closed = true; }
    }
}
