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
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f

        val annotations = LyricsFurigana.annotations(text)
        val surface = text.rubySurfaceText(annotations)
        val matched = mutableListOf<MatchedLyricWord>()
        if (annotations.isEmpty()) {
            var searchOffset = 0
            words.forEach { word ->
                val timedText = word.text.rubySurfaceText().trim()
                if (timedText.isEmpty()) return@forEach
                val start = surface.indexOf(timedText, searchOffset)
                // Keep unmatched provider text out of the sweep. Guessing its
                // position used to light the first lyric character at line start.
                if (start < 0) return@forEach
                val end = (start + timedText.length).coerceAtMost(surface.length)
                if (end <= start) return@forEach
                matched += MatchedLyricWord(word.startMs, word.endMs, start, end)
                searchOffset = end
            }
        } else {
            // With character-synced lyrics the ruby itself is also split into
            // timestamped tokens: `((さだめ))` becomes `((`, `さ`, `だ`, `め`, `))`.
            // Searching those tokens in ruby-free text can bind a reading kana
            // to an identical kana elsewhere and move the sweep to the wrong
            // side of the line. Align against source offsets first, then time
            // only glyphs that survive the ruby removal.
            val sourceToSurface = text.rubySurfaceOffsets(annotations)
            var sourceSearchOffset = 0
            words.forEach { word ->
                val timedText = word.text.trim()
                if (timedText.isEmpty()) return@forEach
                val sourceStart = text.indexOf(timedText, sourceSearchOffset)
                if (sourceStart < 0) return@forEach
                val sourceEnd = (sourceStart + timedText.length).coerceAtMost(text.length)
                sourceSearchOffset = sourceEnd

                var visibleStart = Int.MAX_VALUE
                var visibleEnd = -1
                for (sourceIndex in sourceStart until sourceEnd) {
                    val surfaceIndex = sourceToSurface[sourceIndex]
                    if (surfaceIndex < 0) continue // Ruby markers and reading are not lyric glyphs.
                    visibleStart = minOf(visibleStart, surfaceIndex)
                    visibleEnd = maxOf(visibleEnd, surfaceIndex + 1)
                }
                if (visibleEnd > visibleStart) {
                    matched += MatchedLyricWord(word.startMs, word.endMs, visibleStart, visibleEnd)
                }
            }
        }

        if (positionMs >= endMs) return text.length.toFloat()
        if (matched.isEmpty()) return 0f

        var previous: MatchedLyricWord? = null
        matched.forEach { current ->
            if (positionMs < current.startMs) {
                val prior = previous ?: return sourceOffsetForRubySurface(text, annotations, 0f)
                val span = (current.startMs.toDouble() - prior.endMs.toDouble()).coerceAtLeast(1.0)
                val through = ((positionMs.toDouble() - prior.endMs.toDouble()) / span)
                    .takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                val gapStart = prior.endChar.toFloat()
                val gapEnd = maxOf(gapStart, current.startChar.toFloat())
                return sourceOffsetForRubySurface(text, annotations, gapStart + through * (gapEnd - gapStart))
            }
            if (positionMs < current.endMs) {
                val span = (current.endMs.toDouble() - current.startMs.toDouble()).coerceAtLeast(1.0)
                val through = ((positionMs.toDouble() - current.startMs.toDouble()) / span)
                    .takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                val reveal = current.startChar + through * (current.endChar - current.startChar)
                return sourceOffsetForRubySurface(text, annotations, reveal)
            }
            previous = current
        }

        val last = matched.last()
        return sourceOffsetForRubySurface(text, annotations, last.endChar.toFloat())
    }

    /**
     * Kept for callers compiled against the earlier range-based helper. The
     * ranges are no longer needed because alignment runs on ruby-free text.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun revealedChars(positionMs: Long, rubyReadingRanges: List<IntRange>): Float {
        return revealedChars(positionMs)
    }

    private data class MatchedLyricWord(
        val startMs: Long,
        val endMs: Long,
        val startChar: Int,
        val endChar: Int,
    )

    private fun String.rubySurfaceText(
        annotations: List<LyricsFurigana.Annotation> = LyricsFurigana.annotations(this),
    ): String {
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

    /** Map source UTF-16 offsets to the ruby-free text used by the karaoke sweep. */
    private fun String.rubySurfaceOffsets(
        annotations: List<LyricsFurigana.Annotation>,
    ): IntArray {
        val offsets = IntArray(length) { -1 }
        var sourceCursor = 0
        var surfaceCursor = 0
        annotations.forEach { annotation ->
            while (sourceCursor < annotation.sourceStart) {
                offsets[sourceCursor] = surfaceCursor
                sourceCursor++
                surfaceCursor++
            }
            repeat(annotation.base.length) { baseOffset ->
                offsets[annotation.sourceStart + baseOffset] = surfaceCursor + baseOffset
            }
            sourceCursor = annotation.sourceEndExclusive
            surfaceCursor += annotation.base.length
        }
        while (sourceCursor < length) {
            offsets[sourceCursor] = surfaceCursor
            sourceCursor++
            surfaceCursor++
        }
        return offsets
    }

    /** Convert the timed surface offset back to the source offset used by the ruby renderer. */
    private fun sourceOffsetForRubySurface(
        source: String,
        annotations: List<LyricsFurigana.Annotation>,
        offset: Float,
    ): Float {
        if (!offset.isFinite()) return 0f
        if (annotations.isEmpty()) return offset.coerceIn(0f, source.length.toFloat())
        var sourceCursor = 0
        var surfaceCursor = 0
        annotations.forEach { annotation ->
            val ordinaryLength = (annotation.sourceStart - sourceCursor).coerceAtLeast(0)
            val ordinaryEnd = surfaceCursor + ordinaryLength
            if (offset <= ordinaryEnd.toFloat()) {
                return (sourceCursor + (offset - surfaceCursor).coerceAtLeast(0f))
                    .coerceIn(0f, source.length.toFloat())
            }
            surfaceCursor = ordinaryEnd
            val baseEnd = surfaceCursor + annotation.base.length
            if (offset < baseEnd.toFloat()) {
                return (annotation.sourceStart + offset - surfaceCursor)
                    .coerceIn(0f, source.length.toFloat())
            }
            if (offset <= baseEnd.toFloat()) return annotation.sourceEndExclusive.toFloat()
            surfaceCursor = baseEnd
            sourceCursor = annotation.sourceEndExclusive
        }
        return (sourceCursor + (offset - surfaceCursor).coerceAtLeast(0f))
            .coerceIn(0f, source.length.toFloat())
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
