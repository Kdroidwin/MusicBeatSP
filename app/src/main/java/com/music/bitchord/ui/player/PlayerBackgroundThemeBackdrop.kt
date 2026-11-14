package com.music.bitchord.ui.player

import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Draws the same multicolour background as the expanded player on a compact
 * surface. This stays independent of the selected app theme and retains the
 * player's mesh, legacy mesh, retro background, blur, and queue-tint choices.
 */
@Composable
fun rememberPlayerBackdropSurfaceModifier(
    song: Song?,
    queue: List<Song> = emptyList(),
): Modifier {
    if (song == null) return Modifier

    val folderArtworkFallbackMode by AppSettings.folderArtworkFallbackMode.collectAsStateWithLifecycle()
    val artworkUrl = remember(song, folderArtworkFallbackMode) { song.artworkAt(PLAYER_ART_PX) }
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
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val activeBlurDp = if (
        playerArtworkMode == PlayerArtworkMode.AURIS_FULL_SCREEN && !retroPlayerMode
    ) {
        fullBleedBlurDp.toFloat()
    } else {
        artworkBlurDp
    }
    val configuration = LocalConfiguration.current
    val rawMesh = rememberArtworkMesh(backdropArtworkUrl, artPx = PLAYER_ART_PX)
    val canBlur = !reduceDynamicBlur && activeBlurDp > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val renderMesh = remember(
        rawMesh,
        activeBlurDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        canBlur,
    ) {
        if (!canBlur || rawMesh == null) {
            rawMesh
        } else {
            rawMesh.blurred(
                sigmaX = activeBlurDp * rawMesh.image.width / configuration.screenWidthDp.coerceAtLeast(1),
                sigmaY = activeBlurDp * rawMesh.image.height / configuration.screenHeightDp.coerceAtLeast(1),
            )
        }
    }
    val legacyPalette = if (legacyMesh) rememberArtworkColors(backdropArtworkUrl) else null
    val queueRainbowEnabled by AppSettings.playerQueueRainbowBackdrop.collectAsStateWithLifecycle()
    val queueColors = if (queueRainbowEnabled) rememberQueueBackdropColors(queue, song) else emptyList()
    val queueTintColors = remember(queueColors) {
        queueColors.map { it.copy(alpha = QUEUE_BACKDROP_TINT_ALPHA) }
    }

    var shown by remember { mutableStateOf(renderMesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(renderMesh, reduceAnimation) {
        val next = renderMesh ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect
        if (current == null || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    return Modifier.drawBehind {
        if (retroPlayerMode) {
            drawRect(
                if (retroPlayerBackground == RetroPlayerBackground.BLACK) Color.Black else palette.background,
            )
        } else if (legacyMesh) {
            val colors = (legacyPalette?.colors
                ?: listOf(palette.accent, palette.elevated, palette.wash, palette.background)).take(4)
            drawRect(colors.firstOrNull()?.copy(alpha = 0.12f) ?: palette.wash)
            val anchors = listOf(
                Offset(0.20f, 0.25f), Offset(0.80f, 0.20f),
                Offset(0.75f, 0.80f), Offset(0.25f, 0.75f),
            )
            colors.forEachIndexed { index, color ->
                val center = Offset(size.width * anchors[index].x, size.height * anchors[index].y)
                val radius = size.maxDimension * 0.62f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    center = center,
                    radius = radius,
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.38f)),
                ),
            )
        } else {
            drawRect(palette.wash)
            fun drawBackdropMesh(mesh: ArtworkMesh, alpha: Float) {
                if (alpha <= 0.001f) return
                drawImage(
                    image = mesh.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(mesh.image.width, mesh.image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
            shown?.let { drawBackdropMesh(it, 1f) }
            incoming?.let { drawBackdropMesh(it, fade.value) }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.30f)),
                ),
            )
        }
        if (queueTintColors.size > 1 &&
            !(retroPlayerMode && retroPlayerBackground == RetroPlayerBackground.BLACK)
        ) {
            val colorStops = queueTintColors.mapIndexed { index, color ->
                index.toFloat() / queueTintColors.lastIndex to color
            }.toTypedArray()
            drawRect(
                Brush.linearGradient(
                    colorStops = colorStops,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
    }
}
