package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.bitchord.data.settings.AppSettings.MAX_CROSSFADE_DURATION_MS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Per-track overrides. They are opt-in per field, so existing global crossfade
 * and EQ behavior remains exactly the fallback for every untouched song.
 */
data class TrackPlaybackProfile(
    val crossfadeOverride: Boolean = false,
    val crossfadeDurationMs: Int = 0,
    val eqOverride: Boolean = false,
    val eqEnabled: Boolean = true,
    val eqBandLevels: List<Int> = List(5) { 0 },
)

/** Encrypted local persistence for track-specific audio settings. */
object TrackPlaybackSettings {
    private const val PREFS_NAME = "bitchord_track_playback_secure"
    private const val PROFILES_KEY = "profiles_v1"
    private const val MAX_TRACKS = 4096
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var profiles = linkedMapOf<String, TrackPlaybackProfile>()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    val secureStorageAvailable: Boolean get() = prefs != null

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return@synchronized
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
            profiles = prefs?.getString(PROFILES_KEY, null)?.let(::decodeProfiles) ?: linkedMapOf()
            _revision.value += 1L
        }
    }

    fun profileFor(trackId: String?): TrackPlaybackProfile? {
        val key = hashedKey(trackId) ?: return null
        return synchronized(lock) { profiles[key] }
    }

    fun hasEnabledTrackCrossfades(): Boolean = synchronized(lock) {
        profiles.values.any { it.crossfadeOverride && it.crossfadeDurationMs > 0 }
    }

    fun save(trackId: String, profile: TrackPlaybackProfile): Boolean {
        val storage = prefs ?: return false
        val key = hashedKey(trackId) ?: return false
        val safe = profile.copy(
            crossfadeDurationMs = AppSettings.crossfadeDurationOptionsMs.minByOrNull {
                kotlin.math.abs(it - profile.crossfadeDurationMs.coerceIn(0, MAX_CROSSFADE_DURATION_MS))
            } ?: 0,
            eqBandLevels = (0 until 5).map { index ->
                profile.eqBandLevels.getOrNull(index)?.coerceIn(-1500, 1500) ?: 0
            },
        )
        synchronized(lock) {
            profiles[key] = safe
            while (profiles.size > MAX_TRACKS) profiles.remove(profiles.keys.first())
            return runCatching {
                storage.edit().putString(PROFILES_KEY, encodeProfiles(profiles)).apply()
                _revision.value += 1L
                true
            }.getOrDefault(false)
        }
    }

    private fun hashedKey(trackId: String?): String? {
        val id = trackId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encodeProfiles(values: Map<String, TrackPlaybackProfile>): String = JSONObject().apply {
        values.forEach { (key, profile) ->
            put(key, JSONObject().apply {
                put("crossfadeOverride", profile.crossfadeOverride)
                put("crossfadeMs", profile.crossfadeDurationMs)
                put("eqOverride", profile.eqOverride)
                put("eqEnabled", profile.eqEnabled)
                put("eqBands", JSONArray(profile.eqBandLevels.take(5)))
            })
        }
    }.toString()

    private fun decodeProfiles(encoded: String): LinkedHashMap<String, TrackPlaybackProfile> = runCatching {
        val source = JSONObject(encoded)
        val decoded = linkedMapOf<String, TrackPlaybackProfile>()
        val keys = source.keys()
        while (keys.hasNext() && decoded.size < MAX_TRACKS) {
            val key = keys.next()
            if (!key.matches(Regex("[a-f0-9]{64}"))) continue
            val row = source.optJSONObject(key) ?: continue
            val bands = row.optJSONArray("eqBands")
            decoded[key] = TrackPlaybackProfile(
                crossfadeOverride = row.optBoolean("crossfadeOverride", false),
                crossfadeDurationMs = row.optInt("crossfadeMs", 0)
                    .coerceIn(0, MAX_CROSSFADE_DURATION_MS),
                eqOverride = row.optBoolean("eqOverride", false),
                eqEnabled = row.optBoolean("eqEnabled", true),
                eqBandLevels = (0 until 5).map { index ->
                    bands?.optInt(index, 0)?.coerceIn(-1500, 1500) ?: 0
                },
            )
        }
        decoded
    }.getOrDefault(linkedMapOf())
}
