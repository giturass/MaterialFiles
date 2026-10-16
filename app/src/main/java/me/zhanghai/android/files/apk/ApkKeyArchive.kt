/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import android.content.Context
import me.zhanghai.android.files.R
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Bounded ZIP32 import. Entry paths are metadata and are never used as extraction paths. */
internal object ApkKeyArchive {
    const val MAX_KEYS = 32
    const val MAX_KEY_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + 1024 * 1024
    private const val MAX_RECORDS = 128 // Includes harmless directory entries.
    private const val MAX_METADATA_BYTES = 1024 * 1024
    val KEY_EXTENSIONS = setOf("p12", "pfx", "pkcs12", "jks", "keystore")

    data class StagedKey(val file: File, val preview: ApkKeyImportEntry, val sha256: String)

    fun read(
        context: Context, source: File, sourceName: String, directory: File,
        checkCanceled: () -> Unit, reserveSpace: (Long) -> Unit
    ): List<StagedKey> {
        checkCanceled()
        val magic = if (source.length() >= 4) {
            DataInputStream(FileInputStream(source)).use { it.readInt() }
        } else 0
        val isZip = magic == 0x504B0304 || magic == 0x504B0506
            || sourceName.endsWith(".zip", ignoreCase = true)
        if (!isZip) return listOf(describe(context, source, sourceName, checkCanceled))
        val records = inspect(context, source, checkCanceled)
        reserveSpace(records.sumOf { it.size })
        val result = mutableListOf<StagedKey>()
        ZipFile(source).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (record in records) {
                checkCanceled()
                val entry = zip.getEntry(record.name) ?: invalid(context)
                if (entry.size != record.size || entry.compressedSize != record.compressedSize
                    || entry.crc != record.crc || entry.method != record.method) invalid(context)
                if (record.name.endsWith('/')) {
                    zip.getInputStream(entry).use { if (it.read() != -1) invalid(context) }
                    continue
                }
                // Controlled names also keep directory and symlink metadata from touching the FS.
                val output = File(directory, "import-key-${result.size}")
                var copied = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { stream ->
                        while (true) {
                            checkCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > record.size || copied > MAX_KEY_BYTES) invalid(context)
                            crc.update(buffer, 0, count)
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                if (copied != record.size || crc.value != record.crc) invalid(context)
                result += describe(context, output, record.name, checkCanceled)
            }
        }
        return result
    }

