package com.music.bitchord.data.lyrics.providers

import com.music.bitchord.data.lyrics.EnhancedLrc
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import java.text.BreakIterator
import java.util.Locale

/**
 * Generates estimated LRC timestamps for unsynced plain-text lyrics using a
 * character-weighted acoustic duration distribution model (ported from LyricsForTryptify).
 *
 * When only plain lyrics are available (e.g. from Uta-Net, UtaTen, J-Lyric, Genius)
 * and CTC auto-alignment is enabled, this assigns natural line timestamps proportional
 * to the spoken/sung syllable weight of each line.
 */
object CtcLrcGenerator {

    private val TIMESTAMP_CHECK = Regex("""\[\d{2}:\d{2}[.:]\d{2,3}]""")
    private val FURIGANA_STRIP = Regex("""(?:\(\([ぁ-ゖァ-ヺー]+\)\)|（（[ぁ-ゖァ-ヺー]+））)""")

    fun generateAutoSyncedLrc(plainText: String, durationSeconds: Int): String {
        if (plainText.isBlank()) return ""
        if (TIMESTAMP_CHECK.containsMatchIn(plainText)) return plainText

        val rawLines = plainText.lines().map { it.trim() }
        val nonEmptyCount = rawLines.count { it.isNotEmpty() }
        if (nonEmptyCount == 0) return plainText

        val totalSec = if (durationSeconds > 20) durationSeconds.toDouble() else 210.0
        val startOffsetSec = (totalSec * 0.06).coerceIn(3.0, 14.0)
        val endOffsetSec = (totalSec - (totalSec * 0.07).coerceIn(5.0, 18.0)).coerceAtLeast(startOffsetSec + 10.0)
        val usableWindowSec = (endOffsetSec - startOffsetSec).coerceAtLeast(15.0)

        val weights = rawLines.map { line ->
            if (line.isEmpty()) {
                0.65 // Instrumental breathing gap between stanzas
            } else {
                lineAcousticWeight(line)
            }
        }

        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var currentSec = startOffsetSec
        val sb = StringBuilder()

        for (i in rawLines.indices) {
            val line = rawLines[i]
            val stepSec = (weights[i] / totalWeight) * usableWindowSec
            if (line.isNotEmpty()) {
                val totalMs = (currentSec * 1000.0).toLong().coerceAtLeast(0L)
                val min = (totalMs / 60000)
                val sec = (totalMs % 60000) / 1000
                val cs = (totalMs % 1000) / 10
                sb.append('[')
                    .append(min.toString().padStart(2, '0'))
                    .append(':')
                    .append(sec.toString().padStart(2, '0'))
                    .append('.')
                    .append(cs.toString().padStart(2, '0'))
                    .append(']')
                    .append(line)
                    .append('\n')
            }
            currentSec += stepSec
        }

        return sb.toString().trimEnd()
    }

    /** Estimates a separate timestamp for each visible character in each lyric line. */
    fun generateCharacterSyncedLrc(plainText: String, durationSeconds: Int): String {
        if (plainText.isBlank()) return ""
        val parsedTimed = if (TIMESTAMP_CHECK.containsMatchIn(plainText)) {
            LrcLib.parseLrc(plainText).filter { it.text.isNotBlank() }
        } else emptyList()
        val sourceLines = parsedTimed.ifEmpty {
            plainText.lineSequence().map(String::trim).filter(String::isNotEmpty)
                .map { LyricLine(0L, it) }.toList()
        }
        if (sourceLines.isEmpty()) return ""
        val durationMs = durationSeconds.takeIf { it > 20 }?.times(1_000L) ?: 210_000L
        val aligned = if (sourceLines.any { it.timeMs > 0L }) sourceLines else alignLines(sourceLines, durationMs)
        return aligned.mapIndexedNotNull { index, line ->
            val text = line.text.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val nextStart = aligned.getOrNull(index + 1)?.timeMs?.takeIf { it > line.timeMs }
            val endMs = line.sungUntilMs?.takeIf { it > line.timeMs }
                ?: nextStart
                ?: durationMs.takeIf { it > line.timeMs }
                ?: (line.timeMs + 1_000L)
            val spans = distributeCharacters(text, line.timeMs, endMs)
            if (spans.isEmpty()) return@mapIndexedNotNull null
            buildString {
                append(lineStamp(spans.first().first))
                spans.forEach { (timeMs, value) ->
                    append('<').append(clock(timeMs)).append('>').append(value)
                }
                append('<').append(clock(endMs)).append('>')
            }
        }.joinToString("\n")
    }

