package com.music.bitchord.radio.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.music.bitchord.radio.data.RadioRepository
import com.music.bitchord.radio.model.ProgramEntry
import com.music.bitchord.radio.model.StationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class RadioFavoriteKind { TIME, KEYWORD }
enum class RadioFavoriteRepeat { DAILY, WEEKLY }

data class RadioRecordingFavorite(
    val id: String,
    val kind: RadioFavoriteKind,
    val stationId: String,
    val stationName: String,
    val areaId: String,
    val title: String,
    val timeOfDay: String? = null,
    val weekday: Int? = null,
    val durationMs: Long? = null,
    val repeat: RadioFavoriteRepeat? = null,
    val keyword: String? = null,
    val artworkUrl: String? = null,
)

object RadioRecordingFavoriteStore {
    private const val PREFS = "radio_recording_favorites"
    private const val KEY_FAVORITES = "favorites"
    private val programFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    private val japan = ZoneId.of("Asia/Tokyo")

    fun favorites(context: Context): List<RadioRecordingFavorite> = runCatching {
        val rows = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAVORITES, "[]"))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            RadioRecordingFavorite(
                id = row.getString("id"),
                kind = RadioFavoriteKind.valueOf(row.getString("kind")),
                stationId = row.getString("stationId"),
                stationName = row.getString("stationName"),
                areaId = row.getString("areaId"),
                title = row.getString("title"),
                timeOfDay = row.optString("timeOfDay").takeIf(String::isNotBlank),
                weekday = row.optInt("weekday", -1).takeIf { it in 1..7 },
                durationMs = row.optLong("durationMs", -1L).takeIf { it > 0L },
                repeat = row.optString("repeat").takeIf(String::isNotBlank)?.let(RadioFavoriteRepeat::valueOf),
                keyword = row.optString("keyword").takeIf(String::isNotBlank),
                artworkUrl = row.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    fun find(context: Context, id: String): RadioRecordingFavorite? = favorites(context).firstOrNull { it.id == id }

    /** Replace this device's favorite list with the copy from a user-selected backup. */
    fun restore(context: Context, imported: List<RadioRecordingFavorite>) {
        val cleaned = imported
            .filter { it.id.isNotBlank() && it.stationId.isNotBlank() && it.title.isNotBlank() }
            .distinctBy(RadioRecordingFavorite::id)
        saveAll(context, cleaned)
        RadioRecordingFavoriteScheduler.scheduleAllTimeFavorites(context)
        if (cleaned.any { it.kind == RadioFavoriteKind.KEYWORD }) {
            RadioRecordingFavoriteScheduler.requestKeywordRefresh(context)
        }
    }

    fun addTimeFavorite(
        context: Context,
        program: ProgramEntry,
        areaId: String,
        repeat: RadioFavoriteRepeat,
    ): RadioRecordingFavorite {
        val station = requireNotNull(StationRegistry.getStation(program.stationId))
        val start = LocalDateTime.parse(program.startAt, programFormatter)
        val end = LocalDateTime.parse(program.endAt, programFormatter)
        val duration = end.atZone(japan).toInstant().toEpochMilli() - start.atZone(japan).toInstant().toEpochMilli()
        val weekday = start.dayOfWeek.value
        val id = "time-${station.id}-${start.format(timeFormatter)}-${if (repeat == RadioFavoriteRepeat.DAILY) "daily" else weekday}"
        return RadioRecordingFavorite(
            id = id,
            kind = RadioFavoriteKind.TIME,
            stationId = station.id,
            stationName = station.name,
            areaId = areaId,
            title = program.title,
            timeOfDay = start.format(timeFormatter),
            weekday = weekday,
            durationMs = duration,
            repeat = repeat,
            artworkUrl = program.imageUrl,
        ).also { save(context, it) }
    }

    fun addKeywordFavorite(context: Context, stationId: String, areaId: String, keyword: String): RadioRecordingFavorite {
        val station = requireNotNull(StationRegistry.getStation(stationId))
        val cleaned = keyword.trim()
        require(cleaned.isNotEmpty())
        val favorite = RadioRecordingFavorite(
            id = "keyword-${UUID.randomUUID()}",
            kind = RadioFavoriteKind.KEYWORD,
            stationId = station.id,
            stationName = station.name,
            areaId = areaId,
            title = cleaned,
            keyword = cleaned,
        )
        save(context, favorite)
        return favorite
    }

    fun remove(context: Context, id: String) {
        saveAll(context, favorites(context).filterNot { it.id == id })
        RadioAutoRecordingStore.recordings(context)
            .filter { it.favoriteId == id && !RadioAutoRecordingStore.activeIds(context).contains(it.id) }
            .forEach { RadioAutoRecordingScheduler.cancel(context, it.id) }
    }

    private fun save(context: Context, favorite: RadioRecordingFavorite) {
        saveAll(context, favorites(context).filterNot { it.id == favorite.id } + favorite)
    }

    private fun saveAll(context: Context, favorites: List<RadioRecordingFavorite>) {
        val array = JSONArray()
        favorites.forEach { favorite ->
            array.put(
                JSONObject()
                    .put("id", favorite.id)
                    .put("kind", favorite.kind.name)
                    .put("stationId", favorite.stationId)
                    .put("stationName", favorite.stationName)
                    .put("areaId", favorite.areaId)
                    .put("title", favorite.title)
                    .put("timeOfDay", favorite.timeOfDay)
                    .put("weekday", favorite.weekday)
                    .put("durationMs", favorite.durationMs)
                    .put("repeat", favorite.repeat?.name)
                    .put("keyword", favorite.keyword)
                    .put("artworkUrl", favorite.artworkUrl),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }
}

object RadioRecordingFavoriteScheduler {
    const val ACTION_REFRESH_KEYWORDS = "com.music.bitchord.radio.REFRESH_RECORDING_FAVORITES"
    private const val REFRESH_REQUEST_CODE = 43197
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    private val japan = ZoneId.of("Asia/Tokyo")
    private val programFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    fun scheduleNextTimeFavorite(context: Context, favoriteId: String, afterMs: Long = System.currentTimeMillis()) {
        if (!RadioAutoRecordingScheduler.canScheduleExactAlarms(context) || RadioAutoRecordingStore.folderUri(context) == null) return
        val favorite = RadioRecordingFavoriteStore.find(context, favoriteId)
            ?.takeIf { it.kind == RadioFavoriteKind.TIME } ?: return
        var after = afterMs
        repeat(9) {
            val recording = nextTimeRecording(favorite, after) ?: return
            val overlaps = RadioAutoRecordingStore.recordings(context).any { existing ->
                existing.id != recording.id && recording.startTimeMs < existing.endTimeMs && existing.startTimeMs < recording.endTimeMs
            }
            if (!overlaps) {
                runCatching { RadioAutoRecordingScheduler.schedule(context, recording) }
                return
            }
            after = recording.startTimeMs
        }
    }

    fun scheduleAllTimeFavorites(context: Context) {
        if (!RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) return
        RadioRecordingFavoriteStore.favorites(context)
            .filter { it.kind == RadioFavoriteKind.TIME }
            .forEach { scheduleNextTimeFavorite(context, it.id) }
    }

    fun requestKeywordRefresh(context: Context, delayMs: Long = 1_000L) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        val pending = refreshIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun onBoot(context: Context) {
        scheduleAllTimeFavorites(context)
        if (RadioRecordingFavoriteStore.favorites(context).any { it.kind == RadioFavoriteKind.KEYWORD }) {
            requestKeywordRefresh(context)
        }
    }

    suspend fun refreshKeywordFavorites(context: Context) {
        val favorites = RadioRecordingFavoriteStore.favorites(context).filter { it.kind == RadioFavoriteKind.KEYWORD }
        if (favorites.isEmpty() || RadioAutoRecordingStore.folderUri(context) == null ||
            !RadioAutoRecordingScheduler.canScheduleExactAlarms(context)
        ) return
        val now = System.currentTimeMillis()
        favorites.groupBy { it.stationId }.forEach { (stationId, stationFavorites) ->
            val programs = runCatching { RadioRepository.fetchWeeklyPrograms(stationId) }.getOrDefault(emptyList())
            stationFavorites.forEach { favorite ->
                val keyword = favorite.keyword.orEmpty().trim()
                if (keyword.isNotEmpty()) programs.asSequence()
                    .filter { it.title.contains(keyword, ignoreCase = true) }
                    .filter { program ->
                        runCatching {
                            LocalDateTime.parse(program.startAt, programFormatter).atZone(japan).toInstant().toEpochMilli() > now &&
                            LocalDateTime.parse(program.endAt, programFormatter).atZone(japan).toInstant().toEpochMilli() > now
                        }.getOrDefault(false)
                    }
                    .forEach { program ->
                        runCatching {
                            val recording = RadioAutoRecordingStore.fromProgram(
                                stationId = program.stationId,
                                stationName = favorite.stationName,
                                areaId = favorite.areaId,
                                title = program.title,
                                startAt = program.startAt,
                                endAt = program.endAt,
                                artworkUrl = program.imageUrl,
                            ).copy(favoriteId = favorite.id)
                            val existing = RadioAutoRecordingStore.recording(context, recording.id)
                            if (existing == null && !RadioAutoRecordingStore.recordings(context).any { other ->
                                    other.id != recording.id && recording.startTimeMs < other.endTimeMs && other.startTimeMs < recording.endTimeMs
                                }
                            ) {
                                RadioAutoRecordingScheduler.schedule(context, recording)
                            }
                        }
                    }
            }
        }
    }

    fun scheduleNextKeywordRefresh(context: Context) {
        if (RadioRecordingFavoriteStore.favorites(context).any { it.kind == RadioFavoriteKind.KEYWORD }) {
            requestKeywordRefresh(context, REFRESH_INTERVAL_MS)
        }
    }

    private fun nextTimeRecording(favorite: RadioRecordingFavorite, afterMs: Long): RadioAutoRecording? = runCatching {
        val time = LocalTime.parse(favorite.timeOfDay, timeFormatter)
        val nowDate = Instant.ofEpochMilli(afterMs).atZone(japan).toLocalDate()
        var date = if (favorite.repeat == RadioFavoriteRepeat.WEEKLY) {
            nowDate.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(favorite.weekday ?: return null)))
        } else nowDate
        var start = date.atTime(time).atZone(japan).toInstant().toEpochMilli()
        if (start <= afterMs) {
            date = if (favorite.repeat == RadioFavoriteRepeat.WEEKLY) date.plusWeeks(1) else date.plusDays(1)
            start = date.atTime(time).atZone(japan).toInstant().toEpochMilli()
        }
        val end = start + (favorite.durationMs ?: return null)
        val startAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), japan).format(programFormatter)
        val endAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), japan).format(programFormatter)
        RadioAutoRecordingStore.fromProgram(
            stationId = favorite.stationId,
            stationName = favorite.stationName,
            areaId = favorite.areaId,
            title = favorite.title,
            startAt = startAt,
            endAt = endAt,
            artworkUrl = favorite.artworkUrl,
        ).copy(favoriteId = favorite.id)
    }.getOrNull()

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REFRESH_REQUEST_CODE,
        Intent(context, RadioFavoriteRefreshReceiver::class.java).setAction(ACTION_REFRESH_KEYWORDS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
