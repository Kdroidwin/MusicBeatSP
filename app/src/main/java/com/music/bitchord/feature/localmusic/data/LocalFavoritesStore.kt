package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private const val STORE_FILE_NAME = "local_favorites_store.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = SetSerializer(String.serializer())
    private val songsSerializer = StoredFavoriteSongs.serializer()
    private val storeSerializer = StoredFavoritesSnapshot.serializer()
    private val persistLock = Any()

    private lateinit var storageFile: File
    private lateinit var songsFile: File
    private lateinit var atomicStore: AtomicFile
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()
    private val _favoriteOrder = MutableStateFlow<List<String>>(emptyList())
    /** Stable insertion/manual order used by the Favorites collection. */
    val favoriteOrder: StateFlow<List<String>> = _favoriteOrder.asStateFlow()
    private val _favoriteSongs = MutableStateFlow<Map<String, Song>>(emptyMap())
    /** Track metadata for favorites that are not in the device or remote library list. */
    val favoriteSongs: StateFlow<Map<String, Song>> = _favoriteSongs.asStateFlow()

    fun init(context: Context) {
        storageFile = File(context.filesDir, FILE_NAME)
        songsFile = File(context.filesDir, SONGS_FILE_NAME)
        atomicStore = AtomicFile(File(context.filesDir, STORE_FILE_NAME))
        load()
    }

    private fun load() {
        val snapshot = runCatching {
            atomicStore.openRead().bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString(storeSerializer, it.readText())
            }
        }.getOrNull()
        val loadedIds = snapshot?.ids ?: if (storageFile.exists()) {
            runCatching {
                json.decodeFromString(serializer, storageFile.readText())
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val loadedSongs = snapshot?.songs?.mapValues { (_, song) -> song.toSong() }
            ?: if (songsFile.exists()) {
                runCatching {
                    songsFile.readText()
                        .let { json.decodeFromString(songsSerializer, it) }
                        .songs
                        .mapValues { (_, song) -> song.toSong() }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

        // Previous releases keyed a downloaded YouTube favorite by its local
        // MediaStore URI. Android can issue a different URI after a rescan,
        // which severed both playback and delete-download lookup. Canonicalize
        // those records using the durable video ID already stored with each
        // favorite; local files and source-backed tracks retain their own IDs.
        val migratedIds = loadedIds.toMutableSet()
        val migratedSongs = linkedMapOf<String, Song>()
        val loadedOrder = snapshot?.order.orEmpty().ifEmpty { loadedIds.toList() }
        loadedSongs.forEach { (savedId, song) ->
            if (savedId !in loadedIds) return@forEach
            val canonicalId = song.videoId.takeIf { it.isYouTubeVideoId() } ?: savedId
            if (canonicalId != savedId) {
                migratedIds.remove(savedId)
                migratedIds.add(canonicalId)
            }
            migratedSongs[canonicalId] = song.forFavoriteStorage()
        }
        val migratedOrder = linkedSetOf<String>()
        loadedOrder.forEach { savedId ->
            val song = loadedSongs[savedId]
            val canonicalId = song?.videoId?.takeIf { it.isYouTubeVideoId() } ?: savedId
            if (canonicalId in migratedIds) migratedOrder += canonicalId
        }
        migratedIds.forEach { migratedOrder += it }
        _favoriteOrder.value = migratedOrder.toList()
        _favoriteIds.value = LinkedHashSet(migratedOrder)
        _favoriteSongs.value = migratedSongs
        if (snapshot == null || migratedIds != loadedIds ||
            migratedSongs != loadedSongs.filterKeys { it in loadedIds } ||
            snapshot?.order != migratedOrder.toList()
        ) {
            persist()
        }
    }

    private fun persist() {
        if (!::atomicStore.isInitialized) return
        synchronized(persistLock) {
            val snapshot = StoredFavoritesSnapshot(
                ids = _favoriteIds.value,
                songs = _favoriteSongs.value.mapValues { (_, song) -> StoredFavoriteSong.from(song) },
                order = _favoriteOrder.value,
            )
            var output: java.io.FileOutputStream? = null
            try {
                val stream = atomicStore.startWrite()
                output = stream
                stream.write(json.encodeToString(storeSerializer, snapshot).toByteArray(Charsets.UTF_8))
                atomicStore.finishWrite(stream)
                output = null
            } catch (error: Exception) {
                output?.let { atomicStore.failWrite(it) }
                android.util.Log.w("LocalFavorites", "Could not persist favorites", error)
            }
        }
    }

    fun isFavorite(songId: String): Boolean {
        return songId in _favoriteIds.value
    }

    fun toggleFavorite(songId: String, song: Song? = null): Boolean {
        val canonicalId = canonicalFavoriteId(songId, song)
        val isFav = synchronized(persistLock) {
            val current = _favoriteIds.value
            val wasFavorite = canonicalId in current
            if (wasFavorite) {
                _favoriteIds.value = current - canonicalId
                _favoriteOrder.value = _favoriteOrder.value.filterNot { it == canonicalId }
                _favoriteSongs.value = _favoriteSongs.value - canonicalId
            } else {
                _favoriteIds.value = current + canonicalId
                if (canonicalId !in _favoriteOrder.value) _favoriteOrder.value = _favoriteOrder.value + canonicalId
                song?.let { _favoriteSongs.value = _favoriteSongs.value + (canonicalId to it.forFavoriteStorage()) }
            }
            persist()
            wasFavorite
        }
        return !isFav
    }

    fun addFavorite(songId: String, song: Song? = null) {
        val canonicalId = canonicalFavoriteId(songId, song)
        synchronized(persistLock) {
            if (canonicalId !in _favoriteIds.value) {
                _favoriteIds.value = _favoriteIds.value + canonicalId
                _favoriteOrder.value = _favoriteOrder.value + canonicalId
            }
            song?.let { _favoriteSongs.value = _favoriteSongs.value + (canonicalId to it.forFavoriteStorage()) }
            persist()
        }
    }

    fun removeFavorite(songId: String) {
        synchronized(persistLock) {
            if (songId in _favoriteIds.value || songId in _favoriteSongs.value) {
                _favoriteIds.value = _favoriteIds.value - songId
                _favoriteOrder.value = _favoriteOrder.value.filterNot { it == songId }
                _favoriteSongs.value = _favoriteSongs.value - songId
                persist()
            }
        }
    }

    /** Persist a user-selected order, retaining IDs that are temporarily unresolved. */
    fun setFavoriteOrder(orderedIds: List<String>) {
        synchronized(persistLock) {
            val current = _favoriteIds.value
            val ordered = LinkedHashSet<String>()
            orderedIds.forEach { if (it in current) ordered += it }
            _favoriteOrder.value.forEach { if (it in current) ordered += it }
            current.forEach { ordered += it }
            if (_favoriteOrder.value != ordered.toList()) {
                _favoriteOrder.value = ordered.toList()
                // Set equality ignores order, so assign a fresh insertion-ordered set
                // only after the dedicated order flow has published the change.
                _favoriteIds.value = LinkedHashSet(ordered)
                persist()
            }
        }
    }

    fun exportFavorites(): Set<String> = _favoriteIds.value

    fun exportFavoriteSongs(): Map<String, Song> = _favoriteSongs.value

    fun importFavorites(incoming: Set<String>) {
        synchronized(persistLock) {
            _favoriteOrder.value = incoming.toList()
            _favoriteIds.value = LinkedHashSet(_favoriteOrder.value)
            _favoriteSongs.value = _favoriteSongs.value.filterKeys { it in incoming }
            persist()
        }
    }

    fun importFavoriteSongs(incoming: Map<String, Song>) {
        synchronized(persistLock) {
            _favoriteSongs.value = incoming
                .filterKeys { it in _favoriteIds.value }
                .mapValues { (_, song) -> song.forFavoriteStorage() }
            persist()
        }
    }

    private fun canonicalFavoriteId(songId: String, song: Song?): String =
        song?.videoId?.takeIf { it.isYouTubeVideoId() } ?: songId

    private fun Song.forFavoriteStorage(): Song {
        val isYoutubeTrack = videoId.isYouTubeVideoId()
        return copy(
        // Online stream URLs expire. Keep the durable track ID so the source
        // can resolve a fresh URL when a saved favorite is played later. For a
        // downloaded YouTube track, the saved-download index restores its
        // current local URI from the stable video ID instead of trusting an
        // old MediaStore URI captured by the favorite.
            localUri = localUri?.takeIf {
                !isYoutubeTrack && (it.startsWith("content://") || it.startsWith("file://"))
            },
            localPath = localPath?.takeIf { !isYoutubeTrack && localUri != null },
        )
    }

    private fun String.isYouTubeVideoId(): Boolean = length == 11 &&
        all { it.isLetterOrDigit() || it == '_' || it == '-' }

    @Serializable
    private data class StoredFavoriteSongs(val songs: Map<String, StoredFavoriteSong> = emptyMap())

    @Serializable
    private data class StoredFavoritesSnapshot(
        val ids: Set<String> = emptySet(),
        val songs: Map<String, StoredFavoriteSong> = emptyMap(),
        val order: List<String> = emptyList(),
    )

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
