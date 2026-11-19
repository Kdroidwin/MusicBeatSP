package com.music.bitchord.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.AppGestureAction
import com.music.bitchord.data.settings.AppGestureDirection
import kotlinx.coroutines.flow.MutableSharedFlow

internal enum class GestureScrollRequest { TOP, BOTTOM }

internal object RootGestureScrollRequests {
    val requests = MutableSharedFlow<GestureScrollRequest>(extraBufferCapacity = 4)
    fun request(target: GestureScrollRequest) { requests.tryEmit(target) }
}

/** Observes only edge-originating swipes and never consumes taps or list scrolls. */
internal fun Modifier.observeRootEdgeGestures(
    enabled: Boolean,
    actions: Map<AppGestureDirection, AppGestureAction>,
    onAction: (AppGestureAction) -> Unit,
): Modifier = pointerInput(enabled, actions, onAction) {
    if (!enabled) return@pointerInput
    val edge = 32.dp.toPx()
    val threshold = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        var fired = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!fired) {
                val dx = change.position.x - start.x
                val dy = change.position.y - start.y
                val direction = when {
                    start.x <= edge && dx >= threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f -> AppGestureDirection.LEFT
                    start.x >= size.width - edge && dx <= -threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f -> AppGestureDirection.RIGHT
                    start.y <= edge && dy >= threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f -> AppGestureDirection.UP
                    start.y >= size.height - edge && dy <= -threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f -> AppGestureDirection.DOWN
                    else -> null
                }
                val action = direction?.let(actions::get)?.takeUnless { it == AppGestureAction.NONE }
                if (action != null) {
                    fired = true
                    onAction(action)
                }
            }
            if (!change.pressed) break
        } while (true)
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
