package com.music.bitchord.ui.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.ui.components.ArtworkBackdrop
import com.music.bitchord.ui.components.PlayerPopupSystemBars
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun rememberPlayerOutputDevices(): List<AudioRouting.Device> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var devices by remember(manager) { mutableStateOf(AudioRouting.outputs(manager)) }
    DisposableEffect(manager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                devices = AudioRouting.outputs(manager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                devices = AudioRouting.outputs(manager)
            }
        }
        manager.registerAudioDeviceCallback(callback, null)
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return devices
}

@Composable
internal fun outputDeviceLabel(device: AudioRouting.Device?): String = when (device?.kind) {
    AudioRouting.Kind.PHONE -> stringResource(R.string.equalizer_speaker_output)
    AudioRouting.Kind.WIRED -> stringResource(R.string.equalizer_wired_output)
    AudioRouting.Kind.USB -> device.name.takeIf(String::isNotBlank)
        ?: stringResource(R.string.equalizer_usb_output)
    AudioRouting.Kind.BLUETOOTH -> device.name.takeIf(String::isNotBlank)
        ?: stringResource(R.string.equalizer_bluetooth_output)
    AudioRouting.Kind.HDMI -> device.name.takeIf(String::isNotBlank)
        ?: stringResource(R.string.equalizer_other_output)
    AudioRouting.Kind.OTHER -> device.name.takeIf(String::isNotBlank)
        ?: stringResource(R.string.equalizer_other_output)
    null -> stringResource(R.string.audio_output_system_default)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioOutputSheet(
    artworkPalette: ArtworkPalette,
    artworkUrl: String?,
    useArtworkColor: Boolean,
    onDismiss: () -> Unit,
    onOpenPipeline: () -> Unit,
) {
    val devices = rememberPlayerOutputDevices()
    val selectedId by AudioRouting.selectedId.collectAsStateWithLifecycle()
    val output by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shape,
        containerColor = if (useArtworkColor) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        PlayerPopupSystemBars(true)
        Box(Modifier.fillMaxWidth().clip(shape)) {
            if (useArtworkColor) {
                ArtworkBackdrop(
                    palette = artworkPalette,
                    imageUrl = artworkUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 0.78f,
                    artPx = 480,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 18.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = stringResource(R.string.audio_output),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (useArtworkColor) artworkPalette.onBackground else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
                )
                OutputChoiceRow(
                    name = stringResource(R.string.audio_output_system_default),
                    subtitle = outputDeviceLabel(devices.firstOrNull { it.id == output.activeDeviceId }),
                    selected = selectedId == null,
                    useArtworkColor = useArtworkColor,
                    artworkPalette = artworkPalette,
                    onClick = {
                        AudioRouting.select(null)
                        scope.launch { sheetState.hide(); onDismiss() }
                    },
                )
                devices.forEach { device ->
                    OutputChoiceRow(
                        name = outputDeviceLabel(device),
                        subtitle = device.name.takeIf(String::isNotBlank)
                            ?.takeIf { it != outputDeviceLabel(device) },
                        selected = device.id == output.activeDeviceId,
                        useArtworkColor = useArtworkColor,
                        artworkPalette = artworkPalette,
                        onClick = {
                            AudioRouting.select(device.id)
                            scope.launch { sheetState.hide(); onDismiss() }
                        },
                    )
                }
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.audio_output_no_devices),
                        color = if (useArtworkColor) artworkPalette.onBackgroundVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
                OutputChoiceRow(
                    name = stringResource(R.string.audio_pipeline),
                    subtitle = stringResource(R.string.audio_pipeline_subtitle),
                    iconPipeline = true,
                    selected = false,
                    useArtworkColor = useArtworkColor,
                    artworkPalette = artworkPalette,
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onOpenPipeline()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OutputChoiceRow(
    name: String,
    subtitle: String?,
    selected: Boolean,
    useArtworkColor: Boolean,
    artworkPalette: ArtworkPalette,
    iconPipeline: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (useArtworkColor) artworkPalette.onBackground else MaterialTheme.colorScheme.onSurface
    val muted = if (useArtworkColor) artworkPalette.onBackgroundVariant else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                selected -> Icons.Rounded.Check
                iconPipeline -> Icons.Rounded.GraphicEq
                else -> Icons.Rounded.Headphones
            },
            contentDescription = null,
            tint = if (selected) artworkPalette.accent else muted,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPipelineDialog(
    artworkPalette: ArtworkPalette,
    artworkUrl: String?,
    useArtworkColor: Boolean,
    onDismiss: () -> Unit,
) {
    val nerd by NerdStats.current.collectAsStateWithLifecycle()
    val output by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val bitPerfect by BitPerfectOutput.status.collectAsStateWithLifecycle()
    val eqEnabled by EqualizerSettings.enabled.collectAsStateWithLifecycle()
    val replayGain by AppSettings.replayGainEnabled.collectAsStateWithLifecycle()
    val spatial by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val titleColor = if (useArtworkColor) artworkPalette.onBackground else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (useArtworkColor) artworkPalette.onBackgroundVariant else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = if (useArtworkColor) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        PlayerPopupSystemBars(true)
        Box(Modifier.fillMaxWidth().clip(shape)) {
            if (useArtworkColor) {
                ArtworkBackdrop(
                    palette = artworkPalette,
                    imageUrl = artworkUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 0.78f,
                    artPx = 480,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.audio_pipeline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = titleColor)
                PipelineSection(stringResource(R.string.audio_pipeline_input), useArtworkColor, artworkPalette) {
                    PipelineValue(stringResource(R.string.audio_pipeline_codec), nerd?.mimeType?.substringAfterLast('/')?.uppercase(Locale.ROOT) ?: "—", mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_sample_rate), nerd?.sampleRateHz?.let { "${it / 1000f} kHz" } ?: "—", mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_bitrate), nerd?.bitrateKbps?.let { "$it kbps" } ?: "—", mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_channels), nerd?.channels?.toString() ?: "—", mutedColor, titleColor)
                }
                PipelineSection(stringResource(R.string.audio_pipeline_output), useArtworkColor, artworkPalette) {
                    PipelineValue(stringResource(R.string.audio_output), "${output.sink} · ${output.deviceName}", mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_sample_rate), output.actualSampleRateHz?.let { "${it / 1000f} kHz" } ?: "—", mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_format), AudioOutputStatus.encodingLabel(output), mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.audio_pipeline_channels), output.actualChannelConfig?.let { Integer.bitCount(it).toString() } ?: "—", mutedColor, titleColor)
                    AudioOutputStatus.estimatedTrackBufferDurationMs(output)?.let {
                        PipelineValue(stringResource(R.string.audio_pipeline_buffer), "$it ms", mutedColor, titleColor)
                    }
                }
                PipelineSection(stringResource(R.string.audio_pipeline_processing), useArtworkColor, artworkPalette) {
                    PipelineValue(stringResource(R.string.equalizer), if (eqEnabled) stringResource(R.string.enabled) else stringResource(R.string.service_disabled), mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.replaygain), if (replayGain) stringResource(R.string.enabled) else stringResource(R.string.service_disabled), mutedColor, titleColor)
                    PipelineValue(stringResource(R.string.spatial_audio), if (spatial) stringResource(R.string.enabled) else stringResource(R.string.service_disabled), mutedColor, titleColor)
                    PipelineValue(
                        stringResource(R.string.bit_perfect_playback),
                        when {
                            bitPerfect.active && bitPerfect.exact -> stringResource(R.string.audio_pipeline_active_exact)
                            bitPerfect.active -> stringResource(R.string.audio_pipeline_active_limited)
                            else -> stringResource(R.string.service_disabled)
                        },
                        mutedColor,
                        titleColor,
                    )
                    bitPerfect.fallbackReason?.let { PipelineValue(stringResource(R.string.audio_pipeline_fallback), it, mutedColor, titleColor) }
                }
                Text(
                    stringResource(R.string.audio_output_rate_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        }
    }
}

@Composable
private fun PipelineSection(
    title: String,
    useArtworkColor: Boolean,
    palette: ArtworkPalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = if (useArtworkColor) palette.onBackground else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PipelineValue(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = labelColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(12.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
