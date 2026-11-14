package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import com.music.bitchord.data.lyrics.providers.NativeSearchCandidate
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Rich word timing, with line-synced fallback, from Musixmatch's web API. */
object Musixmatch {

    private const val BASE = "https://apic.musixmatch.com/ws/1.1"
    private const val APP_ID = "mobile-app-v1.0"
    private const val SEARCH_PAGE = "https://www.musixmatch.com/search"
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // Used only if the public page serving the rotating key is briefly unavailable.
    private const val FALLBACK_SIGNING_SECRET = "f09016176ba43a1cfd1031fbd6b3d26c"

    private val tokenMutex = Mutex()
    private val cachedSecret = AtomicReference<String?>(null)
    private val cachedToken = AtomicReference<String?>(null)
    private val processGuid = UUID.randomUUID().toString()

    private val client by lazy {
        Http.lyricsClient.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
        val track = bestTrack(title, artist, seconds) ?: return@withContext null
        fetchTrackLyrics(track)
    }

    /** Manual search returns the verified Musixmatch track metadata with its lyrics. */
    internal suspend fun searchCandidate(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): NativeSearchCandidate? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
        val track = bestTrack(title, artist, seconds) ?: return@withContext null
        val lines = fetchTrackLyrics(track)?.takeIf { it.isNotEmpty() }
            ?: return@withContext null
        val synced = when {
            lines.any { it.isWordSynced } -> lines.toEnhancedLrc()
            lines.any { it.timeMs > 0L } -> lines.toLrc()
            else -> null
        }
        val plain = if (synced == null) lines.joinToString("\n") { it.text } else null
        NativeSearchCandidate(
            id = "musixmatch_${track.trackId}",
            title = track.trackName,
            artist = track.artistName,
            album = track.albumName?.takeIf(String::isNotBlank) ?: album,
            durationSeconds = track.trackLength?.takeIf { it > 0 } ?: seconds,
            provider = "Musixmatch",
            syncedLyrics = synced,
            plainLyrics = plain,
        )
    }

    private suspend fun fetchTrackLyrics(track: Track): List<LyricLine>? {
        // The subtitle endpoint is line-timed by design. Prefer the separate rich-sync
        // tier so tracks that have syllable data do not get flattened on ingestion.
        if (track.hasRichSync != 0) {
            val rich = fetchRichSync(track.trackId)
                ?.let(::parseRichSyncBody)
                ?.takeIf { lines -> lines.any { it.isWordSynced } }
            if (rich != null) return rich
        }

        val subtitle = if (track.hasSubtitles != 0) fetchSubtitle(track.trackId) else null
        val lrc = subtitle?.let(::subtitleToLrc)?.takeIf { it.isNotBlank() }
            ?: return null
        return LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() }
    }

    private suspend fun bestTrack(title: String, artist: String, seconds: Int): Track? {
        val wantedTitle = normalizeTrackText(title)
        if (wantedTitle.isBlank()) return null
        val wantedArtists = artistTokens(artist)
        val tracks = searchTrack(title, artist) ?: return null
        val candidates = tracks.mapNotNull { track ->
            // The old scorer always returned maxByOrNull, even when every
            // result was unrelated: a duration bonus could outweigh a title
            // or artist mismatch. Require exact normalized title and, when
            // known, an exact artist token before ranking any lyric candidate.
            if (normalizeTrackText(track.trackName) != wantedTitle) return@mapNotNull null
            val actualArtists = artistTokens(track.artistName)
            if (wantedArtists.isNotEmpty() && wantedArtists.intersect(actualArtists).isEmpty()) {
                return@mapNotNull null
            }

            val durationDifference = if (seconds > 0 && track.trackLength != null && track.trackLength > 0) {
                abs(track.trackLength - seconds)
            } else null
            val durationTolerance = maxOf(15, seconds / 12)
            if (durationDifference != null && durationDifference > durationTolerance) return@mapNotNull null

            val durationScore = when {
                durationDifference == null -> 0
                durationDifference <= 2 -> 30
                durationDifference <= 6 -> 20
                else -> 5
            }
            TrackCandidate(
                track = track,
                score = 100 + (if (wantedArtists.isNotEmpty()) 50 else 0) + durationScore,
                durationDifference = durationDifference ?: Int.MAX_VALUE,
            )
        }.sortedWith(compareByDescending<TrackCandidate> { it.score }.thenBy { it.durationDifference })

        val best = candidates.firstOrNull()
        if (best == null) {
            LyricsLog.w("Musixmatch", "Rejected search results: no exact title/artist/duration match")
            return null
        }
        val runnerUp = candidates.getOrNull(1)
        if (wantedArtists.isEmpty() && runnerUp != null &&
            runnerUp.score == best.score && runnerUp.durationDifference == best.durationDifference &&
            normalizeTrackText(runnerUp.track.artistName) != normalizeTrackText(best.track.artistName)
        ) {
            LyricsLog.w("Musixmatch", "Rejected ambiguous same-title results without an artist")
            return null
        }
        LyricsLog.i(
            "Musixmatch",
            "Selected exact match '${best.track.trackName}' by '${best.track.artistName}' " +
                "(durationDiff=${best.durationDifference.takeIf { it != Int.MAX_VALUE }?.let { "${it}s" } ?: "n/a"})",
        )
        return best.track
    }

    private fun normalizeTrackText(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)

    private fun artistTokens(value: String): Set<String> = value
        .split(Regex("(?i)\\s*(?:,|，|、|/|&|＆|;|feat\\.?|ft\\.?|with|\\s+x\\s+|\\s+and\\s+)\\s*"))
        .map(::normalizeTrackText)
        .filter { it.isNotBlank() && it !in setOf("unknownartist", "unknown", "variousartists") }
        .toSet()

    private suspend fun searchTrack(title: String, artist: String): List<Track>? {
        val response = signedGet { token ->
            "$BASE/track.search".toHttpUrl().newBuilder()
                .addQueryParameter("app_id", APP_ID)
                .addQueryParameter("format", "json")
                .addQueryParameter("q_track", title)
                .addQueryParameter("q_artist", artist)
                .addQueryParameter("f_has_lyrics", "1")
                .addQueryParameter("s_track_rating", "desc")
                .addQueryParameter("quorum_factor", "1")
                .addQueryParameter("page_size", "10")
                .addQueryParameter("page", "1")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        val body = runCatching {
            lyricsJson.decodeFromString<Envelope<TrackSearchBody>>(response)
        }.getOrNull() ?: return null
        return body.message.body?.trackList?.map { it.track }
    }

    private suspend fun fetchSubtitle(trackId: Long): String? {
        val response = signedGet { token ->
            "$BASE/track.subtitle.get".toHttpUrl().newBuilder()
                .addQueryParameter("app_id", APP_ID)
                .addQueryParameter("format", "json")
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("subtitle_format", "mxm")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        return runCatching {
            lyricsJson.decodeFromString<Envelope<SubtitleBody>>(response)
        }.getOrNull()?.message?.body?.subtitle?.subtitleBody
    }

    private suspend fun fetchRichSync(trackId: Long): String? {
        val response = signedGet { token ->
            "$BASE/track.richsync.get".toHttpUrl().newBuilder()
                .addQueryParameter("app_id", APP_ID)
                .addQueryParameter("format", "json")
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        return runCatching {
            lyricsJson.decodeFromString<Envelope<RichSyncBody>>(response)
        }.getOrNull()?.message?.body?.richsync?.richsyncBody
    }

    /**
     * Decodes rich-sync offsets without flattening them through line LRC.
     * Source order is retained because punctuation and syllable fragments may share a stamp.
     */
    fun parseRichSyncBody(body: String): List<LyricLine> {
        val entries = runCatching {
            lyricsJson.decodeFromString<List<RichSyncEntry>>(body)
        }.getOrNull() ?: return emptyList()

        return entries.mapNotNull { entry ->
            val lineStart = secondsToMs(entry.startSeconds)
            val lineEnd = maxOf(lineStart, secondsToMs(entry.endSeconds))
            var previousStart = lineStart
            // Fragments carry their own spacing, so a fragment with no space
            // after it is a syllable of a word that carries on — see
            // [wordsFromRuns].
            val runs = entry.fragments.mapIndexedNotNull { index, fragment ->
                if (fragment.text.isEmpty()) return@mapIndexedNotNull null
                val start = maxOf(
                    lineStart,
                    previousStart,
                    secondsToMs(entry.startSeconds + fragment.offsetSeconds),
                )
                val next = entry.fragments.getOrNull(index + 1)?.let {
                    secondsToMs(entry.startSeconds + it.offsetSeconds)
                } ?: lineEnd
                previousStart = start
                TimedRun(start, maxOf(start, minOf(lineEnd, next)), fragment.text)
            }
            val words = wordsFromRuns(runs, spacingIsExplicit = true)

            val text = entry.text.trim().ifEmpty { words.joinToString(" ") { it.text } }
            if (text.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = minOf(lineStart, words.firstOrNull()?.startMs ?: lineStart),
                text = text,
                words = words,
                sungUntilMs = lineEnd.takeIf { it > lineStart },
            )
        }.sortedBy { it.timeMs }.withInstrumentalGaps()
    }

    /** Musixmatch's `mxm` subtitle JSON, converted only for the line-sync fallback. */
    private fun subtitleToLrc(subtitleBody: String): String {
        val lines = runCatching { lyricsJson.decodeFromString<List<SubtitleLine>>(subtitleBody) }
            .getOrNull() ?: return ""
        return buildString {
            for (line in lines) {
                if (line.text.isBlank()) continue
                val totalMs = secondsToMs(line.time.total)
                val minutes = totalMs / 1000 / 60
                val seconds = (totalMs / 1000) % 60
                val millis = totalMs % 1000
                appendLine(
                    "[" + "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis) + "]" + line.text,
                )
            }
        }.trim()
    }

    private fun secondsToMs(seconds: Double): Long = (seconds * 1_000.0).toLong()

    /** Signs and issues [buildUrl]; an auth failure refreshes both moving credentials once. */
    private suspend fun signedGet(buildUrl: (token: String) -> okhttp3.HttpUrl): String? {
        val secret = getSecret()
        val token = getToken(secret) ?: return null
        val first = apiGet(sign(buildUrl(token).toString(), secret))
        if (first != null && !looksUnauthorized(first)) return first

        cachedToken.set(null)
        cachedSecret.set(null)
        val freshSecret = getSecret()
        val freshToken = getToken(freshSecret) ?: return null
        return apiGet(sign(buildUrl(freshToken).toString(), freshSecret))
    }

    /** Musixmatch reports an expired token inside a successful HTTP response. */
    private fun looksUnauthorized(body: String): Boolean =
        runCatching { lyricsJson.decodeFromString<Envelope<kotlinx.serialization.json.JsonElement>>(body) }
            .getOrNull()?.message?.header?.statusCode?.let { it == 401 || it == 402 } ?: false

    private suspend fun getToken(secret: String): String? = cachedToken.get() ?: tokenMutex.withLock {
        cachedToken.get() ?: fetchToken(secret)?.also(cachedToken::set)
    }

    private fun fetchToken(secret: String): String? {
        val url = "$BASE/token.get".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP_ID)
            .addQueryParameter("guid", processGuid)
            .addQueryParameter("format", "json")
            .build()
        val body = apiGet(sign(url.toString(), secret)) ?: return null
        return runCatching {
            lyricsJson.decodeFromString<Envelope<TokenBody>>(body)
        }.getOrNull()?.message?.body?.userToken
    }

    /** Reads the rotating signing key from the JavaScript linked by the current web client. */
    private fun getSecret(): String = cachedSecret.get() ?: runCatching {
        val page = browserGet(SEARCH_PAGE, "text/html,application/xhtml+xml")
            ?: error("search page unavailable")
        val script = APP_SCRIPT.find(page)?.groupValues?.get(1)
            ?: error("application script not found")
        val scriptUrl = SEARCH_PAGE.toHttpUrl().resolve(script)?.toString()
            ?: error("invalid application script URL")
        val javascript = browserGet(scriptUrl, "*/*") ?: error("application script unavailable")
        val encoded = ENCODED_SECRET.find(javascript)?.groupValues?.get(1)
            ?: error("signing key not found")
        String(Base64.getDecoder().decode(encoded.reversed()), Charsets.UTF_8)
            .takeIf { it.isNotBlank() }
            ?: error("empty signing key")
    }.getOrElse { FALLBACK_SIGNING_SECRET }.also(cachedSecret::set)

    /** Signs `<url><UTC yyyyMMdd>` with HMAC-SHA256, matching the current web client. */
    private fun sign(url: String, secret: String): String {
        val normalized = url.replace("%20", "+").replace(" ", "+")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("$normalized$date".toByteArray(Charsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(raw)
        return "$normalized&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}" +
            "&signature_protocol=sha256"
    }

    private fun apiGet(url: String): String? = request(url, "application/json, text/plain, */*")

    private fun browserGet(url: String, accept: String): String? =
        request(url, accept, cookie = "mxm_bab=AB")

    private fun request(url: String, accept: String, cookie: String? = null): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) Http.readBoundedUtf8(response.body, 2 * 1024 * 1024) else null
        }
    }.getOrNull()

    @Serializable
    private data class Envelope<T>(val message: Message<T>)

    @Serializable
    private data class Message<T>(val header: Header, val body: T? = null)

    @Serializable
    private data class Header(@SerialName("status_code") val statusCode: Int = 0)

    @Serializable
    private data class TokenBody(@SerialName("user_token") val userToken: String)

    @Serializable
    private data class TrackSearchBody(
        @SerialName("track_list") val trackList: List<TrackWrapper> = emptyList(),
    )

    @Serializable
    private data class TrackWrapper(val track: Track)

    @Serializable
    private data class Track(
        @SerialName("track_id") val trackId: Long,
        @SerialName("track_name") val trackName: String,
        @SerialName("artist_name") val artistName: String = "",
        @SerialName("album_name") val albumName: String? = null,
        @SerialName("track_length") val trackLength: Int? = null,
        @SerialName("has_subtitles") val hasSubtitles: Int? = null,
        @SerialName("has_richsync") val hasRichSync: Int? = null,
    )

    private data class TrackCandidate(
        val track: Track,
        val score: Int,
        val durationDifference: Int,
    )

    @Serializable
    private data class SubtitleBody(val subtitle: Subtitle? = null)

    @Serializable
    private data class Subtitle(@SerialName("subtitle_body") val subtitleBody: String)

    @Serializable
    private data class SubtitleLine(val text: String, val time: SubtitleTime)

    @Serializable
    private data class SubtitleTime(val total: Double)

    @Serializable
    private data class RichSyncBody(val richsync: RichSync? = null)

    @Serializable
    private data class RichSync(@SerialName("richsync_body") val richsyncBody: String? = null)

    @Serializable
    private data class RichSyncEntry(
        @SerialName("ts") val startSeconds: Double,
        @SerialName("te") val endSeconds: Double,
        @SerialName("l") val fragments: List<RichSyncFragment> = emptyList(),
        @SerialName("x") val text: String = "",
    )

    @Serializable
    private data class RichSyncFragment(
        @SerialName("c") val text: String,
        @SerialName("o") val offsetSeconds: Double,
    )

    private val APP_SCRIPT = Regex(
        """src=["']([^"']*/_next/static/chunks/pages/_app-[^"']+\.js)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val ENCODED_SECRET = Regex("""from\(\s*["']([^"']+)["']\s*\.split""")
}
