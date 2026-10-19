package com.music.bitchord.ui.screens.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerSettings
import com.music.bitchord.playback.equalizer.EqualizerPreset
import com.music.bitchord.ui.screens.equalizer.components.EqualizerPresetGroup
import com.music.bitchord.playback.ReplayGainScanner
import com.music.bitchord.ui.screens.equalizer.components.AudioEnhancements
import com.music.bitchord.ui.screens.equalizer.components.EqualizerBandSliders
import com.music.bitchord.ui.screens.equalizer.components.EqualizerPresetChips
import com.music.bitchord.ui.screens.equalizer.components.ReplayGainControls
import com.music.bitchord.ui.screens.equalizer.components.ParametricEqControls
import com.music.bitchord.ui.screens.equalizer.components.ReverbControls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val enabled by EqualizerSettings.enabled.collectAsStateWithLifecycle()
    val presetId by EqualizerSettings.presetId.collectAsStateWithLifecycle()
    val bandLevels by EqualizerSettings.bandLevels.collectAsStateWithLifecycle()
    val bassBoostStrength by EqualizerSettings.bassBoostStrength.collectAsStateWithLifecycle()
    val virtualizerStrength by EqualizerSettings.virtualizerStrength.collectAsStateWithLifecycle()
    val activeDeviceLabel by EqualizerSettings.activeDeviceLabel.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val replayGainEnabled by AppSettings.replayGainEnabled.collectAsStateWithLifecycle()
    val replayGainAlbumMode by AppSettings.replayGainAlbumMode.collectAsStateWithLifecycle()
    val replayGainPreampDb by AppSettings.replayGainPreampDb.collectAsStateWithLifecycle()
    val replayGainPreventClipping by AppSettings.replayGainPreventClipping.collectAsStateWithLifecycle()
    val parametricEqEnabled by AppSettings.parametricEqEnabled.collectAsStateWithLifecycle()
    val parametricEqBands by AppSettings.parametricEqBands.collectAsStateWithLifecycle()
    val channelSwapEnabled by AppSettings.channelSwapEnabled.collectAsStateWithLifecycle()
    val reverbEnabled by AppSettings.reverbEnabled.collectAsStateWithLifecycle()
    val reverbAmount by AppSettings.reverbAmount.collectAsStateWithLifecycle()
    val replayGainScanState by ReplayGainScanner.state.collectAsStateWithLifecycle()
    val diracAudioEnabled by AppSettings.diracAudioEnabled.collectAsStateWithLifecycle()
    val diracAudioPreset by AppSettings.diracAudioPreset.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAutoEqDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.equalizer),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { EqualizerSettings.reset() },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset Equalizer",
                            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    Switch(
                        checked = enabled,
                        onCheckedChange = { EqualizerSettings.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            Text(
                text = stringResource(R.string.equalizer_output_profile, activeDeviceLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            OutlinedButton(
                onClick = { showAutoEqDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autoeq_profiles))
            }

            // Presets
            EqualizerPresetChips(
                selectedPresetId = presetId,
                enabled = enabled,
                onSelectPreset = {
                    AppSettings.setDiracAudioEnabled(false)
                    EqualizerSettings.selectPreset(it)
                },
            )

            Text(
                text = stringResource(R.string.eq_profile_group_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            EqualizerPresetGroup(
                title = stringResource(R.string.eq_profile_group_atmos),
                presets = EqualizerPreset.DOLBY_ATMOS_PRESETS,
                selectedPresetId = presetId,
                enabled = enabled,
                onSelectPreset = {
                    AppSettings.setDiracAudioEnabled(false)
                    EqualizerSettings.selectPreset(it)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { AppSettings.setDiracAudioEnabled(!diracAudioEnabled) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.dirac_audio_eq), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.dirac_audio_eq_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = diracAudioEnabled,
                    onCheckedChange = AppSettings::setDiracAudioEnabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            EqualizerPresetGroup(
                title = stringResource(R.string.eq_profile_group_dirac),
                presets = EqualizerPreset.DIRAC_AUDIO_PRESETS,
                selectedPresetId = EqualizerPreset.diracPresetByIndex(diracAudioPreset).id,
                enabled = true,
                onSelectPreset = { selectedId ->
                    val index = EqualizerPreset.DIRAC_AUDIO_PRESETS.indexOfFirst { it.id == selectedId }
                        .coerceAtLeast(0)
                    AppSettings.setDiracAudioPreset(index)
                    AppSettings.setDiracAudioEnabled(true)
                },
            )

            // Frequency band sliders
            EqualizerBandSliders(
                bandLevels = bandLevels,
                enabled = enabled,
                onBandLevelChange = { band, level ->
                    EqualizerSettings.setBandLevel(band, level)
                },
            )

            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.channel_swap),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.channel_swap_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = channelSwapEnabled,
                        onCheckedChange = AppSettings::setChannelSwapEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            ParametricEqControls(
                enabled = parametricEqEnabled,
                bands = parametricEqBands,
                onEnabledChange = AppSettings::setParametricEqEnabled,
                onBandChange = AppSettings::setParametricEqBand,
            )

            ReverbControls(
                enabled = reverbEnabled,
                amount = reverbAmount,
                onEnabledChange = AppSettings::setReverbEnabled,
                onAmountChange = AppSettings::setReverbAmount,
            )

            // Bass Boost, Virtualizer & System Equalizer action
            AudioEnhancements(
                bassBoostStrength = bassBoostStrength,
                virtualizerStrength = virtualizerStrength,
                enabled = enabled,
                audioSessionId = sessionId,
                onBassBoostChange = { EqualizerSettings.setBassBoostStrength(it) },
                onVirtualizerChange = { EqualizerSettings.setVirtualizerStrength(it) },
            )

            ReplayGainControls(
                enabled = replayGainEnabled,
                useAlbumGain = replayGainAlbumMode,
                preampDb = replayGainPreampDb,
                preventClipping = replayGainPreventClipping,
                onEnabledChange = AppSettings::setReplayGainEnabled,
                onAlbumGainChange = AppSettings::setReplayGainAlbumMode,
                onPreampChange = AppSettings::setReplayGainPreampDb,
                onPreventClippingChange = AppSettings::setReplayGainPreventClipping,
                scanState = replayGainScanState,
                onScan = { ReplayGainScanner.start(context) },
                onCancelScan = ReplayGainScanner::cancel,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAutoEqDialog) {
        AutoEqProfilesDialog(
            onDismiss = { showAutoEqDialog = false },
            onApplyEq = { levels ->
                AppSettings.setDiracAudioEnabled(false)
                EqualizerSettings.setEnabled(true)
                levels.forEachIndexed { index, level -> EqualizerSettings.setBandLevel(index, level) }
            },
        )
    }
}
