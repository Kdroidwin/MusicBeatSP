package com.music.bitchord.data.lyrics

/**
 * One word of a line, with the stretch of the song it is sung over.
 *
 * Apple's TTML splits long words into syllables; those are merged back into
 * whole words on the way in, so [startMs] is the first syllable's start and
 * [endMs] the last one's end. Whole words are what the sweep needs — a
 * highlight that ran across "e" and "nough" separately reads as a stutter.
 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Per-syllable timing retained for providers that supply it. */
    val syllables: List<LyricSyllable> = emptyList(),
)

/** A separately timed run of characters within a displayed word. */
data class LyricSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
) {
    companion object {
        fun normalized(parts: List<LyricSyllable>, length: Int): List<LyricSyllable> {
            val usable = parts
                .filter { it.endChar > it.startChar && it.startChar < length }
                .sortedBy { it.startChar }
            if (usable.size < 2) return emptyList()
            return usable.mapIndexed { index, part ->
                LyricSyllable(
                    startMs = part.startMs,
                    endMs = maxOf(part.startMs, part.endMs),
                    startChar = if (index == 0) 0 else part.startChar,
                    endChar = usable.getOrNull(index + 1)?.startChar ?: length,
                )
            }
        }
    }
}

/** Optional left/right alignment for duet lyrics that identify their singers. */
enum class LyricAlignment { Start, End }

