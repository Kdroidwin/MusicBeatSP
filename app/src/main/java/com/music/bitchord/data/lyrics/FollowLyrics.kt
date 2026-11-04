package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup

/** Search and LRC extraction for Follow Lyrics. */
internal object FollowLyrics {
    private const val BASE = "https://followlyrics.com"
    private val stamp = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]""")
    private val stampedBlock = Regex("""(\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])([\s\S]*?)(?=\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]|$)""")
    private val credits = Regex("""(?i)^\s*(?:作词|作詞|作曲|编曲|編曲|词|曲|詞|arranger|composer|lyricist|lyrics|music|producer)\s*[:：|].*$""")

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val searchUrl = "$BASE/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", title.cleanQuery())
                    .addQueryParameter("type", "song")
                    .build()
                val search = get(searchUrl.toString())?.let(Jsoup::parse) ?: return@runCatching null
                val candidates = search.select("tr").mapNotNull { row ->
                    val href = row.selectFirst("a[href^=/lyrics/]")?.attr("href") ?: return@mapNotNull null
                    val cells = row.select("td")
                    val candidateTitle = cells.getOrNull(0)?.text().orEmpty()
                    val candidateArtist = cells.getOrNull(1)?.text().orEmpty()
                    if (!matchesTitle(candidateTitle, title) || !matchesArtist(candidateArtist, artist)) return@mapNotNull null
                    "$BASE${if (href.startsWith("/")) href else "/$href"}"
                }.distinct().take(4)
                for (pageUrl in candidates) {
                    val page = get(pageUrl)?.let(Jsoup::parse) ?: continue
                    val pageText = page.body()?.wholeText().orEmpty()
                    val lrcText = stampedBlock.findAll(pageText).mapNotNull { match ->
                        val body = match.groupValues[2].trim().removePrefix("|").trim().replace(Regex("\\s+"), " ")
                        if (body.isBlank() || credits.matches(body)) return@mapNotNull null
                        if (artist.isNotBlank() && body.contains(title, true) && body.contains(artist, true)) return@mapNotNull null
                        match.groupValues[1] + body
                    }.joinToString("\n")
                    if (stamp.findAll(lrcText).count() < 2) continue
                    LrcLib.parseLrc(lrcText).takeIf { it.any { line -> line.text.isNotBlank() } }
                        ?.let { return@runCatching it }
                }
                null
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "MusicBeat/2.9").build()
        Http.lyricsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return Http.readBoundedUtf8(response.body, 3 * 1024 * 1024)
        }
    }

    private fun matchesTitle(candidate: String, query: String): Boolean {
        val a = candidate.normalizeMatch()
        val b = query.normalizeMatch()
        return a.isNotBlank() && b.isNotBlank() && (a == b || a.startsWith(b) || b.startsWith(a))
    }

    private fun matchesArtist(candidate: String, query: String): Boolean {
        if (query.isBlank()) return true
        val a = candidate.normalizeMatch()
        val b = query.normalizeMatch()
        return a.isNotBlank() && (a == b || a.contains(b) || b.contains(a))
    }

    private fun String.normalizeMatch() = lowercase()
        .replace(Regex("[\\s\\p{Punct}　]+"), "")

    private fun String.cleanQuery() = substringBefore("｜").substringBefore("|")
        .replace(Regex("\\s+"), " ").trim()
}
