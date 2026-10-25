import android.os.Build;
import android.os.ParcelFileDescriptor;
import com.un4seen.bass.BASS;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import me.zhanghai.android.files.media.BassPcmReader;

/** Runs the official ARM64 JNI decoder and the application's PCM adapter on Android ART. */
public final class MediaBassAndroidTest {
    public static void main(String[] args) throws Exception {
        File fixtures = new File(args[0]);
        File libraries = new File(args[1]);
        System.out.println("ANDROID_RUNTIME api=" + Build.VERSION.SDK_INT);
        check(BASS.BASS_Init(0, 48000, 0), "BASS no-output initialization");
        try {
            for (String name : new String[]{"bassalac", "bassape", "basswv", "bassflac", "bassopus", "bassdsd"}) {
                check(BASS.BASS_PluginLoad(new File(libraries, "lib" + name + ".so").getPath(), 0) != 0,
                        "load " + name);
            }
            byte[] expected = Files.readAllBytes(new File(fixtures, "expected-f32.raw").toPath());
            for (String name : new String[]{"source.wav", "alac.m4a", "source.flac", "source.wv"}) {
                for (boolean descriptor : new boolean[]{false, true}) {
                    verifyFile(new File(fixtures, name), expected, descriptor);
                }
            }
            System.out.println("MEDIA_BASS_RESULT passed=8 formats=WAV,ALAC,FLAC,WavPack file_and_descriptor=true");
        } finally {
            BASS.BASS_Free();
        }
    }

    private static void verifyFile(File file, byte[] expected, boolean useDescriptor) throws Exception {
        int flags = BASS.BASS_STREAM_DECODE | BASS.BASS_SAMPLE_FLOAT;
        ParcelFileDescriptor descriptor = useDescriptor
                ? ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) : null;
        int stream = useDescriptor ? BASS.BASS_StreamCreateFile(descriptor, 0, 0, flags)
                : BASS.BASS_StreamCreateFile(file.getPath(), 0, 0, flags);
        try {
            check(stream != 0, "open " + file.getName());
            BASS.BASS_CHANNELINFO info = new BASS.BASS_CHANNELINFO();
            check(BASS.BASS_ChannelGetInfo(stream, info), "channel info");
            check(info.freq == 48000 && info.chans == 2, "source audio dimensions");
            long length = BASS.BASS_ChannelGetLength(stream, BASS.BASS_POS_BYTE);
            check(length == expected.length, "decoded length " + length);
            BassPcmReader reader = new BassPcmReader(new BassPcmReader.Decoder() {
                @Override public int sampleRate() { return info.freq; }
                @Override public int channels() { return info.chans; }
                @Override public long length() { return length; }
                @Override public void seek(long position) throws java.io.IOException {
                    if (!BASS.BASS_ChannelSetPosition(stream, position, BASS.BASS_POS_BYTE)) {
                        throw new java.io.IOException("BASS seek: " + BASS.BASS_ErrorGetCode());
                    }
                }
                @Override public int read(ByteBuffer buffer) {
                    return BASS.BASS_ChannelGetData(stream, buffer, buffer.capacity());
                }
                @Override public void close() {}
            });
            reader.open(44, -1);
            byte[] decoded = new byte[expected.length];
            int offset = 0;
            while (offset < decoded.length) {
                int count = reader.read(decoded, offset, Math.min(13, decoded.length - offset));
                check(count > 0, "read sample bytes");
                offset += count;
            }
            check(Arrays.equals(expected, decoded), "lossless float PCM samples");
            check(reader.read(new byte[1], 0, 1) == -1, "end of stream");
            for (int pcmOffset : new int[]{37, 240008, 0, expected.length - 19}) {
                reader.open(44L + pcmOffset, 11);
                byte[] part = new byte[11];
                int read = 0;
                while (read < part.length) {
                    int count = reader.read(part, read, part.length - read);
                    check(count > 0, "read after seek");
                    read += count;
                }
                check(Arrays.equals(Arrays.copyOfRange(expected, pcmOffset, pcmOffset + 11), part),
                        "seek " + pcmOffset);
            }
            reader.close();
            System.out.println("PASS " + file.getName() + " descriptor=" + useDescriptor + " bytes=" + length);
        } finally {
            if (stream != 0) BASS.BASS_StreamFree(stream);
            if (descriptor != null) descriptor.close();
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name + " (BASS " + BASS.BASS_ErrorGetCode() + ")");
    }
}
