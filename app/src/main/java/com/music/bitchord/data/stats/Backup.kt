package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerBackup
import com.music.bitchord.data.settings.EqualizerSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.feature.localmusic.data.LocalFavoritesStore
import com.music.bitchord.feature.artistimage.data.ArtistArtworkStore
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.feature.localsongactions.data.LocalPlayStatsStore
import com.music.bitchord.feature.localsongactions.domain.model.LocalPlayStats
import com.music.bitchord.playback.SavedQueue
import com.music.bitchord.playback.SavedQueueStore
import com.music.bitchord.radio.recording.RadioFavoriteKind
import com.music.bitchord.radio.recording.RadioFavoriteRepeat
import com.music.bitchord.radio.recording.RadioRecordingFavorite
import com.music.bitchord.radio.recording.RadioRecordingFavoriteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manages backup and restore of offline player data for MusicBeat:
 * - Local user playlists ([LocalPlaylistStore])
 * - Local song favorites ([LocalFavoritesStore])
 * - Local song play & skip stats ([LocalPlayStatsStore])
 * - Equalizer & audio effect configurations ([EqualizerSettings])
 * - Offline preferences & blacklisted folders ([AppSettings])
 * - Local listening statistics & aggregates ([ListeningStats])
 * - Search history ([SearchHistory])
 */
object Backup {

    private const val APP_TAG = "musicbeat"
    private const val SCHEMA_VERSION = 7
    private const val MAX_IMPORT_BYTES = 128 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Suggested filename for export with today's date. */
    fun suggestedName(): String =
        "musicbeat-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes all offline player data to [target] document picked by user.
     */
    suspend fun exportTo(context: Context, target: Uri): Result<ExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val buckets = ListeningStats.exportAll()
            val playlists = LocalPlaylistStore.exportPlaylists()
            val favorites = LocalFavoritesStore.exportFavorites()
            val favoriteSongs = LocalFavoritesStore.exportFavoriteSongs().map { (id, song) ->
                BackupFavoriteSong.from(id, song)
            }
            val playStats = LocalPlayStatsStore.exportStats(context)
            val equalizer = EqualizerSettings.exportBackup()
            val settingsMap = AppSettings.exportPrefs().mapNotNull { (key, value) ->
                PrefValue.of(value)?.let { key to it }
            }.toMap()
            val savedQueues = SavedQueueStore.exportQueues().map { it.toBackupQueue() }
            val artistArtwork = ArtistArtworkStore.exportBackup(context)
            val radioFavorites = RadioRecordingFavoriteStore.favorites(context).map { BackupRadioFavorite.from(it) }
            require(artistArtwork.sumOf { it.bytes.size.toLong() } <= 90L * 1024 * 1024) {
                "Artist images are too large to include in one backup"
            }

