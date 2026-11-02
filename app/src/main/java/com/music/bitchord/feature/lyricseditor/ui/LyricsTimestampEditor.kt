package com.music.bitchord.feature.lyricseditor.ui

import java.util.regex.Matcher

/** Helpers for shifting LRC timestamps without changing the lyric text or metadata. */
internal object LyricsTimestampEditor {
    private val timestampPattern = Regex("""\[(\d+):(\d{2})(?:\.(\d{1,2}))?\]""")
    private val inputPattern = Regex("""\[(\d+):(\d{2})\.(\d{1,2})\]""")

    fun parseInput(value: String): Long? {
        val match = inputPattern.matchEntire(value.trim()) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        val fraction = match.groupValues[3].toIntOrNull() ?: return null
        if (seconds !in 0..59) return null
        val fractionMs = if (match.groupValues[3].length == 1) fraction * 100L else fraction * 10L
        return runCatching { Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(minutes, 60L), seconds.toLong()), 1_000L), fractionMs) }
            .getOrNull()
    }

    fun hasTimestamps(text: String): Boolean = timestampPattern.containsMatchIn(text)

    fun shiftAll(text: String, deltaMs: Long): String? {
        if (deltaMs == 0L) return text.takeIf(::hasTimestamps)
        if (!hasTimestamps(text)) return null
        var invalid = false
        val shifted = timestampPattern.replace(text) { match ->
            val timestampMs = toMillis(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val result = runCatching { Math.addExact(timestampMs, deltaMs) }
                .getOrElse {
                    invalid = true
                    return@replace match.value
                }
            if (result < 0L) {
                invalid = true
                match.value
            } else {
                format(result, match.groupValues[3].length)
            }
        }
        return shifted.takeUnless { invalid }
    }

    fun setFirst(text: String, targetMs: Long): String? {
        val first = timestampPattern.find(text) ?: return null
        val firstMs = toMillis(first.groupValues[1], first.groupValues[2], first.groupValues[3])
        return shiftAll(text, targetMs - firstMs)
    }

    private fun toMillis(minutesText: String, secondsText: String, fraction: String): Long {
        val minutes = minutesText.toLongOrNull() ?: 0L
        val seconds = secondsText.toLongOrNull() ?: 0L
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            else -> 0L
        }
        return runCatching {
            Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(minutes, 60L), seconds), 1_000L), fractionMs)
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun format(totalMs: Long, fractionDigits: Int): String {
        val totalSeconds = totalMs / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val fraction = totalMs % 1_000
        val fractionText = when (fractionDigits) {
            0 -> ""
            1 -> ".${fraction / 100}"
            else -> ".${(fraction / 10).toString().padStart(2, '0')}"
        }
        return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}$fractionText]"
    }
}
