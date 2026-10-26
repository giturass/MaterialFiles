package me.zhanghai.android.files.viewer.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWaveHeaderTest {
    @Test fun rf64DescribesLargeLosslessAudioWithoutTruncatingLength() {
        val bytes = 5L * 1024 * 1024 * 1024
        val header = PcmWaveHeader.create(192000, 2, bytes)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(80, header.size)
        assertEquals("RF64", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(-1, buffer.getInt(4))
        assertEquals("WAVEds64", header.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals(bytes + 72, buffer.getLong(20))
        assertEquals(bytes, buffer.getLong(28))
        assertEquals(bytes / 8, buffer.getLong(36))
        assertEquals(3, buffer.getShort(56).toInt())
        assertEquals(2, buffer.getShort(58).toInt())
        assertEquals(192000, buffer.getInt(60))
        assertEquals(1536000, buffer.getInt(64))
        assertEquals(8, buffer.getShort(68).toInt())
        assertEquals(32, buffer.getShort(70).toInt())
        assertEquals("data", header.copyOfRange(72, 76).toString(Charsets.US_ASCII))
        assertEquals(-1, buffer.getInt(76))
    }
    @Test fun invalidOrPartialFramesAreRejected() {
        for ((rate, channels, size) in listOf(Triple(0, 2, 8L), Triple(48000, 0, 8L),
            Triple(48000, 9, 36L), Triple(48000, 2, 7L), Triple(48000, 2, -8L))) {
            assertThrows(IllegalArgumentException::class.java) { PcmWaveHeader.create(rate, channels, size) }
        }
    }
}
