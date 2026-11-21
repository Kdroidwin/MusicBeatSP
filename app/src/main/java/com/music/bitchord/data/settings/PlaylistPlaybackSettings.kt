package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Per-playlist audio overrides; disabled fields always fall back to app settings. */
data class PlaylistPlaybackProfile(
    val crossfadeOverride: Boolean = false,
    val crossfadeDurationMs: Int = 0,
    val speedOverride: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val eqOverride: Boolean = false,
    val eqEnabled: Boolean = true,
    val eqBandCount: Int = DEFAULT_EQ_BAND_COUNT,
    /** Gain in centibels (100 units = 1 dB). */
    val eqBandGainsCentiDb: List<Int> = List(PlaylistPlaybackSettings.MAX_EQ_BANDS) { 0 },
) {
    companion object {
        const val DEFAULT_EQ_BAND_COUNT = 10
    }
}

/** Encrypted local storage for playlist-specific settings. */
object PlaylistPlaybackSettings {
    const val MAX_EQ_BANDS = 10
    val eqBandCenterFrequenciesHz = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

    private const val PREFS_NAME = "bitchord_playlist_playback_secure"
    private const val PROFILES_KEY = "profiles_v1"
    private const val MAX_PLAYLISTS = 2_048
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var profiles = linkedMapOf<String, PlaylistPlaybackProfile>()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    val secureStorageAvailable: Boolean get() = prefs != null

    fun init(context: Context) {
        synchronized(lock) {
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
            profiles = prefs?.getString(PROFILES_KEY, null)?.let(::decodeProfiles) ?: linkedMapOf()
            _revision.value += 1L
        }
    }

    fun profileFor(playlistId: String?): PlaylistPlaybackProfile? {
        val key = hashedKey(playlistId) ?: return null
        return synchronized(lock) { profiles[key] }
    }

    fun hasEnabledCrossfades(): Boolean = synchronized(lock) {
        profiles.values.any { it.crossfadeOverride && it.crossfadeDurationMs > 0 }
    }

    fun save(playlistId: String, profile: PlaylistPlaybackProfile): Boolean {
        val storage = prefs ?: return false
        val key = hashedKey(playlistId) ?: return false
        val safeDuration = profile.crossfadeDurationMs.coerceIn(0, AppSettings.MAX_CROSSFADE_DURATION_MS)
        val safe = profile.copy(
            crossfadeDurationMs = AppSettings.crossfadeDurationOptionsMs.minByOrNull { kotlin.math.abs(it - safeDuration) } ?: 0,
            playbackSpeed = profile.playbackSpeed.coerceIn(0.1f, 10f),
            eqBandCount = profile.eqBandCount.coerceIn(1, MAX_EQ_BANDS),
            eqBandGainsCentiDb = (0 until MAX_EQ_BANDS).map { index ->
                profile.eqBandGainsCentiDb.getOrNull(index)?.coerceIn(-1_500, 1_500) ?: 0
            },
        )
        synchronized(lock) {
            profiles[key] = safe
            while (profiles.size > MAX_PLAYLISTS) profiles.remove(profiles.keys.first())
            return runCatching {
                storage.edit().putString(PROFILES_KEY, encodeProfiles(profiles)).apply()
                _revision.value += 1L
                true
            }.getOrDefault(false)
        }
    }

    private fun hashedKey(playlistId: String?): String? {
        val id = playlistId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encodeProfiles(values: Map<String, PlaylistPlaybackProfile>): String = JSONObject().apply {
        values.forEach { (key, profile) ->
            put(key, JSONObject().apply {
                put("crossfadeOverride", profile.crossfadeOverride)
                put("crossfadeMs", profile.crossfadeDurationMs)
                put("speedOverride", profile.speedOverride)
                put("playbackSpeed", profile.playbackSpeed.coerceIn(0.1f, 10f).toDouble())
                put("eqOverride", profile.eqOverride)
                put("eqEnabled", profile.eqEnabled)
                put("eqBandCount", profile.eqBandCount)
                put("eqBandGains", JSONArray(profile.eqBandGainsCentiDb.take(MAX_EQ_BANDS)))
            })
        }
    }.toString()

    private fun decodeProfiles(encoded: String): LinkedHashMap<String, PlaylistPlaybackProfile> = runCatching {
        val source = JSONObject(encoded)
        val decoded = linkedMapOf<String, PlaylistPlaybackProfile>()
        val keys = source.keys()
        while (keys.hasNext() && decoded.size < MAX_PLAYLISTS) {
            val key = keys.next()
            if (!key.matches(Regex("[a-f0-9]{64}"))) continue
            val row = source.optJSONObject(key) ?: continue
            val gains = row.optJSONArray("eqBandGains")
            decoded[key] = PlaylistPlaybackProfile(
                crossfadeOverride = row.optBoolean("crossfadeOverride", false),
                crossfadeDurationMs = row.optInt("crossfadeMs", 0).coerceIn(0, AppSettings.MAX_CROSSFADE_DURATION_MS),
                speedOverride = row.optBoolean("speedOverride", false),
                playbackSpeed = row.optDouble("playbackSpeed", 1.0).toFloat().coerceIn(0.1f, 10f),
                eqOverride = row.optBoolean("eqOverride", false),
                eqEnabled = row.optBoolean("eqEnabled", true),
                eqBandCount = row.optInt("eqBandCount", PlaylistPlaybackProfile.DEFAULT_EQ_BAND_COUNT).coerceIn(1, MAX_EQ_BANDS),
                eqBandGainsCentiDb = (0 until MAX_EQ_BANDS).map { index ->
                    gains?.optInt(index, 0)?.coerceIn(-1_500, 1_500) ?: 0
                },
            )
        }
        decoded
    }.getOrDefault(linkedMapOf())
}
