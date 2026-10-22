package com.music.bitchord.ui.screens

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.cdripping.CdRippingViewModel

@Composable
internal fun CdRippingDialog(
    onDismiss: () -> Unit,
    onRipComplete: () -> Unit,
) {
    val model: CdRippingViewModel = viewModel()
    val speedX by AppSettings.cdRippingSpeedX.collectAsStateWithLifecycle()
    val retries by AppSettings.cdRippingRetries.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) model.setOutputTree(uri)
    }
    var wasRipping by remember { mutableStateOf(false) }

    LaunchedEffect(model.isRipping, model.completedCount) {
        if (wasRipping && !model.isRipping && model.completedCount > 0) onRipComplete()
        wasRipping = model.isRipping
    }

    Dialog(
        onDismissRequest = {
            model.releaseDrive()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.94f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 18.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Album, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.cd_ripping_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        model.releaseDrive()
                        onDismiss()
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_ripping_close))
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cd_ripping_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.cd_ripping_output_folder), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    model.outputFolderName.ifBlank { stringResource(R.string.cd_ripping_folder_not_selected) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { picker.launch(null) }, enabled = !model.isRipping) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.cd_ripping_choose_folder))
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cd_ripping_select_drive), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = model::refreshDevices, enabled = !model.isScanning && !model.isRipping) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_ripping_scan))
                        }
                    }
                    if (model.devices.isEmpty() && !model.isScanning) {
                        Text(
                            stringResource(R.string.cd_ripping_no_drive),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    model.devices.forEach { device ->
                        CdDriveRow(device = device, enabled = !model.isScanning && !model.isRipping) {
                            model.selectDevice(device)
                        }
                    }
                    if (model.isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (model.driveName.isNotBlank()) {
                        Text(
                            text = "${model.driveName} · ${model.tracks.size} ${stringResource(R.string.cd_ripping_tracks)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (model.tracks.isNotEmpty()) {
                        OutlinedTextField(
                            value = model.albumTitle,
                            onValueChange = { model.albumTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.cd_ripping_album_title)) },
                            singleLine = true,
                            enabled = !model.isRipping,
                        )
                        OutlinedTextField(
                            value = model.albumArtist,
                            onValueChange = { model.albumArtist = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.cd_ripping_album_artist)) },
                            singleLine = true,
                            enabled = !model.isRipping,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.cd_ripping_tracks), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { model.selectAllTracks(true) }, enabled = !model.isRipping) {
                                Text(stringResource(R.string.cd_ripping_select_all))
                            }
                            TextButton(onClick = { model.selectAllTracks(false) }, enabled = !model.isRipping) {
                                Text(stringResource(R.string.cd_ripping_deselect_all))
                            }
                        }
                        model.tracks.forEach { track ->
                            val checked = model.selectedTrackNumbers[track.number] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !model.isRipping) { model.toggleTrack(track.number, !checked) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { model.toggleTrack(track.number, it) },
                                    enabled = !model.isRipping,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("Track %02d".format(track.number), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        durationFromSectors(track.endLba - track.startLba),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("WAV", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text(stringResource(R.string.cd_ripping_read_speed), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 4, 8).forEach { speed ->
                                FilterChip(
                                    selected = speedX == speed,
                                    onClick = { AppSettings.setCdRippingSpeedX(speed) },
                                    enabled = !model.isRipping,
                                    label = { Text("${speed}×") },
                                )
                            }
                        }
                        Text(stringResource(R.string.cd_ripping_retries), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1, 2, 3, 5).forEach { count ->
                                FilterChip(
                                    selected = retries == count,
                                    onClick = { AppSettings.setCdRippingRetries(count) },
                                    enabled = !model.isRipping,
                                    label = { Text(count.toString()) },
                                )
                            }
                        }
                    }

                    if (model.statusMessage.isNotBlank()) {
                        Text(
                            text = model.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (model.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.isRipping) {
                        LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.cd_ripping_progress,
                                model.currentTrackNumber,
                                model.trackCount,
                                (model.progress * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (model.isRipping) {
                        TextButton(onClick = model::cancelRip) { Text(stringResource(R.string.cd_ripping_cancel)) }
                    }
                    Button(
                        onClick = model::startRip,
                        enabled = !model.isRipping && model.tracks.isNotEmpty() &&
                            model.selectedTrackNumbers.values.any { it } && model.outputFolderName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.cd_ripping_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun CdDriveRow(device: UsbDevice, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(device.manufacturerName, device.productName)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .ifBlank { device.deviceName },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "USB ${"%04X:%04X".format(device.vendorId, device.productId)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun durationFromSectors(sectors: Int): String {
    val totalSeconds = (sectors.coerceAtLeast(0) / 75)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
