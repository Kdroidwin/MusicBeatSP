package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Word- and character-timed lyrics from PetitLyrics (プチリリ).
 *
 * Particularly strong for Japanese pop, anime, Vocaloid, and game soundtracks,
 * returning `<wsy>` XML with millisecond start and end timestamps per character
 * or word.
 */
object PetitLyrics {

    private const val ENDPOINT = "https://p1.petitlyrics.com/api/GetPetitLyricsData.php"
    private const val CLIENT_APP_ID = "p1110417"
    private const val TERMINAL_TYPE = "10"
    private const val DURATION_TOLERANCE_MS = 15_000L
    private const val BRACKET_VARIANT_DURATION_TOLERANCE_MS = 5_000L
    private const val MAX_REQUESTS_PER_LOOKUP = 6
    private const val MAX_FALLBACK_CANDIDATES = 10
    private const val MAX_TITLE_SEARCH_VARIANTS = 2
    private const val MAX_DIRECT_SEARCHES = 3

    private val PLACEHOLDER_ARTISTS = setOf(
        "曲", "song", "video", "動画", "unknown", "unknownartist", "variousartists", "na", "none", "null",
    )

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val rawArtist = artist.trim()
        val cleanArtist = rawArtist.takeUnless { it.isPlaceholderArtist() }.orEmpty()
        if (cleanTitle.isEmpty()) return@withContext null
        val budget = RequestBudget(MAX_REQUESTS_PER_LOOKUP)

        // Try the original metadata first, then bounded variants for credits such as CV/feat.
        val strippedTitle = cleanTitle.stripBrackets()
        val strippedArtist = cleanArtist.stripBrackets()
        val primaryArtist = extractArtistTokens(cleanArtist).firstOrNull()
        val directQueries = buildList {
            if (cleanArtist.isNotEmpty()) add(cleanTitle to cleanArtist)
            if (strippedArtist.isNotEmpty() && (strippedTitle != cleanTitle || strippedArtist != cleanArtist)) {
                add(strippedTitle to strippedArtist)
            }
            if (!primaryArtist.isNullOrBlank()) add(strippedTitle.nfkc() to primaryArtist.nfkc())
        }.distinct().take(MAX_DIRECT_SEARCHES)

        val artistTokens = extractArtistTokens(cleanArtist)
        for ((queryTitle, queryArtist) in directQueries) {
            querySingle(
                title = queryTitle,
                artist = queryArtist,
                expectedTitle = cleanTitle,
                expectedArtistTokens = artistTokens,
                durationMs = durationMs,
                album = album,
                budget = budget,
            )?.let { return@withContext it }
        }

