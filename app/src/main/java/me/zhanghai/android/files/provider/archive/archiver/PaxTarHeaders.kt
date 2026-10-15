/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.attribute.FileTime
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.Instant

/** Supplements 7-Zip 26.04's TAR metadata; file contents are always extracted by JBinding. */
internal object PaxTarHeaders {
    data class Header(val typeFlag: Char, val size: Long, val properties: Map<String, String>)

    @Throws(IOException::class)
    fun read(channel: SeekableByteChannel, packedSizes: LongArray): List<Header> {
        val savedPosition = channel.position()
        var failure: Throwable? = null
        try {
            val sourceSize = channel.size()
            val headers = ArrayList<Header>(packedSizes.size)
            var position = 0L
            val global = mutableMapOf<String, String>()
            val local = mutableMapOf<String, String>()
            var nativeLocal: Map<String, String> = emptyMap()
            var extendedPending = false
            while (true) {
                checkInterrupted()
                val bytes = readBlock(channel, position, sourceSize)
                position += BLOCK_SIZE
                if (bytes.all { it == 0.toByte() }) {
                    if (extendedPending) {
                        throw IOException("The TAR extended header has no following entry")
                    }
                    if (headers.size != packedSizes.size) {
                        throw IOException("The TAR headers do not match the native entry count")
                    }
                    return headers
                }
                checkChecksum(bytes)
                val typeFlag = (bytes[156].toInt() and 0xFF).toChar()
                val size = readSize(bytes, 124)
                val alignedSize = alignSize(size)
                // Since 22.00, TarIn.cpp consumes every GNU/PAX extension record by type,
                // regardless of its name. None of these records has a native item index.
                if (typeFlag == 'L' || typeFlag == 'K') {
                    position = advance(position, alignedSize, sourceSize)
                    extendedPending = true
                    continue
                }
                if (typeFlag == 'g' || typeFlag == 'x' || typeFlag == 'X') {
                    if (size == 0L || size > MAX_PAX_HEADER_SIZE) {
                        throw IOException("Invalid or oversized PAX extended header")
                    }
                    val values = parse(readBytes(channel, position, size.toInt(), sourceSize))
                        .filterKeys { it in PAX_METADATA_KEYS || it.startsWith("GNU.sparse.") }
                    if (typeFlag == 'g') {
                        // Empty values also suppress the underlying ustar field; retain them
                        // so an earlier global value or the legacy field cannot reappear.
                        global.putAll(values)
                    } else {
                        local.putAll(values)
                        // 26.04 keeps the last x/X buffer when deciding where file data ends.
                        nativeLocal = values
                        extendedPending = true
                    }
                    position = advance(position, alignedSize, sourceSize)
                    continue
                }
                if (typeFlag == '1' && size != 0L) {
                    throw IOException("The bundled 7-Zip cannot extract TAR hard links with data")
                }
                val nativeSize = nativeLocal["size"]?.let(::fileSize)
                // Native TAR reports this as a warning and can otherwise expose padding as
                // content. A zero legacy size is the supported PAX size-override convention.
                if (nativeSize != null && size != 0L && nativeSize != size) {
                    throw IOException("The PAX size conflicts with the TAR entry header")
                }
                if (typeFlag == '1' && nativeSize != null && nativeSize != 0L) {
                    throw IOException("The bundled 7-Zip cannot extract TAR hard links with data")
                }
                val nativePackedSize = nativeSize ?: if (typeFlag == '1' || typeFlag == '5') 0L else size
                if (headers.size >= packedSizes.size
                    || packedSizes[headers.size] != alignSize(nativePackedSize)) {
                    throw IOException("The TAR header size does not match its native entry")
                }
                if (typeFlag == 'S') {
                    var extended = sparseExtensionFlag(bytes[482])
                    while (extended) {
                        checkInterrupted()
                        val sparse = readBlock(channel, position, sourceSize)
                        position += BLOCK_SIZE
                        extended = sparseExtensionFlag(sparse[504])
                    }
                }
                position = advance(position, alignSize(nativePackedSize), sourceSize)
                val effectiveSize = nativeSize ?: if (typeFlag == '1') 0L else size
                headers += Header(typeFlag, effectiveSize, global + local)
                local.clear()
                nativeLocal = emptyMap()
                extendedPending = false
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                channel.position(savedPosition)
            } catch (e: Throwable) {
                if (failure != null) failure.addSuppressed(e) else throw e
            }
        }
    }

