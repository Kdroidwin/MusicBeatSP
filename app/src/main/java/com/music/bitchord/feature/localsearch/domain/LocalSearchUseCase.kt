package com.music.bitchord.feature.localsearch.domain

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.sortedByAlbumTrackOrder
import com.music.bitchord.feature.artistimage.util.ArtistSplitter
import com.music.bitchord.feature.localsearch.domain.model.LocalSearchFilter
import com.music.bitchord.feature.localsearch.domain.model.LocalSearchResult
import java.util.Locale

object LocalSearchUseCase {
    fun search(
        query: String,
        filter: LocalSearchFilter,
        allSongs: List<Song>,
        folderHierarchy: Boolean = false,
    ): List<LocalSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val q = trimmed.lowercase(Locale.ROOT)

        val results = mutableListOf<LocalSearchResult>()

        // Find matching songs
        val matchedSongs = if (filter == LocalSearchFilter.ALL || filter == LocalSearchFilter.SONGS) {
            allSongs.filter { song ->
                song.title.lowercase(Locale.ROOT).contains(q) ||
                    song.artist.lowercase(Locale.ROOT).contains(q) ||
                    song.albumName?.lowercase(Locale.ROOT)?.contains(q) == true
            }
        } else {
            emptyList()
        }

        // Find matching albums
        val matchedAlbums = if (filter == LocalSearchFilter.ALL || filter == LocalSearchFilter.ALBUMS) {
            allSongs
                .filter { !it.albumName.isNullOrBlank() }
                .groupBy { it.albumName.orEmpty() }
                .filter { (albumName, songs) ->
                    albumName.lowercase(Locale.ROOT).contains(q) ||
                        songs.any { it.artist.lowercase(Locale.ROOT).contains(q) }
                }
                .map { (albumName, songs) ->
                    LocalSearchResult.Album(
                        title = albumName,
                        artist = songs.firstOrNull()?.artist.orEmpty(),
                        thumbnailUrl = songs.firstNotNullOfOrNull { it.thumbnailUrl },
                        songs = songs.sortedByAlbumTrackOrder(),
                    )
                }
        } else {
            emptyList()
        }

        // Find matching artists
        val matchedArtists = if (filter == LocalSearchFilter.ALL || filter == LocalSearchFilter.ARTISTS) {
            ArtistSplitter.groupSongsByArtist(allSongs)
                .filter { it.name.lowercase(Locale.ROOT).contains(q) }
                .sortedWith(
                    compareByDescending<com.music.bitchord.feature.artistimage.model.ArtistEntry> {
                        it.name.lowercase(Locale.ROOT).startsWith(q)
                    }.thenByDescending { it.songs.size }
                )
                .map { entry ->
                    LocalSearchResult.Artist(
                        name = entry.name,
                        songs = entry.songs,
                        thumbnailUrl = entry.thumbnailUrl,
                    )
                }
        } else {
            emptyList()
        }

        // Find matching folders. Hierarchy results include matching ancestor
        // directories even when they contain no tracks directly; those empty
        // container directories were the reason nested folders all appeared
        // at depth zero in the previous implementation.
        val matchedFolders = if (filter == LocalSearchFilter.ALL || filter == LocalSearchFilter.FOLDERS) {
            findMatchingFolders(allSongs, q, folderHierarchy)
        } else {
            emptyList()
        }

        when (filter) {
            LocalSearchFilter.ALL -> {
                // Artists and Albums must precede individual tracks so users see the top artist/album results first
                results.addAll(matchedArtists)
                results.addAll(matchedAlbums)
                results.addAll(matchedSongs.map { LocalSearchResult.Track(it) })
                results.addAll(matchedFolders)
            }
            LocalSearchFilter.SONGS -> results.addAll(matchedSongs.map { LocalSearchResult.Track(it) })
            LocalSearchFilter.ALBUMS -> results.addAll(matchedAlbums)
            LocalSearchFilter.ARTISTS -> results.addAll(matchedArtists)
            LocalSearchFilter.FOLDERS -> results.addAll(matchedFolders)
        }

