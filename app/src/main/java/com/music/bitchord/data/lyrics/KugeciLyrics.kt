package com.music.bitchord.data.lyrics

import android.net.Uri
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Search and LRC extraction for 酷歌词 (Kugeci). */
internal object KugeciLyrics {
    private const val BASE = "https://www.kugeci.com"
    private val timedLine = Regex("""(?m)^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\].+\s*$""")
    private val credits = Regex("""(?i)^\s*(?:作词|作詞|作曲|编曲|編曲|词|曲|詞|曲|arranger|composer|lyricist|作詞者|作曲者)\s*[:：].*$""")

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanTitle = title.cleanQuery()
                if (cleanTitle.isBlank()) return@runCatching null
                val searchUrl = "$BASE/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", cleanTitle)
                    .build()
                val search = get(searchUrl.toString())?.let(Jsoup::parse) ?: return@runCatching null
                val pages = search.select("a[href]").mapNotNull { link ->
                    if (!matchesTitle(link.text(), cleanTitle)) return@mapNotNull null
                    val rawUrl = link.absUrl("href").ifBlank { link.attr("href") }
                    val path = Uri.parse(rawUrl).path.orEmpty()
                    if (!path.matches(Regex("""/song/[A-Za-z0-9_-]+/?"""))) return@mapNotNull null
                    if (rawUrl.startsWith("/")) BASE + rawUrl else rawUrl
                }.distinct().take(6)
                for (pageUrl in pages) {
                    val page = get(pageUrl)?.let(Jsoup::parse) ?: continue
                    val pageTitle = page.selectFirst("title")?.text().orEmpty()
                    val titleAndArtist = pageTitle.substringBefore(" - 酷歌词")
                    val pageArtistText = page.select("a[href]").filter { it.attr("href").contains("/artist/") }.joinToString(" ") { it.text() }
                        .ifBlank { titleAndArtist.substringBefore(" - ") }
                    if (!matchesTitle(pageTitle, cleanTitle) || !matchesArtist(pageArtistText, artist)) continue
                    val lrc = page.selectFirst("#lyricsContainer")?.let(::preserveBreaks) ?: continue
                    val parsed = parseTimedLrc(lrc, title)
                    if (parsed.isNotEmpty()) return@runCatching parsed
                }
                null
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private fun preserveBreaks(element: Element): String = element.html()
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(?:p|div|li)>"), "\n")
        .let { Jsoup.parseBodyFragment(it).textWithLineBreaks() }

    private fun org.jsoup.nodes.Document.textWithLineBreaks(): String =
        body().wholeText().ifBlank { body().text() }

    private fun parseTimedLrc(raw: String, title: String): List<LyricLine> {
        val usable = raw.lineSequence().map(String::trim)
            .filter { it.startsWith("[") }
            .map { line ->
                val stampEnd = line.indexOf(']')
                if (stampEnd < 0) line else line.substring(0, stampEnd + 1) + line.substring(stampEnd + 1).trim().removePrefix("|").trimStart()
            }
            .filterNot { credits.matches(it.substringAfter(']').trim()) }
            .filterNot { it.substringAfter(']').trim().contains(" - ") && it.substringAfter(']').trim().contains(title, ignoreCase = true) }
            .joinToString("\n")
        if (timedLine.findAll(usable).count() < 2) return emptyList()
        return LrcLib.parseLrc(usable).filter { line -> line.text.isNotBlank() || line.isGap }
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "MusicBeat/2.9").build()
        Http.lyricsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return Http.readBoundedUtf8(response.body, 2 * 1024 * 1024)
        }
    }

    private fun matchesTitle(candidate: String, query: String): Boolean {
        val a = candidate.normalizeMatch()
        val b = query.normalizeMatch()
        return a.contains(b) || b.contains(a)
    }

    private fun matchesArtist(candidate: String, query: String): Boolean {
        if (query.isBlank()) return true
        val a = candidate.normalizeMatch()
        val b = query.normalizeMatch()
        return a.contains(b) || b.contains(a)
    }

    private fun String.normalizeMatch() = lowercase()
        .replace(Regex("[\\s\\p{Punct}　]+"), "")
        .replace("feat.", "")

    private fun String.cleanQuery() = substringBefore("｜").substringBefore("|")
        .replace(Regex("\\s+"), " ").trim()
}
