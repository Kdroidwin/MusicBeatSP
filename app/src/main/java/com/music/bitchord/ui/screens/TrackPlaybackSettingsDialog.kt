package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackPlaybackProfile
import com.music.bitchord.data.settings.TrackPlaybackSettings

@Composable
fun TrackPlaybackSettingsDialog(
    song: Song,
    onDismiss: () -> Unit,
) {
    var profile by remember(song.videoId) {
        mutableStateOf(TrackPlaybackSettings.profileFor(song.videoId) ?: TrackPlaybackProfile())
    }
    val options = AppSettings.crossfadeDurationOptionsMs
    val selectedCrossfade = options.indices.minByOrNull { index ->
        kotlin.math.abs(options[index] - profile.crossfadeDurationMs)
    } ?: 0
    val secureStorageAvailable = TrackPlaybackSettings.secureStorageAvailable
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_playback_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(song.title, style = MaterialTheme.typography.titleSmall)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingToggle(
                    title = stringResource(R.string.track_crossfade_override),
                    subtitle = stringResource(R.string.track_crossfade_override_subtitle),
                    checked = profile.crossfadeOverride,
                    onCheckedChange = { profile = profile.copy(crossfadeOverride = it) },
                )
                if (profile.crossfadeOverride) {
                    Text(
                        text = if (profile.crossfadeDurationMs == 0) stringResource(R.string.off)
                        else stringResource(R.string.crossfade_value_precise, "%.2f".format(profile.crossfadeDurationMs / 1000f).trimEnd('0').trimEnd('.')),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Slider(
                        value = selectedCrossfade.toFloat(),
                        onValueChange = { value ->
                            val index = value.toInt().coerceIn(options.indices)
                            profile = profile.copy(crossfadeDurationMs = options[index])
                        },
                        valueRange = 0f..options.lastIndex.toFloat(),
                        steps = (options.size - 2).coerceAtLeast(0),
                    )
                }
                SettingToggle(
                    title = stringResource(R.string.track_eq_override),
                    subtitle = stringResource(R.string.track_eq_override_subtitle),
                    checked = profile.eqOverride,
                    onCheckedChange = { profile = profile.copy(eqOverride = it) },
                )
                if (profile.eqOverride) {
                    SettingToggle(
                        title = stringResource(R.string.track_eq_enabled),
                        subtitle = stringResource(R.string.track_eq_enabled_subtitle),
                        checked = profile.eqEnabled,
                        onCheckedChange = { profile = profile.copy(eqEnabled = it) },
                    )
                    if (profile.eqEnabled) {
                        profile.eqBandLevels.forEachIndexed { band, level ->
                            Text(
                                text = stringResource(R.string.track_eq_band, band + 1, level),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { value ->
                                    val levels = profile.eqBandLevels.toMutableList()
                                    levels[band] = value.toInt().coerceIn(-1500, 1500)
                                    profile = profile.copy(eqBandLevels = levels)
                                },
                                valueRange = -1500f..1500f,
                                steps = 29,
                            )
                        }
                    }
                }
                if (!secureStorageAvailable) {
                    Text(
                        stringResource(R.string.track_playback_secure_storage_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = secureStorageAvailable,
                onClick = {
                    if (TrackPlaybackSettings.save(song.videoId, profile)) onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