    @Throws(IOException::class)
    fun parse(bytes: ByteArray): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var position = 0
        while (position < bytes.size) {
            checkInterrupted()
            val start = position
            var length = 0
            while (position < bytes.size && bytes[position] in DIGIT_BYTES) {
                checkInterrupted()
                val digit = bytes[position].toInt() - '0'.code
                if (length > (Int.MAX_VALUE - digit) / 10) {
                    throw IOException("The PAX record length is too large")
                }
                length = length * 10 + digit
                ++position
            }
            if (position == start || position >= bytes.size || bytes[position] != SPACE
                || length < 5 || length > bytes.size - start) {
                throw IOException("Invalid PAX record length")
            }
            val end = start + length
            val keyStart = position + 1
            if (bytes[end - 1] != NEWLINE || keyStart >= end - 1) {
                throw IOException("Invalid PAX record framing")
            }
            position = keyStart
            while (position < end - 1 && bytes[position] != EQUALS) {
                checkInterrupted()
                ++position
            }
            if (position == keyStart || position == end - 1) {
                throw IOException("Invalid PAX record keyword")
            }
            val key = decodeUtf8(bytes, keyStart, position - keyStart)
            val value = decodeUtf8(bytes, position + 1, end - position - 2)
            // Empty values deliberately remain in the map: they delete an inherited value.
            values[key] = value
            position = end
        }
        return values
    }

    @Throws(IOException::class)
    fun fileTime(value: String): FileTime {
        checkInterrupted()
        if (!DECIMAL_SECONDS.matches(value)) throw IOException("Invalid PAX file time")
        try {
            val seconds = BigDecimal(value).setScale(9, RoundingMode.FLOOR)
            val wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR)
            val nanos = seconds.subtract(wholeSeconds).movePointRight(9).intValueExact()
            return FileTime.from(Instant.ofEpochSecond(wholeSeconds.longValueExact(), nanos.toLong()))
        } catch (e: ArithmeticException) {
            throw IOException("The PAX file time is out of range", e)
        } catch (e: DateTimeException) {
            throw IOException("The PAX file time is out of range", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid PAX file time", e)
        }
    }

    @Throws(IOException::class)
    fun fileSize(value: String): Long = value
        .takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
        ?.toLongOrNull() ?: throw IOException("Invalid PAX file size")

    private fun readBlock(channel: SeekableByteChannel, position: Long, size: Long): ByteArray =
        readBytes(channel, position, BLOCK_SIZE.toInt(), size)

    private fun readBytes(
        channel: SeekableByteChannel, position: Long, length: Int, size: Long
    ): ByteArray {
        checkInterrupted()
        if (position < 0 || length < 0 || size < length || position > size - length) {
            throw IOException("The TAR header is truncated")
        }
        channel.position(position)
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            checkInterrupted()
            if (channel.read(buffer) <= 0) throw IOException("Cannot read the complete TAR header")
        }
        return bytes
    }

    private fun advance(position: Long, count: Long, size: Long): Long {
        if (position < 0 || position > size || count < 0 || count > size - position) {
            throw IOException("The TAR entry data is truncated")
        }
        return position + count
    }

    private fun alignSize(size: Long): Long {
        if (size < 0 || size > Long.MAX_VALUE - (BLOCK_SIZE - 1)) {
            throw IOException("The TAR entry size is out of range")
        }
        return (size + BLOCK_SIZE - 1) and -BLOCK_SIZE
    }

    private fun readSize(bytes: ByteArray, offset: Int): Long {
        if (bytes[offset] == 0x80.toByte() && bytes[offset + 1] == 0.toByte()
            && bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte()) {
            if (bytes[offset + 4] < 0) throw IOException("The TAR entry size is out of range")
            var value = 0L
            for (index in offset + 4 until offset + 12) {
                checkInterrupted()
                value = (value shl 8) or (bytes[index].toLong() and 0xFF)
            }
            return value
        }
        return readOctal(bytes, offset, 12)
    }

    private fun readOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        val end = offset + length
        var position = offset
        while (position < end && bytes[position] == SPACE) {
            checkInterrupted()
            ++position
        }
        val start = position
        var value = 0L
        while (position < end && bytes[position] in OCTAL_BYTES) {
            checkInterrupted()
            val digit = bytes[position].toInt() - '0'.code
            if (value > (Long.MAX_VALUE - digit) / 8) {
                throw IOException("The TAR header number is out of range")
            }
            value = value * 8 + digit
            ++position
        }
        if (position == start) throw IOException("Invalid TAR header number")
        while (position < end) {
            checkInterrupted()
            if (bytes[position] != 0.toByte() && bytes[position] != SPACE) {
                throw IOException("Invalid TAR header number")
            }
            ++position
        }
        return value
    }

    private fun checkChecksum(bytes: ByteArray) {
        val checksum = readOctal(bytes, 148, 8)
        var unsigned = 0L
        var signed = 0L
        for (index in bytes.indices) {
            checkInterrupted()
            val value = if (index in 148 until 156) SPACE else bytes[index]
            unsigned += value.toInt() and 0xFF
            signed += value
        }
        if (checksum != unsigned && checksum != signed) {
            throw IOException("The TAR header checksum is invalid")
        }
    }

    private fun sparseExtensionFlag(value: Byte): Boolean = when (value.toInt()) {
        0 -> false
        1 -> true
        else -> throw IOException("Invalid GNU TAR sparse-header extension flag")
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        throw IOException("The PAX record is not valid UTF-8", e)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private const val BLOCK_SIZE = 512L
    private const val MAX_PAX_HEADER_SIZE = 1024 * 1024
    private val PAX_METADATA_KEYS = setOf(
        "path", "linkpath", "size", "mtime", "atime", "ctime", "hdrcharset",
        "SCHILY.realsize", "SCHILY.filetype"
    )
    private const val SPACE: Byte = 0x20
    private const val NEWLINE: Byte = 0x0A
    private const val EQUALS: Byte = 0x3D
    private val DIGIT_BYTES = '0'.code.toByte()..'9'.code.toByte()
    private val OCTAL_BYTES = '0'.code.toByte()..'7'.code.toByte()
    private val DECIMAL_SECONDS = Regex("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
}
