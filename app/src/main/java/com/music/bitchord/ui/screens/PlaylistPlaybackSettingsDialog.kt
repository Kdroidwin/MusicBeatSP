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
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlaylistPlaybackProfile
import com.music.bitchord.data.settings.PlaylistPlaybackSettings
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import java.util.Locale

@Composable
fun PlaylistPlaybackSettingsDialog(playlist: LocalPlaylist, onDismiss: () -> Unit) {
    var profile by remember(playlist.id) {
        mutableStateOf(PlaylistPlaybackSettings.profileFor(playlist.id) ?: PlaylistPlaybackProfile())
    }
    val options = AppSettings.crossfadeDurationOptionsMs
    val crossfadeIndex = options.indices.minByOrNull { kotlin.math.abs(options[it] - profile.crossfadeDurationMs) } ?: 0
    val storageAvailable = PlaylistPlaybackSettings.secureStorageAvailable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_playback_settings)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(playlist.name, style = MaterialTheme.typography.titleSmall)
                PlaylistSettingToggle(
                    title = stringResource(R.string.playlist_crossfade_override),
                    subtitle = stringResource(R.string.playlist_crossfade_override_subtitle),
                    checked = profile.crossfadeOverride,
                    onCheckedChange = { profile = profile.copy(crossfadeOverride = it) },
                )
                if (profile.crossfadeOverride) {
                    Text(
                        text = if (profile.crossfadeDurationMs == 0) stringResource(R.string.off)
                        else stringResource(
                            R.string.crossfade_value_precise,
                            "%.2f".format(Locale.ROOT, profile.crossfadeDurationMs / 1000f).trimEnd('0').trimEnd('.'),
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = crossfadeIndex.toFloat(),
                        onValueChange = { value ->
                            profile = profile.copy(crossfadeDurationMs = options[value.toInt().coerceIn(options.indices)])
                        },
                        valueRange = 0f..options.lastIndex.toFloat(),
                        steps = (options.size - 2).coerceAtLeast(0),
                    )
                }

                PlaylistSettingToggle(
                    title = stringResource(R.string.playlist_eq_override),
                    subtitle = stringResource(R.string.playlist_eq_override_subtitle),
                    checked = profile.eqOverride,
                    onCheckedChange = { profile = profile.copy(eqOverride = it) },
                )
                if (profile.eqOverride) {
                    PlaylistSettingToggle(
                        title = stringResource(R.string.playlist_eq_enabled),
                        subtitle = stringResource(R.string.playlist_eq_enabled_subtitle),
                        checked = profile.eqEnabled,
                        onCheckedChange = { profile = profile.copy(eqEnabled = it) },
                    )
                    if (profile.eqEnabled) {
                        Text(
                            stringResource(R.string.playlist_eq_band_count, profile.eqBandCount),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Slider(
                            value = profile.eqBandCount.toFloat(),
                            onValueChange = { profile = profile.copy(eqBandCount = it.toInt().coerceIn(1, PlaylistPlaybackSettings.MAX_EQ_BANDS)) },
                            valueRange = 1f..PlaylistPlaybackSettings.MAX_EQ_BANDS.toFloat(),
                            steps = PlaylistPlaybackSettings.MAX_EQ_BANDS - 2,
                        )
                        Text(
                            stringResource(R.string.playlist_eq_uses_software_bands),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        repeat(profile.eqBandCount) { index ->
                            val frequency = PlaylistPlaybackSettings.eqBandCenterFrequenciesHz[index]
                            val level = profile.eqBandGainsCentiDb.getOrElse(index) { 0 }
                            Text(
                                text = stringResource(
                                    R.string.playlist_eq_band,
                                    index + 1,
                                    frequency.toInt(),
                                    level / 100f,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { value ->
                                    val gains = profile.eqBandGainsCentiDb.toMutableList()
                                    gains[index] = value.toInt().coerceIn(-1_500, 1_500)
                                    profile = profile.copy(eqBandGainsCentiDb = gains)
                                },
                                valueRange = -1_500f..1_500f,
                                steps = 29,
                            )
                        }
                    }
                }
                if (!storageAvailable) {
                    Text(
                        stringResource(R.string.playlist_playback_secure_storage_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = storageAvailable,
                onClick = { if (PlaylistPlaybackSettings.save(playlist.id, profile)) onDismiss() },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PlaylistSettingToggle(
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
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