    private fun distributeCharacters(text: String, startMs: Long, endMs: Long): List<Pair<Long, String>> {
        val clusters = graphemes(text)
        val weightedIndices = clusters.indices.filter { index ->
            clusters[index].any { Character.isLetterOrDigit(it.code) }
        }
        if (weightedIndices.isEmpty()) return listOf(startMs to text)
        val totalWeight = weightedIndices.sumOf { index -> characterWeight(clusters[index]) }
        if (totalWeight <= 0.0) return listOf(startMs to text)
        val spanMs = (endMs - startMs).coerceAtLeast(0L)
        val timeByIndex = mutableMapOf<Int, Long>()
        var cumulative = 0.0
        weightedIndices.forEach { index ->
            val ratio = cumulative / totalWeight
            timeByIndex[index] = startMs + (spanMs * ratio).toLong()
            cumulative += characterWeight(clusters[index])
        }

        val result = mutableListOf<Pair<Long, String>>()
        var pendingPrefix = StringBuilder()
        clusters.forEachIndexed { index, cluster ->
            val timeMs = timeByIndex[index]
            if (timeMs == null) {
                if (result.isEmpty()) pendingPrefix.append(cluster)
                else result[result.lastIndex] = result.last().let { it.first to (it.second + cluster) }
            } else {
                val value = pendingPrefix.append(cluster).toString()
                pendingPrefix = StringBuilder()
                result += timeMs to value
            }
        }
        if (pendingPrefix.isNotEmpty() && result.isNotEmpty()) {
            result[result.lastIndex] = result.last().let { it.first to (it.second + pendingPrefix) }
        }
        return result
    }

    private fun characterWeight(cluster: String): Double = when {
        cluster.any { Character.UnicodeScript.of(it.code) in setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
        ) } -> 1.0
        cluster.any { it.isLetterOrDigit() } -> 1.15
        else -> 1.0
    }

    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun lineStamp(timeMs: Long): String = "[${clock(timeMs)}]"

    private fun clock(timeMs: Long): String {
        val time = timeMs.coerceAtLeast(0L)
        return "%02d:%02d.%02d".format(
            Locale.ROOT,
            time / 60_000L,
            (time % 60_000L) / 1_000L,
            (time % 1_000L) / 10L,
        )
    }

    /**
     * Aligns unsynced [LyricLine]s (where all `timeMs == 0L`) across [durationMs].
     */
    fun alignLines(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        if (lines.isEmpty()) return lines
        if (lines.any { it.timeMs > 0L }) return lines

        val totalSec = if (durationMs > 20_000L) durationMs / 1000.0 else 210.0
        val startOffsetSec = (totalSec * 0.06).coerceIn(3.0, 14.0)
        val endOffsetSec = (totalSec - (totalSec * 0.07).coerceIn(5.0, 18.0)).coerceAtLeast(startOffsetSec + 10.0)
        val usableWindowSec = (endOffsetSec - startOffsetSec).coerceAtLeast(15.0)

        val weights = lines.map { line ->
            if (line.text.isBlank()) 0.65 else lineAcousticWeight(line.text)
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var currentSec = startOffsetSec

        return lines.mapIndexed { index, line ->
            val timeMs = (currentSec * 1000.0).toLong().coerceAtLeast(100L)
            val stepSec = (weights[index] / totalWeight) * usableWindowSec
            currentSec += stepSec
            val endMs = (currentSec * 1000.0).toLong().coerceAtLeast(timeMs + 500L)
            line.copy(timeMs = timeMs, sungUntilMs = endMs)
        }
    }

    private fun lineAcousticWeight(line: String): Double {
        // Strip inline furigana annotations so kanji + reading isn't double-counted
        val surface = FURIGANA_STRIP.replace(line, "")
        val cjkCount = surface.count { ch ->
            Character.UnicodeScript.of(ch.code) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
            )
        }
        val latinWords = surface.split(Regex("""\s+""")).count { it.any { c -> c.isLetterOrDigit() } }
        val effectiveUnits = cjkCount * 0.45 + latinWords * 0.55
        return (1.0 + effectiveUnits * 0.18).coerceIn(1.0, 4.5)
    }
}
