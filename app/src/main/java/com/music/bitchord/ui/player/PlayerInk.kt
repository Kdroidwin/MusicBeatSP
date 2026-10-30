package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.music.bitchord.data.settings.PLAYER_INK_AUTOMATIC

/** Scoped tints for the four independently configurable player control groups. */
internal val LocalPlayerButtonControlInk = staticCompositionLocalOf { Color.White }
internal val LocalPlayerSeekVolumeInk = staticCompositionLocalOf { Color.White }
internal val LocalPlayerSongArtistInk = staticCompositionLocalOf { Color.White }
internal val LocalPlayerLyricInk = staticCompositionLocalOf { Color.White }
/** The tablet split player paints one shared artwork backdrop behind both panes. */
internal val LocalSharedPlayerBackdrop = staticCompositionLocalOf { false }

/** Use Auris-style artwork-adaptive ink unless a validated user color is saved. */
internal fun resolvePlayerInk(value: String, automatic: Color): Color {
    if (value == PLAYER_INK_AUTOMATIC) return automatic
    return runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(automatic)
}