        // One exact-title search and one NFKC/bracket-normalized search cover common metadata variants.
        val titleVariants = listOf(cleanTitle, strippedTitle.nfkc()).distinct()
            .take(MAX_TITLE_SEARCH_VARIANTS)
        for (queryTitle in titleVariants) {
            queryByTitleFallback(
                title = queryTitle,
                expectedTitle = cleanTitle,
                expectedArtistTokens = artistTokens,
                durationMs = durationMs,
                album = album,
                budget = budget,
            )?.let { return@withContext it }
        }
        null
    }

    private fun querySingle(
        title: String,
        artist: String,
        expectedTitle: String,
        expectedArtistTokens: List<String>,
        durationMs: Long,
        album: String?,
        budget: RequestBudget,
    ): List<LyricLine>? {
        val xml = budget.post(
            buildMap {
                put("key_title", title)
                if (artist.isNotEmpty()) put("key_artist", artist)
                put("clientAppId", CLIENT_APP_ID)
                put("lyricsType", "3")
                put("terminalType", TERMINAL_TYPE)
                put("maxCount", "1")
            },
        ) ?: return null
        val song = selectCandidate(
            candidates = parseSongs(xml),
            expectedTitle = expectedTitle,
            expectedArtistTokens = expectedArtistTokens,
            durationMs = durationMs,
            album = album,
        ) ?: return null
        return decodeOrFetchSong(song, budget)
    }

    private fun queryByTitleFallback(
        title: String,
        expectedTitle: String,
        expectedArtistTokens: List<String>,
        durationMs: Long,
        album: String?,
        budget: RequestBudget,
    ): List<LyricLine>? {
        val xml = budget.post(
            mapOf(
                "key_title" to title,
                "clientAppId" to CLIENT_APP_ID,
                "lyricsType" to "3",
                "terminalType" to TERMINAL_TYPE,
                "maxCount" to MAX_FALLBACK_CANDIDATES.toString(),
            ),
        ) ?: return null
        val candidates = parseSongs(xml)
        if (candidates.isEmpty()) return null

        val match = selectCandidate(candidates, expectedTitle, expectedArtistTokens, durationMs, album)
            ?: return null
        return decodeOrFetchSong(match, budget)
    }

    private fun decodeOrFetchSong(song: SongEntry, budget: RequestBudget): List<LyricLine>? {
        val embedded = decodeSongLyrics(song)
        if (embedded?.any { it.isWordSynced } == true) return embedded
        if (song.lyricsId.isBlank()) return embedded

        var best = embedded
        val syncedDetail = fetchSongByLyricsId(song.lyricsId, lyricsType = 3, budget = budget)
            ?.let(::decodeSongLyrics)
        if (syncedDetail?.any { it.isWordSynced } == true) return syncedDetail
        if (syncedDetail?.any { it.timeMs > 0L } == true) best = syncedDetail
        if (best?.any { it.timeMs > 0L } == true) return best

        // Plain lyrics are a final fallback after trying the word-synced detail endpoint.
        val plainDetail = fetchSongByLyricsId(song.lyricsId, lyricsType = 1, budget = budget)
            ?.let(::decodeSongLyrics)
        return plainDetail?.takeIf { it.isNotEmpty() } ?: best
    }

    private fun fetchSongByLyricsId(
        lyricsId: String,
        lyricsType: Int,
        budget: RequestBudget,
    ): SongEntry? {
        val xml = budget.post(
            mapOf(
                "key_lyricsId" to lyricsId,
                "clientAppId" to CLIENT_APP_ID,
                "lyricsType" to lyricsType.toString(),
                "terminalType" to TERMINAL_TYPE,
                "maxCount" to "1",
            ),
        ) ?: return null
        // Do not use a different record if the endpoint responds with an unexpected result.
        return parseSongs(xml).firstOrNull { it.lyricsId == lyricsId }
    }

    private fun decodeSongLyrics(song: SongEntry): List<LyricLine>? {
        if (song.lyricsData.isBlank()) return null
        val decoded = runCatching {
            Base64.getDecoder().decode(song.lyricsData.trim()).toString(Charsets.UTF_8)
        }.getOrNull()?.trim() ?: return null
        if (decoded.isEmpty()) return null

        return when {
            decoded.contains("<wsy", ignoreCase = true) ||
                (song.lyricsType == 3 && decoded.trimStart().startsWith("<")) ->
                parseWsy(decoded).takeIf { it.isNotEmpty() }
            else ->
                EnhancedLrc.parse(decoded)
                    .ifEmpty { LrcLib.parseLrc(decoded) }
                    .ifEmpty { plainLines(decoded) }
                    .takeIf { it.isNotEmpty() }
        }
    }

    internal fun parseWsy(wsyXml: String): List<LyricLine> = runCatching {
        val document = newXmlDocument(wsyXml) ?: return emptyList()
        val lineNodes = document.getElementsByTagName("line")
        val lines = ArrayList<LyricLine>(lineNodes.length)

        for (i in 0 until lineNodes.length) {
            val lineEl = lineNodes.item(i) as? Element ?: continue
            val rawLineString = lineEl.childText("linestring")
            val lineText = rawLineString.trim()
            if (lineText.isEmpty()) continue

            val wordNodes = lineEl.getElementsByTagName("word")
            val timedWords = ArrayList<TimedRun>(wordNodes.length)
            var firstStartMs: Long? = null

            for (w in 0 until wordNodes.length) {
                val wordEl = wordNodes.item(w) as? Element ?: continue
                val startMs = wordEl.childText("starttime").trim().toLongOrNull() ?: continue
                val endMs = wordEl.childText("endtime").trim().toLongOrNull() ?: startMs
                val wordString = wordEl.childText("wordstring")
                if (startMs > 0L && firstStartMs == null) {
                    firstStartMs = startMs
                }
                if (startMs > 0L || endMs > 0L) {
                    timedWords += TimedRun(startMs, maxOf(startMs, endMs), wordString)
                }
            }

            val words = wordsFromRuns(timedWords, spacingIsExplicit = true)
            when {
                // Whole-line timestamp dressed as a single <word>
                words.size == 1 && timedWords.size == 1 && lineText.length > 4 -> {
                    val only = words.first()
                    lines += LyricLine(
                        timeMs = only.startMs,
                        text = lineText,
                        sungUntilMs = only.endMs.takeIf { it > only.startMs },
                    )
                }
                words.isNotEmpty() -> {
                    val lineStart = words.first().startMs
                    val lineEnd = words.last().endMs
                    lines += LyricLine(
                        timeMs = lineStart,
                        text = lineText,
                        words = words,
                        sungUntilMs = lineEnd.takeIf { it > lineStart },
                    )
                }
                firstStartMs != null -> {
                    lines += LyricLine(timeMs = firstStartMs, text = lineText)
                }
                else -> {
                    lines += LyricLine(timeMs = 0L, text = lineText)
                }
            }
        }

        if (lines.any { it.timeMs > 0L }) {
            lines.sortedBy { it.timeMs }.withInstrumentalGaps()
        } else {
            lines
        }
    }.getOrDefault(emptyList())

    private fun parseSongs(xml: String): List<SongEntry> = runCatching {
        val document = newXmlDocument(xml) ?: return emptyList()
        val nodes = document.getElementsByTagName("song")
        val songs = ArrayList<SongEntry>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            songs += SongEntry(
                lyricsId = el.childText("lyricsId").trim(),
                title = el.childText("title").trim(),
                artist = el.childText("artist").trim(),
                album = el.childText("album").trim(),
                lyricsType = el.childText("lyricsType").trim().toIntOrNull() ?: 0,
                durationMs = el.childText("duration").trim().toLongOrNull() ?: 0L,
                lyricsData = el.childText("lyricsData").trim(),
            )
        }
        songs
    }.getOrDefault(emptyList())

    /**
     * Only accept the expected title, then require corroborating artist or duration metadata.
     * Album is a tie-breaker; it cannot turn a different song into a match by itself.
     */
    private fun selectCandidate(
        candidates: List<SongEntry>,
        expectedTitle: String,
        expectedArtistTokens: List<String>,
        durationMs: Long,
        album: String?,
    ): SongEntry? {
        val expectedTitleKey = expectedTitle.normalizeForMatch()
        val expectedLooseTitleKey = expectedTitle.stripBrackets().normalizeForMatch()
        if (expectedTitleKey.isBlank()) return null

        val expectedArtistKeys = expectedArtistTokens
            .map { it.normalizeForMatch() }
            .filter { it.isNotBlank() }
            .toSet()
        val expectedAlbumKey = album?.normalizeForMatch().orEmpty()

        val ranked = candidates.mapNotNull { candidate ->
            val candidateTitleKey = candidate.title.normalizeForMatch()
            if (candidateTitleKey.isBlank()) return@mapNotNull null

            val exactTitleMatch = candidateTitleKey == expectedTitleKey
            val bracketVariantMatch = candidate.title.stripBrackets().normalizeForMatch() == expectedLooseTitleKey
            if (!exactTitleMatch && !bracketVariantMatch) return@mapNotNull null

            val candidateArtistKeys = extractArtistTokens(candidate.artist)
                .map { it.normalizeForMatch() }
                .filter { it.isNotBlank() }
                .toSet()
            val artistMatch = expectedArtistKeys.any { it in candidateArtistKeys }
            val artistConflict = expectedArtistKeys.isNotEmpty() &&
                candidateArtistKeys.isNotEmpty() && !artistMatch
            if (artistConflict) return@mapNotNull null

            val hasDuration = durationMs > 0L && candidate.durationMs > 0L
            val durationDifference = if (hasDuration) {
                abs(candidate.durationMs - durationMs)
            } else {
                Long.MAX_VALUE
            }
            if (hasDuration && durationDifference > DURATION_TOLERANCE_MS) return@mapNotNull null
            val durationMatch = hasDuration && durationDifference <= DURATION_TOLERANCE_MS

            if (exactTitleMatch) {
                if (!artistMatch && !durationMatch) return@mapNotNull null
            } else {
                // A bracket-stripped match can hide edition labels, so require both signals tightly.
                if (!artistMatch || !hasDuration || durationDifference > BRACKET_VARIANT_DURATION_TOLERANCE_MS) {
                    return@mapNotNull null
                }
            }

            val albumMatch = expectedAlbumKey.isNotBlank() &&
                candidate.album.normalizeForMatch() == expectedAlbumKey
            val score = (if (exactTitleMatch) 100 else 80) +
                (if (artistMatch) 20 else 0) +
                (if (durationMatch) 10 else 0) +
                (if (albumMatch) 2 else 0)
            candidate to score
        }
        val bestScore = ranked.maxOfOrNull { it.second } ?: return null
        val bestCandidates = ranked.asSequence()
            .filter { it.second == bestScore }
            .map { it.first }
            .distinctBy(::songIdentity)
            .toList()
        return bestCandidates.singleOrNull()
    }

    private fun songIdentity(song: SongEntry): String =
        song.lyricsId.takeIf { it.isNotBlank() }
            ?: listOf(
                song.title.normalizeForMatch(),
                song.artist.normalizeForMatch(),
                song.album.normalizeForMatch(),
                song.durationMs.toString(),
                song.lyricsData,
            ).joinToString("|")

    private fun extractArtistTokens(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return emptyList()
        return rawArtist
            .stripBrackets()
            .split(Regex("""[,、&＆/／・×]|\s+(?i:feat|ft|featuring|with|cv)\.?\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.isPlaceholderArtist() }
            .distinctBy { it.normalizeForMatch() }
    }

    private fun String.nfkc(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKC)

    /** Punctuation and width normalization for matching; preserves Japanese long-vowel marks. */
    private fun String.normalizeForMatch(): String =
        nfkc()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s!！?？,、。.&＆/／・×~〜\-'’”]+"""), "")

    private fun String.isPlaceholderArtist(): Boolean =
        normalizeForMatch() in PLACEHOLDER_ARTISTS

    private class RequestBudget(initialRequests: Int) {
        private var remaining = initialRequests

        fun post(form: Map<String, String>): String? {
            if (remaining <= 0) return null
            remaining -= 1
            return lyricsPostForm(url = ENDPOINT, form = form)
        }
    }

    private fun plainLines(text: String): List<LyricLine> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeMs = 0L, text = it) }
            .toList()

    private fun newXmlDocument(xml: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { isExpandEntityReferences = false }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull()

    private fun Element.childText(tagName: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName.equals(tagName, ignoreCase = true)) {
                return node.textContent.orEmpty()
            }
        }
        return ""
    }

    private fun String.stripBrackets(): String =
        replace(Regex("""[(（\[【].*?[)）\]】]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { trim() }

    private data class SongEntry(
        val lyricsId: String,
        val title: String,
        val artist: String,
        val album: String,
        val lyricsType: Int,
        val durationMs: Long,
        val lyricsData: String,
    )
}
