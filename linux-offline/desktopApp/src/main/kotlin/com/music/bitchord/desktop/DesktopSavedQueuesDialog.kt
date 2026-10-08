package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopSavedQueuesDialog(
    queues: List<DesktopSavedQueue>,
    currentSongs: List<Song>,
    currentIndex: Int,
    positionMs: Long,
    onSave: (String, List<Song>, Int, Long) -> Boolean,
    onReplace: (String, List<Song>, Int, Long) -> Boolean,
    onLoad: (DesktopSavedQueue) -> Unit,
    onDelete: (DesktopSavedQueue) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DesktopSavedQueue?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.manage_queues)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); saveError = false },
                    label = { Text(stringResource(Res.string.queue_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                ) { Text(stringResource(Res.string.save_current_queue)) }
                if (saveError) {
                    Text(
                        text = stringResource(Res.string.queue_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (queues.isEmpty()) {
                        Text(
                            stringResource(Res.string.no_saved_queues),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    queues.forEach { queue ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(queue.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(Res.string.queue_track_count, queue.songs.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = { onLoad(queue); onDismiss() }) {
                                    Text(stringResource(Res.string.load_queue))
                                }
                                TextButton(
                                    onClick = {
                                        if (!onReplace(queue.id, currentSongs, currentIndex, positionMs)) saveError = true
                                    },
                                    enabled = currentSongs.isNotEmpty(),
                                ) { Text(stringResource(Res.string.replace_queue)) }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { deleting = queue }) {
                                    Text(stringResource(Res.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.done)) }
        },
    )

    deleting?.let { queue ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.delete_saved_queue)) },
            text = { Text(queue.name) },
            confirmButton = {
                TextButton(onClick = { onDelete(queue); deleting = null }) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}
