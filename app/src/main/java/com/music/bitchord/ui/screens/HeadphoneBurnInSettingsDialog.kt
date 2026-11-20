package com.music.bitchord.ui.screens

import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.HeadphoneBurnInSource
import com.music.bitchord.playback.HeadphoneBurnInRuntime
import kotlin.math.roundToInt

@Composable
fun HeadphoneBurnInSettingsDialog(
    onDismiss: () -> Unit,
    onStart: (HeadphoneBurnInSource, Int, String?) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by AppSettings.headphoneBurnInEnabled.collectAsStateWithLifecycle()
    val source by AppSettings.headphoneBurnInSource.collectAsStateWithLifecycle()
    val duration by AppSettings.headphoneBurnInDurationMinutes.collectAsStateWithLifecycle()
    var durationDraft by remember(duration) { mutableFloatStateOf(duration.toFloat()) }
    val musicUri by AppSettings.headphoneBurnInMusicUri.collectAsStateWithLifecycle()
    val deadline by HeadphoneBurnInRuntime.deadlineElapsedMs.collectAsStateWithLifecycle()
    val running = deadline != null
    var selectedMusicName by remember(musicUri) {
        mutableStateOf(musicUri?.let { uriString ->
            runCatching {
                context.contentResolver.query(
                    android.net.Uri.parse(uriString),
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
            }.getOrNull()
        })
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val retained = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!retained) {
            Toast.makeText(context, R.string.file_access_permission_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        musicUri?.takeIf { it != uri.toString() }?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        AppSettings.setHeadphoneBurnInMusicUri(uri.toString())
        selectedMusicName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.headphone_burn_in)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.headphone_burn_in_enabled))
                        Text(stringResource(R.string.headphone_burn_in_subtitle))
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            if (!value && running) onStop()
                            AppSettings.setHeadphoneBurnInEnabled(value)
                        },
                    )
                }
                if (enabled) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.headphone_burn_in_source))
                    BurnInSourceOption(
                        label = stringResource(R.string.headphone_burn_in_white_noise),
                        selected = source == HeadphoneBurnInSource.WHITE_NOISE,
                    ) { AppSettings.setHeadphoneBurnInSource(HeadphoneBurnInSource.WHITE_NOISE) }
                    BurnInSourceOption(
                        label = stringResource(R.string.headphone_burn_in_pink_noise),
                        selected = source == HeadphoneBurnInSource.PINK_NOISE,
                    ) { AppSettings.setHeadphoneBurnInSource(HeadphoneBurnInSource.PINK_NOISE) }
                    BurnInSourceOption(
                        label = stringResource(R.string.headphone_burn_in_frequency_sweep),
                        selected = source == HeadphoneBurnInSource.FREQUENCY_SWEEP,
                    ) { AppSettings.setHeadphoneBurnInSource(HeadphoneBurnInSource.FREQUENCY_SWEEP) }
                    BurnInSourceOption(
                        label = stringResource(R.string.headphone_burn_in_music),
                        selected = source == HeadphoneBurnInSource.MUSIC,
                    ) { AppSettings.setHeadphoneBurnInSource(HeadphoneBurnInSource.MUSIC) }
                    if (source == HeadphoneBurnInSource.MUSIC) {
                        TextButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }) {
                            Text(selectedMusicName ?: stringResource(R.string.headphone_burn_in_pick_music))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.headphone_burn_in_duration))
                    Text(
                        stringResource(
                            R.string.headphone_burn_in_duration_value,
                            durationDraft.roundToInt() / 60,
                            durationDraft.roundToInt() % 60,
                        ),
                    )
                    Slider(
                        value = durationDraft,
                        onValueChange = { durationDraft = it },
                        onValueChangeFinished = {
                            AppSettings.setHeadphoneBurnInDurationMinutes(
                                (durationDraft.roundToInt() / 5) * 5,
                            )
                        },
                        valueRange = 5f..AppSettings.MAX_HEADPHONE_BURN_IN_DURATION_MINUTES.toFloat(),
                    )
                    if (running) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.headphone_burn_in_running))
                    }
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.headphone_burn_in_stop)) }
            } else {
                Button(
                    enabled = enabled && (source != HeadphoneBurnInSource.MUSIC || !musicUri.isNullOrBlank()),
                    onClick = { onStart(source, duration, musicUri) },
                ) { Text(stringResource(R.string.headphone_burn_in_start)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun BurnInSourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
