package com.music.bitchord.ui.screens

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.playback.ScheduledPlaylistScheduler
import com.music.bitchord.playback.ScheduledPlaylistSpec

@Composable
fun ScheduledPlaylistSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val spec by ScheduledPlaylistScheduler.spec.collectAsStateWithLifecycle()
    val playlists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    var enabled by remember(spec) { mutableStateOf(spec.enabled) }
    var playlistId by remember(spec) { mutableStateOf(spec.playlistId) }
    var hour by remember(spec) { mutableIntStateOf(spec.hour) }
    var minute by remember(spec) { mutableIntStateOf(spec.minute) }
    val secureStorageAvailable = ScheduledPlaylistScheduler.secureStorageAvailable

    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ScheduledPlaylistScheduler.reconcile(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_playlist_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scheduled_playlist), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                TextButton(
                    enabled = secureStorageAvailable,
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, selectedHour, selectedMinute ->
                                hour = selectedHour
                                minute = selectedMinute
                            },
                            hour,
                            minute,
                            DateFormat.is24HourFormat(context),
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.scheduled_playlist_time) + "  " + "%02d:%02d".format(hour, minute))
                }
                Text(stringResource(R.string.scheduled_playlist_choose), style = MaterialTheme.typography.titleSmall)
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.scheduled_playlist_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    playlists.forEach { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playlistId = playlist.id }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = playlistId == playlist.id,
                                onClick = { playlistId = playlist.id },
                            )
                            Text(playlist.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(AlarmManager::class.java)
                    if (alarmManager?.canScheduleExactAlarms() != true) {
                        Text(
                            stringResource(R.string.scheduled_playlist_requires_permission),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!secureStorageAvailable) {
                    Text(
                        stringResource(R.string.scheduled_playlist_secure_storage_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = secureStorageAvailable && (!enabled || playlistId != null),
                onClick = {
                    val requested = ScheduledPlaylistSpec(enabled, playlistId, hour, minute)
                    if (!ScheduledPlaylistScheduler.save(context, requested)) {
                        Toast.makeText(context, R.string.scheduled_playlist_save_failed, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val alarmManager = context.getSystemService(AlarmManager::class.java)
                        if (alarmManager?.canScheduleExactAlarms() != true) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
