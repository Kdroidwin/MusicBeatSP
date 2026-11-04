package com.music.bitchord.data.lyrics

import android.net.Uri
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/** Search and LRC extraction for 酷歌词 (Kugeci). */
internal object KugeciLyrics {
    private const val BASE = "https://www.kugeci.com"
    private val timedLine = Regex("""(?m)^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\].+\s*$""")
    private val credits = Regex("""(?i)^\s*(?:作词|作詞|作曲|编曲|編曲|词|曲|詞|曲|arranger|composer|lyricist|作詞者|作曲者)\s*[:：].*$""")
    private val httpClient: OkHttpClient by lazy {
        Http.lyricsClient.newBuilder()
            .callTimeout(7, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            try {
                val cleanTitle = title.cleanQuery()
                if (cleanTitle.isBlank()) return@withContext null

                // Kugeci ranks title-only searches poorly on common song names.
                // Search with the artist first, then retain the title-only
                // fallback for sites where the singer spelling differs.
                val queries = listOfNotNull(
                    "$cleanTitle ${artist.cleanQuery()}".trim().takeIf { artist.isNotBlank() },
                    cleanTitle,
                ).distinct()
                val pages = linkedSetOf<String>()
                for (query in queries) {
                    val searchUrl = "$BASE/search".toHttpUrl().newBuilder()
                        .addQueryParameter("q", query)
                        .build()
                    val searchHtml = get(searchUrl.toString()) ?: continue
                    val search = Jsoup.parse(searchHtml)
                    search.select("a[href]").forEach { link ->
                        val rawUrl = link.absUrl("href").ifBlank { link.attr("href") }
                        val parsedUri = Uri.parse(rawUrl)
                        if (!parsedUri.host.equals("www.kugeci.com", ignoreCase = true)) return@forEach
                        if (!parsedUri.path.orEmpty().matches(Regex("""/song/[A-Za-z0-9_-]+/?"""))) return@forEach
                        pages += parsedUri.buildUpon().clearQuery().fragment(null).build().toString()
                    }
                    if (pages.size >= 12) break
                }
                if (pages.isEmpty()) {
                    LyricsLog.i("Kugeci", "Search returned no song pages for title/artist query")
                    return@withContext null
                }

                for (pageUrl in pages.take(12)) {
                    val pageHtml = get(pageUrl) ?: continue
                    val page = Jsoup.parse(pageHtml)
                    val pageTitle = page.selectFirst("title")?.text().orEmpty()
                    val titleAndArtist = pageTitle.substringBefore(" - 酷歌词")
                    val songTitle = page.selectFirst("main h1, .container h1, h1")?.text().orEmpty()
                        .ifBlank { titleAndArtist.substringAfter(" - ", titleAndArtist) }
                    val pageArtistText = page.select("a[href*='/singer/'], a[href*='/artist/']")
                        .joinToString(" ") { it.text().replace(Regex("(?i)^\\s*(?:演唱|歌手)\\s*[:：]?\\s*"), "") }
                        .ifBlank { titleAndArtist.substringBefore(" - ") }
                    if (!matchesTitle(songTitle, cleanTitle) || !matchesArtist(pageArtistText, artist)) continue

                    // The site's actual detail pages embed the LRC in
                    // #lyricsContainer (confirmed by the supplied HAR). A
                    // download link is only a fallback for older page builds.
                    val downloadUrl = page.selectFirst("a.downloadlrc[href], a[href*='/download/lrc/']")
                        ?.let { it.absUrl("href").ifBlank { it.attr("href") } }
                        ?.takeIf { Uri.parse(it).host.equals("www.kugeci.com", ignoreCase = true) }
                    val pageLrc = page.selectFirst("#lyricsContainer")?.let(::preserveBreaks)
                        ?.takeIf { timedLine.findAll(it).count() >= 2 }
                    val downloadedLrc = if (pageLrc == null) downloadUrl?.let { get(it) } else null
                    val lrc = pageLrc ?: downloadedLrc?.takeIf { timedLine.findAll(it).count() >= 2 } ?: continue
                    val parsed = parseTimedLrc(lrc, title)
                    if (parsed.isNotEmpty()) {
                        LyricsLog.i("Kugeci", "Matched ${parsed.size} timed lines for '$songTitle'")
                        return@withContext parsed
                    }
                }
                LyricsLog.i("Kugeci", "No verified title/artist match among ${pages.size} song pages")
                null
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                LyricsLog.w("Kugeci", "Lookup failed (${error.javaClass.simpleName})")
                null
            }
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

    private suspend fun get(url: String): String? {
        val direct = withContext(Dispatchers.IO) {
            runCatching {
                val cookie = KugeciWebViewFetcher.cookieFor(url)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", KugeciWebViewFetcher.userAgent() ?: "Mozilla/5.0")
                    .header("Accept", "text/html, text/plain, */*")
                    .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = Http.readBoundedUtf8(response.body, 2 * 1024 * 1024)
                    DirectResponse(response.code, body)
                }
            }.getOrNull()
        }
        val body = direct?.body
        if (direct?.code?.let { it in 200..299 } == true &&
            !body.isNullOrBlank() && isExpectedPage(url, body)
        ) {
            return body
        }
        if (direct?.code == 403 || direct?.code == null ||
            (!body.isNullOrBlank() && !isExpectedPage(url, body))
        ) {
            LyricsLog.i("Kugeci", "Direct response was not a usable Kugeci page; retrying in WebView")
        }
        return KugeciWebViewFetcher.fetchHtml(url)?.takeIf { isExpectedPage(url, it) }
    }

    private fun isExpectedPage(url: String, body: String): Boolean {
        if (body.isBlank() || KugeciWebViewFetcher.isCloudflareChallenge(body)) return false
        val path = Uri.parse(url).path.orEmpty()
        return when {
            path == "/search" -> {
                val document = Jsoup.parse(body)
                document.selectFirst("form[action*='/search']") != null
            }
            path.startsWith("/song/") -> {
                val document = Jsoup.parse(body)
                document.selectFirst("#lyricsContainer") != null && document.selectFirst("h1") != null
            }
            path.startsWith("/download/lrc/") -> timedLine.findAll(body).count() >= 2
            else -> false
        }
    }

    private data class DirectResponse(val code: Int, val body: String?)

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
