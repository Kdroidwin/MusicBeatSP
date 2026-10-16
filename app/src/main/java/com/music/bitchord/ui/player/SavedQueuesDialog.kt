package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.SavedQueue
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.components.ArtworkBackdrop
import com.music.bitchord.data.model.ROW_ART_PX

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedQueuesDialog(
    queues: List<SavedQueue>,
    artworkPalette: ArtworkPalette,
    artworkUrl: String?,
    useFrostedGlass: Boolean,
    currentSongs: List<Song>,
    currentIndex: Int,
    positionMs: Long,
    onSave: (String, List<Song>, Int, Long) -> Boolean,
    onReplace: (String, List<Song>, Int, Long) -> Boolean,
    onLoad: (SavedQueue) -> Unit,
    onDelete: (SavedQueue) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedQueue?>(null) }
    // Match the player-details sheet: the enabled setting uses the same
    // BitChord artwork palette, while disabled returns to the app surface.
    val foreground = if (useFrostedGlass) artworkPalette.onBackground else MaterialTheme.colorScheme.onSurface
    val secondary = if (useFrostedGlass) artworkPalette.onBackgroundVariant else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (useFrostedGlass) artworkPalette.accent else MaterialTheme.colorScheme.primary
    val container = if (useFrostedGlass) {
        artworkPalette.background
    } else MaterialTheme.colorScheme.surfaceContainerHigh

    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = sheetShape,
        containerColor = if (useFrostedGlass) Color.Transparent else container,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .height(4.dp)
                        .fillMaxWidth(0.14f)
                        .clip(RoundedCornerShape(50))
                        .background(foreground.copy(alpha = 0.38f)),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().clip(sheetShape)) {
            if (useFrostedGlass) {
                ArtworkBackdrop(
                    palette = artworkPalette,
                    imageUrl = artworkUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 0.75f,
                    artPx = ROW_ART_PX,
                )
            } else {
                Box(Modifier.matchParentSize().background(container))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = 640.dp)
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            ) {
                Text(
                    stringResource(R.string.manage_queues),
                    color = foreground,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); saveError = false },
                    label = { Text(stringResource(R.string.queue_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = foreground,
                        unfocusedTextColor = foreground,
                        focusedLabelColor = accent,
                        unfocusedLabelColor = secondary,
                        cursorColor = accent,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = if (useFrostedGlass) artworkPalette.divider else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    enabled = name.isNotBlank() && currentSongs.isNotEmpty(),
                    onClick = {
                        if (onSave(name, currentSongs, currentIndex, positionMs)) {
                            name = ""
                            saveError = false
                        } else saveError = true
                    },
                ) {
                    Text(
                        stringResource(R.string.save_current_queue),
                        color = accent,
                    )
                }
                if (saveError) {
                    Text(
                        text = stringResource(R.string.queue_save_failed),
                        color = accent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (queues.isEmpty()) {
                        Text(
                            stringResource(R.string.no_saved_queues),
                            color = secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    queues.forEach { queue ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(queue.name, style = MaterialTheme.typography.titleSmall, color = foreground)
                            Text(
                                stringResource(R.string.queue_track_count, queue.songs.size),
                                color = secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onLoad(queue); onDismiss() }) {
                                    Text(stringResource(R.string.load_queue), color = accent)
                                }
                                TextButton(
                                    onClick = {
                                        if (!onReplace(queue.id, currentSongs, currentIndex, positionMs)) saveError = true
                                    },
                                    enabled = currentSongs.isNotEmpty(),
                                ) { Text(stringResource(R.string.replace_queue), color = accent) }
                                TextButton(onClick = { deleting = queue }) {
                                    Text(stringResource(R.string.delete), color = accent)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = accent) }
                }
            }
        }
    }

    deleting?.let { queue ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.delete_saved_queue), color = foreground) },
            text = { Text(queue.name, color = foreground) },
            containerColor = container,
            titleContentColor = foreground,
            textContentColor = foreground,
            iconContentColor = accent,
            confirmButton = {
                TextButton(onClick = { onDelete(queue); deleting = null }) {
                    Text(stringResource(R.string.delete), color = accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel), color = accent)
                }
            },
        )
    }
}
