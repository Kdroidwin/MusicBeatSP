package com.music.bitchord.radio.data

import android.net.Uri
import android.text.Html
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.radio.RadioPlaybackSession
import com.music.bitchord.radio.auth.DeviceSpoofing
import com.music.bitchord.radio.auth.GpsSpoofing
import com.music.bitchord.radio.auth.PartialKeyGenerator
import com.music.bitchord.radio.model.ProgramEntry
import com.music.bitchord.radio.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Radiko live-stream and program data, adapted from Radikall (MIT; see licenses/RADIKALL-MIT.txt). */
object RadioRepository {
    private const val LIVE_FALLBACK_URL = "https://si-f-radiko.smartstream.ne.jp/so/playlist.m3u8"
    private val sessions = ConcurrentHashMap<String, CachedSession>()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    data class LiveStream(
        val station: Station,
        val streamUrl: String,
        val authHeaders: Map<String, String>,
        val areaId: String,
    )

    suspend fun prepareLiveStream(station: Station, preferredAreaId: String): LiveStream = withContext(Dispatchers.IO) {
        ensureNetworkAllowed()
        val candidateAreas = buildList {
            if (preferredAreaId in station.areaIds) add(preferredAreaId)
            addAll(station.areaIds.filterNot { it == preferredAreaId })
        }
        val session = candidateAreas.asSequence()
            .mapNotNull(sessions::get)
            .firstOrNull { System.currentTimeMillis() - it.createdAtMs < SESSION_MAX_AGE_MS }
            ?: authenticate(candidateAreas.firstOrNull() ?: preferredAreaId)
        val streamUrl = buildLiveStreamUrl(station.id)
        val headers = mapOf(
            "X-Radiko-AuthToken" to session.token,
            "X-Radiko-AreaId" to session.areaId,
        )
        RadioPlaybackSession.setHeaders(headers)
        LiveStream(station, streamUrl, headers, session.areaId)
    }

    suspend fun fetchWeeklyPrograms(stationId: String): List<ProgramEntry> = withContext(Dispatchers.IO) {
        ensureNetworkAllowed()
        val encodedStationId = Uri.encode(stationId)
        val weeklyXml = fetchScheduleXml("https://api.radiko.jp/program/v3/weekly/$encodedStationId.xml")
        val weeklyPrograms = parsePrograms(weeklyXml, stationId)
        val today = LocalDate.now(tokyo).format(DateTimeFormatter.BASIC_ISO_DATE)

        // Radiko's weekly feed can temporarily stop at the previous broadcast day.
        // Fetch today's schedule directly in that case instead of silently showing yesterday only.
        if (weeklyPrograms.any { programDay(it.startAt) == today }) {
            return@withContext weeklyPrograms.sortedBy(ProgramEntry::startAt)
        }

        val todayPrograms = runCatching {
            val todayXml = fetchScheduleXml(
                "https://radiko.jp/v3/program/station/date/$today/$encodedStationId.xml",
            )
            parsePrograms(todayXml, stationId)
        }.getOrDefault(emptyList())

        (weeklyPrograms + todayPrograms)
            .distinctBy(ProgramEntry::startAt)
            .sortedBy(ProgramEntry::startAt)
    }

    private fun parsePrograms(xml: String, stationId: String): List<ProgramEntry> {
        val programRegex = Regex("""<prog\b([^>]*)>([\s\S]*?)</prog>""")
        return programRegex.findAll(xml).mapNotNull { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val payload = match.groupValues[2]
            val startAt = attributes["ft"] ?: return@mapNotNull null
            val endAt = attributes["to"] ?: return@mapNotNull null
            val title = extractHtmlText(payload, "title").orEmpty().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            ProgramEntry(
                stationId = stationId,
                title = title,
                description = extractHtmlText(payload, "desc").orEmpty(),
                performer = extractHtmlText(payload, "pfm"),
                startAt = startAt,
                endAt = endAt,
                info = extractHtmlText(payload, "info"),
                imageUrl = extractTag(payload, "img")?.takeIf(::isHttpsUrl),
                url = extractTag(payload, "url")?.takeIf(::isHttpsUrl),
            )
        }.toList()
    }

    private suspend fun fetchScheduleXml(url: String): String {
        val freshUrl = Uri.parse(url).buildUpon()
            .appendQueryParameter("_", System.currentTimeMillis().toString())
            .build()
            .toString()
        return request(freshUrl) {
            header("Cache-Control", "no-cache, no-store, max-age=0")
            header("Pragma", "no-cache")
        }.body
    }

    fun currentProgram(programs: List<ProgramEntry>, now: LocalDateTime = LocalDateTime.now(tokyo)): ProgramEntry? {
        val current = now.format(dateTimeFormatter)
        return programs.firstOrNull { current >= it.startAt && current < it.endAt }
    }

    fun formatProgramTime(value: String): String = runCatching {
        LocalDateTime.parse(value, dateTimeFormatter).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(value.takeLast(4).let { if (it.length == 4) "${it.take(2)}:${it.takeLast(2)}" else value })

    fun programDay(value: String): String = value.take(8)

    fun defaultProgramDay(programs: List<ProgramEntry>): String? {
        val availableDays = programs.map { programDay(it.startAt) }
            .filter { it.matches(Regex("\\d{8}")) }
            .distinct()
            .sorted()
        val today = LocalDate.now(tokyo).format(DateTimeFormatter.BASIC_ISO_DATE)
        return today.takeIf(availableDays::contains) ?: availableDays.firstOrNull()
    }

    fun formatProgramDay(value: String): String = runCatching {
        LocalDateTime.parse(value.take(8) + "000000", dateTimeFormatter)
            .format(DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.JAPAN))
    }.getOrDefault(value.take(8))

