package com.music.bitchord.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.AppGestureAction
import com.music.bitchord.data.settings.AppGestureBinding
import com.music.bitchord.data.settings.AppGestureDirection
import com.music.bitchord.data.settings.AppGestureShape
import com.music.bitchord.data.settings.GesturePoint
import kotlinx.coroutines.flow.MutableSharedFlow

internal enum class GestureScrollRequest { TOP, BOTTOM }

internal object RootGestureScrollRequests {
    val requests = MutableSharedFlow<GestureScrollRequest>(extraBufferCapacity = 4)
    fun request(target: GestureScrollRequest) { requests.tryEmit(target) }
}

/** Observes only edge-originating swipes and never consumes taps or list scrolls. */
@Composable
internal fun Modifier.observeRootEdgeGestures(
    enabled: Boolean,
    actions: Map<AppGestureDirection, AppGestureAction>,
    bindings: Map<AppGestureShape, AppGestureBinding>,
    onAction: (AppGestureAction) -> Unit,
): Modifier {
    // MainActivity recreates this callback when ordinary UI state changes. If
    // it is a pointerInput key, that cancels an in-progress stroke (playhead,
    // list state and status-bar changes can all recompose during a drag).
    // Keep the pointer coroutine stable and read the latest action at release.
    val currentOnAction = rememberUpdatedState(onAction)
    return pointerInput(enabled, actions, bindings) {
    if (!enabled) return@pointerInput
    val edge = 32.dp.toPx()
    val threshold = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        var multiTouch = false
        val path = mutableListOf(GesturePoint(
            (start.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
            (start.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
        ))
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val point = GesturePoint(
                (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
            )
            val last = path.last()
            val move = kotlin.math.hypot(point.x - last.x, point.y - last.y)
            if (move >= 0.004f && path.size < 256) path += point
            if (!change.pressed) break
        } while (true)
        if (!multiTouch) {
            val shape = GesturePathMatcher.match(path, bindings)
            val drawnAction = shape?.let { bindings[it]?.action }
                ?.takeUnless { it == AppGestureAction.NONE }
            if (drawnAction != null) {
                currentOnAction.value(drawnAction)
            } else {
                val dx = path.last().x * size.width - start.x
                val dy = path.last().y * size.height - start.y
                val direction = when {
                    start.x <= edge && dx >= threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f -> AppGestureDirection.LEFT
                    start.x >= size.width - edge && dx <= -threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f -> AppGestureDirection.RIGHT
                    start.y <= edge && dy >= threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f -> AppGestureDirection.UP
                    start.y >= size.height - edge && dy <= -threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f -> AppGestureDirection.DOWN
                    else -> null
                }
                direction?.let(actions::get)?.takeUnless { it == AppGestureAction.NONE }
                    ?.let(currentOnAction.value)
            }
        }
    }
    }
}

/** Active FastScroller pages respond to configured scroll-to-edge gestures. */
@Composable
internal fun ObserveGestureScroll(listState: LazyListState) {
    LaunchedEffect(listState) {
        RootGestureScrollRequests.requests.collect { request ->
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(if (request == GestureScrollRequest.TOP) 0 else count - 1)
        }
    }
}

@Composable
internal fun ObserveGestureScroll(gridState: LazyGridState) {
    LaunchedEffect(gridState) {
        RootGestureScrollRequests.requests.collect { request ->
            val count = gridState.layoutInfo.totalItemsCount
            if (count > 0) gridState.animateScrollToItem(if (request == GestureScrollRequest.TOP) 0 else count - 1)
        }
    }
}
