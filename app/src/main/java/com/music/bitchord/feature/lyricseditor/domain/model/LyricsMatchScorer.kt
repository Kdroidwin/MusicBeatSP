package com.music.bitchord.feature.lyricseditor.domain.model

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bounded, metadata-only estimate shown to help the user choose lyric results. */
object LyricsMatchScorer {
    private const val MAX_TEXT_CHARS = 512
    private const val MAX_LYRICS_CHARS_FOR_DURATION = 500_000
    private val timestampPattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")

    fun percent(
        queryTitle: String,
        queryArtist: String,
        queryAlbum: String?,
        queryDurationMs: Long,
        result: LyricsSearchResultItem,
    ): Int {
        var weightedScore = 0f
        var totalWeight = 0f

        fun include(weight: Float, score: Float) {
            weightedScore += weight * score.coerceIn(0f, 1f)
            totalWeight += weight
        }

        include(0.45f, similarity(queryTitle, result.title))
        if (queryArtist.isNotBlank() && result.artist.isNotBlank()) {
            include(0.30f, similarity(queryArtist, result.artist))
        }
        if (!queryAlbum.isNullOrBlank() && !result.album.isNullOrBlank()) {
            include(0.10f, similarity(queryAlbum, result.album))
        }
        val querySeconds = queryDurationMs / 1_000L
        if (querySeconds > 0) {
            when {
                result.durationSeconds > 0 -> include(
                    0.15f,
                    1f - abs(result.durationSeconds.toLong() - querySeconds) / 20f,
                )
                else -> {
                    val lastLineSeconds = result.syncedLyrics
                        ?.take(MAX_LYRICS_CHARS_FOR_DURATION)
                        ?.let(::lastTimestampSeconds)
                    if (lastLineSeconds != null && lastLineSeconds > querySeconds + 20) include(0.15f, 0f)
                }
            }
        }

        if (totalWeight == 0f) return 0
        return (weightedScore / totalWeight * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun lastTimestampSeconds(lyrics: String): Long? =
        timestampPattern.findAll(lyrics).lastOrNull()?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return@let null
            val seconds = match.groupValues[2].toLongOrNull() ?: return@let null
            if (seconds >= 60) return@let null
            minutes * 60L + seconds
        }

    private fun similarity(left: String, right: String): Float {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.contains(b) || b.contains(a)) return 0.85f

        fun pairs(value: String): Set<String> =
            if (value.length < 2) setOf(value) else (0 until value.length - 1).map { value.substring(it, it + 2) }.toSet()

        val aPairs = pairs(a)
        val bPairs = pairs(b)
        return (2f * aPairs.intersect(bPairs).size / (aPairs.size + bPairs.size)).coerceIn(0f, 1f)
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.take(MAX_TEXT_CHARS),
        Normalizer.Form.NFKC,
    ).lowercase(Locale.ROOT)
        .replace(Regex("""[(\[（【].*?[)\]）】]"""), " ")
        .replace(Regex("""\b(feat|ft)\.?\s.*$"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
