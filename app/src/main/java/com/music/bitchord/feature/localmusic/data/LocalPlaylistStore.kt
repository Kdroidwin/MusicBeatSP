package com.music.bitchord.feature.localmusic.data

import android.content.Context
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylistSongMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages persistence and state for user-created offline local music playlists.
 */
object LocalPlaylistStore {

    private const val FILE_NAME = "local_playlists.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(LocalPlaylist.serializer())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val persistMutex = Mutex()

    private lateinit var storageFile: File
    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        storageFile = File(context.filesDir, FILE_NAME)
        load()
    }

    private fun load() {
        if (!storageFile.exists()) {
            _playlists.value = emptyList()
            return
        }
        _playlists.value = runCatching {
            val content = storageFile.readText()
            json.decodeFromString(serializer, content)
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                runCatching {
                    val encoded = json.encodeToString(serializer, _playlists.value)
                    storageFile.writeText(encoded)
                }
            }
        }
    }

    fun createPlaylist(
        name: String,
        songIds: List<String> = emptyList(),
        songMetadata: Map<String, LocalPlaylistSongMetadata> = emptyMap(),
        coverUrl: String? = null,
    ): LocalPlaylist {
        val trimmed = name.trim().ifBlank { "New Playlist" }
        val newPlaylist = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAt = System.currentTimeMillis(),
            songIds = songIds.filter(String::isNotBlank).distinct(),
            coverUrl = coverUrl,
            songMetadata = songMetadata.filterKeys { it in songIds },
        )
        _playlists.value = _playlists.value + newPlaylist
        persist()
        return newPlaylist
    }

    fun renamePlaylist(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == id) pl.copy(name = trimmed) else pl
        }
        persist()
    }

    /** Stores a user-selected cover separately so clearing it restores automatic track art. */
    fun setPlaylistCover(id: String, coverUrl: String?) {
        val normalized = coverUrl?.trim()?.takeIf(String::isNotEmpty)
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id || playlist.customCoverUrl == normalized) return@map playlist
            changed = true
            playlist.copy(customCoverUrl = normalized)
        }
        if (changed) persist()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }

    /** Moves a playlist in the collection order without touching its track order. */
    fun movePlaylist(id: String, offset: Int) {
        val current = _playlists.value
        val reordered = PlaylistOrdering.move(current, id, offset)
        if (reordered === current || reordered == current) return
        _playlists.value = reordered
        persist()
    }

    /** Persists a playlist's track order without changing any track metadata. */
    fun setSongOrder(playlistId: String, orderedSongIds: List<String>) {
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            val reordered = PlaylistOrdering.setSongOrder(playlist, orderedSongIds)
            if (reordered != playlist) changed = true
            reordered
        }
        if (changed) persist()
    }

    fun addSongToPlaylist(playlistId: String, songId: String, artworkUrl: String? = null) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                val updatedIds = if (songId in pl.songIds) pl.songIds else pl.songIds + songId
                val updatedCover = pl.coverUrl ?: artworkUrl
                pl.copy(songIds = updatedIds, coverUrl = updatedCover)
            } else {
                pl
            }
        }
        persist()
    }

    /** Adds a selected batch in one state update and persistence write. */
    fun addSongsToPlaylist(
        playlistId: String,
        songIds: List<String>,
        songMetadata: Map<String, LocalPlaylistSongMetadata> = emptyMap(),
        artworkUrl: String? = null,
    ): Int {
        val additions = songIds.filter(String::isNotBlank).distinct()
        var addedCount = 0
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            val existing = playlist.songIds.toHashSet()
            val newIds = additions.filterNot(existing::contains)
            addedCount = newIds.size
            playlist.copy(
                songIds = playlist.songIds + newIds,
                coverUrl = playlist.coverUrl ?: artworkUrl,
                songMetadata = playlist.songMetadata + songMetadata.filterKeys { it in newIds },
            )
        }
        if (addedCount > 0) persist()
        return addedCount
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(songIds = pl.songIds - songId)
            } else {
                pl
            }
        }
        persist()
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun exportPlaylists(): List<LocalPlaylist> = _playlists.value

    fun importPlaylists(incoming: List<LocalPlaylist>) {
        _playlists.value = incoming
        persist()
    }
}
