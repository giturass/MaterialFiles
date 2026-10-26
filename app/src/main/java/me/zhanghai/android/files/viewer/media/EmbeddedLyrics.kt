package me.zhanghai.android.files.viewer.media

import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object EmbeddedLyrics {
    fun read(tracks: Tracks): String? {
        for (group in tracks.groups) {
            for (track in 0 until group.length) {
                val metadata = group.getTrackFormat(track).metadata ?: continue
                for (index in 0 until metadata.length()) {
                    val text = when (val entry = metadata[index]) {
                        is BinaryFrame -> if (entry.id in setOf("USLT", "ULT")) Lyrics.decodeUslt(entry.data) else null
                        is TextInformationFrame -> if (entry.id == "USLT" ||
                            (entry.id in setOf("TXXX", "TXX") && isLyricsKey(entry.description))) {
                            entry.values.joinToString("\n")
                        } else null
                        is VorbisComment -> if (isLyricsKey(entry.key)) entry.value else null
                        else -> null
                    }
                    if (!text.isNullOrBlank() && text.length <= Lyrics.MAX_TEXT_LENGTH) return text
                }
            }
        }
        return null
    }

    private fun isLyricsKey(key: String?) = key?.uppercase(java.util.Locale.ROOT) in
        setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "UNSYNCHRONIZEDLYRICS")
}
