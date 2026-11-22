package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import com.music.bitchord.data.model.Song

/** Keeps the large track-action overlay outside the root navigation method. */
@Composable
fun PlayerSongActionsHost(
    song: Song?,
    content: @Composable (Song) -> Unit,
) {
    if (song != null) content(song)
}
