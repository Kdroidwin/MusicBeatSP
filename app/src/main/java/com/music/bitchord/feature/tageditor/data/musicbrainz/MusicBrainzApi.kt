package com.music.bitchord.feature.tageditor.data.musicbrainz

import android.os.SystemClock
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MusicBrainzCandidate(
    val recordingId: String,
    val releaseId: String?,
    val artistId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long?,
    val score: Int,
    val disambiguation: String?,
)

/** MusicBrainz recording search with bounded responses and the public API's one-request/sec limit. */
object MusicBrainzApi {
    private const val API_ROOT = "https://musicbrainz.org/ws/2/"
    private const val MAX_RESULTS = 8
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MIN_REQUEST_INTERVAL_MS = 1_100L
    private const val MAX_RETRIES = 2
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private val requestMutex = Mutex()
    private var lastRequestStartedAt = 0L

    suspend fun search(song: Song, title: String, artist: String, album: String): List<MusicBrainzCandidate> =
        withContext(Dispatchers.IO) {
            val safeTitle = title.trim().take(200)
            val safeArtist = artist.trim().take(200)
            val safeAlbum = album.trim().take(200).takeUnless(::isUnknownAlbum)
            require(safeTitle.isNotBlank() || safeArtist.isNotBlank()) { "曲名かアーティスト名を入力してください。" }
            val query = buildQuery(safeTitle, safeArtist, safeAlbum)
            requestMutex.withLock {
                val url = API_ROOT.toHttpUrl().newBuilder()
                    .addPathSegment("recording")
                    .addQueryParameter("query", query)
                    .addQueryParameter("inc", "releases")
                    .addQueryParameter("fmt", "json")
                    .addQueryParameter("limit", MAX_RESULTS.toString())
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header(
                        "User-Agent",
                        "MusicBeatSP/${BuildConfig.VERSION_NAME} (https://github.com/Kdroidwin/MusicBeatSP)",
                    )
                    .get()
                    .build()

                for (attempt in 0..MAX_RETRIES) {
                    val elapsed = SystemClock.elapsedRealtime() - lastRequestStartedAt
                    if (elapsed in 0 until MIN_REQUEST_INTERVAL_MS) {
                        delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                    }
                    lastRequestStartedAt = SystemClock.elapsedRealtime()
                    var retryDelayMs: Long? = null
                    Http.lyricsClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = Http.readBoundedUtf8(response.body, MAX_RESPONSE_BYTES)
                                ?: throw IOException("MusicBrainz returned an empty response")
                            return@withLock parse(
                                JSONObject(body),
                                expectedTitle = safeTitle,
                                expectedArtist = safeArtist,
                                expectedAlbum = safeAlbum,
                                expectedDurationMs = song.durationMillis().takeIf { it > 0L },
                            )
                        }
                        val status = response.code
                        if (status !in RETRYABLE_HTTP_STATUS || attempt == MAX_RETRIES) {
                            throw IOException("MusicBrainz HTTP $status")
                        }
                        retryDelayMs = retryDelayMillis(response.header("Retry-After"), attempt)
                    }
                    delay(requireNotNull(retryDelayMs))
                }
                throw IOException("MusicBrainz search failed after retries")
            }
        }

    private fun retryDelayMillis(retryAfter: String?, attempt: Int): Long {
        val serverDelay = retryAfter?.trim()?.toLongOrNull()?.let { seconds ->
            seconds.coerceAtLeast(0L).coerceAtMost(MAX_RETRY_DELAY_MS / 1_000L) * 1_000L
        } ?: retryAfter?.let(::parseRetryAfterDate)
        return (serverDelay ?: (1_500L shl attempt))
            .coerceIn(MIN_REQUEST_INTERVAL_MS, MAX_RETRY_DELAY_MS)
    }

    private fun parseRetryAfterDate(value: String): Long? = runCatching {
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        (parser.parse(value)?.time?.minus(Date().time))?.coerceAtLeast(0L)
    }.getOrNull()

    private fun buildQuery(title: String, artist: String, album: String?): String = buildList {
        if (title.isNotBlank()) add("recording:\"${escapeLucene(title)}\"")
        if (artist.isNotBlank()) add("artist:\"${escapeLucene(artist)}\"")
        if (!album.isNullOrBlank()) add("release:\"${escapeLucene(album)}\"")
    }.joinToString(" AND ")

    private fun escapeLucene(value: String): String = buildString(value.length) {
        value.forEach { char ->
            if (char in LUCENE_SPECIAL_CHARS) append('\\')
            append(char)
        }
    }

    private fun parse(
        json: JSONObject,
        expectedTitle: String,
        expectedArtist: String,
        expectedAlbum: String?,
        expectedDurationMs: Long?,
    ): List<MusicBrainzCandidate> {
        val recordings = json.optJSONArray("recordings") ?: JSONArray()
        return (0 until minOf(recordings.length(), MAX_RESULTS)).mapNotNull { index ->
            val recording = recordings.optJSONObject(index) ?: return@mapNotNull null
            val recordingId = recording.optString("id").takeIf(::isUuid) ?: return@mapNotNull null
            val title = recording.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val credits = recording.optJSONArray("artist-credit")
            val artist = artistCreditName(credits)
            val artistId = credits?.optJSONObject(0)?.optJSONObject("artist")?.optString("id")
                ?.takeIf(::isUuid)
            val release = chooseRelease(recording.optJSONArray("releases"), expectedAlbum)
            val releaseId = release?.optString("id")?.takeIf(::isUuid)
            val album = release?.optString("title")?.trim().orEmpty()
            val date = recording.optString("first-release-date")
                .ifBlank { release?.optString("date").orEmpty() }
            val duration = recording.optLong("length", -1L).takeIf { it >= 0L }
            val score = adjustedScore(
                base = recording.optInt("score", 0),
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                expectedTitle = expectedTitle,
                expectedArtist = expectedArtist,
                expectedAlbum = expectedAlbum,
                expectedDurationMs = expectedDurationMs,
            )
            MusicBrainzCandidate(
                recordingId = recordingId,
                releaseId = releaseId,
                artistId = artistId,
                title = title,
                artist = artist,
                album = album,
                year = date.take(4).toIntOrNull() ?: 0,
                durationMs = duration,
                score = score,
                disambiguation = recording.optString("disambiguation").takeIf(String::isNotBlank),
            )
        }.sortedByDescending(MusicBrainzCandidate::score)
    }

    private fun artistCreditName(credits: JSONArray?): String {
        if (credits == null) return ""
        return buildString {
            for (index in 0 until credits.length()) {
                val credit = credits.optJSONObject(index) ?: continue
                append(credit.optString("name").ifBlank {
                    credit.optJSONObject("artist")?.optString("name").orEmpty()
                })
                append(credit.optString("joinphrase"))
            }
        }.trim()
    }

    private fun chooseRelease(releases: JSONArray?, expectedAlbum: String?): JSONObject? {
        if (releases == null || releases.length() == 0) return null
        val all = (0 until releases.length()).mapNotNull(releases::optJSONObject)
        return all.firstOrNull { normalize(it.optString("title")) == normalize(expectedAlbum.orEmpty()) }
            ?: all.firstOrNull { release ->
                val a = normalize(release.optString("title"))
                val b = normalize(expectedAlbum.orEmpty())
                a.isNotBlank() && b.isNotBlank() && (a.contains(b) || b.contains(a))
            }
            ?: all.firstOrNull()
    }

    private fun adjustedScore(
        base: Int,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        expectedTitle: String,
        expectedArtist: String,
        expectedAlbum: String?,
        expectedDurationMs: Long?,
    ): Int {
        var score = base
        if (normalize(title) == normalize(expectedTitle)) score += 28
        if (normalize(artist) == normalize(expectedArtist)) score += 24
        if (!expectedAlbum.isNullOrBlank() && normalize(album) == normalize(expectedAlbum)) score += 18
        if (durationMs != null && expectedDurationMs != null && kotlin.math.abs(durationMs - expectedDurationMs) <= 2_000L) score += 12
        return score
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{Punct}　]+"), "")

    private fun isUnknownAlbum(value: String): Boolean = value.isBlank() ||
        value.equals("Unknown Album", true) || value.equals("<unknown>", true)

    private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    private val LUCENE_SPECIAL_CHARS = setOf(
        '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/',
    )
    private val RETRYABLE_HTTP_STATUS = setOf(429, 500, 502, 503, 504)
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
