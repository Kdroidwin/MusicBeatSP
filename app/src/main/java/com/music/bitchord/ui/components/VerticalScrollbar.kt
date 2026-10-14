package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/** A slim, draggable scrollbar for long Compose lists, grids and scroll columns. */
@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || total <= 1) return
    val maxIndex = (total - visible).coerceAtLeast(1)
    val progress = (state.firstVisibleItemIndex.toFloat() / maxIndex).coerceIn(0f, 1f)
    val fraction = (visible.toFloat() / total).coerceIn(0.06f, 1f)
    val scope = rememberCoroutineScope()
    ScrollbarThumb(progress, fraction, modifier) { value ->
        scope.launch { state.scrollToItem((value * maxIndex).roundToInt().coerceIn(0, maxIndex)) }
    }
}

@Composable
fun VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || total <= 1) return
    val maxIndex = (total - visible).coerceAtLeast(1)
    val progress = (state.firstVisibleItemIndex.toFloat() / maxIndex).coerceIn(0f, 1f)
    val fraction = (visible.toFloat() / total).coerceIn(0.06f, 1f)
    val scope = rememberCoroutineScope()
    ScrollbarThumb(progress, fraction, modifier) { value ->
        scope.launch { state.scrollToItem((value * maxIndex).roundToInt().coerceIn(0, maxIndex)) }
    }
}

@Composable
fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (state.maxValue <= 0) return
    val density = LocalDensity.current
    val maxValue = state.maxValue
    val progress = (state.value.toFloat() / maxValue).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.width(12.dp).fillMaxHeight()) {
        val viewportPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val fraction = (viewportPx / (viewportPx + maxValue)).coerceIn(0.06f, 1f)
        ScrollbarThumb(progress, fraction, Modifier.fillMaxHeight()) { value ->
            scope.launch { state.scrollTo((value * maxValue).roundToInt().coerceIn(0, maxValue)) }
        }
    }
}

@Composable
private fun ScrollbarThumb(
    progress: Float,
    visibleFraction: Float,
    modifier: Modifier = Modifier,
    onScrollToFraction: (Float) -> Unit,
) {
    val latestProgress by rememberUpdatedState(progress)
    val latestScroll by rememberUpdatedState(onScrollToFraction)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    BoxWithConstraints(
        modifier = modifier.width(12.dp).fillMaxHeight(),
        contentAlignment = Alignment.TopEnd,
    ) {
        val thumbHeight = (maxHeight * visibleFraction).coerceIn(minOf(24.dp, maxHeight), maxHeight)
        val maxOffset = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        val maxOffsetPx = with(LocalDensity.current) { maxOffset.toPx() }
        val offset = maxOffset * progress
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(2.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(trackColor),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = offset)
                .width(4.dp)
                .height(thumbHeight)
                .clip(CircleShape)
                .background(thumbColor)
                .pointerInput(maxOffsetPx) {
                    var dragOffset = latestProgress * maxOffsetPx
                    detectDragGestures(
                        onDragStart = { dragOffset = latestProgress * maxOffsetPx },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset = (dragOffset + dragAmount.y).coerceIn(0f, maxOffsetPx)
                            latestScroll(if (maxOffsetPx > 0f) dragOffset / maxOffsetPx else 0f)
                        },
                    )
                },
        )
    }
}