/**
 * One synced line. [timeMs] is when it starts; a blank [text] is an
 * instrumental stretch — LRC files mark those with a bare timestamp.
 *
 * [words] is populated only by the providers that carry word-level timing
 * (BetterLyrics, LyricsPlus, SimpMusic's rich sync). LRCLIB has none, so a
 * line from there highlights whole; see [isWordSynced].
 *
 * [sungUntilMs] is the line's own end where a line-synced provider states one,
 * which is what lets an interlude be told apart from a slowly sung line.
 *
 * [background] is the answering vocal — the "(ooh)" or the echoed half-phrase
 * a second voice sings over the lead. It is a line in its own right, with its
 * own stamp and its own words, because that is what it is: it starts partway
 * through the line it answers and routinely runs past the *next* line's stamp.
 * Run into [text] it dragged the sweep along with it, and the cursor — which
 * takes the last line whose stamp has passed — moved on before the bracket had
 * been sung, so the tail of the line was skipped. Kept apart it draws
 * underneath the lead on its own clock. Never nested: a background line's own
 * [background] is always null.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: LyricLine? = null,
    val alignment: LyricAlignment = LyricAlignment.Start,
) {
    val isGap: Boolean get() = text.isEmpty()

    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * Whether anything actually told us when the singing stops, rather than
     * only when it starts. Word timings carry it, and so does a provider that
     * stamps the line's own end ([sungUntilMs]).
     *
     * The distance to the next line's stamp is *not* evidence of an end: that
     * distance is the line's own slot, and on a line-synced source it is
     * routinely ten seconds for a line sung over all ten of them.
     */
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    /**
     * When the last word finishes — or the line's own end where the provider
     * gave one, or [timeMs] when nothing did. Check [hasKnownEnd] before
     * reading a silence out of this.
     *
     * The answering vocal counts: it is still this line being sung, and it
     * regularly holds a note past the lead's last word. Measured without it, a
     * break would be found in the middle of a line that is still going.
     */
    val endMs: Long
        get() {
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }

    /**
     * How far through the line the singing has got, 0..1, as a fractional
     * index into [text]. The sweep reveals up to this character.
     *
     * Within a word it interpolates across that word's own span, so a held
     * note draws slowly and a rattled-off one snaps. Whitespace between two
     * words is credited to the gap between them: it fills as the singer moves
     * on rather than jumping ahead of the next word's first letter.
     */
    fun revealedChars(positionMs: Long): Float {
        return revealedChars(positionMs, emptyList())
    }

    /**
     * Computes the sweep position while ignoring inline ruby readings when
     * locating timed lyric text. A synced kana can also occur inside
     * `漢字((かな))`; a plain `indexOf` would bind that timing to the reading
     * and light the following text before it is sung.
     */
    internal fun revealedChars(positionMs: Long, rubyReadingRanges: List<IntRange>): Float {
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        var offset = 0
        words.forEachIndexed { index, word ->
            // Inline furigana may have been added after the provider supplied
            // word timings. Compare the sung surface form, never the markup.
            val timedText = word.text.rubySurfaceText()
            // Where this word sits in [text]. Built by walking rather than
            // searching, so a word repeated in the line still lines up.
            val safeOffset = offset.coerceIn(0, text.length)
            val matchedStart = text.findTimedText(timedText, safeOffset, rubyReadingRanges).takeIf { it >= 0 }
            val start = matchedStart ?: safeOffset
            // If provider text does not match the edited lyric, hold at the
            // last verified position. Advancing by an assumed word length can
            // sweep arbitrary characters (including a ruby reading) early.
            val end = if (matchedStart == null) start else start + timedText.length.coerceAtMost(text.length - start)
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) {
                if (matchedStart == null) return start.toFloat()
                val span = (word.endMs.toDouble() - word.startMs.toDouble()).coerceAtLeast(1.0)
                val through = ((positionMs.toDouble() - word.startMs.toDouble()) / span)
                    .takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                return (start + through * (end - start)).coerceIn(0f, text.length.toFloat())
            }
            // Past this word: the trailing space fills over the pause before
            // the next one, so the highlight keeps creeping instead of resting
            // on the word's last letter.
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val safeEnd = end.coerceIn(0, text.length)
                val gapStart = text.findTimedText(next.text.rubySurfaceText(), safeEnd, rubyReadingRanges)
                    .takeIf { it >= 0 } ?: safeEnd
                val pause = (next.startMs.toDouble() - word.endMs.toDouble()).coerceAtLeast(1.0)
                val through = ((positionMs.toDouble() - word.endMs.toDouble()) / pause)
                    .takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                return (safeEnd + through * (gapStart - safeEnd)).coerceIn(0f, text.length.toFloat())
            }
            offset = end.coerceIn(0, text.length)
        }
        return text.length.toFloat()
    }

    private fun String.findTimedText(needle: String, startIndex: Int, excludedRanges: List<IntRange>): Int {
        if (needle.isEmpty()) return -1
        var searchFrom = startIndex.coerceIn(0, length)
        while (searchFrom <= length) {
            val found = indexOf(needle, searchFrom)
            if (found < 0) return -1
            val matchEnd = found + needle.length
            val excluded = excludedRanges.firstOrNull { range ->
                !range.isEmpty() && found <= range.last && matchEnd > range.first
            }
            if (excluded == null) return found
            searchFrom = (excluded.last + 1).coerceAtMost(length)
        }
        return -1
    }

    private fun String.rubySurfaceText(): String {
        val annotations = LyricsFurigana.annotations(this)
        if (annotations.isEmpty()) return this
        return buildString(length) {
            var cursor = 0
            annotations.forEach { annotation ->
                append(this@rubySurfaceText, cursor, annotation.sourceStart + annotation.base.length)
                cursor = annotation.sourceEndExclusive
            }
            append(this@rubySurfaceText, cursor, this@rubySurfaceText.length)
        }
    }

    /**
     * How much bloom the word being sung has earned, 0..1.
     *
     * Two things decide it. How long the word is held sets the ceiling — a
     * note carried for a second swells, a word rattled off in a tenth of one
     * barely registers, which is the difference between a glow that belongs to
     * the singing and a lamp dragged along under the text. Then an envelope
     * across the word's own span rises as it lands and eases off as it goes,
     * so each word blooms and lets go rather than the light being on
     * throughout and stepping between brightnesses at every boundary.
     *
     * Zero between words and after the last one, which is what keeps the
     * pauses dark and costs nothing to draw.
     */
    fun glowIntensity(positionMs: Long): Float {
        val word = words.firstOrNull { positionMs < it.endMs } ?: return 0f
        if (positionMs < word.startMs) return 0f

        val held = (word.endMs.toDouble() - word.startMs.toDouble()).coerceAtLeast(1.0)
        val through = ((positionMs.toDouble() - word.startMs.toDouble()) / held)
            .takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        val envelope = when {
            through < GLOW_ATTACK -> through / GLOW_ATTACK
            through > 1f - GLOW_RELEASE -> (1f - through) / GLOW_RELEASE
            else -> 1f
        }
        val pace = ((held - GLOW_FAST_MS).toFloat() / (GLOW_SLOW_MS - GLOW_FAST_MS))
            .coerceIn(0f, 1f)
        return (GLOW_FLOOR + (1f - GLOW_FLOOR) * pace) * envelope.coerceIn(0f, 1f)
    }
}

/** A word this short is patter; it gets [GLOW_FLOOR] and no more. */
private const val GLOW_FAST_MS = 130L

/** A word held this long gets the full bloom. */
private const val GLOW_SLOW_MS = 800L

/** What the quickest words still get, so patter doesn't go completely flat. */
private const val GLOW_FLOOR = 0.22f

/** Share of a word's span spent coming up, and going back down. */
private const val GLOW_ATTACK = 0.18f
private const val GLOW_RELEASE = 0.38f
