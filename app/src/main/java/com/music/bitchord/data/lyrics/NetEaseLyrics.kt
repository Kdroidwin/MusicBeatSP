package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Line- and YRC word-timed lyrics from NetEase Cloud Music. */
object NetEaseLyrics {

    private const val BASE = "https://music.163.com/api/"
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        com.music.bitchord.data.Http.lyricsClient.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val query = "$title $artist".trim()
            if (query.isBlank()) return@withContext null
            LyricsLog.i("NetEase", "Searching NetEase for \"$title\" by \"$artist\"")
            val matches = search(query) ?: return@withContext null
            val match = bestMatch(matches, title, artist, durationMs, album)
            if (match == null) {
                val closest = matches.maxByOrNull { textSimilarity(title.normalizedForMatch(), it.title.normalizedForMatch()) }
                LyricsLog.w(
                    "NetEase",
                    if (closest == null) "No usable song candidates in the search response"
                    else "Rejected ${matches.size} candidates; closest was '${closest.title}' by '${closest.artists.joinToString()}'",
                )
                return@withContext null
            }
            val song = match.song
            LyricsLog.i(
                "NetEase",
                "Selected '${song.title}' by '${song.artists.joinToString()}' " +
                    "(title=${match.titleSimilarity.percent()}, artist=${match.artistSimilarity?.percent() ?: "n/a"}, " +
                    "durationDiff=${match.durationDifferenceMs?.let { "${it / 1000}s" } ?: "n/a"}, " +
                    "score=${match.score.percent()})",
            )
            val response = getLyrics(song.id) ?: return@withContext null

            val settings = com.music.bitchord.data.settings.AppSettings
            val removeCredits = settings.removeNeteaseLyricCredits.value
            val removeBraceCredits = settings.removeNeteaseLeadingBraceCredits.value
            val yrc = response.lyric("yrc")
                ?.let { raw ->
                    raw
                        .let { if (removeCredits) it.withoutCreditLines() else it }
                        .let { if (removeBraceCredits) it.withoutLeadingBraceCredits() else it }
                }
            val timed = yrc?.let { raw ->
                KaraokeLrc.parse(raw)
                    .let { if (settings.keepJapaneseNeteaseLyricLines.value) it.keepJapaneseTranslationRows() else it }
                    .takeIf { lines -> lines.any { it.isWordSynced } }
            }
            if (!timed.isNullOrEmpty()) {
                LyricsLog.s("NetEase", "Returned ${timed.size} word-synced lines")
                return@withContext timed
            }

