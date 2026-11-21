package com.music.bitchord.data.lyrics.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Normalizer

/** Public myTyping search and song-lyric reader. It deliberately avoids ranking/user identity fields. */
internal object MyTypingLyricsClient {
    private const val HOST = "typing.twi1.me"
    private const val ORIGIN = "https://typing.twi1.me"
    private val gamePath = Regex("""^/game/(\d+)/?$""")
    private val gameHref = Regex("""^/?game/(\d+)/?(?:[?#].*)?$""")
    private const val MAX_CANDIDATES = 8
    /** Scores at 66% or lower are too weak to offer as lyric candidates. */
    private const val MIN_TITLE_MATCH_PERCENT = 67

    private data class GameLink(val id: String, val title: String)
    private data class GameLyrics(val title: String, val lyrics: String)

    suspend fun getLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val titleQuery = NativeLyricsHttp.cleanQueryText(title).take(160)
        if (titleQuery.isBlank()) return@withContext null
        val links = searchGames(titleQuery, artist).take(MAX_CANDIDATES)
        for (link in links) {
            val lyrics = fetchGame(link.id) ?: continue
            if (bestTitleMatchPercent(titleQuery, link.title, lyrics.title) < MIN_TITLE_MATCH_PERCENT) continue
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
            if (bestTitleMatchPercent(query, link.title, result.title) < MIN_TITLE_MATCH_PERCENT) return@mapNotNull null
            NativeSearchCandidate(
                id = "mytyping_${link.id}",
                title = result.title.ifBlank { link.title }.ifBlank { title },
                artist = artist,
                album = album,
                durationSeconds = durationSeconds.coerceAtLeast(0),
                provider = "myTyping",
                plainLyrics = result.lyrics,
            )
        }.sortedByDescending { candidate -> bestTitleMatchPercent(query, candidate.title) }
            .take(MAX_CANDIDATES)
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
        return parsed.sortedWith(
            compareByDescending<GameLink> { bestTitleMatchPercent(cleanedTitle, it.title) }
                .thenByDescending { result ->
                    cleanedArtist.isNotBlank() && normalize(result.title).contains(normalize(cleanedArtist))
                },
        )
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

    private fun bestTitleMatchPercent(query: String, vararg candidates: String): Int {
        val queryForms = titleForms(query)
        if (queryForms.isEmpty()) return 0
        return candidates.asSequence()
            .flatMap { titleForms(it).asSequence() }
            .map { candidate -> queryForms.maxOfOrNull { similarityPercent(it, candidate) } ?: 0 }
            .maxOrNull() ?: 0
    }

    /** Keep Japanese and Latin titles comparable while ignoring common game/provider suffixes. */
    private fun titleForms(value: String): Set<String> {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return emptySet()
        val withoutTypingLabels = normalize(
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace(Regex("(?i)(歌詞|タイピング(?:ゲーム)?|lyrics?|typing(?:\\s*game)?|game)"), " "),
        )
        return setOf(normalized, withoutTypingLabels).filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun similarityPercent(left: String, right: String): Int {
        if (left.isBlank() || right.isBlank()) return 0
        if (left == right) return 100
        if (left.contains(right) || right.contains(left)) {
            return (minOf(left.length, right.length) * 100f / maxOf(left.length, right.length)).toInt()
        }
        // Levenshtein distance with two rows keeps allocations bounded even for long titles.
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return ((maxOf(left.length, right.length) - previous[right.length]) * 100f /
            maxOf(left.length, right.length)).toInt()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
