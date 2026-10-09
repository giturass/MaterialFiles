/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java8.nio.channels.SeekableByteChannel;

import me.zhanghai.android.files.provider.archive.archiver.NativeSevenZip;

/** Exercises the APK's JNI binding and official engine on Android, without installing the app. */
public final class NativeSevenZipInteropTest {
    private static final String PASSWORD = "测试-password-🔐";
    private static final String NAME = "文档/秘密名称.txt";
    private static final byte[] CONTENT = "加密压缩互操作测试\nhello 7z\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> fail(failure));
        try {
            File fixtures = new File(args[0]);
            File output = new File(args[1]);
            require(output.isDirectory() || output.mkdirs(), "Cannot create output directory");
            for (String kind : new String[]{"headers", "contents", "copy"}) {
                File archive = new File(fixtures, "official-" + kind + ".7z");
                check("official " + kind + ": Unicode, data, empty entries, times and symlink",
                        () -> verifyOfficial(archive));
                for (String password : new String[]{null, "wrong-password"}) {
                    check("official " + kind + ": reject "
                                    + (password == null ? "missing" : "incorrect") + " password",
                            () -> expectIOException(() -> {
                                try (Channel channel = new Channel(archive, false);
                                     NativeSevenZip reader = new NativeSevenZip(channel, password)) {
                                    extract(reader, reader.readEntries(), false);
                                }
                            }));
                }
            }
            for (int preset = 0; preset < 3; ++preset) {
                final int selected = preset;
                for (String encryption : new String[]{"plain", "contents", "headers"}) {
                    String name = encryption + "-" + preset + ".7z";
                    check("create " + name + ": native round trip and input ownership",
                            () -> writeAndRead(new File(output, name), encryption, selected, false));
                }
            }
            check("short writes and short reads retain every byte",
                    () -> writeAndRead(new File(output, "short-writes.7z"), "headers", 0, true));
            check("zero-entry archive round trip", () -> emptyArchive(new File(output, "empty.7z")));
            check("solid batch reads less compressed input than separate extractions",
                    () -> compareBatch(new File(fixtures, "official-contents.7z")));
            check("caller keeps ownership of archive channels", () -> ownership(fixtures));
            check("provider read exception preserves identity", () -> readFailure(fixtures));
            check("provider write exception preserves identity", () -> writeFailure(output));
            check("source close exception aborts creation", () -> sourceCloseFailure(output));
            check("output callback failure aborts extraction", () -> outputFailure(fixtures));
            check("thread interruption aborts JNI and remains set", () -> cancellation(fixtures));
            check("decoder memory limit fails safely and later operations still work",
                    () -> memoryLimit(fixtures));
            check("reject non-increasing entry indices", () -> invalidIndices(fixtures));
            if (args.length > 2) {
                File old = new File(args[2]);
                for (String filename : new String[]{"contents.7z", "headers.7z", "sequential.7z"}) {
                    File archive = new File(old, filename);
                    if (archive.isFile()) {
                        check("read previous app output: " + filename, () -> verifyOld(archive));
                    }
                }
            }
            if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " / " + checks
                    + " native checks failed: " + FAILURES);
            System.out.println("PASS: " + checks + " native 7z checks on Android API "
                    + Build.VERSION.SDK_INT);
            System.exit(0);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void verifyOfficial(File file) throws Exception {
        try (Channel channel = new Channel(file, false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            NativeSevenZip.Entry[] entries = reader.readEntries();
            Map<String, byte[]> data = extract(reader, entries, false);
            require(Arrays.equals(data.get(NAME), CONTENT), "Unicode contents differ");
            require(data.containsKey("empty") && data.get("empty").length == 0, "Missing empty file");
            require(Arrays.equals(data.get("link"), NAME.getBytes(StandardCharsets.UTF_8)),
                    "Symlink target differs");
            require(Arrays.equals(data.get("large.bin"), pattern(1024 * 1024)), "Large file differs");
            NativeSevenZip.Entry text = find(entries, NAME);
            require(text.lastModifiedTime == 1700000000000L, "Modification time differs");
            require(text.hasAttributes && (text.attributes >>> 16 & 0777) == 0640,
                    "Unix permissions differ");
            require((find(entries, "link").attributes >>> 16 & 0170000) == 0120000,
                    "Symlink type differs");
            require(find(entries, "empty-directory").directory, "Missing empty directory");
        }
    }

    private static void writeAndRead(File file, String encryption, int preset, boolean shortIo)
            throws Exception {
        String password = "plain".equals(encryption) ? null : PASSWORD;
        NativeSevenZip.Entry[] entries = writerEntries();
        final int[] opened = {0};
        final int[] closed = {0};
        try (Channel channel = new Channel(file, true)) {
            if (shortIo) channel.maxWrite = 7;
            NativeSevenZip.create(channel, entries, new NativeSevenZip.CreateCallback() {
                @Override public InputStream openInput(int index) {
                    ++opened[0];
                    return new ByteArrayInputStream(writerBytes(entries[index])) {
                        private boolean done;
                        @Override public void close() {
                            require(!done, "Input closed twice");
                            done = true;
                            ++closed[0];
                        }
                    };
                }
                @Override public void onProgress(long completed) {
                    require(completed >= 0, "Negative progress");
                }
                @Override public void onResult(int index, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                }
            }, password, "headers".equals(encryption), new int[]{1, 5, 9}[preset],
                    new int[]{4, 16, 32}[preset] * 1024 * 1024, 2);
            require(channel.isOpen(), "Native writer closed caller's channel");
        }
        require(opened[0] > 0 && opened[0] == closed[0], "Writer leaked a source stream");
        try (Channel channel = new Channel(file, false)) {
            if (shortIo) channel.maxRead = 13;
            try (NativeSevenZip reader = new NativeSevenZip(channel, password)) {
                NativeSevenZip.Entry[] actual = reader.readEntries();
                Map<String, byte[]> data = extract(reader, actual, false);
                require(actual.length == entries.length, "Entry count differs");
                for (NativeSevenZip.Entry entry : entries) {
                    NativeSevenZip.Entry decoded = find(actual, entry.name);
                    require(decoded.directory == entry.directory, "Wrong directory flag");
                    require(decoded.lastModifiedTime == entry.lastModifiedTime, "Wrong write time");
                    if (!entry.directory) require(Arrays.equals(data.get(entry.name), writerBytes(entry)),
                            "Round-trip contents differ: " + entry.name);
                }
            }
        }
    }

    private static NativeSevenZip.Entry[] writerEntries() {
        String[] names = {"文档", "empty-directory", NAME, "empty", "large.bin", "link"};
        NativeSevenZip.Entry[] entries = new NativeSevenZip.Entry[names.length];
        for (int i = 0; i < names.length; ++i) {
            NativeSevenZip.Entry entry = new NativeSevenZip.Entry();
            entries[i] = entry;
            entry.index = i;
            entry.name = names[i];
            entry.directory = i < 2;
            entry.hasAttributes = true;
            int mode = entry.directory ? 0040750 : "link".equals(entry.name) ? 0120777 : 0100640;
            entry.attributes = mode << 16 | 0x8000 | (entry.directory ? 0x10 : 0x20);
            entry.lastModifiedTime = 1700000000000L;
            entry.size = writerBytes(entry).length;
        }
        return entries;
    }

    private static byte[] writerBytes(NativeSevenZip.Entry entry) {
        if (NAME.equals(entry.name)) return CONTENT;
        if ("large.bin".equals(entry.name)) return pattern(8 * 1024 * 1024);
        if ("link".equals(entry.name)) return NAME.getBytes(StandardCharsets.UTF_8);
        return new byte[0];
    }

    private static void emptyArchive(File file) throws Exception {
        try (Channel channel = new Channel(file, true)) {
            NativeSevenZip.create(channel, new NativeSevenZip.Entry[0], callback(), PASSWORD, true);
        }
        try (Channel channel = new Channel(file, false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            require(reader.readEntries().length == 0, "Empty archive gained entries");
        }
    }

    private static void compareBatch(File file) throws Exception {
        long batch;
        long separate;
        try (Channel channel = new Channel(file, false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            NativeSevenZip.Entry[] entries = reader.readEntries();
            channel.bytesRead = 0;
            extract(reader, entries, false);
            batch = channel.bytesRead;
            channel.bytesRead = 0;
            for (NativeSevenZip.Entry entry : entries) {
                if (!entry.directory && entry.size > 0) {
                    extract(reader, new NativeSevenZip.Entry[]{entry}, false);
                }
            }
            separate = channel.bytesRead;
        }
        require(batch > 0 && separate > batch, "Batch did not reduce compressed reads: "
                + batch + " versus " + separate);
        System.out.println("  Compressed bytes read: batch=" + batch + ", separate=" + separate);
    }

    private static void ownership(File fixtures) throws Exception {
        try (Channel channel = new Channel(new File(fixtures, "official-contents.7z"), false)) {
            NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD);
            reader.readEntries();
            reader.close();
            reader.close();
            require(channel.isOpen(), "Reader closed caller's channel");
            expectIOException(reader::readEntries);
        }
    }

    private static void readFailure(File fixtures) throws Exception {
        final IOException expected = new IOException("injected native read failure");
        try (Channel channel = new Channel(new File(fixtures, "official-contents.7z"), false)) {
            channel.readFailure = expected;
            IOException caught = expectIOException(() -> {
                try (NativeSevenZip ignored = new NativeSevenZip(channel, PASSWORD)) { }
            });
            require(caught == expected, "Provider read exception lost identity: " + caught);
        }
    }

    private static void writeFailure(File output) throws Exception {
        final IOException expected = new IOException("injected native write failure");
        try (Channel channel = new Channel(new File(output, "failed-write.7z"), true)) {
            channel.writeFailure = expected;
            IOException caught = expectIOException(() -> NativeSevenZip.create(channel,
                    writerEntries(), callback(), PASSWORD, true));
            require(caught == expected, "Provider write exception lost identity: " + caught);
        }
    }

    private static void sourceCloseFailure(File output) throws Exception {
        final IOException expected = new IOException("injected native source close failure");
        NativeSevenZip.Entry entry = writerEntries()[2];
        entry.index = 0;
        try (Channel channel = new Channel(new File(output, "failed-source-close.7z"), true)) {
            IOException caught = expectIOException(() -> NativeSevenZip.create(channel,
                    new NativeSevenZip.Entry[]{entry}, new NativeSevenZip.CreateCallback() {
                        @Override public InputStream openInput(int index) {
                            return new ByteArrayInputStream(CONTENT) {
                                @Override public void close() throws IOException { throw expected; }
                            };
                        }
                        @Override public void onProgress(long completed) { }
                        @Override public void onResult(int index, IOException failure)
                                throws IOException { if (failure != null) throw failure; }
                    }, PASSWORD, true));
            require(caught == expected, "Source close exception was replaced: " + caught);
        }
    }

    private static void outputFailure(File fixtures) throws Exception {
        final IOException expected = new IOException("injected extraction output failure");
        try (Channel channel = new Channel(new File(fixtures, "official-contents.7z"), false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            int index = find(reader.readEntries(), NAME).index;
            IOException caught = expectIOException(() -> reader.extract(new int[]{index},
                    new NativeSevenZip.ExtractCallback() {
                        @Override public OutputStream openOutput(int selected) {
                            return new OutputStream() {
                                @Override public void write(int value) throws IOException { throw expected; }
                                @Override public void write(byte[] bytes, int offset, int length)
                                        throws IOException { throw expected; }
                            };
                        }
                        @Override public void onResult(int selected, IOException failure)
                                throws IOException { if (failure != null) throw failure; }
                    }, false));
            require(caught == expected, "Output exception lost identity: " + caught);
        }
    }

    private static void cancellation(File fixtures) throws Exception {
        try (Channel channel = new Channel(new File(fixtures, "official-contents.7z"), false)) {
            Thread.currentThread().interrupt();
            try {
                IOException caught = expectIOException(() -> {
                    try (NativeSevenZip ignored = new NativeSevenZip(channel, PASSWORD)) { }
                });
                require(caught instanceof InterruptedIOException, "Interruption changed type");
                require(Thread.currentThread().isInterrupted(), "Native cleared interruption");
            } finally { Thread.interrupted(); }
        }
    }

    private static void memoryLimit(File fixtures) throws Exception {
        File file = new File(fixtures, "official-contents.7z");
        try (Channel channel = new Channel(file, false)) {
            expectIOException(() -> {
                try (NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD, 256 * 1024)) {
                    extract(reader, reader.readEntries(), true);
                }
            });
        }
        verifyOfficial(file);
    }

    private static void invalidIndices(File fixtures) throws Exception {
        try (Channel channel = new Channel(new File(fixtures, "official-contents.7z"), false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            try {
                reader.extract(new int[]{1, 0}, new NativeSevenZip.ExtractCallback() {
                    @Override public OutputStream openOutput(int index) { throw new AssertionError(); }
                    @Override public void onResult(int index, IOException failure) { }
                }, true);
                throw new AssertionError("Accepted unsorted indices");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void verifyOld(File file) throws Exception {
        try (Channel channel = new Channel(file, false);
             NativeSevenZip reader = new NativeSevenZip(channel, PASSWORD)) {
            Map<String, byte[]> data = extract(reader, reader.readEntries(), false);
            require(Arrays.equals(data.get(NAME), CONTENT), "Previous writer's content differs");
            if (data.containsKey("large.bin")) {
                require(Arrays.equals(data.get("large.bin"), pattern(16 * 1024 * 1024)),
                        "Previous writer's large stream differs");
            }
        }
    }

    private static NativeSevenZip.CreateCallback callback() {
        NativeSevenZip.Entry[] entries = writerEntries();
        return new NativeSevenZip.CreateCallback() {
            @Override public InputStream openInput(int index) {
                return new ByteArrayInputStream(writerBytes(entries[index]));
            }
            @Override public void onProgress(long completed) { }
            @Override public void onResult(int index, IOException failure) throws IOException {
                if (failure != null) throw failure;
            }
        };
    }

    private static Map<String, byte[]> extract(NativeSevenZip reader, NativeSevenZip.Entry[] entries,
                                               boolean test) throws IOException {
        int[] indices = new int[entries.length];
        Map<Integer, NativeSevenZip.Entry> byIndex = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; ++i) {
            indices[i] = entries[i].index;
            byIndex.put(entries[i].index, entries[i]);
        }
        Arrays.sort(indices);
        Map<Integer, ByteArrayOutputStream> streams = new LinkedHashMap<>();
        Map<String, byte[]> output = new LinkedHashMap<>();
        reader.extract(indices, new NativeSevenZip.ExtractCallback() {
            @Override public OutputStream openOutput(int index) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                streams.put(index, stream);
                return stream;
            }
            @Override public void onResult(int index, IOException failure) throws IOException {
                if (failure != null) throw failure;
                ByteArrayOutputStream stream = streams.get(index);
                if (stream != null) {
                    output.put(byIndex.get(index).name, stream.toByteArray());
                    stream.close();
                }
            }
        }, test);
        return output;
    }

    private static NativeSevenZip.Entry find(NativeSevenZip.Entry[] entries, String name) {
        for (NativeSevenZip.Entry entry : entries) if (name.equals(entry.name)) return entry;
        throw new AssertionError("Missing entry: " + name);
    }

    private static byte[] pattern(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; ++i) bytes[i] = (byte) (i * 37);
        return bytes;
    }

    private static final class Channel implements SeekableByteChannel {
        final RandomAccessFile file;
        int maxRead = Integer.MAX_VALUE;
        int maxWrite = Integer.MAX_VALUE;
        long bytesRead;
        IOException readFailure;
        IOException writeFailure;
        private boolean open = true;

        Channel(File path, boolean write) throws IOException {
            file = new RandomAccessFile(path, write ? "rw" : "r");
            if (write) file.setLength(0);
        }
        @Override public int read(ByteBuffer target) throws IOException {
            if (readFailure != null) throw readFailure;
            int old = target.limit();
            target.limit(target.position() + Math.min(target.remaining(), maxRead));
            try {
                int count = file.getChannel().read(target);
                if (count > 0) bytesRead += count;
                return count;
            } finally { target.limit(old); }
        }
        @Override public int write(ByteBuffer source) throws IOException {
            if (writeFailure != null) throw writeFailure;
            int old = source.limit();
            source.limit(source.position() + Math.min(source.remaining(), maxWrite));
            try { return file.getChannel().write(source); }
            finally { source.limit(old); }
        }
        @Override public long position() throws IOException { return file.getFilePointer(); }
        @Override public SeekableByteChannel position(long position) throws IOException {
            file.seek(position); return this;
        }
        @Override public long size() throws IOException { return file.length(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            if (size < file.length()) file.setLength(size);
            return this;
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() throws IOException {
            if (open) { open = false; file.close(); }
        }
    }

    private interface Checked { void run() throws Exception; }
    private static IOException expectIOException(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return expected; }
        throw new AssertionError("Expected an IOException");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void check(String name, Checked action) {
        ++checks;
        try { action.run(); System.out.println("ok " + checks + " - " + name); }
        catch (Throwable failure) {
            Thread.interrupted();
            FAILURES.add(name);
            System.out.println("FAIL " + checks + " - " + name);
            failure.printStackTrace(System.out);
        }
    }
    private static void fail(Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
}
