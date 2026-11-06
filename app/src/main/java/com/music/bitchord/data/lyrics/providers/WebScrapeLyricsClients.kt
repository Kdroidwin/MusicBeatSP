package com.music.bitchord.data.lyrics.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

internal object WebScrapeHtmlUtils {
    private val RUBY_BLOCK_REGEX = Regex(
        """<span\s+class=["']ruby["'][^>]*>\s*<span\s+class=["']rb["'][^>]*>([\s\S]*?)</span>\s*<span\s+class=["']rt["'][^>]*>([\s\S]*?)</span>\s*</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY_DEC = Regex("""&#(\d+);""")
    private val NUMERIC_ENTITY_HEX = Regex("""&#x([0-9a-fA-F]+);""")

    /**
     * Converts UtaTen's `<span class="ruby"><span class="rb">漢字</span><span class="rt">かんじ</span></span>`
     * into Tinque's native inline furigana syntax `漢字((かんじ))`.
     */
    fun convertUtatenRubyToFurigana(rawHtml: String): String {
        val withFurigana = RUBY_BLOCK_REGEX.replace(rawHtml) { match ->
            val base = HTML_TAG_REGEX.replace(match.groupValues[1], "").trim()
            val reading = HTML_TAG_REGEX.replace(match.groupValues[2], "").trim()
            if (base.isNotEmpty() && reading.isNotEmpty()) {
                "$base(($reading))"
            } else {
                base
            }
        }
        return htmlToCleanLyrics(withFurigana)
    }

    fun htmlToCleanLyrics(rawHtml: String): String {
        val withBreaks = rawHtml
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:p|div|li|tr)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
        val stripped = HTML_TAG_REGEX.replace(withBreaks, "")
        val decoded = decodeHtmlEntities(stripped)
        return decoded
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    fun decodeHtmlEntities(input: String): String {
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
        s = NUMERIC_ENTITY_DEC.replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        s = NUMERIC_ENTITY_HEX.replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return s
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 11. 歌ネット (Uta-Net - www.uta-net.com)
// ─────────────────────────────────────────────────────────────────────────────
internal object UtaNetLyricsClient {
    private val SONG_ROW_REGEX = Regex(
        """<a\s+href=["']/song/(\d+)/["'][^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val KASHI_AREA_REGEX = Regex(
        """<div[^>]*id=["']kashi_area["'][^>]*>([\s\S]*?)</div>""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
    ): String? = withContext(Dispatchers.IO) {
        val hits = searchSongLinks(title, artist)
        for (hit in hits.take(2)) {
            val text = fetchSongLyrics(hit.songId)
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val hits = withContext(Dispatchers.IO) { searchSongLinks(title, artist) }
        hits.take(3).map { hit ->
            async(Dispatchers.IO) {
                val text = fetchSongLyrics(hit.songId) ?: return@async null
                NativeSearchCandidate(
                    id = "utanet_${hit.songId}",
                    title = hit.title.ifBlank { title },
                    artist = artist,
                    album = album,
                    durationSeconds = durationSec,
                    provider = "歌ネット (Uta-Net)",
                    plainLyrics = text,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private data class UtaNetHit(val songId: String, val title: String)

    private fun searchSongLinks(title: String, artist: String): List<UtaNetHit> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val urls = listOf(
            "https://www.uta-net.com/search/?Aselect=2&Bselect=3&Keyword=${NativeLyricsHttp.urlEncode(cleanTitle)}",
            "https://www.uta-net.com/search/?Keyword=${NativeLyricsHttp.urlEncode("$cleanTitle $cleanArtist".trim())}",
        )
        val seen = HashSet<String>()
        val results = mutableListOf<UtaNetHit>()
        for (url in urls) {
            val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return@use
                for (m in SONG_ROW_REGEX.findAll(html)) {
                    val id = m.groupValues[1]
                    if (!seen.add(id)) continue
                    val rawTitle = WebScrapeHtmlUtils.htmlToCleanLyrics(m.groupValues[2]).lines().firstOrNull().orEmpty()
                    results += UtaNetHit(id, rawTitle)
                    if (results.size >= 5) break
                }
            }
            if (results.isNotEmpty()) break
        }
        results
    }.getOrDefault(emptyList())

    private fun fetchSongLyrics(songId: String): String? = runCatching {
        val url = "https://www.uta-net.com/song/$songId/"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return null
            val kashiHtml = KASHI_AREA_REGEX.find(html)?.groupValues?.get(1) ?: return null
            WebScrapeHtmlUtils.htmlToCleanLyrics(kashiHtml).takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 12. UtaTen (utaten.com - Native Furigana Ruby Support)
// ─────────────────────────────────────────────────────────────────────────────
internal object UtaTenLyricsClient {
    private val LYRIC_LINK_REGEX = Regex(
        """href=["'](/lyric/[^"'\s>]+)["']""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
    ): String? = withContext(Dispatchers.IO) {
        val paths = searchLyricPaths(title, artist)
        for (path in paths.take(2)) {
            val text = fetchLyricPage(path)
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val paths = withContext(Dispatchers.IO) { searchLyricPaths(title, artist) }
        paths.take(3).mapIndexed { idx, path ->
            async(Dispatchers.IO) {
                val text = fetchLyricPage(path) ?: return@async null
                NativeSearchCandidate(
                    id = "utaten_${path.hashCode()}_$idx",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = durationSec,
                    provider = "UtaTen",
                    plainLyrics = text,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private fun searchLyricPaths(title: String, artist: String): List<String> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val url = "https://utaten.com/search?title=${NativeLyricsHttp.urlEncode(cleanTitle)}&artist_name=${NativeLyricsHttp.urlEncode(cleanArtist)}"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return emptyList()
            LYRIC_LINK_REGEX.findAll(html)
                .map { it.groupValues[1] }
                .filterNot { it.contains("=") }
                .distinct()
                .take(4)
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun fetchLyricPage(path: String): String? = runCatching {
        val url = if (path.startsWith("http")) path else "https://utaten.com$path"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return null
            extractUtatenMediumBlock(html)?.let { block ->
                WebScrapeHtmlUtils.convertUtatenRubyToFurigana(block).takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    internal fun extractUtatenMediumBlock(html: String): String? {
        val markerIdx = html.indexOf("""class="medium"""")
            .takeIf { it >= 0 }
            ?: html.indexOf("""class="lyricBody"""")
            .takeIf { it >= 0 }
            ?: return null

        val openTagEnd = html.indexOf('>', markerIdx)
        if (openTagEnd < 0) return null

        var depth = 1
        var cursor = openTagEnd + 1
        while (cursor < html.length && depth > 0) {
            val nextOpen = html.indexOf("<div", cursor, ignoreCase = true)
            val nextClose = html.indexOf("</div>", cursor, ignoreCase = true)
            if (nextClose < 0) break
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen + 4
            } else {
                depth--
                if (depth == 0) {
                    return html.substring(openTagEnd + 1, nextClose)
                }
                cursor = nextClose + 6
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 13. J-Lyric (j-lyric.net)
// ─────────────────────────────────────────────────────────────────────────────
internal object JLyricClient {
    private val SONG_URL_REGEX = Regex(
        """href=["'](https?://j-lyric\.net/artist/a[0-9a-z]+/s[0-9a-z]+\.html)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val LYRIC_P_REGEX = Regex(
        """<p[^>]*id=["']Lyric["'][^>]*>([\s\S]*?)</p>""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
    ): String? = withContext(Dispatchers.IO) {
        val urls = searchSongUrls(title, artist)
        for (url in urls.take(2)) {
            val text = fetchSongPage(url)
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val urls = withContext(Dispatchers.IO) { searchSongUrls(title, artist) }
        urls.take(3).mapIndexed { idx, url ->
            async(Dispatchers.IO) {
                val text = fetchSongPage(url) ?: return@async null
                NativeSearchCandidate(
                    id = "jlyric_${url.hashCode()}_$idx",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = durationSec,
                    provider = "J-Lyric",
                    plainLyrics = text,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private fun searchSongUrls(title: String, artist: String): List<String> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val url = "https://search3.j-lyric.net/index.php?kt=${NativeLyricsHttp.urlEncode(cleanTitle)}&ct=2&ka=${NativeLyricsHttp.urlEncode(cleanArtist)}&ca=2"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return emptyList()
            SONG_URL_REGEX.findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(4)
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun fetchSongPage(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return null
            val rawLyric = LYRIC_P_REGEX.find(html)?.groupValues?.get(1) ?: return null
            WebScrapeHtmlUtils.htmlToCleanLyrics(rawLyric).takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 14. Genius (genius.com API Search + Lyrics Container Extraction)
// ─────────────────────────────────────────────────────────────────────────────
internal object GeniusLyricsClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
    ): String? = withContext(Dispatchers.IO) {
        val hits = searchGeniusSongs(title, artist)
        for (hit in hits.take(2)) {
            val text = fetchGeniusPageLyrics(hit.url)
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val hits = withContext(Dispatchers.IO) { searchGeniusSongs(title, artist) }
        hits.take(3).map { hit ->
            async(Dispatchers.IO) {
                val text = fetchGeniusPageLyrics(hit.url) ?: return@async null
                NativeSearchCandidate(
                    id = "genius_${hit.id}",
                    title = hit.title,
                    artist = hit.artist,
                    album = album,
                    durationSeconds = durationSec,
                    provider = "Genius",
                    plainLyrics = text,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private data class GeniusHit(
        val id: String,
        val title: String,
        val artist: String,
        val url: String,
    )

    private fun searchGeniusSongs(title: String, artist: String): List<GeniusHit> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val q = "$cleanTitle $cleanArtist".trim()
        val url = "https://genius.com/api/search?q=${NativeLyricsHttp.urlEncode(q)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Accept", "application/json")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = NativeLyricsHttp.readBoundedBody(resp.body) ?: return emptyList()
            val hitsArr = JSONObject(body)
                .optJSONObject("response")
                ?.optJSONArray("hits") ?: return emptyList()

            val list = ArrayList<GeniusHit>(hitsArr.length())
            for (i in 0 until hitsArr.length()) {
                val hit = hitsArr.optJSONObject(i) ?: continue
                if (!hit.optString("type", "song").equals("song", ignoreCase = true)) continue
                val res = hit.optJSONObject("result") ?: continue
                val songUrl = res.optString("url")
                if (!songUrl.startsWith("http")) continue
                list += GeniusHit(
                    id = res.optLong("id", i.toLong()).toString(),
                    title = res.optString("title", cleanTitle),
                    artist = res.optJSONObject("primary_artist")?.optString("name", cleanArtist) ?: cleanArtist,
                    url = songUrl,
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private fun fetchGeniusPageLyrics(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = NativeLyricsHttp.readBoundedBody(resp.body) ?: return null
            extractGeniusContainers(html)
        }
    }.getOrNull()

    internal fun extractGeniusContainers(html: String): String? {
        val sb = StringBuilder()
        var searchFrom = 0
        val marker = """data-lyrics-container="true""""
        while (true) {
            val idx = html.indexOf(marker, searchFrom)
            if (idx < 0) break
            val openTagStart = html.lastIndexOf("<div", idx)
            val openTagEnd = html.indexOf('>', idx)
            if (openTagStart < 0 || openTagEnd < 0) break

            var depth = 1
            var cursor = openTagEnd + 1
            var blockEnd = -1
            while (cursor < html.length && depth > 0) {
                val nextOpen = html.indexOf("<div", cursor, ignoreCase = true)
                val nextClose = html.indexOf("</div>", cursor, ignoreCase = true)
                if (nextClose < 0) break
                if (nextOpen in 0 until nextClose) {
                    depth++
                    cursor = nextOpen + 4
                } else {
                    depth--
                    if (depth == 0) {
                        blockEnd = nextClose
                        break
                    }
                    cursor = nextClose + 6
                }
            }
            if (blockEnd > openTagEnd) {
                var inner = html.substring(openTagEnd + 1, blockEnd)
                // Remove Genius contributor/translation header inside lyrics container
                inner = inner.replace(
                    Regex("""<div[^>]*data-exclude-from-selection=["']true["'][^>]*>[\s\S]*?</div>""", RegexOption.IGNORE_CASE),
                    "",
                )
                val cleaned = WebScrapeHtmlUtils.htmlToCleanLyrics(inner)
                if (cleaned.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(cleaned)
                }
                searchFrom = blockEnd + 6
            } else {
                searchFrom = openTagEnd + 1
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }
}
