package com.music.bitchord.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.music.bitchord.ui.player.PlayerLyricsAlignment
import com.music.bitchord.ui.player.PlayerSeekButtonMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Settings-only transfer. Android MusicBeat backups are accepted without importing their songs. */
internal object DesktopSettingsBackup {
    private const val ANDROID_APP_ID = "musicbeat"
    private const val DESKTOP_APP_ID = "musicbeatsp-desktop-settings"
    private const val ANDROID_SCHEMA = 3
    private const val DESKTOP_SCHEMA = 1
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Serializable
    private data class BackupFile(
        val app: String,
        val version: Int,
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, SettingValue> = emptyMap(),
    )

    @Serializable
    private data class SettingValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun asText(): String? = when (type) {
            "bool" -> value?.toBooleanStrictOrNull()?.toString()
            "int" -> value?.toIntOrNull()?.toString()
            "long" -> value?.toLongOrNull()?.toString()
            "float" -> value?.toFloatOrNull()?.takeIf(Float::isFinite)?.toString()
            "string" -> value
            "stringSet" -> values.joinToString(",")
            else -> null
        }
    }

    data class ImportResult(val imported: Int, val skipped: Int, val source: String)

    fun suggestedFile(): File = File(
        System.getProperty("user.home"),
        "musicbeatsp-settings-${LocalDate.now()}.json",
    )

    fun exportTo(file: File): Int {
        val persistence = DesktopPersistence()
        val values = linkedMapOf<String, SettingValue>()
        val keys = persistence.preferences.keys()
            .filterNot { it.matches(Regex(".*\\.\\d+")) }
            .distinct()

        keys.forEach { key ->
            if (!isExportable(key, allowLocalPath = true)) return@forEach
            val value = DesktopPreferenceChunks.read(persistence.preferences, key) ?: return@forEach
            values[key] = SettingValue("string", value)
        }
        // The Android backup format stores these shortcuts as a string set. Include the same
        // standard key so the ordering and compatible actions can be transferred across targets.
        values["player_quick_actions"] = SettingValue(
            type = "stringSet",
            values = DesktopPlayerSettings.miniPlayerControls.value.map { it.name },
        )
        val backup = BackupFile(
            app = DESKTOP_APP_ID,
            version = DESKTOP_SCHEMA,
            versionName = "2.1.0-desktop",
            exportedAt = Instant.now().toString(),
            settings = values,
        )
        val text = json.encodeToString(backup)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Settings backup is too large" }
        file.writeText(text, Charsets.UTF_8)
        return values.size
    }

    fun importFrom(file: File): ImportResult {
        require(file.isFile) { "The selected backup file does not exist" }
        require(file.length() in 1..MAX_FILE_BYTES) { "The selected file is empty or too large" }
        val backup = runCatching { json.decodeFromString<BackupFile>(file.readText(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("This is not a valid MusicBeat settings backup", it) }
        val androidBackup = backup.app == ANDROID_APP_ID
        require(androidBackup || backup.app == DESKTOP_APP_ID) { "This backup belongs to a different app" }
        require(backup.version <= if (androidBackup) ANDROID_SCHEMA else DESKTOP_SCHEMA) {
            "This backup was created by a newer version"
        }

        val persistence = DesktopPersistence()
        var imported = 0
        var skipped = 0
        var importedActions: List<DesktopMiniPlayerControl>? = null
        backup.settings.forEach { (key, stored) ->
            if (!isExportable(key, allowLocalPath = !androidBackup)) {
                skipped++
                return@forEach
            }
            if (key == "player_quick_actions" || key == "mini_player_controls") {
                val names = if (stored.type == "stringSet") stored.values else stored.value.orEmpty().split(',')
                importedActions = names.mapNotNull { name ->
                    runCatching { DesktopMiniPlayerControl.valueOf(name) }.getOrNull()
                }
                return@forEach
            }
            val value = stored.asText()
            if (value == null) {
                skipped++
                return@forEach
            }
            if (value.length > java.util.prefs.Preferences.MAX_VALUE_LENGTH) {
                skipped++
                return@forEach
            }
            DesktopPreferenceChunks.remove(persistence.preferences, key)
            if (!applyLiveSetting(key, value)) persistence.preferences.put(key, value)
            imported++
        }
        importedActions?.let {
            DesktopPlayerSettings.setMiniPlayerControls(it)
            imported++
        }
        runCatching { persistence.preferences.flush() }
        return ImportResult(imported, skipped, if (androidBackup) "Android" else "desktop")
    }

    private fun applyLiveSetting(key: String, value: String): Boolean = when (key) {
        "app_language" -> {
            DesktopStrings.setLanguage(value)
            true
        }
        "hide_volume_bar" -> value.toBooleanStrictOrNull()?.let { DesktopAppearanceSettings.setHideVolumeBar(it); true } ?: false
        "hide_lossless_label" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHideLosslessLabel(it); true } ?: false
        "hide_player_artist" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHidePlayerArtist(it); true } ?: false
        "hide_unknown_player_artist" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHideUnknownPlayerArtist(it); true } ?: false
        "center_player_track_info" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setCenterPlayerTrackInfo(it); true } ?: false
        "hide_lyrics_status_text" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHideLyricsStatusText(it); true } ?: false
        "hide_song_status" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHideSongStatus(it); true } ?: false
        "seek_button_mode" -> runCatching { PlayerSeekButtonMode.valueOf(value) }
            .getOrNull()?.let { DesktopPlayerSettings.setSeekButtonMode(it); true } ?: false
        "seek_button_seconds" -> value.toIntOrNull()?.let { DesktopPlayerSettings.setSeekButtonSeconds(it); true } ?: false
        "hide_seek_seconds_label" -> value.toBooleanStrictOrNull()?.let { DesktopPlayerSettings.setHideSeekSecondsLabel(it); true } ?: false
        "lyrics_font_scale" -> value.toFloatOrNull()?.let { DesktopPlayerSettings.setLyricsFontScale(it); true } ?: false
        "lyrics_text_alignment" -> runCatching { PlayerLyricsAlignment.valueOf(value) }
            .getOrNull()?.let { DesktopPlayerSettings.setLyricsTextAlignment(it); true } ?: false
        "theme_mode" -> runCatching { DesktopThemeMode.valueOf(value) }
            .getOrNull()?.let { DesktopPlayerSettings.setThemeMode(it); true } ?: false
        else -> false
    }

    /** Never move credentials, device-specific playback data, or Android's storage URI to desktop. */
    private fun isExportable(key: String, allowLocalPath: Boolean): Boolean {
        val name = key.lowercase()
        if (name.isBlank() || name.endsWith(".dpapi_v1")) return false
        if (name in setOf(
                "liked_ids", "disliked_ids", "history", "queue", "downloads", "playlists",
                "original_versions", "downloaded_tracks", "downloaded_tracks_metadata",
                "downloaded_collections", "last_version_code", "source_configs",
                "source_configs_dpapi_v1", "module_index_url", "local_music_folder",
            ) && !(allowLocalPath && name == "local_music_folder")
        ) return false
        if (name.startsWith("lastfm_") || name.startsWith("listenbrainz_") ||
            name.startsWith("spotify_") || name.startsWith("discord_") ||
            name.startsWith("youtube_") || name.startsWith("source_configs")
        ) return false
        if (listOf("token", "secret", "password", "cookie", "credential", "api_key", "session_key").any(name::contains)) {
            return false
        }
        return true
    }
}
