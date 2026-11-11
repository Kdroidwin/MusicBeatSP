package com.music.bitchord.feature.artistimage.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-artist source overrides for artist pages. */
object ArtistPageModeStore {
    private const val PREFS = "artist_page_mode_overrides"
    private const val KEY_ENTRIES = "local_only_artists"

    data class BackupEntry(
        val artistName: String,
        val artistKey: String,
    )

    private val lock = Any()
    private var entries: Map<String, String> = emptyMap()
    private val _localOnlyArtistKeys = MutableStateFlow<Set<String>>(emptySet())
    val localOnlyArtistKeys: StateFlow<Set<String>> = _localOnlyArtistKeys.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        entries = readEntries(context.applicationContext)
        _localOnlyArtistKeys.value = entries.keys
    }

    fun identityKeyFor(artistName: String, artistId: String? = null): String {
        val remoteId = artistId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("local:") }
        return remoteId?.let { "browse:$it" } ?: ArtistArtworkStore.keyFor(artistName)
    }

    fun isLocalOnly(
        artistName: String,
        artistId: String? = null,
        keys: Set<String> = _localOnlyArtistKeys.value,
    ): Boolean = identityKeyFor(artistName, artistId) in keys

    fun setLocalOnly(context: Context, artistName: String, artistId: String?, enabled: Boolean): Boolean =
        synchronized(lock) {
            val name = artistName.trim().replace(Regex("\\s+"), " ")
            val key = identityKeyFor(name, artistId)
            if (name.isBlank() || key.isBlank() || key.length > 500) return false
            val updated = if (enabled) entries + (key to name) else entries - key
            if (updated == entries) return true
            if (!persist(context.applicationContext, updated)) return false
            entries = updated
            _localOnlyArtistKeys.value = updated.keys
            true
        }

    fun exportBackup(): List<BackupEntry> = synchronized(lock) {
        entries.map { (key, name) -> BackupEntry(name, key) }
    }

    fun importBackup(context: Context, items: List<BackupEntry>) = synchronized(lock) {
        require(items.size <= 2000) { "Too many artist page settings in this backup" }
        val validated = LinkedHashMap<String, String>()
        items.forEach { item ->
            val name = item.artistName.trim().replace(Regex("\\s+"), " ")
            val key = item.artistKey.trim()
            require(name.isNotBlank() && name.length <= 300) { "Invalid artist name in backup" }
            require(key.length in 1..500 && !key.contains('/') && !key.contains('\\')) {
                "Invalid artist identity in backup"
            }
            validated[key] = name
        }
        val app = context.applicationContext
        check(persist(app, validated)) { "Could not save artist page settings" }
        entries = validated
        _localOnlyArtistKeys.value = validated.keys
    }

    private fun readEntries(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyMap()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (index in 0 until array.length().coerceAtMost(2000)) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("artistName").trim().replace(Regex("\\s+"), " ")
                val key = item.optString("artistKey").trim()
                if (name.isNotBlank() && name.length <= 300 && key.isNotBlank() && key.length <= 500) {
                    put(key, name)
                }
            }
        }
    }

    private fun persist(context: Context, values: Map<String, String>): Boolean {
        val array = JSONArray()
        values.forEach { (key, name) ->
            array.put(JSONObject().put("artistKey", key).put("artistName", name))
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).commit()
    }
}