            val lrc = response.lyric("lrc")
                ?.let { raw ->
                    raw
                        .let { if (removeCredits) it.withoutCreditLines() else it }
                        .let { if (removeBraceCredits) it.withoutLeadingBraceCredits() else it }
                }
            if (lrc == null) {
                LyricsLog.w("NetEase", "Song ${song.id} has no LRC lyrics")
                return@withContext null
            }
            LrcLib.parseLrc(lrc)
                .let { if (settings.keepJapaneseNeteaseLyricLines.value) it.keepJapaneseTranslationRows() else it }
                .takeIf { it.isNotEmpty() }
                ?.also { LyricsLog.s("NetEase", "Returned ${it.size} line-synced lines") }
                ?: run {
                    LyricsLog.w("NetEase", "NetEase returned lyrics that could not be parsed")
                    null
                }
        }

    private fun search(query: String): List<SongHit>? {
        val url = (BASE + "search/pc").toHttpUrl().newBuilder()
            .addQueryParameter("limit", "10")
            .addQueryParameter("type", "1")
            .addQueryParameter("offset", "0")
            .addQueryParameter("os", "pc")
            .addQueryParameter("s", query)
            .build()
        val body = request(url.toString(), "search") ?: return null
        val root = runCatching { lyricsJson.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root == null) {
            LyricsLog.w("NetEase", "Search response was not valid JSON")
            return null
        }
        val songs = root.obj("result")?.array("songs")
            ?: root.obj("data")?.array("songs")
            ?: run {
                LyricsLog.w("NetEase", "Search response contained no song list (code=${root.string("code") ?: "unknown"})")
                return null
            }
        return songs.mapNotNull { element ->
            val song = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = song.long("id") ?: return@mapNotNull null
            val title = song.string("name") ?: return@mapNotNull null
            val artists = (song.array("artists") ?: song.array("ar")).orEmpty().mapNotNull { item ->
                runCatching { item.jsonObject.string("name") }.getOrNull()
            }
            val duration = song.long("duration") ?: song.long("dt")
            val album = song.obj("album")?.string("name")
                ?: song.obj("al")?.string("name")
                ?: song.string("album")
            SongHit(id, title, artists, duration, album)
        }
    }

    private fun bestMatch(
        rows: List<SongHit>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): SongMatch? {
        val targetTitle = title.normalizedForMatch()
        val targetAlbum = album.orEmpty().normalizedForMatch()
        val targetDuration = durationMs.takeIf { it > 0L }
        if (targetTitle.isBlank()) return null

        val matches = rows.mapNotNull { row ->
            val candidateTitle = row.title.normalizedForMatch()
            val titleSimilarity = textSimilarity(targetTitle, candidateTitle)
            val artistSimilarity = artistSimilarity(artist, row.artists)
            val candidateAlbum = row.album.orEmpty().normalizedForMatch()
            val albumSimilarity = if (targetAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                textSimilarity(targetAlbum, candidateAlbum)
            } else null
            val durationDifference = if (targetDuration != null && row.durationMs != null) {
                abs(row.durationMs - targetDuration)
            } else null

            // Reject a different title or performer before ranking. Previously
            // maxByOrNull always returned some result, even when every score was 0.
            if (titleSimilarity < MIN_TITLE_SIMILARITY) return@mapNotNull null
            if (artistSimilarity != null && artistSimilarity < MIN_ARTIST_SIMILARITY) return@mapNotNull null
            if (durationDifference != null && durationDifference > MAX_DURATION_DIFFERENCE_MS &&
                !(titleSimilarity >= 0.98 && (artistSimilarity == null || artistSimilarity >= 0.80))
            ) return@mapNotNull null

            val weightedScore = buildList {
                add(titleSimilarity to TITLE_WEIGHT)
                artistSimilarity?.let { add(it to ARTIST_WEIGHT) }
                durationDifference?.let {
                    add((1.0 - (it.toDouble() / DURATION_SCORE_WINDOW_MS).coerceAtMost(1.0)) to DURATION_WEIGHT)
                }
                albumSimilarity?.let { add(it to ALBUM_WEIGHT) }
            }
            val totalWeight = weightedScore.sumOf { it.second }
            val score = weightedScore.sumOf { (similarity, weight) -> similarity * weight } / totalWeight
            SongMatch(row, titleSimilarity, artistSimilarity, durationDifference, score)
        }

        val ranked = matches.sortedWith(
            compareBy<SongMatch> { it.score }
                .thenBy { it.titleSimilarity }
                .thenBy { it.artistSimilarity ?: 0.0 },
        ).asReversed()
        val best = ranked.firstOrNull()?.takeIf { it.score >= MIN_TOTAL_SIMILARITY } ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null &&
            best.score - runnerUp.score < AMBIGUOUS_SCORE_GAP &&
            (best.artistSimilarity == null || runnerUp.artistSimilarity == null) &&
            best.song.artists.joinToString().normalizedForMatch() !=
            runnerUp.song.artists.joinToString().normalizedForMatch()
        ) return null
        return best
    }

    private fun getLyrics(id: Long): JsonObject? {
        val url = (BASE + "song/lyric").toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("tv", "1")
            .addQueryParameter("rv", "1")
            .addQueryParameter("os", "pc")
            .build()
        val body = request(url.toString(), "lyrics") ?: return null
        return runCatching { lyricsJson.parseToJsonElement(body).jsonObject }
            .getOrNull()
            ?.also { response ->
                if (response.string("code")?.toIntOrNull() !in listOf(null, 200)) {
                    LyricsLog.w("NetEase", "Lyrics endpoint returned code ${response.string("code")}")
                }
            }
    }

    private fun request(url: String, operation: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
            .header("Cache-Control", "max-age=0")
            .header("Cookie", "NMTID=")
            .header("Referer", "https://music.163.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                com.music.bitchord.data.Http.readBoundedUtf8(response.body, 2 * 1024 * 1024)
            } else {
                LyricsLog.w("NetEase", "$operation request failed with HTTP ${response.code}")
                null
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LyricsLog.w("NetEase", "$operation request failed (${error.javaClass.simpleName})")
        null
    }

    private fun JsonObject.lyric(key: String): String? =
        obj(key)?.string("lyric")?.takeIf(String::isNotBlank)

    private fun String.withoutCreditLines(): String = lineSequence()
        .filterNot { NETEASE_CREDIT_LINE.matches(it) }
        .joinToString("\n")

    /** Removes only a brace block at the start of a lyric row, preserving any row timestamp. */
    private fun String.withoutLeadingBraceCredits(): String = lineSequence()
        .map { line ->
            val match = NETEASE_LEADING_BRACE_BLOCK.find(line) ?: return@map line
            val tail = line.substring(match.range.last + 1).trimStart()
            if (tail.isBlank()) "" else match.groupValues[1] + tail
        }
        .filter(String::isNotBlank)
        .joinToString("\n")

    /**
     * NetEase may return a Chinese original and a Japanese translation as
     * neighbouring rows. Only drop Han-only rows when a Japanese-script row
     * is present at the same point in the song, so Chinese-only songs remain.
     */
    private fun List<LyricLine>.keepJapaneseTranslationRows(): List<LyricLine> {
        val japaneseRows = filter { line -> line.text.any { it.isKana() } }
        if (japaneseRows.isEmpty()) return this
        val japaneseTimes = japaneseRows.map(LyricLine::timeMs)
        val kept = filterNot { line ->
            val hasHan = line.text.any { it.isHan() }
            val hasKana = line.text.any { it.isKana() }
            hasHan && !hasKana && japaneseTimes.any { time -> kotlin.math.abs(time - line.timeMs) <= 2_000L }
        }
        return kept.takeIf { it.isNotEmpty() } ?: this
    }

    private fun Char.isKana(): Boolean = this in '\u3040'..'\u30FF' || this in '\u31F0'..'\u31FF'

    private fun Char.isHan(): Boolean = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

    private fun String.normalizedForMatch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)

    private fun textSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.length >= MIN_CONTAINMENT_LENGTH && right.length >= MIN_CONTAINMENT_LENGTH &&
            (left.contains(right) || right.contains(left))
        ) {
            return 0.92 + (0.08 * minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length))
        }

        // Titles are short; a two-row edit-distance calculation is cheap and
        // avoids substring hits making unrelated short titles look identical.
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            current.copyInto(previous)
        }
        val longest = maxOf(left.length, right.length)
        return (1.0 - previous[right.length].toDouble() / longest).coerceAtLeast(0.0)
    }

    private fun artistSimilarity(target: String, candidates: List<String>): Double? {
        val targetNames = target.split(ARTIST_SEPARATOR)
            .map { it.normalizedForMatch() }
            .filter { it.isNotBlank() }
        val candidateNames = candidates.flatMap { it.split(ARTIST_SEPARATOR) }
            .map { it.normalizedForMatch() }
            .filter { it.isNotBlank() }
        if (targetNames.isEmpty() || candidateNames.isEmpty()) return null
        return targetNames.maxOfOrNull { targetName ->
            candidateNames.maxOfOrNull { candidateName -> textSimilarity(targetName, candidateName) } ?: 0.0
        }
    }

    private fun Double.percent(): String = "${(this * 100).toInt()}%"

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.array(key: String) = runCatching { this[key]?.jsonArray }.getOrNull()
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private data class SongHit(
        val id: Long,
        val title: String,
        val artists: List<String>,
        val durationMs: Long?,
        val album: String?,
    )

    private data class SongMatch(
        val song: SongHit,
        val titleSimilarity: Double,
        val artistSimilarity: Double?,
        val durationDifferenceMs: Long?,
        val score: Double,
    )

    private const val MIN_CONTAINMENT_LENGTH = 4
    private const val MIN_TITLE_SIMILARITY = 0.78
    private const val MIN_ARTIST_SIMILARITY = 0.45
    private const val MIN_TOTAL_SIMILARITY = 0.72
    private const val AMBIGUOUS_SCORE_GAP = 0.025
    private const val MAX_DURATION_DIFFERENCE_MS = 45_000L
    private const val DURATION_SCORE_WINDOW_MS = 30_000L
    private const val TITLE_WEIGHT = 0.58
    private const val ARTIST_WEIGHT = 0.27
    private const val DURATION_WEIGHT = 0.10
    private const val ALBUM_WEIGHT = 0.05
    private val ARTIST_SEPARATOR = Regex(
        """\s*(?:,|&|/|;|、|，|＆|×)\s*|\s+(?:feat\.?|ft\.?|with|x)\s+""",
        RegexOption.IGNORE_CASE,
    )

    /** Stamped songwriter/composer/arranger credits are metadata, not sung lines. */
    private val NETEASE_CREDIT_LINE = Regex(
        """^\s*(?:(?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])|(?:\[\d{1,8},\d{1,8}]))+\s*""" +
            """(?:作词|作曲|编曲|作詞|編曲|詞曲|词曲|作詞作曲|词|曲|lyrics?\s+by|written\s+by|composed\s+by|arranged\s+by)\s*[:：].*$""",
        RegexOption.IGNORE_CASE,
    )
    private val NETEASE_LEADING_BRACE_BLOCK = Regex(
        """^((?:(?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])|(?:\[\d{1,8},\d{1,8}]))*)\s*[{｛][^{}｛｝]*[}｝]\s*""",
    )
}
