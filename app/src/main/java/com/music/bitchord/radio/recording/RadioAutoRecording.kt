package com.music.bitchord.radio.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A user-approved recording kept across process death and device restarts. */
data class RadioAutoRecording(
    val id: String,
    val stationId: String,
    val stationName: String,
    val areaId: String,
    val title: String,
    val startAt: String,
    val endAt: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val artworkUrl: String? = null,
)

object RadioAutoRecordingStore {
    private const val PREFS = "radio_auto_recordings"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_RECORDINGS = "recordings"
    private const val KEY_ACTIVE = "active_ids"
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val japan = ZoneId.of("Asia/Tokyo")

    fun programId(stationId: String, startAt: String): String = "$stationId-$startAt"

    fun fromProgram(
        stationId: String,
        stationName: String,
        areaId: String,
        title: String,
        startAt: String,
        endAt: String,
        artworkUrl: String? = null,
    ): RadioAutoRecording {
        fun toMillis(value: String) = LocalDateTime.parse(value, formatter).atZone(japan).toInstant().toEpochMilli()
        return RadioAutoRecording(
            id = programId(stationId, startAt),
            stationId = stationId,
            stationName = stationName,
            areaId = areaId,
            title = title,
            startAt = startAt,
            endAt = endAt,
            startTimeMs = toMillis(startAt),
            endTimeMs = toMillis(endAt),
            artworkUrl = artworkUrl,
        )
    }

    fun folderUri(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun setFolderUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_FOLDER, uri.toString()).apply()
    }

    fun recordings(context: Context): List<RadioAutoRecording> {
        val json = prefs(context).getString(KEY_RECORDINGS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val row = array.getJSONObject(index)
                RadioAutoRecording(
                    id = row.getString("id"),
                    stationId = row.getString("stationId"),
                    stationName = row.getString("stationName"),
                    areaId = row.getString("areaId"),
                    title = row.getString("title"),
                    startAt = row.getString("startAt"),
                    endAt = row.getString("endAt"),
                    startTimeMs = row.getLong("startTimeMs"),
                    endTimeMs = row.getLong("endTimeMs"),
                    artworkUrl = row.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun recording(context: Context, id: String): RadioAutoRecording? = recordings(context).firstOrNull { it.id == id }

    fun save(context: Context, recording: RadioAutoRecording) {
        val rows = recordings(context).filterNot { it.id == recording.id } + recording
        persistRecordings(context, rows)
    }

    fun remove(context: Context, id: String) {
        persistRecordings(context, recordings(context).filterNot { it.id == id })
        setActive(context, id, false)
    }

    fun activeIds(context: Context): Set<String> = prefs(context).getStringSet(KEY_ACTIVE, emptySet()).orEmpty()

    fun setActive(context: Context, id: String, active: Boolean) {
        val updated = activeIds(context).toMutableSet().apply {
            if (active) add(id) else remove(id)
        }
        prefs(context).edit().putStringSet(KEY_ACTIVE, updated).apply()
    }

    fun clearActive(context: Context) {
        prefs(context).edit().putStringSet(KEY_ACTIVE, emptySet()).apply()
    }

    private fun persistRecordings(context: Context, recordings: List<RadioAutoRecording>) {
        val array = JSONArray()
        recordings.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("stationId", item.stationId)
                    .put("stationName", item.stationName)
                    .put("areaId", item.areaId)
                    .put("title", item.title)
                    .put("startAt", item.startAt)
                    .put("endAt", item.endAt)
                    .put("startTimeMs", item.startTimeMs)
                    .put("endTimeMs", item.endTimeMs)
                    .put("artworkUrl", item.artworkUrl),
            )
        }
        prefs(context).edit().putString(KEY_RECORDINGS, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object RadioAutoRecordingScheduler {
    const val ACTION_RECORD = "com.music.bitchord.radio.RECORD_PROGRAM"
    const val ACTION_BOOT = "android.intent.action.BOOT_COMPLETED"
    const val EXTRA_RECORDING_ID = "radio_recording_id"

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, recording: RadioAutoRecording) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = alarmIntent(context, recording.id)
        RadioAutoRecordingStore.save(context, recording)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, recording.startTimeMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, recording.startTimeMs, pendingIntent)
            }
        } catch (failure: Throwable) {
            RadioAutoRecordingStore.remove(context, recording.id)
            throw failure
        }
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
        RadioAutoRecordingStore.remove(context, id)
    }

    fun rescheduleAfterBoot(context: Context) {
        if (!canScheduleExactAlarms(context)) return
        val now = System.currentTimeMillis()
        RadioAutoRecordingStore.clearActive(context)
        RadioAutoRecordingStore.recordings(context)
            .filter { it.endTimeMs > now }
            .forEach { recording ->
                runCatching {
                    context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        recording.startTimeMs,
                        alarmIntent(context, recording.id),
                    )
                }
            }
    }

    private fun alarmIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, RadioRecordingAlarmReceiver::class.java)
            .setAction(ACTION_RECORD)
            .putExtra(EXTRA_RECORDING_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
