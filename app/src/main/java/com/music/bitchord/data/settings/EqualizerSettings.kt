package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.playback.equalizer.EqualizerPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.json.JSONObject

object EqualizerSettings {
    private const val PREF_FILE = "bitchord_equalizer_settings"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET_ID = "eq_preset_id"
    private const val KEY_BASS_BOOST = "eq_bass_boost"
    private const val KEY_VIRTUALIZER = "eq_virtualizer"
    private const val KEY_BAND_PREFIX = "eq_band_"
    private const val KEY_ACTIVE_DEVICE = "eq_active_device"
    private const val PROFILE_PREFIX = "eq_device_profile_"

    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()

    private val _presetId = MutableStateFlow(0) // Default Flat (id = 0)
    val presetId = _presetId.asStateFlow()

    private val _bandLevels = MutableStateFlow<Map<Int, Short>>(emptyMap())
    val bandLevels = _bandLevels.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength = _virtualizerStrength.asStateFlow()
    private val _activeDeviceLabel = MutableStateFlow("This device")
    val activeDeviceLabel = _activeDeviceLabel.asStateFlow()
    private var activeDeviceKey = "default"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        activeDeviceKey = prefs.getString(KEY_ACTIVE_DEVICE, "default") ?: "default"
        readAll()
    }

    private fun readAll() {
        if (!this::prefs.isInitialized) return
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        val pId = prefs.getInt(KEY_PRESET_ID, 0)
        _presetId.value = pId
        _bassBoostStrength.value = prefs.getInt(KEY_BASS_BOOST, 0)
        _virtualizerStrength.value = prefs.getInt(KEY_VIRTUALIZER, 0)

        val levels = mutableMapOf<Int, Short>()
        for (i in 0 until 5) {
            val defaultLevel = EqualizerPreset.getPresetById(pId)?.bandLevels?.getOrNull(i) ?: 0
            val saved = prefs.getInt("$KEY_BAND_PREFIX$i", defaultLevel.toInt()).toShort()
            levels[i] = saved
        }
        _bandLevels.value = levels
        if (activeDeviceKey != "default") loadProfile(activeDeviceKey)
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persistCurrentProfile()
    }

    fun selectPreset(id: Int) {
        _presetId.value = id
        val preset = EqualizerPreset.getPresetById(id)
        if (preset != null) {
            val newLevels = mutableMapOf<Int, Short>()
            preset.bandLevels.forEachIndexed { index, level ->
                newLevels[index] = level
            }
            _bandLevels.value = newLevels
            persistCurrentProfile()
        } else {
            persistCurrentProfile()
        }
    }

    fun setBandLevel(band: Int, level: Short) {
        val current = _bandLevels.value.toMutableMap()
        current[band] = level
        _bandLevels.value = current
        _presetId.value = EqualizerPreset.CUSTOM_PRESET_ID

        persistCurrentProfile()
    }

    fun setBassBoostStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clamped
        persistCurrentProfile()
    }

    fun setVirtualizerStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = clamped
        persistCurrentProfile()
    }

    fun reset() {
        selectPreset(0) // Flat
        setBassBoostStrength(0)
        setVirtualizerStrength(0)
    }

    /** Persist and activate an EQ profile for the routed output device. */
    fun activateOutputProfile(deviceKey: String, label: String) {
        if (!this::prefs.isInitialized) return
        val key = deviceKey.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "default"
        _activeDeviceLabel.value = label.ifBlank { "This device" }
        if (activeDeviceKey == key) return
        persistCurrentProfile()
        activeDeviceKey = key
        prefs.edit().putString(KEY_ACTIVE_DEVICE, key).apply()
        if (!loadProfile(key)) persistCurrentProfile()
    }

    private fun loadProfile(key: String): Boolean {
        if (!this::prefs.isInitialized) return false
        val raw = prefs.getString(PROFILE_PREFIX + key, null) ?: return false
        val profile = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        _enabled.value = profile.optBoolean("enabled", false)
        _presetId.value = profile.optInt("preset", 0)
        _bassBoostStrength.value = profile.optInt("bass", 0).coerceIn(0, 1000)
        _virtualizerStrength.value = profile.optInt("virtualizer", 0).coerceIn(0, 1000)
        val bandsJson = profile.optJSONObject("bands")
        _bandLevels.value = (0 until 5).associateWith { index ->
            bandsJson?.optInt(index.toString(), 0)?.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())?.toShort() ?: 0
        }
        return true
    }

    private fun persistCurrentProfile() {
        if (!this::prefs.isInitialized) return
        val levels = _bandLevels.value
        val profileJson = JSONObject().apply {
            put("enabled", _enabled.value)
            put("preset", _presetId.value)
            put("bass", _bassBoostStrength.value)
            put("virtualizer", _virtualizerStrength.value)
            put("bands", JSONObject().apply { levels.forEach { (band, level) -> put(band.toString(), level.toInt()) } })
        }
        prefs.edit()
            // Keep the original keys as the migration source for profiles on
            // a newly attached output and for older versions of the app.
            .putBoolean(KEY_ENABLED, _enabled.value)
            .putInt(KEY_PRESET_ID, _presetId.value)
            .putInt(KEY_BASS_BOOST, _bassBoostStrength.value)
            .putInt(KEY_VIRTUALIZER, _virtualizerStrength.value)
            .also { editor -> levels.forEach { (band, level) -> editor.putInt("$KEY_BAND_PREFIX$band", level.toInt()) } }
            .putString(KEY_ACTIVE_DEVICE, activeDeviceKey)
            .putString(PROFILE_PREFIX + activeDeviceKey, profileJson.toString())
            .apply()
    }

    fun exportBackup(): EqualizerBackup = EqualizerBackup(
        enabled = _enabled.value,
        presetId = _presetId.value,
        bandLevels = _bandLevels.value,
        bassBoostStrength = _bassBoostStrength.value,
        virtualizerStrength = _virtualizerStrength.value,
        activeDeviceKey = activeDeviceKey,
        deviceProfiles = readProfilesForBackup() + (activeDeviceKey to currentProfileSnapshot()),
    )

    fun importBackup(backup: EqualizerBackup?) {
        if (backup == null) return
        if (!this::prefs.isInitialized) return

        val importedKey = backup.activeDeviceKey.takeIf(::isValidDeviceKey) ?: "default"
        val profiles = backup.deviceProfiles
            .filterKeys(::isValidDeviceKey)
            .mapValues { (_, profile) -> profile.sanitized() }

        activeDeviceKey = importedKey
        _enabled.value = backup.enabled
        _presetId.value = backup.presetId
        _bassBoostStrength.value = backup.bassBoostStrength.coerceIn(0, 1000)
        _virtualizerStrength.value = backup.virtualizerStrength.coerceIn(0, 1000)
        _bandLevels.value = backup.bandLevels
            .filterKeys { it in 0 until 5 }
            .ifEmpty {
                (0 until 5).associateWith { index ->
                    EqualizerPreset.getPresetById(backup.presetId)?.bandLevels?.getOrNull(index) ?: 0.toShort()
                }
            }

        prefs.edit().apply {
            putString(KEY_ACTIVE_DEVICE, importedKey)
            // New backups carry the complete device map. Replace it only when
            // one is present, so a v1-v3 backup (which had no profiles) does
            // not silently erase profiles on the device receiving the restore.
            if (profiles.isNotEmpty()) {
                prefs.all.keys.filter { it.startsWith(PROFILE_PREFIX) }.forEach(::remove)
                profiles.forEach { (key, profile) ->
                    putString(PROFILE_PREFIX + key, profile.toJson().toString())
                }
            }
        }.apply()
        persistCurrentProfile()
    }

    private fun readProfilesForBackup(): Map<String, EqualizerDeviceProfile> =
        prefs.all.entries
            .asSequence()
            .filter { (key, _) -> key.startsWith(PROFILE_PREFIX) }
            .mapNotNull { (storedKey, storedValue) ->
                val key = storedKey.removePrefix(PROFILE_PREFIX)
                if (!isValidDeviceKey(key)) return@mapNotNull null
                val json = (storedValue as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@mapNotNull null
                key to json.toDeviceProfile()
            }
            .toMap()

    private fun currentProfileSnapshot() = EqualizerDeviceProfile(
        enabled = _enabled.value,
        presetId = _presetId.value,
        bandLevels = _bandLevels.value,
        bassBoostStrength = _bassBoostStrength.value,
        virtualizerStrength = _virtualizerStrength.value,
    )

    private fun JSONObject.toDeviceProfile() = EqualizerDeviceProfile(
        enabled = optBoolean("enabled", false),
        presetId = optInt("preset", 0),
        bandLevels = (0 until 5).associateWith { index ->
            optJSONObject("bands")?.optInt(index.toString(), 0)
                ?.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())?.toShort() ?: 0
        },
        bassBoostStrength = optInt("bass", 0).coerceIn(0, 1000),
        virtualizerStrength = optInt("virtualizer", 0).coerceIn(0, 1000),
    )

    private fun EqualizerDeviceProfile.sanitized() = copy(
        bandLevels = bandLevels.filterKeys { it in 0 until 5 },
        bassBoostStrength = bassBoostStrength.coerceIn(0, 1000),
        virtualizerStrength = virtualizerStrength.coerceIn(0, 1000),
    )

    private fun EqualizerDeviceProfile.toJson() = JSONObject().apply {
        put("enabled", enabled)
        put("preset", presetId)
        put("bass", bassBoostStrength)
        put("virtualizer", virtualizerStrength)
        put("bands", JSONObject().apply {
            bandLevels.forEach { (band, level) ->
                if (band in 0 until 5) put(band.toString(), level.toInt())
            }
        })
    }

    private fun isValidDeviceKey(key: String): Boolean =
        key == "default" || key.matches(Regex("[A-Za-z0-9_-]{1,64}"))
}

@Serializable
data class EqualizerBackup(
    val enabled: Boolean = false,
    val presetId: Int = 0,
    val bandLevels: Map<Int, Short> = emptyMap(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val activeDeviceKey: String = "default",
    val deviceProfiles: Map<String, EqualizerDeviceProfile> = emptyMap(),
)

@Serializable
data class EqualizerDeviceProfile(
    val enabled: Boolean = false,
    val presetId: Int = 0,
    val bandLevels: Map<Int, Short> = emptyMap(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
)
