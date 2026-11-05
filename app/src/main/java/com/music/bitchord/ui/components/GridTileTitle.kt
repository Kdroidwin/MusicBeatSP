package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/** Whether grid titles sit on the artwork (Settings › Grid titles). */
@Composable
fun rememberGridTitleOverlay(): Boolean {
    val overlay by AppSettings.gridTitleOverlay.collectAsStateWithLifecycle()
    return overlay
}

/**
 * A grid tile's title laid over the bottom of its artwork: the lower part of
 * the art darkened just enough for white text, two lines of title, and the
 * subtitle while the tile is wide enough (2–3 columns). Below
 * [OVERLAY_TITLE_MIN_WIDTH] (5 columns on a phone) nothing is drawn: at that
 * density the grid is for picking by artwork.
 */
@Composable
fun BoxScope.GridTitleOnArt(
    title: String,
    subtitle: String?,
    tileWidth: Dp,
    highlight: Boolean = false,
    explicit: Boolean = false,
) {
    if (tileWidth < OVERLAY_TITLE_MIN_WIDTH) return
    val showSubtitle = subtitle != null && tileWidth >= 100.dp
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(if (showSubtitle) 0.55f else 0.45f)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(horizontal = if (tileWidth < 100.dp) 6.dp else 8.dp, vertical = if (tileWidth < 100.dp) 5.dp else 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (explicit) {
                Text(
                    text = "E",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = title,
                style = if (tileWidth < 100.dp) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (showSubtitle) {
            Text(
                text = subtitle.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
