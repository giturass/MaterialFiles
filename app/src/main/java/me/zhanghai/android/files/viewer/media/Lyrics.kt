/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

/**
 * A song's lyrics, either timed against the timeline as LRC is, or a plain block of text as most
 * lyrics embedded in tags are.
 */
class Lyrics(val lines: List<Line>, val isSynced: Boolean) {
    class Line(
        /** Milliseconds into the song, or [UNTIMED] for lyrics that aren't synced. */
        val timeMillis: Long,
        val text: String
    )

    /**
     * The line to highlight at [timeMillis], or -1 when the song hasn't reached the first line yet
     * or the lyrics aren't synced.
     */
    fun indexAt(timeMillis: Long): Int {
        if (!isSynced) {
            return -1
        }
        var low = 0
        var high = lines.size - 1
        var index = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMillis <= timeMillis) {
                index = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return index
    }

    companion object {
        const val UNTIMED = -1L

        /** Parses [text] as LRC, falling back to treating it as plain lyrics. */
        fun parse(text: String): Lyrics? {
            if (text.isBlank()) {
                return null
            }
            var offsetMillis = 0L
            val syncedLines = mutableListOf<Line>()
            val plainLines = mutableListOf<String>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim().removePrefix("﻿").trim()
                if (line.isEmpty()) {
                    continue
                }
                val offsetMatch = OFFSET_TAG_REGEX.matchEntire(line)
                if (offsetMatch != null) {
                    offsetMillis = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                    continue
                }
                // Only tags at the very start of the line are timestamps for it.
                var index = 0
                val times = mutableListOf<Long>()
                while (true) {
                    val match = TIME_TAG_REGEX.find(line, index) ?: break
                    if (match.range.first != index) {
                        break
                    }
                    times += match.toTimeMillis()
                    index = match.range.last + 1
                }
                if (times.isEmpty()) {
                    if (!METADATA_TAG_REGEX.matches(line)) {
                        plainLines += line
                    }
                    continue
                }
                val content = line.substring(index).trim()
                for (time in times) {
                    // A positive offset makes the lyrics appear sooner.
                    syncedLines += Line((time - offsetMillis).coerceAtLeast(0L), content)
                }
            }
            return when {
                syncedLines.isNotEmpty() ->
                    Lyrics(syncedLines.sortedBy { it.timeMillis }, true)
                plainLines.isNotEmpty() ->
                    Lyrics(plainLines.map { Line(UNTIMED, it) }, false)
                else -> null
            }
        }

        private val TIME_TAG_REGEX = Regex("""\[(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
        private val OFFSET_TAG_REGEX =
            Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        /** `[ar:…]`, `[ti:…]` and friends, which describe the file rather than the song. */
        private val METADATA_TAG_REGEX = Regex("""\[[a-zA-Z#]+:.*]""")

        private fun MatchResult.toTimeMillis(): Long {
            val minutes = groupValues[1].toLongOrNull() ?: 0L
            val seconds = groupValues[2].toLongOrNull() ?: 0L
            val fraction = groupValues[3]
            val fractionMillis = when (fraction.length) {
                0 -> 0L
                1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                else -> fraction.take(3).toLongOrNull() ?: 0L
            }
            return (minutes * 60L + seconds) * 1000L + fractionMillis
        }
    }
}
