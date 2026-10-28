package me.zhanghai.android.files.viewer.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Seekable RF64 stream, including decoded audio larger than the RIFF 4 GiB limit. */
internal object PcmWaveHeader {
    const val SIZE = 80

    fun create(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
        require(sampleRate in 1..768000 && channels in 1..8)
        val frameSize = channels * 4
        require(dataSize >= 0 && dataSize <= Long.MAX_VALUE - SIZE && dataSize % frameSize == 0L)
        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RF64".toByteArray(Charsets.US_ASCII)); putInt(-1)
            put("WAVEds64".toByteArray(Charsets.US_ASCII)); putInt(28)
            putLong(dataSize + SIZE - 8); putLong(dataSize); putLong(dataSize / frameSize)
            putInt(0)
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(3) // IEEE float: retain the precision of lossless 24-bit sources.
            putShort(channels.toShort()); putInt(sampleRate); putInt(sampleRate * frameSize)
            putShort(frameSize.toShort()); putShort(32)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(-1)
        }.array()
    }
}
