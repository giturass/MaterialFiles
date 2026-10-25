package me.zhanghai.android.files.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A virtual WAV header: the samples are decoded on demand, never written to disk. */
public final class BassWaveHeader {
    private BassWaveHeader() {}

    public static byte[] create(int sampleRate, int channels, long pcmBytes) {
        if (sampleRate <= 0 || channels <= 0 || channels > 32 || pcmBytes < 0
                || pcmBytes % (channels * 4) != 0 || pcmBytes > Long.MAX_VALUE - 80) {
            throw new IllegalArgumentException("Invalid decoded audio dimensions");
        }
        boolean rf64 = pcmBytes > 0xFFFFFFFFL - 36;
        int headerSize = rf64 ? 80 : 44;
        ByteBuffer buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        putId(buffer, rf64 ? "RF64" : "RIFF");
        buffer.putInt(rf64 ? -1 : (int) (pcmBytes + headerSize - 8));
        putId(buffer, "WAVE");
        if (rf64) {
            putId(buffer, "ds64");
            buffer.putInt(28);
            buffer.putLong(pcmBytes + headerSize - 8);
            buffer.putLong(pcmBytes);
            buffer.putLong(pcmBytes / (channels * 4));
            buffer.putInt(0);
        }
        putId(buffer, "fmt ");
        buffer.putInt(16);
        buffer.putShort((short) 3); // IEEE float, BASS_SAMPLE_FLOAT.
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(Math.multiplyExact(sampleRate, channels * 4));
        buffer.putShort((short) (channels * 4));
        buffer.putShort((short) 32);
        putId(buffer, "data");
        buffer.putInt(rf64 ? -1 : (int) pcmBytes);
        return buffer.array();
    }

    private static void putId(ByteBuffer buffer, String id) {
        buffer.put(id.getBytes(StandardCharsets.US_ASCII));
    }
}
