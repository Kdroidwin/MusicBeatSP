package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import java.util.Locale

/**
 * Where the player gets its lyrics.
 *
 * Built-in providers and enabled script extensions, tried in [order] — the
 * user's own priority list in Settings. Built-ins come from
 * [BuiltinLyricsSources.sources]; their visible list follows BitChord upstream:
 *
 *  - [BetterLyrics] and [PaxSenix] — Apple Music TTML, per-syllable, from two
 *    independent hosts so one having a bad day doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *  - [Unison], [YouTubeMusicLyrics], [YouTubeTranscriptLyrics], and [Genius]
 *    provide community, video-caption, and plain-text fallbacks.
 *
 * SimpMusic and Megalobiz source files are retained from upstream, but are not
 * listed or queried because upstream marks those providers hidden.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried, and a track with no
 * lyrics anywhere would spend the best part of a minute finding that out with
 * eight of them. Run together, a miss costs whatever the slowest one needed
 * to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = com.music.bitchord.data.settings.AppSettings.lyricsSources.value,
        order: List<LyricsSource> = com.music.bitchord.data.settings.AppSettings.lyricsSourceOrder.value,
        prioritizeSyllableSync: Boolean = false,
        onProviderState: (LyricsSource, LyricsProviderState) -> Unit = { _, _ -> },
    ): Result? {
        LyricsLog.clear()
        LyricsLog.i("Repository", "Looking up lyrics for \"$title\" by \"$artist\" (${durationMs / 1000}s)")

        val strictness = LyricsStrictness.fromPreference(
            com.music.bitchord.data.settings.AppSettings.lyricsAutoStrictness.value,
        )
        val queryArtist = if (strictness.useArtist) artist else ""
        val queryAlbum = album.takeIf { strictness.useAlbum }
        val queryDurationMs = if (strictness.useDuration) durationMs else 0L

        val result = LyricsFetchEngine.fetchLyrics(
            videoId = videoId,
            title = title,
            artist = queryArtist,
            durationMs = queryDurationMs,
            album = queryAlbum,
            sources = sources,
            order = order,
            prioritizeSyllableSync = prioritizeSyllableSync,
            onProviderState = onProviderState,
            fetcher = { source, vId, t, a, dMs, alb ->
                fetch(source, vId, t, a, dMs, alb)
                    ?.takeIf { lines ->
                        // Do not accept lyrics whose timestamps continue far
                        // past this song just because duration matching was
                        // omitted from the provider query.
                        val realDuration = durationMs
                        val lastTimestamp = lines.maxOfOrNull { it.timeMs }
                        realDuration <= 0L || lastTimestamp == null || lastTimestamp <= realDuration + 20_000L
                    }
            }
        ) ?: return null
        return result.copy(lines = applySourceDisplayOptions(result.source, result.lines, title, artist))
    }

    /** Applies provider display options consistently to automatic and manual-search results. */
    internal fun applySourceDisplayOptions(
        source: LyricsSource,
        lines: List<LyricLine>,
        title: String = "",
        artist: String = "",
    ): List<LyricLine> = when (source.id) {
        "qq_music" -> if (com.music.bitchord.data.settings.AppSettings.removeQqMusicLyricMetadata.value ||
            com.music.bitchord.data.settings.AppSettings.removeQqMusicTimestampArtifacts.value
        ) {
            QQMusicLyrics.cleanLines(lines, title, artist.split(Regex("[,，、/&＆]+")))
        } else lines
        "followlyrics" -> {
            val settings = com.music.bitchord.data.settings.AppSettings
            val cleaned = if (settings.removeFollowLyricsCreditRows.value) {
                lines.filterNot { line -> FollowLyrics.isCreditLine(line.text) }
            } else lines
            if (settings.convertFollowLyricsToJapaneseKanji.value ||
                settings.convertFollowLyricsSimplifiedToJapaneseKanji.value
            ) {
                cleaned.map(::convertFollowLyricsLine)
            } else cleaned
        }
        "kugou" -> {
            val settings = com.music.bitchord.data.settings.AppSettings
            val cleaned = if (settings.removeKugouLyricMetadata.value) {
                removeKugouMetadataLines(lines, title, artist)
            } else lines
            if (settings.convertKugouToJapaneseKanji.value) cleaned.map(::convertKugouLine) else cleaned
        }
        "netease" -> if (com.music.bitchord.data.settings.AppSettings
                .convertNeteaseToJapaneseKanji.value
        ) {
            lines.map(::convertNeteaseLine)
        } else lines
        else -> lines
    }

    /** Applies KuGou display options to search-result text without rewriting its timestamps. */
    internal fun applySourceDisplayOptionsToText(
        source: LyricsSource,
        lyrics: String,
        title: String,
        artist: String,
    ): String {
        if (source.id != "kugou" || lyrics.isBlank()) return lyrics
        val settings = com.music.bitchord.data.settings.AppSettings
        var rows = lyrics.split(Regex("\r\n|\r|\n"))
        if (settings.removeKugouLyricMetadata.value) {
            val removed = kugouMetadataRowsToRemove(rows, title, artist)
            rows = rows.filterIndexed { index, _ -> index !in removed }
        }
        if (settings.convertKugouToJapaneseKanji.value) {
            rows = rows.map(JapaneseKanji::convertSimplifiedToJapanese)
        }
        return rows.joinToString("\n")
    }

    private fun removeKugouMetadataLines(lines: List<LyricLine>, title: String, artist: String): List<LyricLine> {
        val removed = kugouMetadataRowsToRemove(lines.map(LyricLine::text), title, artist)
        return lines.filterIndexed { index, _ -> index !in removed }
    }

    /**
     * KuGou can timestamp its opening title and credit block one glyph at a
     * time. Judge reconstructed row text rather than timed tokens, and stop at
     * the first actual lyric so matching words in verses are preserved.
     */
    private fun kugouMetadataRowsToRemove(rows: List<String>, title: String, artist: String): Set<Int> {
        val normalizedTitle = normalizeKugouMetadata(title)
        val normalizedArtists = artist.split(Regex("[,，、/&＆;；|]+"))
            .map(::normalizeKugouMetadata)
            .filter { it.length >= 2 }
        var openingRows = 0
        var lyricsStarted = false
        val removed = mutableSetOf<Int>()

        rows.forEachIndexed { index, rawText ->
            val text = stripKugouTimestamps(rawText).trim()
            if (text.isBlank()) return@forEachIndexed

            val inMetadataHeader = !lyricsStarted && openingRows < MAX_KUGOU_METADATA_HEADER_ROWS
            openingRows++
            val normalizedText = normalizeKugouMetadata(text)
            val labelText = KUGOU_WHITESPACE.replace(text, "")
            val labeledMetadata = KUGOU_METADATA_LABEL.matches(labelText)
            val titleRow = normalizedTitle.length >= 2 && normalizedText == normalizedTitle
            val artistRow = normalizedText.length >= 2 && normalizedArtists.any { normalizedText == it }
            if (inMetadataHeader && (labeledMetadata || titleRow || artistRow)) {
                removed += index
            } else {
                lyricsStarted = true
            }
        }
        return removed
    }

    private fun stripKugouTimestamps(text: String): String =
        KUGOU_LINE_TIMESTAMP.replaceFirst(KUGOU_WORD_TIMESTAMP.replace(text, ""), "")

    private fun normalizeKugouMetadata(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)

    private val KUGOU_METADATA_LABEL = Regex(
        """^\s*(?:歌曲名|曲名|歌名|標題|标题|title|artist|アーティスト|歌手|演唱(?:者)?|歌唱(?:者)?|主唱|vocal(?:ist)?|作詞\s*[/&＆・+]\s*作曲|作词\s*[/&＆・+]\s*作曲|作詞(?:者)?|作词(?:者|人)?|填詞|填词|詞曲|词曲|詞|词|作曲(?:者|人)?|曲|編曲(?:者)?|编曲(?:者)?|lyricist|composer|arranger|lyrics?)\s*[:：=＝]\s*\S.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val KUGOU_WHITESPACE = Regex("""\s+""")
    private val KUGOU_LINE_TIMESTAMP = Regex("""^\s*\[\d{1,3}:\d{2}[.:]\d{1,3}]""")
    private val KUGOU_WORD_TIMESTAMP = Regex("""<\d{1,3}:\d{2}[.:]\d{1,3}>""")

    private const val MAX_KUGOU_METADATA_HEADER_ROWS = 24

    /** Reapply source-specific display options to entries cached by older releases. */
    private fun convertFollowLyricsLine(line: LyricLine): LyricLine {
        val settings = com.music.bitchord.data.settings.AppSettings
        fun convert(text: String): String = when {
            settings.convertFollowLyricsSimplifiedToJapaneseKanji.value ->
                JapaneseKanji.convertSimplifiedToJapanese(text)
            settings.convertFollowLyricsToJapaneseKanji.value -> JapaneseKanji.convert(text)
            else -> text
        }
        return line.copy(
            text = convert(line.text),
            words = line.words.map { word -> word.copy(text = convert(word.text)) },
            background = line.background?.let(::convertFollowLyricsLine),
        )
    }

    private fun convertKugouLine(line: LyricLine): LyricLine = line.copy(
        text = JapaneseKanji.convertSimplifiedToJapanese(line.text),
        words = line.words.map { word -> word.copy(text = JapaneseKanji.convertSimplifiedToJapanese(word.text)) },
        background = line.background?.let(::convertKugouLine),
    )

    private fun convertNeteaseLine(line: LyricLine): LyricLine = line.copy(
        text = JapaneseKanji.convertSimplifiedToJapanese(line.text),
        words = line.words.map { word -> word.copy(text = JapaneseKanji.convertSimplifiedToJapanese(word.text)) },
        background = line.background?.let(::convertNeteaseLine),
    )

    suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? {
        LyricsLog.i(source.label, "Querying ${source.label} (${source.id})...")

        if (source.id in BuiltinLyricsSources.nativeIds) {
            // YouTube-backed lyric providers follow the existing opt-in and
            // offline gates. Merely upgrading never starts YouTube requests.
            if (source.id in BuiltinLyricsSources.youtubeIds &&
                !com.music.bitchord.data.settings.AppSettings.youtubeMusicNetworkAllowed
            ) {
                LyricsLog.i(source.label, "Skipped: YouTube network access is disabled")
                return null
            }
            val searchTitle = title.forLyricsSearch()
            val searchArtist = artist.artistForLyricsSearch()
            val native = BuiltinLyricsSources.fetch(
                id = source.id,
                videoId = videoId,
                title = searchTitle,
                artist = searchArtist,
                durationMs = durationMs,
                album = album,
            )
            if (native.isNullOrEmpty()) LyricsLog.w(source.label, "No lyrics returned")
            else LyricsLog.s(source.label, "Returned ${native.size} lines")
            return native
        }

        val ext = com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager.getExtension(source.id)
        var found: List<LyricLine>? = null

        if (ext != null && ext.scriptFile.exists()) {
            val raw = com.music.bitchord.feature.lyrics.engine.LyricsExtensionExecutor.getLyrics(
                extensionId = ext.id,
                scriptFile = ext.scriptFile,
                title = title,
                artist = artist,
                durationMs = durationMs,
                album = album,
                videoId = videoId,
            )
            if (!raw.isNullOrBlank()) {
                found = ProviderLyrics.parse(raw)
            }
        }

        if (found.isNullOrEmpty()) {
            LyricsLog.w(source.label, "No lyrics returned")
        } else {
            val syncType = when {
                found.any { it.isWordSynced } -> "word-synced"
                found.any { it.timeMs > 0 } -> "line-synced"
                else -> "plain text"
            }
            LyricsLog.s(source.label, "Returned ${found.size} lines ($syncType)")
        }
        return found
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())
}