    fun describe(
        context: Context, file: File, name: String, checkCanceled: () -> Unit
    ): StagedKey {
        val size = file.length()
        if (size !in 4L..MAX_KEY_BYTES) limits(context)
        val format = DataInputStream(FileInputStream(file)).use {
            val magic = it.readInt()
            when {
                magic == 0xFEEDFEED.toInt() -> "JKS"
                magic ushr 24 == 0x30 -> "PKCS12"
                else -> throw IOException(context.getString(R.string.apk_key_operation_invalid_store, name))
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                checkCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > size) invalid(context)
                hash.update(buffer, 0, count)
            }
            if (copied != size) invalid(context)
        }
        val digest = hash.digest()
        hash.update(name.toByteArray(Charsets.UTF_8))
        hash.update(0.toByte())
        hash.update(digest)
        val id = hex(hash.digest())
        return StagedKey(file, ApkKeyImportEntry(id, name, format, size), hex(digest))
    }

    /** Stored entries avoid wasting time compressing already protected key-store bytes. */
    fun write(
        context: Context, keys: List<Pair<String, File>>, output: File, checkCanceled: () -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            for ((name, source) in keys) {
                checkCanceled()
                checkName(context, name)
                val crc = CRC32()
                FileInputStream(source).use { input ->
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                    }
                }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = source.length()
                    compressedSize = size
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                FileInputStream(source).use { input ->
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                // ZipOutputStream verifies both size and CRC for every stored entry.
                zip.closeEntry()
            }
        }
        if (output.length() > MAX_ARCHIVE_BYTES) limits(context)
    }

    private data class Record(
        val name: String, val method: Int, val size: Long, val compressedSize: Long,
        val crc: Long, val offset: Long, val end: Long
    )

    /** Bound and validate the central directory before ZipFile allocates entry objects. */
    private fun inspect(context: Context, file: File, checkCanceled: () -> Unit): List<Record> =
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length !in 22L..MAX_ARCHIVE_BYTES) invalid(context)
            val tail = ByteArray(minOf(length, 65535L + 22).toInt())
            input.seek(length - tail.size)
            input.readFully(tail)
            val end = (tail.size - 22 downTo 0).firstOrNull {
                u32(tail, it) == 0x06054B50L && it + 22 + u16(tail, it + 20) == tail.size
            } ?: invalid(context)
            val count = u16(tail, end + 10)
            val centralSize = u32(tail, end + 12)
            val centralOffset = u32(tail, end + 16)
            val endOffset = length - tail.size + end
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                || u16(tail, end + 8) != count || count !in 1..MAX_RECORDS
                || centralSize > MAX_METADATA_BYTES || centralOffset + centralSize != endOffset) {
                invalid(context)
            }
            val records = mutableListOf<Record>()
            val allRecords = mutableListOf<Record>()
            val names = mutableSetOf<String>()
            var total = 0L
            var position = centralOffset
            repeat(count) {
                checkCanceled()
                if (position > endOffset - 46) invalid(context)
                input.seek(position)
                val header = ByteArray(46).also(input::readFully)
                if (u32(header, 0) != 0x02014B50L) invalid(context)
                val nameSize = u16(header, 28)
                val extraSize = u16(header, 30)
                val next = position + 46 + nameSize + extraSize + u16(header, 32)
                if (nameSize !in 1..1024 || next > endOffset) invalid(context)
                val nameBytes = ByteArray(nameSize).also(input::readFully)
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes)).toString()
                checkName(context, name)
                if (!names.add(name.removeSuffix("/").lowercase())) invalid(context)
                checkExtra(context, ByteArray(extraSize).also(input::readFully))
                val flags = u16(header, 8)
                val method = u16(header, 10)
                val size = u32(header, 24)
                val compressed = u32(header, 20)
                val crc = u32(header, 16)
                val offset = u32(header, 42)
                val mode = (u32(header, 38) ushr 16).toInt() and 0xF000
                val directory = name.endsWith('/')
                val unix = (u16(header, 4) ushr 8) == 3
                val expectedMode = if (directory) 0x4000 else 0x8000
                if (flags and (1 or 64 or 8192) != 0 || method != 0 && method != 8
                    || u16(header, 34) != 0 || size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL
                    || offset == 0xFFFFFFFFL || method == 0 && size != compressed
                    || unix && mode != 0 && mode != expectedMode) invalid(context)
                if (directory) {
                    if (size != 0L || crc != 0L) invalid(context)
                } else {
                    if (name.substringAfterLast('.').lowercase() !in KEY_EXTENSIONS) {
                        throw IOException(context.getString(R.string.apk_key_operation_zip_entry_unsupported, name))
                    }
                    if (size !in 4L..MAX_KEY_BYTES || records.size >= MAX_KEYS) limits(context)
                    total += size
                    if (total > MAX_TOTAL_BYTES || size > 1024 * 1024L && size > compressed * 100) {
                        limits(context)
                    }
                }
                val recordEnd = checkLocal(context, input, centralOffset, nameBytes, flags, method,
                    crc, size, compressed, offset)
                val record = Record(name, method, size, compressed, crc, offset, recordEnd)
                allRecords += record
                if (!directory) records += record
                position = next
            }
            if (position != endOffset || records.isEmpty()) invalid(context)
            var previousEnd = 0L
            for (record in allRecords.sortedBy { it.offset }) {
                if (record.offset < previousEnd) invalid(context)
                previousEnd = record.end
            }
            if (records.any { fileRecord ->
                    names.any { it.startsWith(fileRecord.name.lowercase() + "/") }
                }) invalid(context)
            allRecords
        }

    private fun checkLocal(
        context: Context, input: RandomAccessFile, central: Long, name: ByteArray,
        flags: Int, method: Int, crc: Long, size: Long, compressed: Long, offset: Long
    ): Long {
        if (offset > central - 30) invalid(context)
        input.seek(offset)
        val header = ByteArray(30).also(input::readFully)
        if (u32(header, 0) != 0x04034B50L || u16(header, 6) != flags
            || u16(header, 8) != method || u16(header, 26) != name.size) invalid(context)
        val data = offset + 30 + name.size + u16(header, 28)
        if (data > central || compressed > central - data) invalid(context)
        if (!ByteArray(name.size).also(input::readFully).contentEquals(name)) invalid(context)
        checkExtra(context, ByteArray(u16(header, 28)).also(input::readFully))
        for ((field, value) in listOf(14 to crc, 18 to compressed, 22 to size)) {
            if (u32(header, field) != value && (flags and 8 == 0 || u32(header, field) != 0L)) {
                invalid(context)
            }
        }
        var end = data + compressed
        if (flags and 8 != 0) {
            if (end > central - 12) invalid(context)
            input.seek(end)
            val descriptor = ByteArray(16)
            input.readFully(descriptor, 0, 12)
            var start = 0
            if (!(u32(descriptor, 0) == crc && u32(descriptor, 4) == compressed
                    && u32(descriptor, 8) == size) && u32(descriptor, 0) == 0x08074B50L) {
                if (end > central - 16) invalid(context)
                input.readFully(descriptor, 12, 4)
                start = 4
            }
            if (u32(descriptor, start) != crc || u32(descriptor, start + 4) != compressed
                || u32(descriptor, start + 8) != size) invalid(context)
            end += start + 12
        }
        return end
    }

    private fun checkName(context: Context, name: String) {
        if (name.isEmpty() || name.startsWith('/') || name.any { it == '\\' || it == ':' || it.isISOControl() }
            || name.removeSuffix("/").split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            invalid(context)
        }
    }

    private fun checkExtra(context: Context, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (offset > bytes.size - 4) invalid(context)
            val id = u16(bytes, offset)
            val size = u16(bytes, offset + 2)
            if (id == 1 || offset + 4 + size > bytes.size) invalid(context) // No ZIP64.
            offset += 4 + size
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun invalid(context: Context): Nothing =
        throw IOException(context.getString(R.string.apk_key_operation_zip_invalid))

    fun limits(context: Context): Nothing =
        throw IOException(context.getString(R.string.apk_key_operation_batch_limits))
}
