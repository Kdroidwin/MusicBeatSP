package com.music.bitchord.data.lyrics.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/** Public myTyping search and song-lyric reader. It deliberately avoids ranking/user identity fields. */
internal object MyTypingLyricsClient {
    private const val HOST = "typing.twi1.me"
    private const val ORIGIN = "https://typing.twi1.me"
    private val gamePath = Regex("""^/game/(\d+)/?$""")
    private val gameHref = Regex("""^/?game/(\d+)/?(?:[?#].*)?$""")
    private const val MAX_CANDIDATES = 8

    private data class GameLink(val id: String, val title: String)
    private data class GameLyrics(val title: String, val lyrics: String)

    suspend fun getLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val titleQuery = NativeLyricsHttp.cleanQueryText(title).take(160)
        if (titleQuery.isBlank()) return@withContext null
        val links = searchGames(titleQuery, artist).take(MAX_CANDIDATES)
        for (link in links) {
            val lyrics = fetchGame(link.id) ?: continue
            if (lyrics.lyrics.isNotBlank()) return@withContext lyrics.lyrics
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): List<NativeSearchCandidate> = withContext(Dispatchers.IO) {
        val query = NativeLyricsHttp.cleanQueryText(title).take(160)
        if (query.isBlank()) return@withContext emptyList()
        searchGames(query, artist).take(MAX_CANDIDATES).mapNotNull { link ->
            val result = fetchGame(link.id) ?: return@mapNotNull null
            if (result.lyrics.isBlank()) return@mapNotNull null
            NativeSearchCandidate(
                id = "mytyping_${link.id}",
                title = result.title.ifBlank { link.title }.ifBlank { title },
                artist = artist,
                album = album,
                durationSeconds = durationSeconds.coerceAtLeast(0),
                provider = "myTyping",
                plainLyrics = result.lyrics,
            )
        }
    }

    private suspend fun searchGames(title: String, artist: String): List<GameLink> {
        val searchUrl = ORIGIN.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("list/")
            ?.addQueryParameter("keyword", title)
            ?.build()
            ?: return emptyList()
        val html = requestText(searchUrl) ?: return emptyList()
        val document = Jsoup.parse(html, ORIGIN)
        val seen = HashSet<String>()
        val parsed = document.select("a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            val relative = href.substringBefore('?').substringBefore('#')
            val id = gameHref.matchEntire(href)?.groupValues?.getOrNull(1)
                ?: gamePath.matchEntire(relative)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val linkTitle = anchor.text().trim().take(180)
            GameLink(id, linkTitle)
        }
        val cleanedTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanedArtist = NativeLyricsHttp.cleanQueryText(NativeLyricsHttp.primaryArtist(artist))
        return parsed.sortedByDescending { result ->
            titleMatchScore(cleanedTitle, result.title) +
                if (cleanedArtist.isNotBlank() && result.title.contains(cleanedArtist, ignoreCase = true)) 1 else 0
        }
    }

    private suspend fun fetchGame(gameId: String): GameLyrics? {
        if (!gameId.matches(Regex("\\d{1,12}"))) return null
        val url = "$ORIGIN/game-json".toHttpUrlOrNull() ?: return null
        val form = FormBody.Builder()
            .add("gameId", gameId)
            .add("t", System.currentTimeMillis().toString())
            .build()
        val request = Request.Builder()
            .url(requireAllowedUrl(url))
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/game/$gameId")
            .post(form)
            .build()
        val raw = runCatching {
            NativeLyricsHttp.fastClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                NativeLyricsHttp.readBoundedBody(response.body)
            }
        }.getOrNull() ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: root
        val questions = data.optJSONArray("questions") ?: return null
        val lines = buildList {
            for (index in 0 until questions.length().coerceAtMost(700)) {
                val row = questions.optJSONObject(index) ?: continue
                val rawLine = row.optString("kanji").take(2_000)
                val line = Jsoup.parseBodyFragment(rawLine).text().trim()
                    .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
                if (line.isNotBlank()) add(line)
                if (sumOf(String::length) >= 60_000) break
            }
        }
        if (lines.isEmpty()) return null
        val gameTitle = data.optString("title").trim().take(180)
        return GameLyrics(gameTitle, lines.joinToString("\n").take(60_000))
    }

    private suspend fun requestText(url: HttpUrl): String? {
        val allowed = requireAllowedUrl(url)
        val request = Request.Builder()
            .url(allowed)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        return runCatching {
            NativeLyricsHttp.fastClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                NativeLyricsHttp.readBoundedBody(response.body)
            }
        }.getOrNull()
    }

    private fun requireAllowedUrl(url: HttpUrl): HttpUrl {
        require(url.scheme == "https" && url.host == HOST && url.port == 443)
        return url
    }

    private fun titleMatchScore(query: String, candidate: String): Int {
        if (candidate.isBlank()) return 0
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        return when {
            c == q -> 4
            c.contains(q) || q.contains(c) -> 2
            q.split(' ').any { it.length > 1 && c.contains(it) } -> 1
            else -> 0
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
