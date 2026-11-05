package com.music.bitchord.data.lyrics.providers

import android.util.Base64
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Result item returned by native provider searches.
 */
data class NativeSearchCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    val provider: String,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
) {
    val bestRaw: String?
        get() = syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }
}

internal object NativeLyricsHttp {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val fastClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val paxClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun urlEncode(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8")

    fun cleanQueryText(text: String): String =
        text.replace(Regex("""(?i)\s*[\(\[（【](?:feat\.?|ft\.?|official|video|audio|mv|lyric|remaster|live|ver\.?|version|inst)[^)\]）】]*[\)\]）】]"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:remastered|single|ep|live|version|ver\.).*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun primaryArtist(artist: String): String =
        artist.split(Regex("""[,;&/、]|(?i)\b(?:feat\.?|ft\.?|cv[:.：]?)\b"""))
            .firstOrNull()
            ?.trim()
            ?.ifBlank { artist.trim() }
            ?: artist.trim()

    fun formatLineStamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val min = (clamped / 60000).toString().padStart(2, '0')
        val sec = ((clamped % 60000) / 1000).toString().padStart(2, '0')
        val cs = ((clamped % 1000) / 10).toString().padStart(2, '0')
        return "[$min:$sec.$cs]"
    }

    fun formatWordStamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val min = (clamped / 60000).toString().padStart(2, '0')
        val sec = ((clamped % 60000) / 1000).toString().padStart(2, '0')
        val cs = ((clamped % 1000) / 10).toString().padStart(2, '0')
        return "<$min:$sec.$cs>"
    }

    /**
     * Merges a synced LRC and a translated LRC so translation lines share the exact
     * `[mm:ss.xx]` timestamp immediately after the original line (which Tinque's
     * `ProviderLyrics.markTranslations` detects as `timingSource`).
     */
    fun mergeLrcWithTranslation(mainLrc: String, transLrc: String?): String {
        if (transLrc.isNullOrBlank()) return mainLrc
        val timeRegex = Regex("""^\[(\d{2,}:\d{2}[.:]\d{2,3})](.*)$""")
        val transMap = HashMap<String, String>()
        for (rawLine in transLrc.lineSequence()) {
            val m = timeRegex.find(rawLine.trim()) ?: continue
            val stamp = m.groupValues[1].replace(':', '.').let {
                val parts = m.groupValues[1].split(':', '.')
                if (parts.size >= 3) "${parts[0]}:${parts[1]}.${parts[2].take(2).padEnd(2, '0')}" else m.groupValues[1]
            }
            val text = m.groupValues[2].trim()
            if (text.isNotEmpty() && text != "//" && !text.startsWith("作词") && !text.startsWith("作曲")) {
                transMap[stamp] = text
            }
        }
        if (transMap.isEmpty()) return mainLrc

        val sb = StringBuilder()
        for (rawLine in mainLrc.lineSequence()) {
            val trimmed = rawLine.trim()
            sb.append(trimmed).append('\n')
            val m = timeRegex.find(trimmed) ?: continue
            val parts = m.groupValues[1].split(':', '.')
            val normStamp = if (parts.size >= 3) "${parts[0]}:${parts[1]}.${parts[2].take(2).padEnd(2, '0')}" else m.groupValues[1]
            val trans = transMap[normStamp]
            if (!trans.isNullOrBlank()) {
                sb.append('[').append(m.groupValues[1]).append(']').append(trans).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Apple Music (PaxSenix + iTunes Zero-Auth Search)
// ─────────────────────────────────────────────────────────────────────────────
internal object AppleMusicPaxClient {
    private const val PAX_BASE = "https://lyrics.paxsenix.org"

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = withContext(Dispatchers.IO) {
        val tracks = searchTracks(title, artist, durationSec)
        for (track in tracks.take(2)) {
            val content = fetchLyricsById(track.id)
            if (!content.isNullOrBlank()) return@withContext content
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val tracks = withContext(Dispatchers.IO) { searchTracks(title, artist, durationSec) }
        tracks.take(3).map { track ->
            async(Dispatchers.IO) {
                val lrc = fetchLyricsById(track.id) ?: return@async null
                NativeSearchCandidate(
                    id = "apple_music_${track.id}",
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSeconds = (track.durationMs / 1000).toInt(),
                    provider = "Apple Music",
                    syncedLyrics = lrc,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private data class AppleTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    private fun searchTracks(title: String, artist: String, durationSec: Int): List<AppleTrack> {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val query = "$cleanTitle $cleanArtist".trim()
        val paxTracks = searchPaxTracks(query)
        val candidates = if (paxTracks.isNotEmpty()) paxTracks else searchItunesTracks(query)
        if (candidates.isEmpty()) return emptyList()

        val targetMs = durationSec * 1000L
        return candidates.sortedByDescending { track ->
            var score = 0
            if (track.title.equals(cleanTitle, ignoreCase = true)) score += 50
            else if (track.title.contains(cleanTitle, ignoreCase = true)) score += 25
            if (cleanArtist.isNotBlank() && track.artist.contains(cleanArtist, ignoreCase = true)) score += 30
            if (targetMs > 0 && track.durationMs > 0) {
                val diff = abs(track.durationMs - targetMs)
                if (diff <= 3000) score += 30
                else if (diff <= 8000) score += 15
                else if (diff > 25000) score -= 30
            }
            score
        }
    }

    private fun searchPaxTracks(query: String): List<AppleTrack> = runCatching {
        val url = "$PAX_BASE/apple-music/search?q=${NativeLyricsHttp.urlEncode(query)}"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.paxClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val list = ArrayList<AppleTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("trackId") }
                if (id.isBlank()) continue
                list += AppleTrack(
                    id = id,
                    title = obj.optString("songName").ifBlank { obj.optString("trackName", query) },
                    artist = obj.optString("artistName"),
                    album = obj.optString("albumName").takeIf { it.isNotBlank() },
                    durationMs = obj.optLong("duration", obj.optLong("durationInMillis", 0L)),
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private fun searchItunesTracks(query: String): List<AppleTrack> = runCatching {
        val url = "https://itunes.apple.com/search?term=${NativeLyricsHttp.urlEncode(query)}&entity=song&limit=6"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val list = ArrayList<AppleTrack>(results.length())
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val id = obj.optLong("trackId", 0L)
                if (id <= 0L) continue
                list += AppleTrack(
                    id = id.toString(),
                    title = obj.optString("trackName"),
                    artist = obj.optString("artistName"),
                    album = obj.optString("collectionName").takeIf { it.isNotBlank() },
                    durationMs = obj.optLong("trackTimeMillis", 0L),
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private fun fetchLyricsById(id: String): String? = runCatching {
        val url = "$PAX_BASE/apple-music/lyrics?id=${NativeLyricsHttp.urlEncode(id)}"
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.paxClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim() ?: return null
            if (body.isBlank()) return null
            if (body.contains("<tt", ignoreCase = true)) return body
            parsePaxAppleJson(body)
        }
    }.getOrNull()

    internal fun parsePaxAppleJson(rawJson: String): String? = runCatching {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val ttml = root.optString("ttmlContent").ifBlank { root.optString("ttml") }.ifBlank { root.optString("lyrics") }
            if (ttml.contains("<tt", ignoreCase = true) || ttml.contains("[0")) return ttml
            val contentArr = root.optJSONArray("content") ?: root.optJSONArray("data")
            if (contentArr != null) return parsePaxLinesArray(contentArr)
        } else if (trimmed.startsWith("[")) {
            return parsePaxLinesArray(JSONArray(trimmed))
        }
        null
    }.getOrNull()

    private fun parsePaxLinesArray(arr: JSONArray): String? {
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val lineObj = arr.optJSONObject(i) ?: continue
            val lineTime = lineObj.optLong("timestamp", lineObj.optLong("time", -1L))
            if (lineTime < 0L) continue
            sb.append(NativeLyricsHttp.formatLineStamp(lineTime))
            val wordsArr = lineObj.optJSONArray("text") ?: lineObj.optJSONArray("syllabus")
            if (wordsArr != null && wordsArr.length() > 0) {
                var lastEnd = lineTime
                for (j in 0 until wordsArr.length()) {
                    val w = wordsArr.optJSONObject(j) ?: continue
                    val wText = w.optString("text")
                    val wStart = w.optLong("timestamp", w.optLong("time", lineTime))
                    val wEnd = w.optLong("endtime", wStart + w.optLong("duration", 200L))
                    sb.append(NativeLyricsHttp.formatWordStamp(wStart)).append(wText)
                    if (!w.optBoolean("part", false) && j < wordsArr.length() - 1) {
                        sb.append(' ')
                    }
                    lastEnd = maxOf(lastEnd, wEnd)
                }
                sb.append(NativeLyricsHttp.formatWordStamp(lastEnd))
            } else {
                sb.append(lineObj.optString("text"))
            }
            sb.append('\n')
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. LyricsPlus (YouLy+ KPOE Servers - Parallel Mirror Racing)
// ─────────────────────────────────────────────────────────────────────────────
internal object LyricsPlusClient {
    private val SERVERS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = coroutineScope {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val resultChannel = Channel<String?>(SERVERS.size)

        val jobs = SERVERS.map { base ->
            launch(Dispatchers.IO) {
                val res = fetchFromMirror(base, cleanTitle, cleanArtist, album, durationSec)
                resultChannel.send(res)
            }
        }

        var found: String? = null
        repeat(SERVERS.size) {
            val candidate = resultChannel.receive()
            if (!candidate.isNullOrBlank()) {
                found = candidate
                jobs.forEach { it.cancel() }
                return@coroutineScope found
            }
        }
        found
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> {
        val lrc = getLyrics(title, artist, album, durationSec) ?: return emptyList()
        return listOf(
            NativeSearchCandidate(
                id = "lyricsplus_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "LyricsPlus",
                syncedLyrics = lrc,
            )
        )
    }

    private fun fetchFromMirror(
        baseUrl: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = runCatching {
        val urlBuilder = "$baseUrl/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("title", title)
        urlBuilder.addQueryParameter("artist", artist)
        if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("album", album)
        if (durationSec > 0) urlBuilder.addQueryParameter("duration", durationSec.toString())
        urlBuilder.addQueryParameter("source", "apple,lyricsplus,musixmatch,spotify,musixmatch-word")

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Accept", "application/json")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            parseKpoeJson(body)
        }
    }.getOrNull()

    internal fun parseKpoeJson(rawJson: String): String? = runCatching {
        val root = JSONObject(rawJson)
        val type = root.optString("type", "Line")
        val lyricsArray = root.optJSONArray("lyrics") ?: return null
        if (lyricsArray.length() == 0) return null

        val isWord = type.equals("Word", ignoreCase = true)
        val sb = StringBuilder()

        for (i in 0 until lyricsArray.length()) {
            val lineObj = lyricsArray.optJSONObject(i) ?: continue
            val startTimeMs = lineObj.optLong("time", -1L)
            val lineDurMs = lineObj.optLong("duration", 0L)
            if (startTimeMs < 0L) continue

            sb.append(NativeLyricsHttp.formatLineStamp(startTimeMs))
            val syllabus = lineObj.optJSONArray("syllabus")
            if (isWord && syllabus != null && syllabus.length() > 0) {
                var lastEnd = startTimeMs + lineDurMs
                for (j in 0 until syllabus.length()) {
                    val syl = syllabus.optJSONObject(j) ?: continue
                    val sylText = syl.optString("text")
                    if (sylText.isEmpty()) continue
                    val sylTime = syl.optLong("time", startTimeMs)
                    val sylDur = syl.optLong("duration", 150L)
                    sb.append(NativeLyricsHttp.formatWordStamp(sylTime)).append(sylText)
                    lastEnd = maxOf(lastEnd, sylTime + sylDur)
                }
                sb.append(NativeLyricsHttp.formatWordStamp(lastEnd))
            } else {
                val text = lineObj.optString("text")
                if (text.isBlank()) continue
                sb.append(text)
            }
            sb.append('\n')
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. BetterLyrics (lyrics-api.boidu.dev + Portato QQ)
// ─────────────────────────────────────────────────────────────────────────────
internal object BetterLyricsClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = coroutineScope {
        val mainDeferred = async(Dispatchers.IO) {
            queryEndpoint("https://lyrics-api.boidu.dev/getLyrics", title, artist, album, durationSec)
        }
        val qqDeferred = async(Dispatchers.IO) {
            queryEndpoint("https://lyrics-api.boidu.dev/qq/getLyrics", title, artist, album, durationSec)
        }
        val mainRes = mainDeferred.await()
        if (!mainRes.isNullOrBlank()) {
            qqDeferred.cancel()
            return@coroutineScope mainRes
        }
        qqDeferred.await()
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val mainDeferred = async(Dispatchers.IO) {
            queryEndpoint("https://lyrics-api.boidu.dev/getLyrics", title, artist, album, durationSec)
        }
        val qqDeferred = async(Dispatchers.IO) {
            queryEndpoint("https://lyrics-api.boidu.dev/qq/getLyrics", title, artist, album, durationSec)
        }
        val out = mutableListOf<NativeSearchCandidate>()
        mainDeferred.await()?.takeIf { it.isNotBlank() }?.let {
            out += NativeSearchCandidate(
                id = "better_lyrics_main_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "BetterLyrics",
                syncedLyrics = it,
            )
        }
        qqDeferred.await()?.takeIf { it.isNotBlank() }?.let {
            out += NativeSearchCandidate(
                id = "better_lyrics_qq_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "BetterLyrics (Portato QQ)",
                syncedLyrics = it,
            )
        }
        out
    }

    internal fun queryEndpoint(
        endpoint: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = runCatching {
        val urlBuilder = endpoint.toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("s", NativeLyricsHttp.cleanQueryText(title))
        urlBuilder.addQueryParameter("a", NativeLyricsHttp.primaryArtist(artist))
        if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("al", album)
        if (durationSec > 0) urlBuilder.addQueryParameter("d", durationSec.toString())

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim() ?: return null
            if (body.isBlank()) return null
            if (body.startsWith("<tt", ignoreCase = true) || body.startsWith("<?xml", ignoreCase = true)) {
                return body
            }
            val json = JSONObject(body)
            json.optString("ttml").takeIf { it.isNotBlank() }
                ?: json.optString("lyrics").takeIf { it.isNotBlank() }
                ?: json.optString("lrc").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. BiniLyrics & lrc.red (TTML & ISRC Word-Sync)
// ─────────────────────────────────────────────────────────────────────────────
internal object BiniAndLrcRedClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = coroutineScope {
        val biniDeferred = async(Dispatchers.IO) { queryBini(title, artist, album, durationSec) }
        val lrcRedDeferred = async(Dispatchers.IO) { queryLrcRed(title, artist) }

        val bini = biniDeferred.await()
        if (!bini.isNullOrBlank()) {
            lrcRedDeferred.cancel()
            return@coroutineScope bini
        }
        lrcRedDeferred.await()
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val biniDeferred = async(Dispatchers.IO) { queryBini(title, artist, album, durationSec) }
        val lrcRedDeferred = async(Dispatchers.IO) { queryLrcRed(title, artist) }
        val list = mutableListOf<NativeSearchCandidate>()
        biniDeferred.await()?.takeIf { it.isNotBlank() }?.let {
            list += NativeSearchCandidate(
                id = "bini_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "BiniLyrics",
                syncedLyrics = it,
            )
        }
        lrcRedDeferred.await()?.takeIf { it.isNotBlank() }?.let {
            list += NativeSearchCandidate(
                id = "lrcred_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "lrc.red",
                syncedLyrics = it,
            )
        }
        list
    }

    private fun queryBini(title: String, artist: String, album: String?, durationSec: Int): String? = runCatching {
        val urlBuilder = "https://lyrics-api.binimum.org/".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("title", NativeLyricsHttp.cleanQueryText(title))
        urlBuilder.addQueryParameter("artist", NativeLyricsHttp.primaryArtist(artist))
        if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("album", album)
        if (durationSec > 0) urlBuilder.addQueryParameter("duration", durationSec.toString())

        val req = Request.Builder().url(urlBuilder.build()).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim() ?: return null
            if (body.startsWith("<tt", ignoreCase = true) || body.startsWith("<?xml", ignoreCase = true)) return body
            val json = JSONObject(body)
            val ttmlUrl = json.optString("ttmlUrl").ifBlank { json.optString("url") }
            if (ttmlUrl.startsWith("http")) {
                val ttmlReq = Request.Builder().url(ttmlUrl).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
                NativeLyricsHttp.fastClient.newCall(ttmlReq).execute().use { ttmlResp ->
                    if (ttmlResp.isSuccessful) {
                        val ttmlBody = ttmlResp.body?.string()?.trim()
                        if (!ttmlBody.isNullOrBlank()) return ttmlBody
                    }
                }
            }
            val direct = json.optString("ttml").ifBlank { json.optString("lyrics") }.ifBlank { json.optString("lrc") }
            if (direct.isNotBlank()) return direct

            val isrc = json.optString("isrc")
            if (isrc.isNotBlank()) {
                return fetchLrcRedByIsrc(isrc)
            }
            null
        }
    }.getOrNull()

    private fun queryLrcRed(title: String, artist: String): String? = runCatching {
        val q = "${NativeLyricsHttp.cleanQueryText(title)} ${NativeLyricsHttp.primaryArtist(artist)}".trim()
        val searchUrl = "https://lrc.red/search.json?q=${NativeLyricsHttp.urlEncode(q)}"
        val req = Request.Builder().url(searchUrl).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        val isrc = NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("results")
            arr?.optJSONObject(0)?.let { first ->
                first.optString("isrc").ifBlank { first.optString("id") }
            }
        } ?: return null

        if (isrc.isBlank()) return null
        fetchLrcRedByIsrc(isrc)
    }.getOrNull()

    private fun fetchLrcRedByIsrc(isrc: String): String? {
        val urls = listOf(
            "https://lrc.red/s/$isrc.ttml",
            "https://lrc.red/api/lyrics?id=$isrc&type=isrc",
        )
        for (url in urls) {
            val res = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
                NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string()?.trim() else null
                }
            }.getOrNull()
            if (!res.isNullOrBlank() && (res.contains("<tt", ignoreCase = true) || res.contains("[0"))) {
                return res
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. NetEase Cloud Music (網易雲音楽 - YRC Word-Sync + LRC + Translation)
// ─────────────────────────────────────────────────────────────────────────────
internal object NetEaseLyricsClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = withContext(Dispatchers.IO) {
        val songs = searchSongs(title, artist, durationSec)
        for (song in songs.take(2)) {
            val lrc = fetchLyricsBySongId(song.id)
            if (!lrc.isNullOrBlank()) return@withContext lrc
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val songs = withContext(Dispatchers.IO) { searchSongs(title, artist, durationSec) }
        songs.take(4).map { song ->
            async(Dispatchers.IO) {
                val lrc = fetchLyricsBySongId(song.id) ?: return@async null
                NativeSearchCandidate(
                    id = "netease_${song.id}",
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationSeconds = (song.durationMs / 1000).toInt(),
                    provider = "NetEase",
                    syncedLyrics = lrc,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private data class NetEaseSong(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    private fun searchSongs(title: String, artist: String, durationSec: Int): List<NetEaseSong> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val keyword = "$cleanTitle $cleanArtist".trim()
        val body = FormBody.Builder()
            .add("s", keyword)
            .add("type", "1")
            .add("offset", "0")
            .add("limit", "8")
            .build()

        val req = Request.Builder()
            .url("https://music.163.com/api/cloudsearch/pc")
            .post(body)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .build()

        val songs = NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string() ?: return emptyList()
            val songsArr = JSONObject(text).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
            val list = ArrayList<NetEaseSong>(songsArr.length())
            for (i in 0 until songsArr.length()) {
                val s = songsArr.optJSONObject(i) ?: continue
                val id = s.optLong("id", 0L)
                if (id <= 0L) continue
                val arArr = s.optJSONArray("ar") ?: s.optJSONArray("artists")
                val artistNames = buildString {
                    if (arArr != null) {
                        for (j in 0 until arArr.length()) {
                            val name = arArr.optJSONObject(j)?.optString("name").orEmpty()
                            if (name.isNotBlank()) {
                                if (isNotEmpty()) append(", ")
                                append(name)
                            }
                        }
                    }
                }
                val albumName = (s.optJSONObject("al") ?: s.optJSONObject("album"))?.optString("name")
                val dt = s.optLong("dt", s.optLong("duration", 0L))
                list += NetEaseSong(id, s.optString("name"), artistNames, albumName, dt)
            }
            list
        }

        val targetMs = durationSec * 1000L
        songs.sortedByDescending { song ->
            var score = 0
            if (song.title.equals(cleanTitle, ignoreCase = true)) score += 50
            if (cleanArtist.isNotBlank() && song.artist.contains(cleanArtist, ignoreCase = true)) score += 30
            if (targetMs > 0 && song.durationMs > 0 && abs(song.durationMs - targetMs) <= 4000) score += 25
            score
        }
    }.getOrDefault(emptyList())

    private fun fetchLyricsBySongId(songId: Long): String? = runCatching {
        val url = "https://music.163.com/api/song/lyric/v1?id=$songId&lv=1&kv=1&tv=-1&yv=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val yrcRaw = json.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            val lrcRaw = json.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            val transRaw = json.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() }

            val mainLrc = if (!yrcRaw.isNullOrBlank()) {
                convertYrcToEnhancedLrc(yrcRaw) ?: lrcRaw
            } else {
                lrcRaw
            } ?: return null

            NativeLyricsHttp.mergeLrcWithTranslation(mainLrc, transRaw)
        }
    }.getOrNull()

    private val YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.+)$""")
    private val YRC_WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    internal fun convertYrcToEnhancedLrc(yrc: String): String? {
        val sb = StringBuilder()
        var count = 0
        for (rawLine in yrc.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("{")) continue // JSON metadata header line in YRC
            val lineMatch = YRC_LINE_REGEX.matchEntire(trimmed) ?: continue
            val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDur = lineMatch.groupValues[2].toLongOrNull() ?: 0L
            val content = lineMatch.groupValues[3]

            val words = YRC_WORD_REGEX.findAll(content).toList()
            if (words.isEmpty()) continue

            sb.append(NativeLyricsHttp.formatLineStamp(lineStart))
            var lastEnd = lineStart + lineDur
            for (w in words) {
                val wStart = w.groupValues[1].toLongOrNull() ?: lineStart
                val wDur = w.groupValues[2].toLongOrNull() ?: 150L
                val wText = w.groupValues[3]
                sb.append(NativeLyricsHttp.formatWordStamp(wStart)).append(wText)
                lastEnd = maxOf(lastEnd, wStart + wDur)
            }
            sb.append(NativeLyricsHttp.formatWordStamp(lastEnd)).append('\n')
            count++
        }
        return if (count > 0) sb.toString().trimEnd() else null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. QQ Music (QQ音楽 - Portato QQ Word-Sync + Smartbox & Base64 LRC + Translation)
// ─────────────────────────────────────────────────────────────────────────────
internal object QqMusicLyricsClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = coroutineScope {
        // Race Portato QQ (word-synced TTML/LRC) and direct QQ Music API
        val portatoDeferred = async(Dispatchers.IO) {
            BetterLyricsClient.queryEndpoint("https://lyrics-api.boidu.dev/qq/getLyrics", title, artist, album, durationSec)
        }
        val directDeferred = async(Dispatchers.IO) {
            val tracks = searchSongs(title, artist)
            tracks.firstOrNull()?.let { fetchLyricsByMid(it.songMid) }
        }

        val portato = portatoDeferred.await()
        if (!portato.isNullOrBlank()) {
            directDeferred.cancel()
            return@coroutineScope portato
        }
        directDeferred.await()
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val tracks = withContext(Dispatchers.IO) { searchSongs(title, artist) }
        val jobs = tracks.take(4).map { track ->
            async(Dispatchers.IO) {
                val lrc = fetchLyricsByMid(track.songMid) ?: return@async null
                NativeSearchCandidate(
                    id = "qqmusic_${track.songMid}",
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSeconds = durationSec,
                    provider = "QQ Music",
                    syncedLyrics = lrc,
                )
            }
        }
        jobs.awaitAll().filterNotNull()
    }

    private data class QqSong(
        val songMid: String,
        val title: String,
        val artist: String,
        val album: String? = null,
    )

    private fun searchSongs(title: String, artist: String): List<QqSong> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val key = "$cleanTitle $cleanArtist".trim()
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&inCharset=utf-8&outCharset=utf-8&key=${NativeLyricsHttp.urlEncode(key)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val itemlist = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist") ?: return emptyList()

            val list = ArrayList<QqSong>(itemlist.length())
            for (i in 0 until itemlist.length()) {
                val item = itemlist.optJSONObject(i) ?: continue
                val mid = item.optString("mid")
                if (mid.isBlank()) continue
                list += QqSong(
                    songMid = mid,
                    title = item.optString("name", cleanTitle),
                    artist = item.optString("singer", cleanArtist),
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private fun fetchLyricsByMid(songMid: String): String? = runCatching {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${NativeLyricsHttp.urlEncode(songMid)}&format=json&nobase64=0&g_tk=5381"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NativeLyricsHttp.USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            var body = resp.body?.string()?.trim() ?: return null
            if (body.startsWith("MusicJsonCallback(") && body.endsWith(")")) {
                body = body.removePrefix("MusicJsonCallback(").removeSuffix(")")
            }
            val json = JSONObject(body)
            val lyricB64 = json.optString("lyric")
            if (lyricB64.isBlank()) return null
            val mainLrc = String(Base64.decode(lyricB64, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (mainLrc.isBlank()) return null

            val transB64 = json.optString("trans")
            val transLrc = if (transB64.isNotBlank()) {
                runCatching { String(Base64.decode(transB64, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrNull()
            } else null

            NativeLyricsHttp.mergeLrcWithTranslation(mainLrc, transLrc)
        }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. KuGou Music (酷狗音楽 - KRC Decrypt Word-Sync + Base64 LRC Fallback)
// ─────────────────────────────────────────────────────────────────────────────
internal object KugouLyricsClient {
    private val KRC_XOR_KEY = intArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = withContext(Dispatchers.IO) {
        val candidates = searchCandidates(title, artist, durationSec)
        for (cand in candidates.take(2)) {
            val lrc = downloadKrcOrLrc(cand.id, cand.accessKey)
            if (!lrc.isNullOrBlank()) return@withContext lrc
        }
        null
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val candidates = withContext(Dispatchers.IO) { searchCandidates(title, artist, durationSec) }
        candidates.take(4).map { cand ->
            async(Dispatchers.IO) {
                val lrc = downloadKrcOrLrc(cand.id, cand.accessKey) ?: return@async null
                NativeSearchCandidate(
                    id = "kugou_${cand.id}",
                    title = cand.song,
                    artist = cand.singer,
                    album = album,
                    durationSeconds = (cand.durationMs / 1000).toInt(),
                    provider = "KuGou",
                    syncedLyrics = lrc,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private data class KugouCandidate(
        val id: String,
        val accessKey: String,
        val song: String,
        val singer: String,
        val durationMs: Long,
    )

    private fun searchCandidates(title: String, artist: String, durationSec: Int): List<KugouCandidate> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val keyword = if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle" else cleanTitle
        val durMs = if (durationSec > 0) durationSec * 1000 else 200000
        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=${NativeLyricsHttp.urlEncode(keyword)}&duration=$durMs&hash="
        val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("candidates") ?: return emptyList()
            val list = ArrayList<KugouCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val key = c.optString("accesskey")
                if (id.isBlank() || key.isBlank()) continue
                list += KugouCandidate(
                    id = id,
                    accessKey = key,
                    song = c.optString("song", cleanTitle),
                    singer = c.optString("singer", cleanArtist),
                    durationMs = c.optLong("duration", durMs.toLong()),
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private fun downloadKrcOrLrc(id: String, accessKey: String): String? {
        // 1. Try KRC (Word-Synced)
        val krcContent = runCatching {
            val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=krc&charset=utf8"
            val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val b64 = JSONObject(body).optString("content")
                if (b64.isBlank()) return@runCatching null
                val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                val decrypted = decryptKrcBytes(rawBytes) ?: return@runCatching null
                convertKrcToEnhancedLrc(decrypted)
            }
        }.getOrNull()

        if (!krcContent.isNullOrBlank()) return krcContent

        // 2. Fallback to LRC (Line-Synced)
        return runCatching {
            val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val req = Request.Builder().url(url).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val b64 = JSONObject(body).optString("content")
                if (b64.isBlank()) return@runCatching null
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    internal fun decryptKrcBytes(raw: ByteArray): String? = runCatching {
        if (raw.size <= 4) return null
        if (raw[0] != 'k'.code.toByte() || raw[1] != 'r'.code.toByte() || raw[2] != 'c'.code.toByte() || raw[3] != '1'.code.toByte()) {
            return null
        }
        val payload = ByteArray(raw.size - 4)
        for (i in payload.indices) {
            payload[i] = (raw[i + 4].toInt() xor KRC_XOR_KEY[i % 16]).toByte()
        }
        val inflater = Inflater()
        inflater.setInput(payload)
        val out = ByteArrayOutputStream(payload.size * 3)
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count <= 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buf, 0, count)
            }
        }
        inflater.end()
        out.toString("UTF-8")
    }.getOrNull()

    private val KRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val KRC_WORD_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    internal fun convertKrcToEnhancedLrc(krcText: String): String? {
        val sb = StringBuilder()
        var lineCount = 0
        for (rawLine in krcText.lineSequence()) {
            val trimmed = rawLine.trim()
            val m = KRC_LINE_REGEX.matchEntire(trimmed) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: continue
            val lineDur = m.groupValues[2].toLongOrNull() ?: 0L
            val content = m.groupValues[3]

            val words = KRC_WORD_REGEX.findAll(content).toList()
            sb.append(NativeLyricsHttp.formatLineStamp(lineStart))
            if (words.isNotEmpty()) {
                var lastEnd = lineStart + lineDur
                for (w in words) {
                    val wOffset = w.groupValues[1].toLongOrNull() ?: 0L
                    val wDur = w.groupValues[2].toLongOrNull() ?: 150L
                    val wText = w.groupValues[3]
                    val wStart = lineStart + wOffset
                    sb.append(NativeLyricsHttp.formatWordStamp(wStart)).append(wText)
                    lastEnd = maxOf(lastEnd, wStart + wDur)
                }
                sb.append(NativeLyricsHttp.formatWordStamp(lastEnd))
            } else {
                sb.append(content)
            }
            sb.append('\n')
            lineCount++
        }
        return if (lineCount > 0) sb.toString().trimEnd() else null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. PetitLyrics (プチリリ - SDK API + Web Fallback)
// ─────────────────────────────────────────────────────────────────────────────
internal object PetitLyricsClient {
    private const val SDK_URL = "https://sdk.petitlyrics.com/api/GetLyricsData.php"
    private const val CLIENT_APP_ID = "p1110417"

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = withContext(Dispatchers.IO) {
        val sdkCandidates = querySdkCandidates(title, artist, album)
        if (sdkCandidates.isNotEmpty()) {
            val best = sdkCandidates.first()
            return@withContext best.bestRaw
        }
        queryWebPetitLyrics(title, artist)
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = withContext(Dispatchers.IO) {
        val sdk = querySdkCandidates(title, artist, album)
        if (sdk.isNotEmpty()) return@withContext sdk
        val web = queryWebPetitLyrics(title, artist)
        if (!web.isNullOrBlank()) {
            listOf(
                NativeSearchCandidate(
                    id = "petitlyrics_web_${title.hashCode()}",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = durationSec,
                    provider = "プチリリ (PetitLyrics)",
                    plainLyrics = web,
                )
            )
        } else emptyList()
    }

    private fun querySdkCandidates(
        title: String,
        artist: String,
        album: String?,
    ): List<NativeSearchCandidate> = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)

        val form = FormBody.Builder()
            .add("key_title", cleanTitle)
            .add("key_artist", cleanArtist)
            .apply { if (!album.isNullOrBlank()) add("key_album", album) }
            .add("clientAppId", CLIENT_APP_ID)
            .add("terminal_type", "10")
            .add("lyricsType", "1")
            .add("max_count", "5")
            .build()

        val req = Request.Builder()
            .url(SDK_URL)
            .post(form)
            .header("User-Agent", "PetitLyricsAndroid/2.7")
            .build()

        NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string() ?: return emptyList()
            parsePetitXml(xml, cleanTitle, cleanArtist)
        }
    }.getOrDefault(emptyList())

    private val SONG_BLOCK_REGEX = Regex("""<song>([\s\S]*?)</song>""")
    private fun extractTag(block: String, tag: String): String =
        Regex("""<$tag>([\s\S]*?)</$tag>""").find(block)?.groupValues?.get(1)?.trim().orEmpty()

    internal fun parsePetitXml(xml: String, defaultTitle: String, defaultArtist: String): List<NativeSearchCandidate> {
        val out = mutableListOf<NativeSearchCandidate>()
        for ((idx, m) in SONG_BLOCK_REGEX.findAll(xml).withIndex()) {
            val block = m.groupValues[1]
            val id = extractTag(block, "lyricsId").ifBlank { idx.toString() }
            val songTitle = extractTag(block, "title").ifBlank { defaultTitle }
            val songArtist = extractTag(block, "artist").ifBlank { defaultArtist }
            val songAlbum = extractTag(block, "album").takeIf { it.isNotBlank() }
            val b64 = extractTag(block, "lyricsData")
            if (b64.isBlank()) continue

            val decoded = runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull() ?: continue
            if (decoded.isBlank() || decoded.startsWith("wsy", ignoreCase = true) && !decoded.contains("\n")) continue

            val isSynced = Regex("""\[\d{2}:\d{2}[.:]\d{2,3}]""").containsMatchIn(decoded)
            out += NativeSearchCandidate(
                id = "petitlyrics_$id",
                title = songTitle,
                artist = songArtist,
                album = songAlbum,
                provider = "プチリリ (PetitLyrics)",
                syncedLyrics = if (isSynced) decoded else null,
                plainLyrics = if (!isSynced) decoded else null,
            )
        }
        return out
    }

    private fun queryWebPetitLyrics(title: String, artist: String): String? = runCatching {
        val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
        val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
        val searchUrl = "https://petitlyrics.com/search_lyrics?title=${NativeLyricsHttp.urlEncode(cleanTitle)}&artist=${NativeLyricsHttp.urlEncode(cleanArtist)}"
        val searchReq = Request.Builder().url(searchUrl).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        val lyricId = NativeLyricsHttp.fastClient.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            Regex("""href="/lyrics/(\d+)"""").find(html)?.groupValues?.get(1)
        } ?: return null

        val pageUrl = "https://petitlyrics.com/lyrics/$lyricId"
        val pageReq = Request.Builder().url(pageUrl).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.fastClient.newCall(pageReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            val canvasMatch = Regex("""<canvas[^>]*id="lyrics"[^>]*>([\s\S]*?)</canvas>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            val raw = canvasMatch ?: Regex("""<div[^>]*id="lyrics_list"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: return null
            WebScrapeHtmlUtils.htmlToCleanLyrics(raw)
        }
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// 9. Musixmatch (Desktop RichSync + Subtitles + PaxSenix Fallback)
// ─────────────────────────────────────────────────────────────────────────────
internal object MusixmatchLyricsClient {
    private const val MXM_BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
    private val tokenMutex = Mutex()
    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = coroutineScope {
        val paxDeferred = async(Dispatchers.IO) { queryPaxMusixmatch(title, artist, durationSec) }
        val desktopDeferred = async(Dispatchers.IO) { queryDesktopMusixmatch(title, artist, durationSec) }

        val pax = paxDeferred.await()
        if (!pax.isNullOrBlank()) {
            desktopDeferred.cancel()
            return@coroutineScope pax
        }
        desktopDeferred.await()
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = coroutineScope {
        val lrc = getLyrics(title, artist, album, durationSec) ?: return@coroutineScope emptyList()
        val isSynced = Regex("""\[\d{2}:\d{2}[.:]\d{2,3}]""").containsMatchIn(lrc)
        listOf(
            NativeSearchCandidate(
                id = "musixmatch_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "Musixmatch",
                syncedLyrics = if (isSynced) lrc else null,
                plainLyrics = if (!isSynced) lrc else null,
            )
        )
    }

    private fun queryPaxMusixmatch(title: String, artist: String, durationSec: Int): String? = runCatching {
        val urlBuilder = "https://lyrics.paxsenix.org/musixmatch/lyrics".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("t", NativeLyricsHttp.cleanQueryText(title))
        urlBuilder.addQueryParameter("a", NativeLyricsHttp.primaryArtist(artist))
        if (durationSec > 0) urlBuilder.addQueryParameter("d", durationSec.toString())
        urlBuilder.addQueryParameter("type", "word")
        urlBuilder.addQueryParameter("format", "lrc")

        val req = Request.Builder().url(urlBuilder.build()).header("User-Agent", NativeLyricsHttp.USER_AGENT).build()
        NativeLyricsHttp.paxClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim() ?: return null
            if (body.startsWith("[0")) return body
            val json = JSONObject(body)
            json.optString("synced").takeIf { it.isNotBlank() }
                ?: json.optString("lyrics").takeIf { it.isNotBlank() }
                ?: json.optString("lrc").takeIf { it.isNotBlank() }
                ?: json.optString("plain").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private suspend fun queryDesktopMusixmatch(title: String, artist: String, durationSec: Int): String? {
        val token = getOrFetchToken() ?: return null
        return runCatching {
            val cleanTitle = NativeLyricsHttp.cleanQueryText(title)
            val cleanArtist = NativeLyricsHttp.primaryArtist(artist)
            val urlBuilder = "$MXM_BASE/macro.subtitles.get".toHttpUrlOrNull()?.newBuilder() ?: return null
            urlBuilder.addQueryParameter("format", "json")
            urlBuilder.addQueryParameter("namespace", "lyrics_richsynched")
            urlBuilder.addQueryParameter("subtitle_format", "lrc")
            urlBuilder.addQueryParameter("app_id", "web-desktop-app-v1.0")
            urlBuilder.addQueryParameter("q_track", cleanTitle)
            urlBuilder.addQueryParameter("q_artist", cleanArtist)
            if (durationSec > 0) urlBuilder.addQueryParameter("q_duration", durationSec.toString())
            urlBuilder.addQueryParameter("usertoken", token)

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", NativeLyricsHttp.USER_AGENT)
                .header("Cookie", "x-mxm-token-guid=1")
                .build()

            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val calls = JSONObject(body)
                    .optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("macro_calls") ?: return null

                // 1. Check RichSync (Word-level)
                val richSyncBody = calls.optJSONObject("track.richsync.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("richsync")
                    ?.optString("richsync_body")
                if (!richSyncBody.isNullOrBlank()) {
                    val enhanced = parseRichSyncJson(richSyncBody)
                    if (!enhanced.isNullOrBlank()) return enhanced
                }

                // 2. Check Subtitle (Line-level LRC)
                val subtitleBody = calls.optJSONObject("track.subtitles.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONArray("subtitle_list")
                    ?.optJSONObject(0)
                    ?.optJSONObject("subtitle")
                    ?.optString("subtitle_body")
                if (!subtitleBody.isNullOrBlank()) return subtitleBody.trim()

                // 3. Check Plain Lyrics
                calls.optJSONObject("track.lyrics.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("lyrics")
                    ?.optString("lyrics_body")
                    ?.lineSequence()
                    ?.filterNot { it.contains("******* This Lyrics is NOT for Commercial use") }
                    ?.joinToString("\n")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun parseRichSyncJson(rawJson: String): String? = runCatching {
        val arr = JSONArray(rawJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val lineObj = arr.optJSONObject(i) ?: continue
            val tsSec = lineObj.optDouble("ts", -1.0)
            val teSec = lineObj.optDouble("te", tsSec + 2.0)
            if (tsSec < 0) continue
            val lineStartMs = (tsSec * 1000.0).toLong()
            val lineEndMs = (teSec * 1000.0).toLong()
            sb.append(NativeLyricsHttp.formatLineStamp(lineStartMs))
            val words = lineObj.optJSONArray("l")
            if (words != null && words.length() > 0) {
                for (j in 0 until words.length()) {
                    val w = words.optJSONObject(j) ?: continue
                    val ch = w.optString("c")
                    if (ch.isEmpty()) continue
                    val offsetSec = w.optDouble("o", 0.0)
                    val wStartMs = lineStartMs + (offsetSec * 1000.0).toLong()
                    sb.append(NativeLyricsHttp.formatWordStamp(wStartMs)).append(ch)
                }
                sb.append(NativeLyricsHttp.formatWordStamp(lineEndMs))
            } else {
                sb.append(lineObj.optString("x"))
            }
            sb.append('\n')
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private suspend fun getOrFetchToken(): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (!cachedToken.isNullOrBlank() && now < tokenExpiresAtMs) {
            return cachedToken
        }
        val token = runCatching {
            val url = "$MXM_BASE/token.get?app_id=web-desktop-app-v1.0&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NativeLyricsHttp.USER_AGENT)
                .header("Cookie", "x-mxm-token-guid=1")
                .build()
            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                JSONObject(body)
                    .optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optString("user_token")
                    ?.takeIf { it.isNotBlank() && it != "UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly" }
            }
        }.getOrNull()
        if (!token.isNullOrBlank()) {
            cachedToken = token
            tokenExpiresAtMs = now + 45 * 60 * 1000L
        }
        token
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 10. LRCLIB (Native Direct Get + Search)
// ─────────────────────────────────────────────────────────────────────────────
internal object LrcLibClient {
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = withContext(Dispatchers.IO) {
        val candidates = searchLyrics(title, artist, album, durationSec)
        candidates.firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.syncedLyrics
            ?: candidates.firstOrNull()?.bestRaw
    }

    suspend fun searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val urlBuilder = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
            urlBuilder.addQueryParameter("track_name", NativeLyricsHttp.cleanQueryText(title))
            if (artist.isNotBlank()) urlBuilder.addQueryParameter("artist_name", NativeLyricsHttp.primaryArtist(artist))
            if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("album_name", album)

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "Tinque (https://github.com/bitchord)")
                .build()

            NativeLyricsHttp.fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                val out = ArrayList<NativeSearchCandidate>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                    val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                    if (synced == null && plain == null) continue
                    out += NativeSearchCandidate(
                        id = "lrclib_${obj.optLong("id", i.toLong())}",
                        title = obj.optString("trackName", title),
                        artist = obj.optString("artistName", artist),
                        album = obj.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
                        durationSeconds = obj.optDouble("duration", 0.0).toInt(),
                        provider = "LRCLIB",
                        syncedLyrics = synced,
                        plainLyrics = plain,
                    )
                }
                out
            }
        }.getOrDefault(emptyList())
    }
}