    private suspend fun authenticate(areaId: String): CachedSession {
        ensureNetworkAllowed()
        require(areaId.matches(Regex("JP\\d{1,2}"))) { "Invalid radio area" }
        val device = DeviceSpoofing.generate()
        val appName = DeviceSpoofing.appNameForVersion(device.appVersion)
        val auth1 = request("https://radiko.jp/v2/api/auth1") {
            header("X-Radiko-App", appName)
            header("X-Radiko-App-Version", device.appVersion)
            header("X-Radiko-Device", device.device)
            header("X-Radiko-User", device.userId)
            header("User-Agent", device.userAgent)
        }
        val token = auth1.headers["x-radiko-authtoken"]
            ?: throw IOException("Radio authentication did not return a token")
        val offset = auth1.headers["x-radiko-keyoffset"]?.toIntOrNull()
            ?: throw IOException("Radio authentication returned an invalid key offset")
        val length = auth1.headers["x-radiko-keylength"]?.toIntOrNull()
            ?: throw IOException("Radio authentication returned an invalid key length")
        val partialKey = runCatching { PartialKeyGenerator.generate(offset, length) }
            .getOrElse { throw IOException("Radio authentication key was invalid", it) }
        val auth2 = request("https://radiko.jp/v2/api/auth2") {
            header("X-Radiko-App", appName)
            header("X-Radiko-App-Version", device.appVersion)
            header("X-Radiko-Device", device.device)
            header("X-Radiko-User", device.userId)
            header("X-Radiko-AuthToken", token)
            header("X-Radiko-Partialkey", partialKey)
            header("X-Radiko-Location", GpsSpoofing.generate(areaId))
            header("X-Radiko-Connection", "wifi")
            header("User-Agent", device.userAgent)
        }
        val resolvedArea = Regex("JP\\d{1,2}").find(auth2.body)?.value ?: areaId
        return CachedSession(token, resolvedArea, System.currentTimeMillis()).also { cached ->
            sessions[areaId] = cached
            sessions[resolvedArea] = cached
        }
    }

    private suspend fun buildLiveStreamUrl(stationId: String): String {
        val xml = getText("https://radiko.jp/v3/station/stream/pc_html5/${Uri.encode(stationId)}.xml")
        val blocks = Regex("""<url\b([^>]*)>([\s\S]*?)</url>""")
        val playlist = blocks.findAll(xml).firstNotNullOfOrNull { match ->
            val attributes = match.groupValues[1]
            val body = match.groupValues[2]
            if (Regex("""areafree=["']0["']""").containsMatchIn(attributes) &&
                Regex("""timefree=["']0["']""").containsMatchIn(attributes)
            ) extractTag(body, "playlist_create_url") else null
        }?.takeIf(::isHttpsUrl) ?: LIVE_FALLBACK_URL
        return Uri.parse(playlist).buildUpon()
            .appendQueryParameter("station_id", stationId)
            .appendQueryParameter("lsid", DeviceSpoofing.randomHex(32))
            .appendQueryParameter("type", "b")
            .appendQueryParameter("l", "15")
            .build()
            .toString()
    }

    private data class Response(val body: String, val headers: Map<String, String>)
    private data class CachedSession(val token: String, val areaId: String, val createdAtMs: Long)

    private suspend fun getText(url: String): String = request(url).body

    private suspend fun request(
        url: String,
        configure: okhttp3.Request.Builder.() -> Unit = {},
    ): Response {
        ensureNetworkAllowed()
        val request = Request.Builder().url(url).apply(configure).get().build()
        return Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Radio service returned HTTP ${response.code}")
            Response(body, response.headers.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() })
        }
    }

    private fun ensureNetworkAllowed() {
        check(!AppSettings.offlineMode.value) { "Radio is unavailable while offline mode is enabled" }
        check(!com.music.bitchord.BuildConfig.APPLICATION_ID.endsWith(".nointernet")) {
            "Radio is unavailable in the offline app build"
        }
    }

    private fun parseAttributes(raw: String): Map<String, String> =
        Regex("""([\w-]+)=["']([^"']*)["']""")
            .findAll(raw)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun extractTag(payload: String, tag: String): String? =
        Regex("""<$tag(?:\s[^>]*)?>([\s\S]*?)</$tag>""")
            .find(payload)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { Parser.unescapeEntities(it, false) }

    /** Radiko description fields contain HTML fragments inside their XML tags. */
    private fun extractHtmlText(payload: String, tag: String): String? =
        extractTag(payload, tag)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }
            ?.replace('\u00A0', ' ')
            ?.replace(Regex("[\\t\\x0B\\f ]+"), " ")
            ?.replace(Regex(" *\\n *"), "\n")
            ?.replace(Regex("\\n{3,}"), "\n\n")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun isHttpsUrl(value: String): Boolean =
        runCatching { Uri.parse(value).scheme.equals("https", ignoreCase = true) }.getOrDefault(false)

    private const val SESSION_MAX_AGE_MS = 70L * 60L * 1000L
}
