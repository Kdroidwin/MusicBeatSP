package com.music.bitchord.ui.player

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.SavedQueue
import com.music.bitchord.ui.components.frostedGlassEffect
import com.music.bitchord.ui.theme.ArtworkPalette

@Composable
internal fun SavedQueuesDialog(
    queues: List<SavedQueue>,
    artworkPalette: ArtworkPalette,
    hazeState: HazeState,
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
    val foreground = if (useFrostedGlass) MaterialTheme.colorScheme.onSurface else artworkPalette.onBackground
    val secondary = if (useFrostedGlass) MaterialTheme.colorScheme.onSurfaceVariant else artworkPalette.onBackgroundVariant
    val accent = if (useFrostedGlass) MaterialTheme.colorScheme.primary else artworkPalette.accent
    val container = if (useFrostedGlass) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.52f)
    } else artworkPalette.background

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (useFrostedGlass) {
            Modifier.frostedGlassEffect(hazeState, MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
        } else Modifier,
        title = { Text(stringResource(R.string.manage_queues), color = foreground) },
        containerColor = container,
        titleContentColor = foreground,
        textContentColor = foreground,
        iconContentColor = accent,
        text = {
            DialogBackdropBlur(useFrostedGlass)
            Column {
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
                        unfocusedBorderColor = if (useFrostedGlass) MaterialTheme.colorScheme.outlineVariant else artworkPalette.divider,
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = accent) }
        },
    )

    deleting?.let { queue ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            modifier = if (useFrostedGlass) {
                Modifier.frostedGlassEffect(hazeState, MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
            } else Modifier,
            title = { Text(stringResource(R.string.delete_saved_queue), color = foreground) },
            text = { DialogBackdropBlur(useFrostedGlass); Text(queue.name, color = foreground) },
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

@Composable
private fun DialogBackdropBlur(enabled: Boolean) {
    val view = LocalView.current
    val window = androidx.compose.runtime.remember(view) {
        (view.parent as? DialogWindowProvider)?.window
    }
    val radiusPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    DisposableEffect(window, enabled, radiusPx) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setBackgroundBlurRadius(radiusPx)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
                window.setBackgroundBlurRadius(0)
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
    }
}
