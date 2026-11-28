package com.music.bitchord.data.lyrics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.Normalizer

/** Native normalized LiriQo client. Raw Kuwo/Migu payloads are decoded server-side. */
internal object LiriQoLyrics {
    private const val API = "https://api.liriqo-alfarrizi.my.id/v1/lyrics"
    private const val CACHE_TTL_MS = 20_000L
    private const val MAX_CACHE_ENTRIES = 24

    private data class Cached(val response: JsonObject?, val atMs: Long)

    data class SearchCandidate(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSeconds: Int,
        val provider: String,
        val syncedLyrics: String?,
        val plainLyrics: String?,
    )

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Cached>()

    suspend fun lyrics(
        sourceId: String,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || sourceId !in SOURCE_PROVIDER_IDS) return@withContext null
        val response = response(videoId, title, artist, durationMs, album, full = false) ?: return@withContext null
        val provider = SOURCE_PROVIDER_IDS[sourceId] ?: return@withContext null
        val track = tracks(response)
            .asSequence()
            .filter { it.string("provider") == provider }
            .maxByOrNull(::trackRichness)
            ?: return@withContext null
        parseTrack(track, durationMs)
    }

    /**
     * Returns every usable result from one LiriQo provider for the manual
     * lyrics search screen. The normal playback lookup deliberately favors a
     * quick primary result; manual search asks the API to wait for all sources
     * so provider-specific candidates are not hidden by that fast path.
     */
    suspend fun search(
        sourceId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<SearchCandidate> = withContext(Dispatchers.IO) {
        val providerId = SOURCE_PROVIDER_IDS[sourceId] ?: return@withContext emptyList()
        if (title.isBlank()) return@withContext emptyList()
        // This is a candidate search, so don't let local album/duration tags
        // filter out a valid provider match. Apple Music and Migu often omit
        // those fields or use different album editions. Local metadata is
        // still attached to incomplete candidates below and used by the UI's
        // match-score ranking.
        val artistMatch = response("", title, artist, 0L, null, full = true)
            ?.let { candidatesForProvider(it, providerId, title, artist, album, durationMs) }
            .orEmpty()
        if (artistMatch.isNotEmpty() || artist.isBlank()) return@withContext artistMatch

        // Provider catalogues don't always agree with local artist credits
        // (multi-artist separators, aliases, or featured artists). If that
        // exact query yields no candidate for this provider, retry by title
        // alone; the search sheet still shows its title/artist match score so
        // the user can distinguish alternate recordings.
        response("", title, "", 0L, null, full = true)
            ?.let { candidatesForProvider(it, providerId, title, artist, album, durationMs) }
            .orEmpty()
    }

    private fun candidatesForProvider(
        response: JsonObject,
        providerId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<SearchCandidate> {
        val metadata = response["metadata"] as? JsonObject
        return tracks(response)
            .asSequence()
            .filter { it.string("provider")?.equals(providerId, ignoreCase = true) == true }
            .take(80)
            // One malformed track must not discard other valid candidates
            // from the same provider response.
            .mapIndexedNotNull { index, track ->
                runCatching {
                    val lines = parseTrack(track, durationMs)
                    val rawPlain = track.string("plain")?.takeIf(String::isNotBlank)
                    if (lines.isNullOrEmpty() && rawPlain.isNullOrBlank()) return@runCatching null

                    val ttml = track.string("ttml")?.takeIf(String::isNotBlank)
                    val lrc = track.string("lrc")?.takeIf(String::isNotBlank)
                    val mrc = track.string("mrc")?.takeIf(String::isNotBlank)
                    val timed = (track["timed"] as? JsonArray)?.isNotEmpty() == true
                    val syncedLyrics = if (ttml != null || lrc != null || mrc != null || timed) {
                        lines?.let { parsedLines ->
                            (if (parsedLines.any { it.isWordSynced || it.background?.isWordSynced == true }) {
                                parsedLines.toEnhancedLrc()
                            } else {
                                parsedLines.toLrc()
                            }).takeIf(String::isNotBlank)
                        }
                    } else null
                    val plainLyrics = rawPlain
                        ?: lines?.joinToString("\n") { it.text }?.takeIf(String::isNotBlank)
                    if (syncedLyrics.isNullOrBlank() && plainLyrics.isNullOrBlank()) return@runCatching null

                    val resultTitle = track.string("title")
                        ?: metadata?.string("title")
                        ?: title
                    val resultArtist = track.string("artist")
                        ?: metadata?.string("artist")
                        ?: artist
                    val resultAlbum = track.string("album") ?: metadata?.string("album") ?: album
                    val resultDuration = track.long("duration")
                        ?: metadata?.long("duration")
                        ?: durationMs.takeIf { it > 0L }?.div(1000L)
                        ?: 0L
                    val resultId = track.string("id")
                        ?: track.string("isrc")
                        ?: "$providerId:${resultTitle.take(100)}:${resultArtist.take(100)}:$resultDuration:$index"
                    SearchCandidate(
                        id = resultId.take(512),
                        title = resultTitle.take(512),
                        artist = resultArtist.take(512),
                        album = resultAlbum?.take(512),
                        durationSeconds = resultDuration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                        provider = track.string("upstreamProvider") ?: providerId,
                        syncedLyrics = syncedLyrics,
                        plainLyrics = plainLyrics,
                    )
                }.getOrNull()
            }
            .toList()
    }

    private suspend fun response(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        full: Boolean,
    ): JsonObject? = mutex.withLock {
        val key = listOf(title.trim(), artist.trim(), album.orEmpty().trim(), durationMs.coerceAtLeast(0L),
            videoId.takeIf { VIDEO_ID.matches(it) }.orEmpty(), full).joinToString("\u001f")
        val now = SystemClock.elapsedRealtime()
        cache[key]?.takeIf { now - it.atMs < CACHE_TTL_MS }?.let { return@withLock it.response }

        val url = API.toHttpUrl().newBuilder().apply {
            val validVideoId = videoId.takeIf(VIDEO_ID::matches)
            if (validVideoId != null) addQueryParameter("v", validVideoId)
            addQueryParameter("title", title.take(512))
            if (artist.isNotBlank()) addQueryParameter("artist", artist.take(512))
            if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000L).coerceAtLeast(1L).toString())
            if (!album.isNullOrBlank()) addQueryParameter("album", album.take(512))
            if (full) addQueryParameter("full", "1")
        }.build().toString()
        val parsed = lyricsGet(url, timeoutSeconds = if (full) 20L else 10L)
            ?.let { runCatching { lyricsJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?.takeUnless { it["error"]?.let { error -> error !is JsonNull && error.toString() !in setOf("false", "\"\"") } == true }
        cache[key] = Cached(parsed, now)
        while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.entries.first().key)
        parsed
    }

    private fun tracks(response: JsonObject): List<JsonObject> {
        val candidates = (response["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val primary = response["primary"] as? JsonObject
        return if (primary != null && candidates.none { it == primary }) listOf(primary) + candidates else candidates.ifEmpty {
            listOfNotNull(primary)
        }
    }

    private fun trackRichness(track: JsonObject): Int {
        val timed = track["timed"] as? JsonArray
        val embeddedWordCount = timed.orEmpty().sumOf {
            ((it as? JsonObject)?.get("words") as? JsonArray)?.size ?: 0
        }
        val topLevelWordCount = (track["words"] as? JsonArray).orEmpty().sumOf { (it as? JsonArray)?.size ?: 0 }
        return (if (!track.string("ttml").isNullOrBlank()) 32 else 0) +
            (if (!track.string("lrc").isNullOrBlank()) 16 else 0) +
            (if (embeddedWordCount + topLevelWordCount > 0) 8 else 0) +
            minOf(timed?.size ?: 0, 100) +
            (if (!track.string("plain").isNullOrBlank()) 1 else 0)
    }

    private fun parseTrack(track: JsonObject, durationMs: Long): List<LyricLine>? {
        track.string("ttml")?.let { ttml ->
            TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() }?.let { return it }
        }
        parseTimed(track, durationMs)?.let { return it }
        for (field in listOf("lrc", "mrc", "plain")) {
            track.string(field)?.let { raw ->
                ProviderLyrics.parse(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun parseTimed(track: JsonObject, durationMs: Long): List<LyricLine>? {
        val rows = track["timed"] as? JsonArray ?: return null
        val indexedWords = track["words"] as? JsonArray
        val lines = rows.mapIndexedNotNull { rowIndex, element ->
            val row = element as? JsonObject ?: return@mapIndexedNotNull null
            // LiriQo marks Apple Music's lead-vocal rows as isBackground=true
            // in some TTML conversions. Keep these timed lyric rows; dropping
            // that flag erased the entire provider result for affected tracks.
            if (row.boolean("isCue") == true || row.boolean("isInstrumental") == true) {
                return@mapIndexedNotNull null
            }
            val text = row.string("text")?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val start = row.long("start")?.takeIf { it >= 0L } ?: return@mapIndexedNotNull null
            val end = row.long("end")?.takeIf { it > start } ?: return@mapIndexedNotNull null
            if (durationMs > 0L && start > durationMs + 20_000L) return@mapIndexedNotNull null
            val lineId = row.long("id")?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt() ?: rowIndex
            val wordRows = row["words"] ?: indexedWords?.getOrNull(lineId)
                ?: (track["words"] as? JsonObject)?.get(lineId.toString())
            val words = parseWords(wordRows, start, end, text)
            LyricLine(
                timeMs = start,
                text = text,
                words = words,
                sungUntilMs = end,
            )
        }.sortedBy(LyricLine::timeMs)
        return lines.withInstrumentalGaps().takeIf { it.any { line -> line.text.isNotBlank() } }
    }

    private fun parseWords(element: JsonElement?, lineStart: Long, lineEnd: Long, lineText: String): List<LyricWord> {
        val rows = element as? JsonArray ?: return emptyList()
        if (rows.isEmpty()) return emptyList()
        val words = rows.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val text = row.string("text") ?: return@mapNotNull null
            val start = row.long("start") ?: return@mapNotNull null
            val end = row.long("end") ?: return@mapNotNull null
            if (text.isEmpty() || start < lineStart || end <= start || end > lineEnd) return@mapNotNull null
            LyricWord(start, end, text)
        }
        if (words.size != rows.size || words.zipWithNext().any { (left, right) -> right.startMs < left.startMs }) {
            return emptyList()
        }
        val expected = comparableText(lineText)
        val actual = comparableText(words.joinToString("") { it.text })
        return words.takeIf { expected == actual && it.any { word -> word.endMs > word.startMs } }.orEmpty()
    }

    private fun comparableText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).filterNot(Char::isWhitespace)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val SOURCE_PROVIDER_IDS = mapOf(
        "liriqo_apple_music" to "apple_music_liriqo",
        "liriqo_kuwo" to "kuwo",
        "liriqo_migu" to "migu",
    )
}
