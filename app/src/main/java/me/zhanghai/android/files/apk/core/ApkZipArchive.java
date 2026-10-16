/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded ZIP32 validation and raw-entry alignment. Compressed entry bytes are never recompressed.
 * APK signing blocks disappear when the local records and central directory are rewritten.
 */
final class ApkZipArchive {
    private static final long UINT32_MAX = 0xffffffffL;
    private static final long MAX_EXPANDED_SIZE = 8L * 1024 * 1024 * 1024;
    private static final int ALIGNMENT_EXTRA_ID = 0xd935;
    private static final int NATIVE_ALIGNMENT = 16 * 1024;

    final List<Entry> entries;
    private final byte[] endRecord;
    private final long expandedSize;

    private ApkZipArchive(List<Entry> entries, byte[] endRecord, long expandedSize) {
        this.entries = entries;
        this.endRecord = endRecord;
        this.expandedSize = expandedSize;
    }

    static ApkZipArchive inspect(File file, ApkOperationCallback callback) throws IOException {
        ApkIo.check(callback);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length < 22 || length >= UINT32_MAX) {
                throw new IOException("Only standalone ZIP32 APKs smaller than 4 GiB are supported");
            }
            int tailSize = (int) Math.min(length, 65535 + 22);
            byte[] tail = new byte[tailSize];
            input.seek(length - tailSize);
            input.readFully(tail);
            int endOffset = -1;
            for (int i = tail.length - 22; i >= 0; --i) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
                    endOffset = i;
                    break;
                }
            }
            if (endOffset == -1) throw new IOException("Malformed APK: ZIP end record is missing");
            byte[] end = Arrays.copyOfRange(tail, endOffset, tail.length);
            long endFileOffset = length - tailSize + endOffset;
            int count = u16(end, 10);
            long centralSize = u32(end, 12);
            long centralOffset = u32(end, 16);
            if (u16(end, 4) != 0 || u16(end, 6) != 0 || u16(end, 8) != count
                    || count == 65535 || centralSize == UINT32_MAX || centralOffset == UINT32_MAX) {
                throw new IOException("Split ZIP and ZIP64 APKs are not supported");
            }
            if (count == 0 || centralSize > ApkIo.MAX_METADATA_SIZE
                    || centralOffset > endFileOffset || centralSize != endFileOffset - centralOffset) {
                throw new IOException("Malformed or excessively large APK central directory");
            }
            List<Entry> entries = new ArrayList<>(count);
            Set<String> names = new HashSet<>();
            long expanded = 0;
            long position = centralOffset;
            for (int i = 0; i < count; ++i) {
                ApkIo.check(callback);
                if (position > endFileOffset - 46) throw new IOException("Truncated APK directory");
                input.seek(position);
                byte[] header = new byte[46];
                input.readFully(header);
                if (u32(header, 0) != 0x02014b50L) throw new IOException("Invalid APK directory entry");
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int commentLength = u16(header, 32);
                long nextPosition = position + 46L + nameLength + extraLength + commentLength;
                if (nextPosition > endFileOffset) throw new IOException("Truncated APK entry metadata");
                byte[] nameBytes = new byte[nameLength];
                input.readFully(nameBytes);
                String name = decodeName(nameBytes);
                checkName(name);
                if (!names.add(name)) throw new IOException("APK contains duplicate entry: " + name);
                byte[] extra = new byte[extraLength];
                input.readFully(extra);
                byte[] comment = new byte[commentLength];
                input.readFully(comment);
                int flags = u16(header, 8);
                int method = u16(header, 10);
                long compressedSize = u32(header, 20);
                long size = u32(header, 24);
                long localOffset = u32(header, 42);
                if ((flags & (1 | 64 | 8192)) != 0 || (method != 0 && method != 8)) {
                    throw new IOException("APK has an encrypted or unsupported ZIP entry: " + name);
                }
                if (u16(header, 34) != 0 || compressedSize == UINT32_MAX || size == UINT32_MAX
                        || localOffset == UINT32_MAX) {
                    throw new IOException("ZIP64 or split APK entry is unsupported: " + name);
                }
                if ((u16(header, 4) >>> 8) == 3 && ((u32(header, 38) >>> 16) & 0170000) == 0120000) {
                    throw new IOException("APK contains a symbolic link: " + name);
                }
                if (method == 0 && compressedSize != size) {
                    throw new IOException("Invalid stored APK entry length: " + name);
                }
                if (name.endsWith("/") && size != 0) {
                    throw new IOException("APK directory contains unexpected data: " + name);
                }
                expanded += size;
                if (expanded > MAX_EXPANDED_SIZE) {
                    throw new IOException("APK expanded size exceeds the supported 8 GiB safety limit");
                }
                Entry entry = new Entry(name, nameBytes, header, extra, comment,
                        flags, method, u32(header, 16), compressedSize, size, localOffset);
                entry.readLocal(input, centralOffset);
                entries.add(entry);
                position = nextPosition;
            }
            if (position != endFileOffset) throw new IOException("APK directory length is inconsistent");
            if (!names.contains("AndroidManifest.xml")) {
                throw new IOException("Input is not a standalone APK: AndroidManifest.xml is missing");
            }
            List<Entry> byOffset = new ArrayList<>(entries);
            Collections.sort(byOffset, Comparator.comparingLong(entry -> entry.localOffset));
            long endOfPrevious = 0;
            for (Entry entry : byOffset) {
                if (entry.localOffset < endOfPrevious) {
                    throw new IOException("APK contains overlapping ZIP entries: " + entry.name);
                }
                endOfPrevious = entry.recordEnd;
            }
            return new ApkZipArchive(entries, end, expanded);
        }
    }

    void validateContents(File file, ApkOperationCallback callback) throws IOException {
        byte[] buffer = new byte[ApkIo.BUFFER_SIZE];
        long completed = 0;
        try (ZipFile zip = new ZipFile(file)) {
            for (Entry entry : entries) {
                ApkIo.check(callback);
                ZipEntry zipEntry = zip.getEntry(entry.name);
                if (zipEntry == null || zipEntry.getSize() != entry.size
                        || zipEntry.getCompressedSize() != entry.compressedSize
                        || zipEntry.getCrc() != entry.crc || zipEntry.getMethod() != entry.method) {
                    throw new IOException("Ambiguous ZIP entry: " + entry.name);
                }
                CRC32 crc = new CRC32();
                long actualSize = 0;
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ApkIo.check(callback);
                        actualSize += count;
                        if (actualSize > entry.size) {
                            throw new IOException("APK entry expands beyond its declared size: " + entry.name);
                        }
                        crc.update(buffer, 0, count);
                        completed += count;
                        callback.onProgress("inspect", completed, expandedSize);
                    }
                }
                if (actualSize != entry.size || crc.getValue() != entry.crc) {
                    throw new IOException("APK entry data is corrupt: " + entry.name);
                }
            }
        }
        ApkIo.check(callback);
    }

    void writeAligned(File source, File destination, ApkOperationCallback callback) throws IOException {
        long compressedTotal = 0;
        for (Entry entry : entries) compressedTotal += entry.compressedSize;
        byte[] buffer = new byte[ApkIo.BUFFER_SIZE];
        long completed = 0;
        try (RandomAccessFile input = new RandomAccessFile(source, "r");
                RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
            output.setLength(0);
            for (Entry entry : entries) {
                ApkIo.check(callback);
                entry.outputOffset = output.getFilePointer();
                byte[] extra = cleanExtra(entry.localExtra);
                if (entry.method == 0) {
                    int alignment = alignment(entry.name);
                    long dataOffset = entry.outputOffset + 30 + entry.nameBytes.length + extra.length + 6;
                    int padding = (int) ((alignment - dataOffset % alignment) % alignment);
                    byte[] aligned = Arrays.copyOf(extra, extra.length + 6 + padding);
                    put16(aligned, extra.length, ALIGNMENT_EXTRA_ID);
                    put16(aligned, extra.length + 2, 2 + padding);
                    put16(aligned, extra.length + 4, alignment);
                    extra = aligned;
                }
                if (extra.length > 65535) throw new IOException("APK entry has no room for ZIP alignment");
                byte[] local = entry.localHeader.clone();
                put16(local, 6, entry.flags & ~8);
                put32(local, 14, entry.crc);
                put32(local, 18, entry.compressedSize);
                put32(local, 22, entry.size);
                put16(local, 28, extra.length);
                output.write(local);
                output.write(entry.nameBytes);
                output.write(extra);
                input.seek(entry.dataOffset);
                long remaining = entry.compressedSize;
                while (remaining > 0) {
                    ApkIo.check(callback);
                    int count = (int) Math.min(remaining, buffer.length);
                    input.readFully(buffer, 0, count);
                    output.write(buffer, 0, count);
                    remaining -= count;
                    completed += count;
                    callback.onProgress("align", completed, compressedTotal);
                }
                if (output.getFilePointer() >= UINT32_MAX) {
                    throw new IOException("Aligned APK exceeds the ZIP32 size limit");
                }
            }
            long centralOffset = output.getFilePointer();
            for (Entry entry : entries) {
                ApkIo.check(callback);
                byte[] central = entry.centralHeader.clone();
                byte[] extra = cleanExtra(entry.centralExtra);
                put16(central, 8, entry.flags & ~8);
                put16(central, 30, extra.length);
                put32(central, 42, entry.outputOffset);
                output.write(central);
                output.write(entry.nameBytes);
                output.write(extra);
                output.write(entry.comment);
            }
            long centralSize = output.getFilePointer() - centralOffset;
            byte[] end = endRecord.clone();
            put32(end, 12, centralSize);
            put32(end, 16, centralOffset);
            output.write(end);
            if (output.length() >= UINT32_MAX) throw new IOException("Aligned APK exceeds the ZIP32 size limit");
            output.getFD().sync();
        }
        ApkIo.check(callback);
    }

    void requireAligned() throws IOException {
        for (Entry entry : entries) {
            if (entry.method == 0 && entry.dataOffset % alignment(entry.name) != 0) {
                throw new IOException("Signed APK ZIP alignment is invalid: " + entry.name);
            }
        }
    }

    private static int alignment(String name) { return name.endsWith(".so") ? NATIVE_ALIGNMENT : 4; }

    private static String decodeName(byte[] name) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("APK entry name is not valid UTF-8", exception);
        }
    }

    private static void checkName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') != -1
                || name.indexOf(':') != -1 || name.indexOf('\0') != -1) {
            throw new IOException("APK contains an unsafe entry path");
        }
        String[] segments = name.split("/", -1);
        for (int i = 0; i < segments.length; ++i) {
            String segment = segments[i];
            if (segment.equals(".") || segment.equals("..")
                    || (segment.isEmpty() && i != segments.length - 1)) {
                throw new IOException("APK contains an unsafe entry path: " + name);
            }
        }
    }

    /** Discard both legacy zero padding and Android alignment fields before adding our own. */
    private static byte[] cleanExtra(byte[] extra) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(extra.length);
        int offset = 0;
        while (offset + 4 <= extra.length) {
            int id = u16(extra, offset);
            int size = u16(extra, offset + 2);
            if (offset + 4 + size > extra.length) throw new IOException("Malformed APK ZIP extra field");
            if (id == 1) throw new IOException("ZIP64 APK entries are not supported");
            if (id != 0 && id != ALIGNMENT_EXTRA_ID) result.write(extra, offset, 4 + size);
            offset += 4 + size;
        }
        // zipalign's older padding format may end in one to three zero bytes.
        for (; offset < extra.length; ++offset) {
            if (extra[offset] != 0) throw new IOException("Malformed APK ZIP extra field padding");
        }
        return result.toByteArray();
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return u16(bytes, offset) | ((long) u16(bytes, offset + 2) << 16);
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, long value) throws IOException {
        if (value < 0 || value > UINT32_MAX) throw new IOException("APK exceeds ZIP32 size limits");
        put16(bytes, offset, (int) value);
        put16(bytes, offset + 2, (int) (value >>> 16));
    }

    static final class Entry {
        final String name;
        final byte[] nameBytes;
        final byte[] centralHeader;
        final byte[] centralExtra;
        final byte[] comment;
        final int flags;
        final int method;
        final long crc;
        final long compressedSize;
        final long size;
        final long localOffset;
        byte[] localHeader;
        byte[] localExtra;
        long dataOffset;
        long recordEnd;
        long outputOffset;

        Entry(String name, byte[] nameBytes, byte[] centralHeader, byte[] centralExtra, byte[] comment,
                int flags, int method, long crc, long compressedSize, long size, long localOffset) {
            this.name = name;
            this.nameBytes = nameBytes;
            this.centralHeader = centralHeader;
            this.centralExtra = centralExtra;
            this.comment = comment;
            this.flags = flags;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localOffset = localOffset;
        }

        void readLocal(RandomAccessFile input, long centralOffset) throws IOException {
            if (localOffset > centralOffset - 30) throw new IOException("Invalid APK local entry offset");
            input.seek(localOffset);
            localHeader = new byte[30];
            input.readFully(localHeader);
            if (u32(localHeader, 0) != 0x04034b50L || u16(localHeader, 6) != flags
                    || u16(localHeader, 8) != method || u16(localHeader, 26) != nameBytes.length) {
                throw new IOException("APK local and central entry headers disagree: " + name);
            }
            dataOffset = localOffset + 30L + nameBytes.length + u16(localHeader, 28);
            if (dataOffset > centralOffset || compressedSize > centralOffset - dataOffset) {
                throw new IOException("APK entry data is outside the ZIP data section: " + name);
            }
            byte[] localName = new byte[nameBytes.length];
            input.readFully(localName);
            if (!Arrays.equals(localName, nameBytes)) throw new IOException("Ambiguous APK entry name");
            localExtra = new byte[u16(localHeader, 28)];
            input.readFully(localExtra);
            cleanExtra(localExtra);
            cleanExtra(centralExtra);
            if ((flags & 8) == 0) {
                if (u32(localHeader, 14) != crc || u32(localHeader, 18) != compressedSize
                        || u32(localHeader, 22) != size) {
                    throw new IOException("APK entry size or checksum headers disagree: " + name);
                }
            } else if ((u32(localHeader, 14) != 0 && u32(localHeader, 14) != crc)
                    || (u32(localHeader, 18) != 0 && u32(localHeader, 18) != compressedSize)
                    || (u32(localHeader, 22) != 0 && u32(localHeader, 22) != size)) {
                throw new IOException("APK data descriptor headers disagree: " + name);
            }
            recordEnd = dataOffset + compressedSize;
            if ((flags & 8) != 0) {
                if (recordEnd > centralOffset - 12) throw new IOException("Missing APK data descriptor");
                input.seek(recordEnd);
                byte[] descriptor = new byte[16];
                input.readFully(descriptor, 0, 12);
                int start = 0;
                if (!(u32(descriptor, 0) == crc && u32(descriptor, 4) == compressedSize
                        && u32(descriptor, 8) == size) && u32(descriptor, 0) == 0x08074b50L) {
                    if (recordEnd > centralOffset - 16) throw new IOException("Truncated APK descriptor");
                    input.readFully(descriptor, 12, 4);
                    start = 4;
                }
                if (u32(descriptor, start) != crc || u32(descriptor, start + 4) != compressedSize
                        || u32(descriptor, start + 8) != size) {
                    throw new IOException("APK data descriptor checksum or length is inconsistent: " + name);
                }
                recordEnd += start + 12;
            }
        }
    }
}
