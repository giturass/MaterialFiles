package me.zhanghai.android.files.viewer.media

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun timedLyricsHandleRepeatedLinesTranslationsOffsetsAndSeekingBackwards() {
        val lyrics = Lyrics.parse("""
            [ti:Example]
            [00:10.5][00:30.500]Repeated line
            [00:10.500]译文
            [00:20.050]
            [offset:+500]
        """.trimIndent())!!
        assertTrue(lyrics.timed)
        assertEquals(listOf(10000L, 19550L, 30000L), lyrics.lines.map { it.timeMs })
        assertEquals("Repeated line\n译文", lyrics.lines[0].text)
        assertEquals("", lyrics.lines[1].text)
        assertEquals(-1, lyrics.activeLine(9999))
        assertEquals(0, lyrics.activeLine(10000))
        assertEquals(1, lyrics.activeLine(20000))
        assertEquals(2, lyrics.activeLine(90000))
        assertEquals(0, lyrics.activeLine(12000))
    }

    @Test fun fractionsAndNegativeOffsetsUseMilliseconds() {
        val lyrics = Lyrics.parse("[offset:-250]\n[00:00]Start\n[00:01.2]A\n[00:02.34]B\n[00:03.456]C")!!
        assertEquals(listOf(250L, 1450L, 2590L, 3706L), lyrics.lines.map { it.timeMs })
        assertEquals(0L, Lyrics.parse("[offset:500]\n[00:00]Start")!!.lines[0].timeMs)
    }

    @Test fun plainLyricsStayReadableWithoutFakeTimingAndMetadataOnlyIsEmpty() {
        val lyrics = Lyrics.parse("\uFEFF[ar:Artist]\r\nFirst line\r\n\r\n[Chorus]\nSecond line")!!
        assertFalse(lyrics.timed)
        assertEquals(listOf("First line", "[Chorus]", "Second line"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.activeLine(60000))
        assertNull(Lyrics.parse("[ti:Title]\n[ar:Artist]\n[offset:200]"))
        assertNull(Lyrics.parse("[00:01]\n[00:02]"))
    }

    @Test fun malformedTagsDoNotHideValidTimingAndEnhancedTagsAreRemoved() {
        val lyrics = Lyrics.parse("[99:99.999]Invalid\n[00:03.50]<00:03.50>Hello <00:04.00>world")!!
        assertEquals(listOf(LyricLine(3500, "Hello world")), lyrics.lines)
        assertEquals(0L, Lyrics.parse("[offset:9999999999999999999999999]\n[00:00]Start")!!.lines[0].timeMs)
    }

    @Test fun textEncodingsPreserveChineseLyrics() {
        val text = "[00:01.00]你好，世界"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charset.forName("GB18030"))) {
            assertEquals(text, Lyrics.decode(text.toByteArray(charset)))
        }
        assertEquals(text, Lyrics.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun usltSkipsDescriptionsAndSupportsBothUtf16ByteOrders() {
        val words = "[00:01]你好"
        val utf8 = byteArrayOf(3) + "engdescription\u0000$words\u0000".toByteArray(Charsets.UTF_8)
        assertEquals(words, Lyrics.decodeUslt(utf8))
        val littleEndian = byteArrayOf(1) + "zho".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "说明\u0000$words\u0000".toByteArray(Charsets.UTF_16LE)
        assertEquals(words, Lyrics.decodeUslt(littleEndian))
        val bigEndian = byteArrayOf(2) + "zho".toByteArray() + "说明\u0000$words\u0000".toByteArray(Charsets.UTF_16BE)
        assertEquals(words, Lyrics.decodeUslt(bigEndian))
        assertNull(Lyrics.decodeUslt(byteArrayOf(1, 0, 0, 0, 0)))
        assertNull(Lyrics.decodeUslt(byteArrayOf(5, 0, 0, 0, 0, 0)))
        assertNull(Lyrics.decodeUslt(byteArrayOf(3) + "engmissing terminator".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinaryInput() { Lyrics.parse("Lyrics\u0000binary") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedText() { Lyrics.parse("x".repeat(Lyrics.MAX_TEXT_LENGTH + 1)) }
}
