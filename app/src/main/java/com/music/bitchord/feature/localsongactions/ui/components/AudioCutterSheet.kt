package com.music.bitchord.feature.localsongactions.ui.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.feature.localsongactions.data.AudioCutterExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class CutterState { EDITING, EXPORTING, COMPLETE, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCutterSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fallbackDuration = remember(song.durationText) { song.durationMillis().coerceAtLeast(0L) }
    var durationMs by remember(song.localUri, song.localPath, song.durationText) {
        mutableLongStateOf(fallbackDuration)
    }
    var selectedRange by remember(song.videoId, durationMs) {
        mutableStateOf(0f..durationMs.toFloat())
    }
    var cutterState by remember(song.videoId) { mutableStateOf(CutterState.EDITING) }
    var showConfirm by remember(song.videoId) { mutableStateOf(false) }

    val displayName = remember(song.title) {
        song.title.trim()
            .ifBlank { "audio" }
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(80) + "_edited.m4a"
    }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mp4"),
    ) { destination ->
        if (destination != null) {
            val source = song.toAudioUri()
            if (source == null) {
                cutterState = CutterState.ERROR
            } else {
                scope.launch {
                    cutterState = CutterState.EXPORTING
                    try {
                        AudioCutterExporter.export(
                            context = context,
                            source = source,
                            destination = destination,
                            startMs = selectedRange.start.toLong(),
                            endMs = selectedRange.endInclusive.toLong(),
                        )
                        cutterState = CutterState.COMPLETE
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        cutterState = CutterState.ERROR
                    }
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(song.localUri, song.localPath, fallbackDuration) {
        if (fallbackDuration > 0L) return@LaunchedEffect
        durationMs = readDurationMs(context, song) ?: 0L
    }

    val isExporting = cutterState == CutterState.EXPORTING
    ModalBottomSheet(
        onDismissRequest = { if (!isExporting) onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            }
            Text(
                stringResource(R.string.audio_cutter_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (durationMs <= 0L) {
                Text(
                    stringResource(R.string.audio_cutter_duration_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(
                        R.string.audio_cutter_range,
                        formatTime(selectedRange.start.toLong()),
                        formatTime(selectedRange.endInclusive.toLong()),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                RangeSlider(
                    value = selectedRange,
                    onValueChange = { selectedRange = it },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = !isExporting,
                )
                if (selectedRange.endInclusive - selectedRange.start < MINIMUM_CLIP_MS) {
                    Text(
                        text = stringResource(R.string.audio_cutter_minimum_range),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (isExporting) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(
                    text = stringResource(R.string.audio_cutter_exporting),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FilledTonalButton(
                    onClick = { showConfirm = true },
                    enabled = durationMs > 0L && selectedRange.endInclusive - selectedRange.start >= MINIMUM_CLIP_MS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.audio_cutter_export))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.audio_cutter_confirm_title)) },
            text = { Text(stringResource(R.string.audio_cutter_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        createDocument.launch(displayName)
                    },
                ) { Text(stringResource(R.string.audio_cutter_export)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (cutterState == CutterState.COMPLETE) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.audio_cutter_complete_title)) },
            text = { Text(stringResource(R.string.audio_cutter_complete_message)) },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.done)) }
            },
        )
    } else if (cutterState == CutterState.ERROR) {
        AlertDialog(
            onDismissRequest = { cutterState = CutterState.EDITING },
            title = { Text(stringResource(R.string.audio_cutter_error_title)) },
            text = { Text(stringResource(R.string.audio_cutter_error_message)) },
            confirmButton = {
                TextButton(onClick = { cutterState = CutterState.EDITING }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
}

private fun Song.toAudioUri(): Uri? = runCatching {
    localUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
        ?: localPath?.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)) }
}.getOrNull()

private suspend fun readDurationMs(context: android.content.Context, song: Song): Long? =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = song.toAudioUri() ?: return@withContext null
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: return@withContext null)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, seconds)
    }
}

private const val MINIMUM_CLIP_MS = 1_000f
