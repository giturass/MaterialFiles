package me.zhanghai.android.files.viewer.media

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

internal data class LyricLine(val timeMs: Long?, val text: String)

internal data class Lyrics(val lines: List<LyricLine>) {
    val timed: Boolean get() = lines.firstOrNull()?.timeMs != null

    fun activeLine(positionMs: Long): Int {
        if (!timed) return -1
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs!! <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }

    companion object {
        const val MAX_TEXT_LENGTH = 65536
        private val timestamp = Regex("\\[(\\d{1,5}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
        private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        private val metadata = Regex("^\\[[a-z]+:.*]$", RegexOption.IGNORE_CASE)
        private val wordTimestamp = Regex("<\\d{1,5}:[0-5]\\d(?:[.:]\\d{1,3})?>")

        fun parse(text: String): Lyrics? {
            require(text.length <= MAX_TEXT_LENGTH) { "Lyrics are too large" }
            require(text.none { it < ' ' && it !in "\r\n\t" }) { "Lyrics must be text" }
            val offsetMs = offset.findAll(text).lastOrNull()?.groupValues?.get(1)
                ?.toLongOrNull()?.coerceIn(-86400000, 86400000) ?: 0L
            val timed = mutableListOf<LyricLine>()
            val plain = mutableListOf<LyricLine>()
            for (raw in text.removePrefix("\uFEFF").lineSequence()) {
                val line = raw.trim()
                val times = mutableListOf<Long>()
                var end = 0
                for (match in timestamp.findAll(line)) {
                    if (match.range.first != end) break
                    val (minutes, seconds, fraction) = match.destructured
                    val time = minutes.toLong() * 60000 + seconds.toLong() * 1000 +
                        fraction.padEnd(3, '0').toLong()
                    times += (time - offsetMs).coerceAtLeast(0)
                    end = match.range.last + 1
                }
                if (times.isNotEmpty()) {
                    val words = wordTimestamp.replace(line.substring(end), "").trim()
                    times.forEach { timed += LyricLine(it, words) }
                } else if (line.isNotEmpty() && !metadata.matches(line)) {
                    plain += LyricLine(null, line)
                }
            }
            val lines = if (timed.isNotEmpty()) timed.groupBy { it.timeMs!! }.toSortedMap()
                .map { (time, values) -> LyricLine(time, values.map { it.text }.distinct().joinToString("\n")) }
                else plain
            return lines.takeIf { it.any { line -> line.text.isNotBlank() } }?.let(::Lyrics)
        }

        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2) {
                if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16)
                if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16)
            }
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        }

        /** ID3 USLT/ULT: encoding, three-byte language, terminated description, lyrics. */
        fun decodeUslt(data: ByteArray): String? {
            if (data.size < 5 || data.size > MAX_TEXT_LENGTH * 4) return null
            val encoding = data[0].toInt()
            var charset = when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> return null
            }
            val step = if (encoding == 1 || encoding == 2) 2 else 1
            var end = 4
            while (end + step <= data.size) {
                if (data[end] == 0.toByte() && (step == 1 || data[end + 1] == 0.toByte())) break
                end += step
            }
            val start = end + step
            if (start >= data.size) return null
            // Some writers omit the second BOM and use the descriptor's byte order.
            if (encoding == 1 && data[4] == 0xFF.toByte() && data[5] == 0xFE.toByte()
                && !(data[start] == 0xFF.toByte() && data.getOrNull(start + 1) == 0xFE.toByte())
                && !(data[start] == 0xFE.toByte() && data.getOrNull(start + 1) == 0xFF.toByte())) {
                charset = Charsets.UTF_16LE
            }
            return String(data, start, data.size - start, charset).trim('\u0000', '\uFEFF')
                .takeIf { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH }
        }
    }
}
