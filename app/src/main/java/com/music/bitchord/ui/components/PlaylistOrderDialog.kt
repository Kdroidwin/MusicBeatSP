package com.music.bitchord.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.music.bitchord.R
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist

private const val SWAP_THRESHOLD = 0.6f

@Composable
fun PlaylistOrderDialog(
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onReorder: (List<LocalPlaylist>) -> Unit,
) {
    var liveOrder by remember(playlists) { mutableStateOf(playlists) }
    var draggedPlaylistId by remember { mutableStateOf<String?>(null) }
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var startIndex by remember { mutableIntStateOf(0) }
    var pitchPx by remember { mutableFloatStateOf(0f) }
    var lockedPitchPx by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_order_title)) },
        text = {
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.no_playlists_to_reorder))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.playlist_order_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    liveOrder.forEachIndexed { index, playlist ->
                        key(playlist.id) {
                            val dragging = playlist.id == draggedPlaylistId
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (dragging) 1f else 0f)
                                    .onSizeChanged { pitchPx = it.height.toFloat() }
                                    .graphicsLayer {
                                        translationY = if (dragging) {
                                            totalDrag -
                                                (liveOrder.indexOfFirst { it.id == playlist.id } - startIndex) * lockedPitchPx
                                        } else {
                                            0f
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .pointerInput(playlist.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { _ ->
                                                    draggedPlaylistId = playlist.id
                                                    totalDrag = 0f
                                                    startIndex = liveOrder.indexOfFirst { it.id == playlist.id }
                                                    lockedPitchPx = pitchPx
                                                },
                                                onDrag = { change, delta ->
                                                    change.consume()
                                                    val pitch = lockedPitchPx
                                                    if (pitch <= 0f) return@detectDragGesturesAfterLongPress
                                                    var at = liveOrder.indexOfFirst { it.id == playlist.id }
                                                    if (at < 0) return@detectDragGesturesAfterLongPress

                                                    totalDrag = (totalDrag + delta.y).coerceIn(
                                                        -startIndex * pitch,
                                                        (liveOrder.lastIndex - startIndex) * pitch,
                                                    )

                                                    while (true) {
                                                        val travelled = totalDrag / pitch
                                                        val moved = (at - startIndex).toFloat()
                                                        if (travelled > moved + SWAP_THRESHOLD && at < liveOrder.lastIndex) {
                                                            liveOrder = liveOrder.toMutableList().apply { add(at + 1, removeAt(at)) }
                                                            at++
                                                        } else if (travelled < moved - SWAP_THRESHOLD && at > 0) {
                                                            liveOrder = liveOrder.toMutableList().apply { add(at - 1, removeAt(at)) }
                                                            at--
                                                        } else {
                                                            break
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggedPlaylistId = null
                                                    totalDrag = 0f
                                                    onReorder(liveOrder)
                                                },
                                                onDragCancel = {
                                                    draggedPlaylistId = null
                                                    totalDrag = 0f
                                                    liveOrder = playlists
                                                },
                                            )
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = playlist.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Rounded.DragHandle,
                                        contentDescription = stringResource(R.string.drag_to_reorder),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (index < liveOrder.lastIndex) HorizontalDivider() else {
                                    androidx.compose.foundation.layout.Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
