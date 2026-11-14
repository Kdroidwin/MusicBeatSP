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
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val stampedBlock = Regex("""(\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])([\s\S]*?)(?=\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]|$)""")
    private val credits = Regex(
        """(?i)^\s*(?:作词者?|作詞者?|作曲者?|编曲者?|編曲者?|詞作者|词作者|詞|词|曲|arranger|composer|lyricist|lyrics|music|producer)\s*[:：|]\s*.+$""",
    )

    internal fun isCreditLine(text: String): Boolean = credits.matches(text.trim())

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
                    val pageText = page.body().wholeText()
                    val blocks = stampedBlock.findAll(pageText).mapNotNull { match ->
                        val stampMatch = stamp.matchAt(match.groupValues[1], 0) ?: return@mapNotNull null
                        val (minutes, seconds, fraction) = stampMatch.destructured
                        val fractionMs = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0L
                        }
                        val timeMs = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
                        val body = match.groupValues[2].trim().removePrefix("|").trim().replace(Regex("\\s+"), " ")
                        Triple(timeMs, match.groupValues[1], body)
                    }.toList()

                    // FollowLyrics appends pinyin and tone-marked pinyin as
                    // separate timed sections. Flattening the whole page makes
                    // those translations look like extra lyrics at the exact
                    // same timestamps. The original section ends when its next
                    // line jumps back to the opening timestamp.
                    val firstTime = blocks.firstOrNull()?.first ?: continue
                    val originalBlock = buildList<Triple<Long, String, String>> {
                        var previousTime = firstTime
                        for ((timeMs, timeStamp, body) in blocks) {
                            if (isNotEmpty() && timeMs <= firstTime + 1_000 && previousTime > timeMs + 10_000) break
                            add(Triple(timeMs, timeStamp, body))
                            previousTime = timeMs
                        }
                    }
                    val lrcText = originalBlock.mapNotNull { (_, timeStamp, body) ->
                        if (body.isBlank() ||
                            (com.music.bitchord.data.settings.AppSettings.removeFollowLyricsCreditRows.value &&
                                isCreditLine(body))
                        ) return@mapNotNull null
                        if (artist.isNotBlank() && body.contains(title, true) && body.contains(artist, true)) return@mapNotNull null
                        "$timeStamp$body"
                    }.joinToString("\n")
                    if (stamp.findAll(lrcText).count() < 2) continue
                    val parsed = LrcLib.parseLrc(lrcText)
                        .takeIf { it.any { line -> line.text.isNotBlank() } }
                    parsed?.let { return@runCatching it }
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
