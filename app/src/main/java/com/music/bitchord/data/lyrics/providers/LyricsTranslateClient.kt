package com.music.bitchord.data.lyrics.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.Locale

/** Native scraper for LyricsTranslate's server-rendered song search and lyric pages. */
internal object LyricsTranslateClient {
    private const val BASE_URL = "https://lyricstranslate.com"
    private const val SEARCH_LANGUAGE = "ja"
    private const val PROVIDER_NAME = "LyricsTranslate"
    private const val MAX_SEARCH_HITS = 6
    private const val MIN_SEARCH_TITLE_SIMILARITY = 0.36
    private const val MIN_AUTO_TITLE_SIMILARITY = 0.58
    private val LYRIC_LINE_CLASS = Regex("""ll-\d+-\d+""")

    private data class SongHit(
        val url: String,
        val title: String,
        val artist: String,
    )

    suspend fun getLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val candidates = searchLyrics(title, artist, album = null, durationSec = 0)
        val best = candidates
            .filter { titleSimilarity(title, it.title) >= MIN_AUTO_TITLE_SIMILARITY }
            .maxByOrNull { candidateScore(title, artist, it.title, it.artist) }
        best?.plainLyrics?.takeIf(String::isNotBlank)
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = withContext(Dispatchers.IO) {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title).take(180)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist).take(180)
        val query = listOf(cleanTitle, cleanArtist)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (query.isBlank()) return@withContext emptyList()

        val hits = searchSongs(query)
            .map { hit -> hit to titleSimilarity(cleanTitle, hit.title) }
            .filter { (_, similarity) -> similarity >= MIN_SEARCH_TITLE_SIMILARITY }
            .sortedWith(
                compareByDescending<Pair<SongHit, Double>> { candidateScore(cleanTitle, cleanArtist, it.first.title, it.first.artist) }
                    .thenByDescending { it.second },
            )
            .take(MAX_SEARCH_HITS)

        coroutineScope {
            hits.map { (hit, _) ->
                async(Dispatchers.IO) {
                    val lyrics = fetchSongLyrics(hit.url) ?: return@async null
                    NativeSearchCandidate(
                        id = "lyricstranslate_${Integer.toHexString(hit.url.hashCode())}",
                        title = hit.title.ifBlank { cleanTitle },
                        artist = hit.artist.ifBlank { cleanArtist },
                        album = album,
                        // LyricsTranslate does not expose track duration or timestamps.
                        durationSeconds = 0,
                        provider = PROVIDER_NAME,
                        plainLyrics = lyrics,
                    )
                }
            }.awaitAll().filterNotNull()
                .sortedByDescending { candidateScore(cleanTitle, cleanArtist, it.title, it.artist) }
        }
    }

    private fun searchSongs(query: String): List<SongHit> = runCatching {
        val url = BASE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("$SEARCH_LANGUAGE/site-search")
            ?.addQueryParameter("query", query)
            ?.build()
            ?: return emptyList()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Accept", "text/html")
            .build()

        NativeLyricsHttp.fastClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !isLyricsTranslateHost(response.request.url)) return emptyList()
            val html = NativeLyricsHttp.readBoundedBody(response.body) ?: return emptyList()
            val document = Jsoup.parse(html, response.request.url.toString())

            // The HAR shows separate server-rendered "songs" and full-text
            // "lyrics" groups. Prefer the song catalogue; use full-text matches
            // only when the catalogue has no candidate for this query.
            val songItems = document.select(".search-res__block._songs .block-search-res__item")
            val items = songItems.ifEmpty {
                document.select(".search-res__block._lyrics .block-search-res__item")
            }
            val seen = HashSet<String>()
            items.mapNotNull { item ->
                val titleLink = item.selectFirst("a.block-1-table__title[href]") ?: return@mapNotNull null
                val target = safeSongPageUrl(titleLink.absUrl("href")) ?: return@mapNotNull null
                if (!seen.add(target)) return@mapNotNull null
                val candidateTitle = titleLink.text().trim()
                if (candidateTitle.isBlank()) return@mapNotNull null
                val candidateArtist = item.selectFirst("a.block-1-table__author")?.text()?.trim().orEmpty()
                SongHit(target, candidateTitle, candidateArtist)
            }
        }
    }.getOrDefault(emptyList())

    private fun fetchSongLyrics(url: String): String? = runCatching {
        val safeUrl = safeSongPageUrl(url) ?: return null
        val request = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Accept", "text/html")
            .build()
        NativeLyricsHttp.fastClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !isLyricsTranslateHost(response.request.url)) return null
            val html = NativeLyricsHttp.readBoundedBody(response.body) ?: return null
            extractOriginalLyrics(html, response.request.url.toString())
        }
    }.getOrNull()

    /**
     * The supplied HAR has original lyrics in `#song-body .ltf .par > .ll-*`.
     * Translation blocks and page chrome live outside that tree and are omitted.
     */
    internal fun extractOriginalLyrics(html: String, baseUrl: String = BASE_URL): String? {
        val document = Jsoup.parse(html, baseUrl)
        val lyricRoot = document.selectFirst("#song-body .ltf") ?: return null
        val verses = lyricRoot.children()
            .filter { it.hasClass("par") }
            .mapNotNull { paragraph ->
                paragraph.children()
                    .filter { element -> element.classNames().any(LYRIC_LINE_CLASS::matches) }
                    .map { element -> cleanLyricLine(element) }
                    .filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
            }
        return verses.joinToString("\n\n").trim().takeIf(String::isNotBlank)
    }

    private fun cleanLyricLine(element: Element): String = element
        .clone()
        .also { clone ->
            // Furigana readings are annotations, not a second lyric phrase.
            clone.select("rt, .rt").remove()
        }
        .text()
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun safeSongPageUrl(value: String): String? {
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (!isLyricsTranslateHost(parsed)) return null
        val path = parsed.encodedPath.lowercase(Locale.ROOT)
        if (!(path.endsWith("-lyrics.html") || path.endsWith("-lyrics"))) return null
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun isLyricsTranslateHost(url: HttpUrl): Boolean =
        url.isHttps && url.host in setOf("lyricstranslate.com", "www.lyricstranslate.com")

    private fun candidateScore(queryTitle: String, queryArtist: String, resultTitle: String, resultArtist: String): Double {
        val titleScore = titleSimilarity(queryTitle, resultTitle)
        val artistScore = if (queryArtist.isBlank()) 0.5 else similarity(queryArtist, resultArtist)
        return titleScore * 0.82 + artistScore * 0.18
    }

    private fun titleSimilarity(left: String, right: String): Double = similarity(
        NativeLyricsHttp.cleanQueryText(left),
        NativeLyricsHttp.cleanQueryText(right),
    )

    private fun similarity(left: String, right: String): Double {
        val a = normalizeForMatch(left)
        val b = normalizeForMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) {
            return minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        }
        if (maxOf(a.length, b.length) > 180) return 0.0

        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val substitutionCost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost,
                )
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun normalizeForMatch(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
