package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.ArtworkPalette

/** Artwork-derived blurred background used by the player action sheet. */
@Composable
fun ArtworkBackdrop(
    palette: ArtworkPalette,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    washFraction: Float = 0.72f,
    artPx: Int = CARD_ART_PX,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val canBlur = !reduceDynamicBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier.background(palette.background)) {
        if (canBlur && imageUrl != null) {
            AsyncImage(
                model = imageUrl.artworkAt(artPx),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(washFraction)
                    .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                    .blur(72.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        val dark = isSystemInDarkTheme()
        val topAlpha = if (dark) 0.40f else 0.08f
        val middleAlpha = if (dark) 0.55f else 0.25f
        val lowerAlpha = if (dark) 0.85f else 0.72f
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = topAlpha),
                        0.20f to palette.background.copy(alpha = middleAlpha),
                        0.55f to palette.background.copy(alpha = lowerAlpha),
                        washFraction to palette.background,
                    ),
                ),
        )
    }
}