            val file = BackupFile(
                app = APP_TAG,
                version = SCHEMA_VERSION,
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = settingsMap,
                playlists = playlists,
                favorites = favorites,
                favoriteSongs = favoriteSongs,
                playStats = playStats,
                equalizer = equalizer,
                listening = buckets,
                savedQueues = savedQueues,
                artistArtwork = artistArtwork.map { item ->
                    ArtistArtworkBackup(
                        artistName = item.artistName,
                        mimeType = item.mimeType,
                        imageBase64 = Base64.encodeToString(item.bytes, Base64.NO_WRAP),
                    )
                },
                radioRecordingFavorites = radioFavorites,
            )
            val text = json.encodeToString(BackupFile.serializer(), file)
            require(text.toByteArray().size <= MAX_IMPORT_BYTES) { "Backup is too large to import" }
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(text.toByteArray()) }
                ?: error("Couldn't open that file for writing")

            ExportSummary(
                playlists = playlists.size,
                favorites = favorites.size,
                settings = settingsMap.size,
                months = buckets.size,
                hasEqualizer = equalizer.enabled,
                playStats = playStats.size,
                queues = savedQueues.size,
                radioFavorites = radioFavorites.size,
            )
        }
    }

    /**
     * Reads [source] and restores offline player data (playlists, favorites, stats, equalizer, settings, history).
     */
    suspend fun importFrom(context: Context, source: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use(::readBoundedBackup)
                ?: error("Couldn't open that file")
            val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
                .getOrElse { error("That doesn't look like a valid MusicBeat backup") }

            require(file.app == APP_TAG) { "That backup is from another app: ${file.app}" }
            require(file.version <= SCHEMA_VERSION) {
                "That backup was written by a newer version of MusicBeat"
            }

            LocalPlaylistStore.importPlaylists(file.playlists)
            if (file.version >= 2 || file.favorites.isNotEmpty()) {
                LocalFavoritesStore.importFavorites(file.favorites)
            }
            if (file.version >= 6 || file.favoriteSongs.isNotEmpty()) {
                LocalFavoritesStore.importFavoriteSongs(
                    file.favoriteSongs.associate { it.favoriteId to it.toSong() },
                )
            }
            if (file.version >= 2 || file.playStats.isNotEmpty()) {
                LocalPlayStatsStore.importStats(context, file.playStats)
            }
            EqualizerSettings.importBackup(file.equalizer)
            AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
            ListeningStats.importAll(file.listening)
            SearchHistory.reload()
            check(SavedQueueStore.importQueues(file.savedQueues.map { it.toSavedQueue() })) {
                "Could not restore saved queues"
            }
            if (file.version >= 5) {
                val artwork = file.artistArtwork.map { item ->
                    val bytes = runCatching { Base64.decode(item.imageBase64, Base64.DEFAULT) }
                        .getOrElse { error("That backup contains an invalid artist image") }
                    ArtistArtworkStore.BackupArtwork(item.artistName, item.mimeType, bytes)
                }
                ArtistArtworkStore.importBackup(context, artwork)
            }
            if (file.version >= 7 || file.radioRecordingFavorites.isNotEmpty()) {
                RadioRecordingFavoriteStore.restore(
                    context,
                    file.radioRecordingFavorites.map(BackupRadioFavorite::toFavorite),
                )
            }

            Summary(
                playlists = file.playlists.size,
                favorites = file.favorites.size,
                settings = file.settings.size,
                months = file.listening.size,
                hasEqualizer = file.equalizer != null,
                playStats = file.playStats.size,
                from = file.versionName,
                at = file.exportedAt,
                queues = file.savedQueues.size,
                radioFavorites = file.radioRecordingFavorites.size,
            )
        }
    }

    /** A user-picked document is untrusted input; cap it before allocating the JSON string. */
    private fun readBoundedBackup(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_IMPORT_BYTES) { "Backup file is too large" }
            bytes.write(buffer, 0, count)
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    data class ExportSummary(
        val playlists: Int,
        val favorites: Int,
        val settings: Int,
        val months: Int,
        val hasEqualizer: Boolean,
        val playStats: Int = 0,
        val queues: Int = 0,
        val radioFavorites: Int = 0,
    )

    data class Summary(
        val playlists: Int,
        val favorites: Int,
        val settings: Int,
        val months: Int,
        val hasEqualizer: Boolean,
        val playStats: Int = 0,
        val from: String,
        val at: String,
        val queues: Int = 0,
        val radioFavorites: Int = 0,
    )

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val playlists: List<LocalPlaylist> = emptyList(),
        val favorites: Set<String> = emptySet(),
        val favoriteSongs: List<BackupFavoriteSong> = emptyList(),
        val playStats: Map<String, LocalPlayStats> = emptyMap(),
        val equalizer: EqualizerBackup? = null,
        val listening: List<StoredBucket> = emptyList(),
        val savedQueues: List<BackupQueue> = emptyList(),
        val artistArtwork: List<ArtistArtworkBackup> = emptyList(),
        val radioRecordingFavorites: List<BackupRadioFavorite> = emptyList(),
    )

    @Serializable
    data class BackupRadioFavorite(
        val id: String,
        val kind: String,
        val stationId: String,
        val stationName: String,
        val areaId: String,
        val title: String,
        val timeOfDay: String? = null,
        val weekday: Int? = null,
        val durationMs: Long? = null,
        val repeat: String? = null,
        val keyword: String? = null,
        val artworkUrl: String? = null,
    ) {
        fun toFavorite() = RadioRecordingFavorite(
            id = id,
            kind = RadioFavoriteKind.valueOf(kind),
            stationId = stationId,
            stationName = stationName,
            areaId = areaId,
            title = title,
            timeOfDay = timeOfDay,
            weekday = weekday,
            durationMs = durationMs,
            repeat = repeat?.let(RadioFavoriteRepeat::valueOf),
            keyword = keyword,
            artworkUrl = artworkUrl,
        )

        companion object {
            fun from(favorite: RadioRecordingFavorite) = BackupRadioFavorite(
                id = favorite.id,
                kind = favorite.kind.name,
                stationId = favorite.stationId,
                stationName = favorite.stationName,
                areaId = favorite.areaId,
                title = favorite.title,
                timeOfDay = favorite.timeOfDay,
                weekday = favorite.weekday,
                durationMs = favorite.durationMs,
                repeat = favorite.repeat?.name,
                keyword = favorite.keyword,
                artworkUrl = favorite.artworkUrl,
            )
        }
    }

    @Serializable
    data class ArtistArtworkBackup(
        val artistName: String,
        val mimeType: String,
        val imageBase64: String,
    )

    @Serializable
    data class BackupFavoriteSong(
        val favoriteId: String,
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
        val localUri: String? = null,
        val downloadFormat: String? = null,
        val localPath: String? = null,
        val albumArtist: String? = null,
        val sourceQuality: String? = null,
        val isExplicit: Boolean? = null,
    ) {
        fun toSong() = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin,
            localUri = localUri,
            downloadFormat = downloadFormat,
            localPath = localPath,
            albumArtist = albumArtist,
            sourceQuality = sourceQuality,
            isExplicit = isExplicit,
        )

        companion object {
            fun from(favoriteId: String, song: Song) = BackupFavoriteSong(
                favoriteId = favoriteId,
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                albumId = song.albumId,
                albumName = song.albumName,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                localUri = song.localUri,
                downloadFormat = song.downloadFormat,
                localPath = song.localPath,
                albumArtist = song.albumArtist,
                sourceQuality = song.sourceQuality,
                isExplicit = song.isExplicit,
            )
        }
    }

    @Serializable
    data class BackupQueue(
        val id: String,
        val name: String,
        val songs: List<BackupQueueSong>,
        val currentIndex: Int,
        val positionMs: Long,
    ) {
        fun toSavedQueue() = SavedQueue(
            id = id,
            name = name,
            songs = songs.map { it.toSong() },
            currentIndex = currentIndex,
            positionMs = positionMs,
        )
    }

    @Serializable
    data class BackupQueueSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
        val setVideoId: String? = null,
        val fromAutoplay: Boolean = false,
        val radioName: String? = null,
        val localUri: String? = null,
        val localPath: String? = null,
        val albumArtist: String? = null,
        val trackNumber: Int? = null,
        val localStartMs: Long? = null,
        val localEndMs: Long? = null,
        val downloadFormat: String? = null,
        val isExplicit: Boolean? = null,
    ) {
        fun toSong() = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin,
            setVideoId = setVideoId,
            fromAutoplay = fromAutoplay,
            radioName = radioName,
            localUri = localUri,
            localPath = localPath,
            albumArtist = albumArtist,
            trackNumber = trackNumber?.takeIf { it > 0 },
            localStartMs = localStartMs?.coerceAtLeast(0L),
            localEndMs = localEndMs?.coerceAtLeast(0L),
            downloadFormat = downloadFormat,
            isExplicit = isExplicit,
        )
    }

    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            private const val BOOLEAN = "bool"
            private const val INT = "int"
            private const val LONG = "long"
            private const val FLOAT = "float"
            private const val STRING = "string"
            private const val STRING_SET = "stringSet"
        }
    }

    private fun SavedQueue.toBackupQueue() = BackupQueue(
        id = id,
        name = name,
        songs = songs.map { song ->
            BackupQueueSong(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                albumId = song.albumId,
                albumName = song.albumName,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                setVideoId = song.setVideoId,
                fromAutoplay = song.fromAutoplay,
                radioName = song.radioName,
                localUri = song.localUri,
                localPath = song.localPath,
                albumArtist = song.albumArtist,
                trackNumber = song.trackNumber,
                localStartMs = song.localStartMs,
                localEndMs = song.localEndMs,
                downloadFormat = song.downloadFormat,
                isExplicit = song.isExplicit,
            )
        },
        currentIndex = currentIndex,
        positionMs = positionMs,
    )
}
