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

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val query = "$title $artist".trim()
            if (query.isBlank()) return@withContext null
            LyricsLog.i("NetEase", "Searching NetEase for \"$title\" by \"$artist\"")
            val matches = search(query) ?: return@withContext null
            val song = bestMatch(matches, title, artist, durationMs)
            if (song == null) {
                LyricsLog.w("NetEase", "Search returned no matching songs (${matches.size} candidates)")
                return@withContext null
            }
            val response = getLyrics(song.id) ?: return@withContext null

            val removeCredits = com.music.bitchord.data.settings.AppSettings.removeNeteaseLyricCredits.value
            val yrc = response.lyric("yrc")
                ?.let { if (removeCredits) it.withoutCreditLines() else it }
            val timed = yrc?.let { raw ->
                KaraokeLrc.parse(raw).takeIf { lines -> lines.any { it.isWordSynced } }
            }
            if (!timed.isNullOrEmpty()) {
                LyricsLog.s("NetEase", "Returned ${timed.size} word-synced lines")
                return@withContext timed
            }

            val lrc = response.lyric("lrc")
                ?.let { if (removeCredits) it.withoutCreditLines() else it }
            if (lrc == null) {
                LyricsLog.w("NetEase", "Song ${song.id} has no LRC lyrics")
                return@withContext null
            }
            LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() }
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
            SongHit(id, title, artists, duration)
        }
    }

    private fun bestMatch(rows: List<SongHit>, title: String, artist: String, durationMs: Long): SongHit? {
        val targetTitle = title.normalizedForMatch()
        val targetArtist = artist.normalizedForMatch()
        val targetDuration = durationMs.takeIf { it > 0L }
        return rows.maxByOrNull { row ->
            val candidateTitle = row.title.normalizedForMatch()
            val candidateArtist = row.artists.joinToString(" ").normalizedForMatch()
            var score = when {
                candidateTitle == targetTitle -> 100
                candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle) -> 55
                else -> 0
            }
            if (candidateArtist.isNotBlank() && targetArtist.isNotBlank()) {
                score += when {
                    candidateArtist == targetArtist -> 60
                    candidateArtist.contains(targetArtist) || targetArtist.contains(candidateArtist) -> 35
                    else -> 0
                }
            }
            if (targetDuration != null && row.durationMs != null) {
                val difference = abs(row.durationMs - targetDuration)
                score += when {
                    difference <= 3_000L -> 30
                    difference <= 8_000L -> 18
                    difference <= 15_000L -> 5
                    else -> -20
                }
            }
            score
        }
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

    private fun String.normalizedForMatch(): String =
        lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.array(key: String) = runCatching { this[key]?.jsonArray }.getOrNull()
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private data class SongHit(
        val id: Long,
        val title: String,
        val artists: List<String>,
        val durationMs: Long?,
    )

    /** Stamped songwriter/composer/arranger credits are metadata, not sung lines. */
    private val NETEASE_CREDIT_LINE = Regex(
        """^\s*(?:(?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])|(?:\[\d{1,8},\d{1,8}]))+\s*""" +
            """(?:作词|作曲|编曲|作詞|編曲|詞曲|词曲|作詞作曲|词|曲|lyrics?\s+by|written\s+by|composed\s+by|arranged\s+by)\s*[:：].*$""",
        RegexOption.IGNORE_CASE,
    )
}