        return results
    }

    private fun findMatchingFolders(
        allSongs: List<Song>,
        query: String,
        hierarchy: Boolean,
    ): List<LocalSearchResult.Folder> {
        val songsByDirectory = allSongs.mapNotNull { song ->
            val filePath = song.localPath?.takeIf(String::isNotBlank)?.replace('\\', '/') ?: return@mapNotNull null
            val directory = normalizePath(filePath.substringBeforeLast('/', ""))
            directory.takeIf(String::isNotBlank)?.let { it to song }
        }.groupBy({ it.first }, { it.second })

        val matching = songsByDirectory.filterKeys { directory ->
            directory.substringAfterLast('/').lowercase(Locale.ROOT).contains(query) ||
                directory.lowercase(Locale.ROOT).contains(query)
        }

        if (!hierarchy) {
            return matching.map { (directory, songs) ->
                LocalSearchResult.Folder(
                    name = directory.substringAfterLast('/'),
                    path = directory,
                    songs = LocalFileNameOrdering.sort(songs),
                )
            }.sortedWith(
                compareByDescending<LocalSearchResult.Folder> { it.name.lowercase(Locale.ROOT).startsWith(query) }
                    .thenByDescending { it.songs.size }
            )
        }

        val visibleSongs = linkedMapOf<String, List<Song>>()
        matching.forEach { (path, songs) -> visibleSongs[path] = LocalFileNameOrdering.sort(songs) }
        val songsBySubtree = mutableMapOf<String, MutableList<Song>>()
        songsByDirectory.forEach { (directory, songs) ->
            var path: String? = directory
            while (path != null) {
                songsBySubtree.getOrPut(path) { mutableListOf() }.addAll(songs)
                path = parentPath(path)
            }
        }

        // Preserve meaningful context when the matching directory is a
        // container with no audio files of its own (a common album/artist tree
        // layout). Add the nearest query-matching ancestor as a visible parent;
        // the previous code only counted ancestors that themselves contained
        // matching tracks, so these branches were flattened to depth zero.
        matching.keys.forEach { matchingPath ->
            var ancestor = parentPath(matchingPath)
            while (ancestor != null) {
                if (ancestor.substringAfterLast('/').lowercase(Locale.ROOT).contains(query)) {
                    val descendantSongs = songsBySubtree[ancestor].orEmpty()
                    if (descendantSongs.isNotEmpty()) {
                        visibleSongs.putIfAbsent(ancestor, LocalFileNameOrdering.sort(descendantSongs))
                    }
                    break
                }
                ancestor = parentPath(ancestor)
            }
        }

        return visibleSongs.map { (path, songs) ->
            val depth = visibleSongs.keys.count { candidate ->
                candidate != path && isStrictDescendant(path, candidate)
            }
            LocalSearchResult.Folder(
                name = path.substringAfterLast('/'),
                path = path,
                songs = songs,
                depth = depth,
            )
        }.sortedBy { it.path.lowercase(Locale.ROOT) }
    }

    private fun normalizePath(path: String): String {
        val normalized = buildString(path.length) {
            path.replace('\\', '/').forEach { character ->
                if (character != '/' || isEmpty() || last() != '/') append(character)
            }
        }
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }

    private fun parentPath(path: String): String? {
        if (path == "/") return null
        val separator = path.lastIndexOf('/')
        return when {
            separator < 0 -> null
            separator == 0 -> "/"
            else -> path.substring(0, separator)
        }
    }

    private fun isSameOrDescendant(path: String, ancestor: String): Boolean =
        path == ancestor || if (ancestor == "/") path.startsWith('/') else path.startsWith("$ancestor/")

    private fun isStrictDescendant(path: String, ancestor: String): Boolean =
        path != ancestor && isSameOrDescendant(path, ancestor)
}
