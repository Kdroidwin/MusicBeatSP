package com.music.bitchord.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

data class ScheduledPlaylistSpec(
    val enabled: Boolean = false,
    val playlistId: String? = null,
    val hour: Int = 8,
    val minute: Int = 0,
)

/** Daily playlist alarm with an encrypted playlist identifier and explicit app-only receiver. */
object ScheduledPlaylistScheduler {
    private const val PREFS_NAME = "bitchord_scheduled_playlist_secure"
    private const val ACTION_PLAY = "com.music.bitchord.action.PLAY_SCHEDULED_PLAYLIST"
    private const val EXTRA_PLAYLIST_ID = "playlist_id"
    private const val REQUEST_SECRET_KEY = "service_request_secret"
    private const val REQUEST_CODE = 6418
    private var prefs: SharedPreferences? = null
    private val _spec = MutableStateFlow(ScheduledPlaylistSpec())
    val spec = _spec.asStateFlow()

    val secureStorageAvailable: Boolean get() = prefs != null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = runCatching {
            val app = context.applicationContext
            EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
        _spec.value = readSpec()
    }

    @Synchronized
    fun save(context: Context, requested: ScheduledPlaylistSpec): Boolean {
        val storage = prefs ?: return false
        val id = requested.playlistId?.takeIf(::isUuid)
        val safe = requested.copy(
            enabled = requested.enabled && id != null,
            playlistId = id,
            hour = requested.hour.coerceIn(0, 23),
            minute = requested.minute.coerceIn(0, 59),
        )
        return runCatching {
            storage.edit()
                .putBoolean("enabled", safe.enabled)
                .putString("playlist_id", safe.playlistId)
                .putInt("hour", safe.hour)
                .putInt("minute", safe.minute)
                .apply()
            _spec.value = safe
            reconcile(context)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun reconcile(context: Context) {
        val safe = readSpec()
        _spec.value = safe
        if (safe.enabled && safe.playlistId != null && isUuid(safe.playlistId)) schedule(context, safe)
        else cancel(context)
    }

    fun isCurrentSchedule(playlistId: String?): Boolean {
        val current = _spec.value
        return current.enabled && playlistId != null && current.playlistId == playlistId && isUuid(playlistId)
    }

    internal fun handleAlarm(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PLAY) return
        val id = intent.getStringExtra(EXTRA_PLAYLIST_ID)?.takeIf(::isUuid) ?: return
        if (!isCurrentSchedule(id)) return
        runCatching {
            val issuedAt = System.currentTimeMillis()
            val token = signServiceRequest(id, issuedAt) ?: return
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY_SCHEDULED_PLAYLIST)
                    .putExtra(EXTRA_SCHEDULED_PLAYLIST_ID, id)
                    .putExtra(EXTRA_SCHEDULED_PLAYLIST_TIME, issuedAt)
                    .putExtra(EXTRA_SCHEDULED_PLAYLIST_TOKEN, token),
            )
        }
        // One-shot exact alarms are recreated after every firing to preserve
        // the selected local wall-clock time through DST changes.
        reconcile(context)
    }

    private fun schedule(context: Context, safe: ScheduledPlaylistSpec) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, safe.playlistId ?: return)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(safe.hour, safe.minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }.onFailure {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    private fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context, _spec.value.playlistId ?: "scheduled"))
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduledPlaylistAlarmReceiver::class.java)
            .setAction(ACTION_PLAY)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PLAYLIST_ID, id)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun readSpec(): ScheduledPlaylistSpec {
        val values = prefs ?: return ScheduledPlaylistSpec()
        val id = values.getString("playlist_id", null)?.takeIf(::isUuid)
        return ScheduledPlaylistSpec(
            enabled = values.getBoolean("enabled", false) && id != null,
            playlistId = id,
            hour = values.getInt("hour", 8).coerceIn(0, 23),
            minute = values.getInt("minute", 0).coerceIn(0, 59),
        )
    }

    @Synchronized
    private fun signServiceRequest(id: String, issuedAt: Long): String? {
        val storage = prefs ?: return null
        val secret = serviceRequestSecret(storage) ?: return null
        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal("$id|$issuedAt".toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun validateServiceRequest(id: String?, issuedAt: Long, token: String?): Boolean {
        val safeId = id?.takeIf(::isUuid) ?: return false
        val safeToken = token?.takeIf { it.length in 32..64 } ?: return false
        val now = System.currentTimeMillis()
        if (issuedAt <= 0L || kotlin.math.abs(now - issuedAt) > REQUEST_MAX_AGE_MS) return false
        if (!isCurrentSchedule(safeId)) return false
        val expected = signServiceRequest(safeId, issuedAt) ?: return false
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            safeToken.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun serviceRequestSecret(storage: SharedPreferences): ByteArray? = runCatching {
        val existing = storage.getString(REQUEST_SECRET_KEY, null)
        if (!existing.isNullOrBlank()) return@runCatching Base64.getDecoder().decode(existing)
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        storage.edit().putString(REQUEST_SECRET_KEY, Base64.getEncoder().encodeToString(generated)).commit()
        generated
    }.getOrNull()

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

    private const val REQUEST_MAX_AGE_MS = 2 * 60 * 1000L
}

class ScheduledPlaylistAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ScheduledPlaylistScheduler.handleAlarm(context.applicationContext, intent)
    }
}
