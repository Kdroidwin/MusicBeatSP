package com.music.bitchord.feature.localmusic.data

import android.content.Context
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages persistence and state for user-favorited local songs.
 */
object LocalFavoritesStore {

    private const val FILE_NAME = "local_favorites.json"
    private const val SONGS_FILE_NAME = "local_favorite_songs.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = SetSerializer(String.serializer())
    private val songsSerializer = StoredFavoriteSongs.serializer()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val persistMutex = Mutex()

    private lateinit var storageFile: File
    private lateinit var songsFile: File
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()
    private val _favoriteSongs = MutableStateFlow<Map<String, Song>>(emptyMap())
    /** Track metadata for favorites that are not in the device or remote library list. */
    val favoriteSongs: StateFlow<Map<String, Song>> = _favoriteSongs.asStateFlow()

    fun init(context: Context) {
        storageFile = File(context.filesDir, FILE_NAME)
        songsFile = File(context.filesDir, SONGS_FILE_NAME)
        load()
    }

    private fun load() {
        if (!storageFile.exists()) {
            _favoriteIds.value = emptySet()
            return
        }
        _favoriteIds.value = runCatching {
            val content = storageFile.readText()
            json.decodeFromString(serializer, content)
        }.getOrDefault(emptySet())
        _favoriteSongs.value = if (songsFile.exists()) {
            runCatching {
                songsFile.readText()
                    .let { json.decodeFromString(songsSerializer, it) }
                    .songs
                    .filterKeys { it in _favoriteIds.value }
                    .mapValues { (_, song) -> song.toSong() }
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                runCatching {
                    storageFile.writeText(json.encodeToString(serializer, _favoriteIds.value))
                    songsFile.writeText(
                        json.encodeToString(
                            songsSerializer,
                            StoredFavoriteSongs(_favoriteSongs.value.mapValues { (_, song) -> StoredFavoriteSong.from(song) }),
                        ),
                    )
                }
            }
        }
    }

    fun isFavorite(songId: String): Boolean {
        return songId in _favoriteIds.value
    }

    fun toggleFavorite(songId: String, song: Song? = null): Boolean {
        val current = _favoriteIds.value
        val isFav = songId in current
        if (isFav) {
            _favoriteIds.value = current - songId
            _favoriteSongs.value = _favoriteSongs.value - songId
        } else {
            _favoriteIds.value = current + songId
            song?.let { _favoriteSongs.value = _favoriteSongs.value + (songId to it.forFavoriteStorage()) }
        }
        persist()
        return !isFav
    }

    fun addFavorite(songId: String, song: Song? = null) {
        if (songId !in _favoriteIds.value) {
            _favoriteIds.value = _favoriteIds.value + songId
        }
        song?.let { _favoriteSongs.value = _favoriteSongs.value + (songId to it.forFavoriteStorage()) }
        persist()
    }

    fun removeFavorite(songId: String) {
        if (songId in _favoriteIds.value || songId in _favoriteSongs.value) {
            _favoriteIds.value = _favoriteIds.value - songId
            _favoriteSongs.value = _favoriteSongs.value - songId
            persist()
        }
    }

    fun exportFavorites(): Set<String> = _favoriteIds.value

    fun exportFavoriteSongs(): Map<String, Song> = _favoriteSongs.value

    fun importFavorites(incoming: Set<String>) {
        _favoriteIds.value = incoming
        _favoriteSongs.value = _favoriteSongs.value.filterKeys { it in incoming }
        persist()
    }

    fun importFavoriteSongs(incoming: Map<String, Song>) {
        _favoriteSongs.value = incoming
            .filterKeys { it in _favoriteIds.value }
            .mapValues { (_, song) -> song.forFavoriteStorage() }
        persist()
    }

    private fun Song.forFavoriteStorage(): Song = copy(
        // Online stream URLs expire. Keep the durable track ID so the source
        // can resolve a fresh URL when a saved favorite is played later.
        localUri = localUri?.takeIf { it.startsWith("content://") || it.startsWith("file://") },
        localPath = localPath?.takeIf { localUri != null },
    )

    @Serializable
    private data class StoredFavoriteSongs(val songs: Map<String, StoredFavoriteSong> = emptyMap())

    @Serializable
    private data class StoredFavoriteSong(
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
            fun from(song: Song) = StoredFavoriteSong(
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
}
