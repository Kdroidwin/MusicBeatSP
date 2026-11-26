package com.music.bitchord.feature.library.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/** Shows the complete playlist title, reducing its size before allowing it to wrap. */
@Composable
fun AutoFitPlaylistName(
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val autoFit by AppSettings.autoFitLibraryPlaylistNames.collectAsStateWithLifecycle()
    val explicitLines = remember(name) { (name.count { it == '\n' } + 1).coerceAtLeast(1) }
    if (!autoFit) {
        Text(
            text = name,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = explicitLines,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val textMeasurer = rememberTextMeasurer()
        val availableWidthPx = constraints.maxWidth.takeUnless { it == Constraints.Infinity } ?: 0
        val fittedStyle = remember(name, style, availableWidthPx) {
            fitPlaylistTitleStyle(name, style, availableWidthPx, textMeasurer)
        }
        Text(
            text = name,
            modifier = Modifier.fillMaxWidth(),
            style = fittedStyle,
            color = color,
            maxLines = explicitLines,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun fitPlaylistTitleStyle(
    text: String,
    style: TextStyle,
    availableWidthPx: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): TextStyle {
    val originalSize = style.fontSize
    if (availableWidthPx <= 0 || !originalSize.isSp || !originalSize.value.isFinite()) return style
    val originalSp = originalSize.value
    if (originalSp <= MIN_PLAYLIST_TITLE_SP) return style

    fun fits(sizeSp: Float): Boolean = runCatching {
        textMeasurer.measure(
            text = text,
            style = style.copy(fontSize = sizeSp.sp),
            constraints = Constraints(maxWidth = Constraints.Infinity),
            maxLines = (text.count { it == '\n' } + 1).coerceAtLeast(1),
            softWrap = false,
        ).size.width <= availableWidthPx
    }.getOrDefault(false)

    if (fits(originalSp)) return style
    var low = MIN_PLAYLIST_TITLE_SP
    var high = originalSp
    repeat(12) {
        val candidate = (low + high) / 2f
        if (fits(candidate)) low = candidate else high = candidate
    }
    return style.copy(fontSize = low.sp)
}

private const val MIN_PLAYLIST_TITLE_SP = 8f
