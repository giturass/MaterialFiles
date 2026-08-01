/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.net.Uri
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.localFileOrNull
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Finds the lyrics for a song, preferring an `.lrc` file next to it - those are the ones that are
 * actually synced - and falling back to whatever the file's own tags carry.
 *
 * Every method here blocks on I/O and must be called off the main thread.
 */
object LyricsLoader {
    fun load(context: Context, path: Path?, uri: Uri): Lyrics? {
        if (path != null) {
            loadSidecar(path)?.let { return it }
        }
        // Reading tags out of a remote file means pulling its bytes over the network, and an MP4
        // whose metadata sits at the end would mean pulling all of them, for lyrics.
        val isLocal = path == null || path.localFileOrNull != null
        return try {
            openStream(context, path, uri)?.use { loadEmbedded(it, isLocal) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** An `.lrc` file with the same name as the song, which is how synced lyrics are distributed. */
    private fun loadSidecar(path: Path): Lyrics? {
        val parent = path.parent ?: return null
        val name = path.fileName?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        val baseName = name.substringBeforeLast('.', name)
        for (extension in LRC_EXTENSIONS) {
            val bytes = try {
                parent.resolve(baseName + extension).newInputStream()
                    .use { it.readAtMost(MAX_LYRICS_BYTES) }
            } catch (e: Exception) {
                // Almost always just a missing file.
                continue
            }
            Lyrics.parse(bytes.decodeText())?.let { return it }
        }
        return null
    }

    private fun openStream(context: Context, path: Path?, uri: Uri): InputStream? {
        if (path != null) {
            try {
                return path.newInputStream()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadEmbedded(inputStream: InputStream, isLocal: Boolean): Lyrics? {
        val stream = BufferedInputStream(inputStream)
        val magic = ByteArray(12)
        stream.mark(magic.size + 1)
        val magicSize = stream.readAtMostInto(magic)
        if (magicSize < magic.size) {
            return null
        }
        stream.reset()
        val text = try {
            when {
                magic.startsWith(ID3_MAGIC) -> readId3v2Lyrics(stream)
                magic.startsWith(FLAC_MAGIC) -> readFlacLyrics(stream)
                magic.startsWith(FTYP_MAGIC, 4) -> readMp4Lyrics(stream, isLocal)
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        return text?.let { Lyrics.parse(it) }
    }

    // https://id3.org/id3v2.4.0-structure
    private fun readId3v2Lyrics(stream: InputStream): String? {
        val header = ByteArray(10)
        stream.readFully(header)
        val version = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val size = header.readSynchsafeInt(6)
        if (size <= 0 || size > MAX_TAG_BYTES) {
            return null
        }
        val tag = ByteArray(size)
        stream.readFully(tag)
        var offset = 0
        if (flags and 0x40 != 0) {
            // An extended header sits before the frames and carries no lyrics.
            if (tag.size < 4) {
                return null
            }
            val extendedSize = if (version >= 4) {
                tag.readSynchsafeInt(0)
            } else {
                tag.readInt(0, 4) + 4
            }
            offset = extendedSize.coerceIn(4, tag.size)
        }
        val idSize = if (version < 3) 3 else 4
        val sizeSize = if (version < 3) 3 else 4
        val flagsSize = if (version < 3) 0 else 2
        while (offset + idSize + sizeSize + flagsSize <= tag.size) {
            val id = String(tag, offset, idSize, StandardCharsets.ISO_8859_1)
            if (id[0] == '\u0000') {
                // Padding after the last frame.
                break
            }
            val frameSize = if (version >= 4) {
                tag.readSynchsafeInt(offset + idSize)
            } else {
                tag.readInt(offset + idSize, sizeSize)
            }
            val contentOffset = offset + idSize + sizeSize + flagsSize
            if (frameSize <= 0 || contentOffset + frameSize > tag.size) {
                break
            }
            if (id == "USLT" || id == "ULT") {
                tag.decodeUnsyncedLyricsFrame(contentOffset, frameSize)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            offset = contentOffset + frameSize
        }
        return null
    }

    /** A USLT frame is a text encoding, a language, a descriptor and then the lyrics. */
    private fun ByteArray.decodeUnsyncedLyricsFrame(offset: Int, size: Int): String? {
        val end = offset + size
        if (offset + 4 >= end) {
            return null
        }
        val encoding = this[offset].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        // Skip the text encoding byte and the three byte language code.
        var index = offset + 4
        index = if (encoding == 1 || encoding == 2) {
            while (index + 1 < end && !(this[index] == ZERO && this[index + 1] == ZERO)) {
                index += 2
            }
            index + 2
        } else {
            while (index < end && this[index] != ZERO) {
                index++
            }
            index + 1
        }
        if (index >= end) {
            return null
        }
        return String(this, index, end - index, charset).trimEnd('\u0000')
    }

    // https://xiph.org/flac/format.html
    private fun readFlacLyrics(stream: InputStream): String? {
        stream.readFully(ByteArray(FLAC_MAGIC.size))
        var readBytes = FLAC_MAGIC.size
        while (readBytes < MAX_TAG_BYTES) {
            val header = ByteArray(4)
            stream.readFully(header)
            readBytes += header.size
            val isLast = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val size = header.readInt(1, 3)
            if (size < 0 || size > MAX_TAG_BYTES) {
                return null
            }
            if (type == FLAC_BLOCK_VORBIS_COMMENT) {
                val block = ByteArray(size)
                stream.readFully(block)
                return block.readVorbisCommentLyrics()
            }
            stream.skipFully(size.toLong())
            readBytes += size
            if (isLast) {
                return null
            }
        }
        return null
    }

    /** Vorbis comments are `KEY=value` entries, length prefixed and little endian throughout. */
    private fun ByteArray.readVorbisCommentLyrics(): String? {
        var offset = 0
        val vendorSize = readIntLe(offset) ?: return null
        offset += 4 + vendorSize
        val count = readIntLe(offset) ?: return null
        offset += 4
        for (index in 0 until count) {
            val size = readIntLe(offset) ?: return null
            offset += 4
            if (size < 0 || offset + size > this.size) {
                return null
            }
            val comment = String(this, offset, size, StandardCharsets.UTF_8)
            offset += size
            val separator = comment.indexOf('=')
            if (separator == -1) {
                continue
            }
            val key = comment.substring(0, separator).uppercase()
            if (key in VORBIS_LYRICS_KEYS) {
                val value = comment.substring(separator + 1)
                if (value.isNotBlank()) {
                    return value
                }
            }
        }
        return null
    }

    /** Walks the atom tree down to `moov.udta.meta.ilst.©lyr`. */
    private fun readMp4Lyrics(stream: InputStream, isLocal: Boolean): String? {
        val scanLimit = if (isLocal) MAX_MP4_SCAN_BYTES else MAX_REMOTE_MP4_SCAN_BYTES
        var readBytes = 0L
        while (readBytes < scanLimit) {
            val header = ByteArray(8)
            stream.readFully(header)
            readBytes += header.size
            val size = header.readInt(0, 4).toLong()
            val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)
            // 0 means "to the end of the file" and 1 means a 64 bit size follows, neither of which
            // can be a moov worth reading here.
            if (size < header.size) {
                return null
            }
            val contentSize = size - header.size
            if (type == "moov") {
                if (contentSize > MAX_TAG_BYTES) {
                    return null
                }
                val moov = ByteArray(contentSize.toInt())
                stream.readFully(moov)
                return moov.findMp4Lyrics(0, moov.size, 0)
            }
            if (readBytes + contentSize > scanLimit) {
                // The metadata is past where we are willing to read, which for a file that isn't
                // local means past where we are willing to download.
                return null
            }
            stream.skipFully(contentSize)
            readBytes += contentSize
        }
        return null
    }

    private fun ByteArray.findMp4Lyrics(start: Int, end: Int, depth: Int): String? {
        if (depth > MAX_MP4_DEPTH) {
            return null
        }
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt(offset, 4)
            val type = String(this, offset + 4, 4, StandardCharsets.ISO_8859_1)
            if (size < 8 || offset + size > end) {
                return null
            }
            val contentStart = offset + 8
            when (type) {
                "udta", "ilst" -> findMp4Lyrics(contentStart, offset + size, depth + 1)
                    ?.let { return it }
                // A full atom, so its version and flags come before the children.
                "meta" -> findMp4Lyrics(contentStart + 4, offset + size, depth + 1)
                    ?.let { return it }
                MP4_LYRICS_TYPE -> {
                    // The value lives in a data atom: version, flags and locale, then the text.
                    val dataStart = contentStart + 8 + 8
                    if (dataStart < offset + size) {
                        val text = String(
                            this, dataStart, offset + size - dataStart, StandardCharsets.UTF_8
                        )
                        if (text.isNotBlank()) {
                            return text
                        }
                    }
                }
            }
            offset += size
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
        if (offset + prefix.size > size) {
            return false
        }
        for (index in prefix.indices) {
            if (this[offset + index] != prefix[index]) {
                return false
            }
        }
        return true
    }

    private fun ByteArray.readInt(offset: Int, size: Int): Int {
        var value = 0
        for (index in 0 until size) {
            value = (value shl 8) or (this[offset + index].toInt() and 0xFF)
        }
        return value
    }

    private fun ByteArray.readIntLe(offset: Int): Int? {
        if (offset < 0 || offset + 4 > size) {
            return null
        }
        var value = 0
        for (index in 3 downTo 0) {
            value = (value shl 8) or (this[offset + index].toInt() and 0xFF)
        }
        return if (value >= 0) value else null
    }

    /** ID3 sizes only use 7 bits per byte so that they can never look like a frame sync. */
    private fun ByteArray.readSynchsafeInt(offset: Int): Int {
        var value = 0
        for (index in 0 until 4) {
            value = (value shl 7) or (this[offset + index].toInt() and 0x7F)
        }
        return value
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val bytesRead = read(bytes, offset, bytes.size - offset)
            if (bytesRead == -1) {
                throw EOFException()
            }
            offset += bytesRead
        }
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (read() == -1) {
                throw EOFException()
            }
            remaining--
        }
    }

    private fun InputStream.readAtMostInto(bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val bytesRead = read(bytes, offset, bytes.size - offset)
            if (bytesRead == -1) {
                break
            }
            offset += bytesRead
        }
        return offset
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        val size = readAtMostInto(bytes)
        return if (size == limit) bytes else bytes.copyOf(size)
    }

    /**
     * Lyrics files carry no encoding declaration. UTF-8 is self-validating, so anything that isn't
     * valid UTF-8 is almost certainly GBK, which is what Chinese lyrics are usually saved as.
     */
    private fun ByteArray.decodeText(): String {
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() &&
            this[2] == 0xBF.toByte()) {
            return String(this, 3, size - 3, StandardCharsets.UTF_8)
        }
        if (size >= 2) {
            if (this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) {
                return String(this, 2, size - 2, StandardCharsets.UTF_16LE)
            }
            if (this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()) {
                return String(this, 2, size - 2, StandardCharsets.UTF_16BE)
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (e: CharacterCodingException) {
            // Not UTF-8, fall through.
        }
        for (charsetName in FALLBACK_CHARSETS) {
            val charset = try {
                Charset.forName(charsetName)
            } catch (e: Exception) {
                continue
            }
            try {
                return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(this))
                    .toString()
            } catch (e: CharacterCodingException) {
                // Try the next one.
            }
        }
        return String(this, StandardCharsets.UTF_8)
    }

    private const val ZERO: Byte = 0
    private const val MAX_LYRICS_BYTES = 512 * 1024
    private const val MAX_TAG_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_SCAN_BYTES = 512L * 1024 * 1024
    /**
     * How far into a file that isn't local we are willing to look for the metadata atom. Enough for
     * one written for streaming, which puts it at the front, and not enough to be felt otherwise.
     */
    private const val MAX_REMOTE_MP4_SCAN_BYTES = 4L * 1024 * 1024
    private const val MAX_MP4_DEPTH = 8
    private const val FLAC_BLOCK_VORBIS_COMMENT = 4
    /** `©lyr`, the iTunes lyrics atom. */
    private const val MP4_LYRICS_TYPE = "©lyr"

    private val LRC_EXTENSIONS = listOf(".lrc", ".LRC", ".Lrc")
    private val ID3_MAGIC = "ID3".toByteArray(StandardCharsets.ISO_8859_1)
    private val FLAC_MAGIC = "fLaC".toByteArray(StandardCharsets.ISO_8859_1)
    private val FTYP_MAGIC = "ftyp".toByteArray(StandardCharsets.ISO_8859_1)
    private val VORBIS_LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")
    private val FALLBACK_CHARSETS = listOf("GBK", "Big5", "Shift_JIS")
}
