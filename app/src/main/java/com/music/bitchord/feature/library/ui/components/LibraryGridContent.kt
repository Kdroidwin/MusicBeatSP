package com.music.bitchord.feature.library.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LibraryCategory

/**
 * Grid layout presentation of the Library featuring Favorites and Playlists.
 */
@Composable
fun LibraryGridContent(
    filter: LibraryFilter,
    favoriteSongCount: Int,
    playlists: List<LocalPlaylist>,
    onFavoritesClick: () -> Unit,
    onFavoritesPlay: () -> Unit,
    onPlaylistClick: (LocalPlaylist) -> Unit,
    onPlaylistPlay: (LocalPlaylist) -> Unit,
    onRenamePlaylist: (LocalPlaylist) -> Unit,
    onDeletePlaylist: (LocalPlaylist) -> Unit,
    onChangePlaylistCover: (LocalPlaylist) -> Unit = {},
    onResetPlaylistCover: (LocalPlaylist) -> Unit = {},
    onMovePlaylist: (LocalPlaylist, Int) -> Unit,
    onCreatePlaylist: () -> Unit,
    librarySongs: List<Song> = emptyList(),
    favoriteUsesStar: Boolean = false,
    visibleCategories: Set<LibraryCategory> = LibraryCategory.entries.toSet(),
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val showFavorites = LibraryCategory.FAVORITES in visibleCategories &&
            (filter == LibraryFilter.ALL || filter == LibraryFilter.FAVORITES)
        val showPlaylists = LibraryCategory.PLAYLISTS in visibleCategories &&
            (filter == LibraryFilter.ALL || filter == LibraryFilter.PLAYLISTS)

        if (showFavorites) {
            item(key = "library_favorites") {
                LibraryFavoritesCard(
                    songCount = favoriteSongCount,
                    onClick = onFavoritesClick,
                    onPlay = onFavoritesPlay,
                    useStarIcon = favoriteUsesStar,
                )
            }
        }

        if (showPlaylists) {
            items(playlists, key = { it.id }) { playlist ->
                val index = playlists.indexOfFirst { it.id == playlist.id }
                LibraryPlaylistCard(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist) },
                    onPlay = { onPlaylistPlay(playlist) },
                    onRename = { onRenamePlaylist(playlist) },
                    onDelete = { onDeletePlaylist(playlist) },
                    onChangeCover = { onChangePlaylistCover(playlist) },
                    onResetCover = { onResetPlaylistCover(playlist) },
                    canMoveUp = index > 0,
                    canMoveDown = index < playlists.lastIndex,
                    onMoveUp = { onMovePlaylist(playlist, -1) },
                    onMoveDown = { onMovePlaylist(playlist, 1) },
                    librarySongs = librarySongs,
                )
            }

            item(key = "library_create_playlist") {
                LibraryCreatePlaylistCard(
                    onClick = onCreatePlaylist,
                )
            }
        }
    }
}
