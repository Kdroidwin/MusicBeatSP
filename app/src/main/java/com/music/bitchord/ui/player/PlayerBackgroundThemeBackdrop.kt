package com.music.bitchord.ui.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlayerArtworkMode
import com.music.bitchord.data.settings.RetroPlayerBackground
import com.music.bitchord.feature.localmusic.coil.LocalAudioArtworkFetcher
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * Draws the same artwork backdrop used by the expanded player behind the app
 * when the Player background theme is selected. The artwork mesh mode, legacy
 * mesh mode, blur setting, Retro background and optional queue tint all share
 * the player's existing palette and drawing components.
 */
@Composable
fun PlayerBackgroundThemeBackdrop(
    song: Song?,
    queue: List<Song>,
    modifier: Modifier = Modifier,
) {
    if (song == null) return

    val folderArtworkFallbackMode by AppSettings.folderArtworkFallbackMode.collectAsStateWithLifecycle()
    val artworkUrl = remember(song, folderArtworkFallbackMode) {
        song.artworkAt(PLAYER_ART_PX)
    }
    val localPreviewArtworkUrl = remember(song, artworkUrl, folderArtworkFallbackMode) {
        artworkUrl?.takeIf { LocalAudioArtworkFetcher.isLocalAudioUri(Uri.parse(it)) }
            ?.let { song.artworkAt(480) }
    }
    val backdropArtworkUrl = localPreviewArtworkUrl ?: artworkUrl
    val palette = rememberArtworkPalette(backdropArtworkUrl, dark = true, artPx = PLAYER_ART_PX)
    val legacyMesh by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val retroPlayerMode by AppSettings.retroPlayerMode.collectAsStateWithLifecycle()
    val retroPlayerBackground by AppSettings.retroPlayerBackground.collectAsStateWithLifecycle()
    val playerArtworkMode by AppSettings.playerArtworkMode.collectAsStateWithLifecycle()
    val artworkBlurDp by AppSettings.artworkBackdropBlurDp.collectAsStateWithLifecycle()
    val fullBleedBlurDp by AppSettings.fullBleedArtworkBottomBlurStrengthDp.collectAsStateWithLifecycle()
    val activeBlurDp = if (
        playerArtworkMode == PlayerArtworkMode.AURIS_FULL_SCREEN && !retroPlayerMode
    ) {
        fullBleedBlurDp.toFloat()
    } else {
        artworkBlurDp
    }
    val queueRainbowEnabled by AppSettings.playerQueueRainbowBackdrop.collectAsStateWithLifecycle()
    val queueColors = if (queueRainbowEnabled) rememberQueueBackdropColors(queue, song) else emptyList()
    val queueTintColors = remember(queueColors) {
        queueColors.map { it.copy(alpha = QUEUE_BACKDROP_TINT_ALPHA) }
    }

    Box(modifier.fillMaxSize()) {
        if (retroPlayerMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (retroPlayerBackground == RetroPlayerBackground.BLACK) {
                            Color.Black
                        } else {
                            palette.background
                        },
                    ),
            )
        } else if (legacyMesh) {
            MeshGradientBackground(
                palette = rememberArtworkColors(backdropArtworkUrl),
                trackKey = song.videoId,
                blurRadius = activeBlurDp.dp,
            )
        } else {
            ArtworkMeshBackdrop(
                mesh = rememberArtworkMesh(backdropArtworkUrl, artPx = PLAYER_ART_PX),
                fallback = palette.wash,
                blurRadius = activeBlurDp.dp,
            )
        }
        if (queueTintColors.size > 1 &&
            !(retroPlayerMode && retroPlayerBackground == RetroPlayerBackground.BLACK)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val colorStops = queueTintColors.mapIndexed { index, color ->
                            (index.toFloat() / queueTintColors.lastIndex) to color
                        }.toTypedArray()
                        drawRect(
                            Brush.linearGradient(
                                colorStops = colorStops,
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                        )
                    },
            )
        }
    }
}
