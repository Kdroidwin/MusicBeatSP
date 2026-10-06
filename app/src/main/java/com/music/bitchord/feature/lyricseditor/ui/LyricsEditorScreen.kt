package com.music.bitchord.feature.lyricseditor.ui

import android.app.Activity
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.data.model.Song
import com.music.bitchord.R
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsEditorSource
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsDownloadDialog
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsDownloadSheet
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsEditorBottomBar
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsEditorHeader
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsEditorTopBar
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsSearchResultsDialog
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsSearchResultsSheet
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsSelectorDialog
import com.music.bitchord.feature.lyricseditor.ui.components.LyricsSourcePillSelector
import com.music.bitchord.feature.lyricseditor.ui.viewmodel.LyricsEditorViewModel
import androidx.compose.ui.graphics.luminance
import com.music.bitchord.ui.theme.SystemBarIcons

@Composable
fun LyricsEditorScreen(
    song: Song,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LyricsEditorViewModel = viewModel(),
    onLyricsSaved: (() -> Unit)? = null,
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
    playbackIsPlaying: Boolean = false,
    onSyncPlaybackToggle: (() -> Unit)? = null,
    onSyncSeek: ((Long) -> Unit)? = null,
    onSyncPrevious: (() -> Unit)? = null,
    onSyncNext: (() -> Unit)? = null,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SystemBarIcons(dark = !isDarkTheme)

    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    val selectedSource by viewModel.selectedSource.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val candidateResult by viewModel.candidateResult.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchingResults by viewModel.isSearchingResults.collectAsState()

    var showDownloadDialog by remember { mutableStateOf(false) }
    var showSearchResultsDialog by remember { mutableStateOf(false) }
    var syncMode by remember(song.videoId) { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(currentText)) }

    val lineCount = remember(currentText) {
        currentText.lineSequence().filter { it.isNotBlank() }.count()
    }
    val syncLines = remember(textFieldValue.text) {
        textFieldValue.text.split('\n').mapIndexed { index, raw ->
            val trimmed = raw.trim()
            val timestamp = SYNC_STAMP.find(trimmed)?.value.orEmpty()
            val lyric = trimmed.replace(SYNC_PREFIX, "").trim()
            SyncEditorLine(
                index = index,
                raw = raw,
                lyric = lyric,
                timestamp = timestamp.removePrefix("[").removeSuffix("]"),
                isMetadata = lyric.isBlank() || lyric.startsWith("#") || lyric.matches(SYNC_METADATA),
            )
        }
    }
    val isTimeSynced = remember(currentText) {
        currentText.contains(Regex("""\[\d{1,3}:\d{2}"""))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.saveLyrics(song)
        } else {
            Toast.makeText(context, "Permission denied to edit audio file", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleSave() {
        if (selectedSource == LyricsEditorSource.Embedded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uriStr = song.localUri ?: song.videoId
            val uri = if (uriStr.startsWith("content://")) Uri.parse(uriStr) else null
            if (uri != null) {
                val hasPerm = context.checkUriPermission(
                    uri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    val pendingIntent = runCatching {
                        MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                    }.getOrNull()
                    if (pendingIntent != null) {
                        permissionLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                        return
                    }
                }
            }
        }
        viewModel.saveLyrics(song)
    }

    // Sync external currentText changes into TextFieldValue
    LaunchedEffect(currentText) {
        if (textFieldValue.text != currentText) {
            textFieldValue = TextFieldValue(
                text = currentText,
                selection = TextRange(currentText.length),
            )
        }
    }

    LaunchedEffect(song.videoId) {
        viewModel.loadSong(song)
    }

    LaunchedEffect(Unit) {
        viewModel.saveResult.collect { success ->
            val message = if (success) "Lyrics saved successfully" else "Failed to save lyrics"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (success) {
                onLyricsSaved?.invoke()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val isFileSource = selectedSource == LyricsEditorSource.File

    Scaffold(
        topBar = {
            LyricsEditorTopBar(
                onBackClick = onBackClick,
                actions = {
                    if (syncMode) {
                        IconButton(onClick = { handleSave() }, enabled = !isLoading && !isSaving) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.lyrics_sync_save),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (syncMode) {
                SyncPlaybackBar(
                    positionMs = playbackPositionMs,
                    durationMs = playbackDurationMs,
                    isPlaying = playbackIsPlaying,
                    enabled = !isLoading && !isSaving,
                    onPlayPause = { onSyncPlaybackToggle?.invoke() },
                    onPrevious = { onSyncPrevious?.invoke() },
                    onNext = { onSyncNext?.invoke() },
                    onSeek = { onSyncSeek?.invoke(it) },
                )
            } else LyricsEditorBottomBar(
                enabled = !isLoading && !isSaving,
                onSearchClick = {
                    val query = "${song.title} ${song.artist} lyrics".trim()
                    runCatching {
                        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, query)
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        val fallback = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                        )
                        context.startActivity(fallback)
                    }
                },
                onDownloadClick = {
                    if (searchResults.isNotEmpty()) {
                        showSearchResultsDialog = true
                    } else {
                        showDownloadDialog = true
                    }
                },
                onSaveClick = {
                    handleSave()
                },
                onPasteClick = {
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val pasteText = clip.getItemAt(0).text?.toString().orEmpty()
                        if (pasteText.isNotBlank()) {
                            textFieldValue = TextFieldValue(pasteText, selection = TextRange(pasteText.length))
                            viewModel.updateText(pasteText)
                            Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSelectAllClick = {
                    textFieldValue = textFieldValue.copy(
                        selection = TextRange(0, textFieldValue.text.length)
                    )
                },
                onUndoChangesClick = {
                    viewModel.undoChanges()
                    Toast.makeText(context, "Changes undone", Toast.LENGTH_SHORT).show()
                },
                onInsertTimestampClick = {
                    val current = textFieldValue.text
                    val cursor = textFieldValue.selection.start.coerceIn(0, current.length)
                    val before = current.substring(0, cursor)
                    val after = current.substring(cursor)
                    val insert = if (before.isNotEmpty() && !before.endsWith("\n")) "\n[00:00.00]" else "[00:00.00]"
                    val newText = before + insert + after
                    val newCursor = cursor + insert.length
                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                    viewModel.updateText(newText)
                },
                onClearAllClick = {
                    textFieldValue = TextFieldValue("")
                    viewModel.updateText("")
                    Toast.makeText(context, "Cleared lyrics", Toast.LENGTH_SHORT).show()
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.imePadding(),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row {
                        LyricsEditorModeButton(
                            text = stringResource(R.string.lyrics_sync_simple),
                            selected = !syncMode,
                            enabled = !isLoading && !isSaving,
                            onClick = { syncMode = false },
                        )
                        LyricsEditorModeButton(
                            text = stringResource(R.string.lyrics_sync_mode),
                            selected = syncMode,
                            enabled = !isLoading && !isSaving,
                            onClick = { syncMode = true },
                        )
                    }
                }
            }

            if (!syncMode) {
                LyricsEditorHeader(
                    song = song,
                    isLoading = isLoading,
                    lineCount = lineCount,
                    isTimeSynced = isTimeSynced,
                )

                LyricsSourcePillSelector(
                    selectedSource = selectedSource,
                    onSourceSelected = { source -> viewModel.selectSource(source) },
                    enabled = !isLoading && !isSaving,
                )
            }

            AnimatedVisibility(
                visible = isFileSource && !syncMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Companion .lrc file will be saved alongside the audio file",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Modern Studio Lyrics Text Box
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Studio status bar
                    if (!syncMode) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "LRC Studio",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp,
                                ),
                            )
                        }

                        Text(
                            text = "$lineCount lines • ${textFieldValue.text.length} chars",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 1.dp,
                    )

                    if (syncMode) {
                        Column(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(syncLines, key = { _, line -> line.index }) { _, line ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !line.isMetadata && !isSaving) {
                                                val stamped = stampLrcLine(line.raw, playbackPositionMs)
                                                val updatedLines = textFieldValue.text.split('\n').toMutableList()
                                                if (line.index in updatedLines.indices) {
                                                    updatedLines[line.index] = stamped
                                                    val updatedText = updatedLines.joinToString("\n")
                                                    textFieldValue = TextFieldValue(updatedText, TextRange(updatedText.length))
                                                    viewModel.updateText(updatedText)
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Sync,
                                            contentDescription = null,
                                            tint = if (line.timestamp.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = line.timestamp.ifBlank { "--:--.--" },
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                            color = if (line.timestamp.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                        Text(
                                            text = line.lyric.ifBlank { stringResource(R.string.lyrics_sync_not_lyric) },
                                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (line.isMetadata) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        IconButton(
                                            onClick = {
                                                val timestampMs = parseLrcTime(line.timestamp)
                                                if (timestampMs != null) onSyncSeek?.invoke(timestampMs)
                                            },
                                            enabled = line.timestamp.isNotBlank() && onSyncSeek != null,
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = stringResource(R.string.lyrics_sync_preview_line),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
                                }
                            }
                        }
                    } else {
                        TextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                textFieldValue = newValue
                                viewModel.updateText(newValue.text)
                            },
                            placeholder = {
                                Text(
                                    text = "Write or paste lyrics here...\n\nExample:\n[00:12.30]First line of lyrics\n[00:15.80]Second line of lyrics",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 22.sp,
                                        fontSize = 13.5.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.5.sp,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showDownloadDialog) {
        LyricsDownloadSheet(
            initialTitle = song.title,
            initialArtist = song.artist,
            initialAlbum = song.albumName,
            hasPreviousResults = searchResults.isNotEmpty(),
            previousResultsCount = searchResults.size,
            onViewPreviousResultsClick = {
                showDownloadDialog = false
                showSearchResultsDialog = true
            },
            onDismissRequest = { showDownloadDialog = false },
            onAutoDownloadClick = { title, artist, album, providers ->
                showDownloadDialog = false
                viewModel.autoDownload(title, artist, album, providers)
            },
            onSearchAllClick = { title, artist, album, providers ->
                showDownloadDialog = false
                showSearchResultsDialog = true
                viewModel.searchAllProviders(title, artist, album, providers)
            },
        )
    }

    if (showSearchResultsDialog) {
        LyricsSearchResultsSheet(
            results = searchResults,
            isSearching = isSearchingResults,
            onDismissRequest = {
                showSearchResultsDialog = false
            },
            onResultSelected = { item ->
                showSearchResultsDialog = false
                viewModel.applySearchResult(item)
            },
            onRefineSearchClick = {
                showSearchResultsDialog = false
                showDownloadDialog = true
            },
            onResearchClick = {
                viewModel.research()
            },
        )
    }

    if (candidateResult != null) {
        LyricsSelectorDialog(
            onDismissRequest = { viewModel.dismissCandidateDialog() },
            onModeSelected = { mode ->
                viewModel.applyCandidateSelection(mode)
            },
        )
    }
}

private data class SyncEditorLine(
    val index: Int,
    val raw: String,
    val lyric: String,
    val timestamp: String,
    val isMetadata: Boolean,
)

@Composable
private fun LyricsEditorModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun SyncPlaybackBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val canControlTrack = enabled && durationMs > 0L
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember(durationMs) {
        mutableStateOf(if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f)
    }
    val displayedFraction = if (isScrubbing) scrubValue else {
        if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Slider(
            value = displayedFraction,
            onValueChange = { fraction ->
                scrubValue = fraction
                isScrubbing = true
            },
            onValueChangeFinished = {
                if (durationMs > 0L) onSeek((scrubValue * durationMs).toLong())
                isScrubbing = false
            },
            enabled = canControlTrack,
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onPlayPause, enabled = enabled) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.lyrics_sync_pause else R.string.lyrics_sync_play),
                    modifier = Modifier.size(27.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = canControlTrack) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.lyrics_sync_previous))
            }
            IconButton(onClick = onNext, enabled = canControlTrack) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.lyrics_sync_next))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatLrcTime(positionMs)} / ${formatLrcTime(durationMs)}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SYNC_STAMP = Regex("""\[\d{1,3}:\d{2}[.:]\d{2,3}]""")
private val SYNC_PREFIX = Regex("""^(?:\[\d{1,3}:\d{2}(?:[.:]\d{2,3})?])+""")
private val SYNC_METADATA = Regex("""\[[A-Za-z]{2,}:.*]""")

private fun formatLrcTime(positionMs: Long): String {
    val centiseconds = positionMs.coerceAtLeast(0L) / 10L
    val minutes = centiseconds / 6_000L
    val seconds = (centiseconds / 100L) % 60L
    val fraction = centiseconds % 100L
    return String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, fraction)
}

private fun parseLrcTime(timestamp: String): Long? {
    val parts = timestamp.replace('.', ':').split(':')
    if (parts.size != 3) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toLongOrNull() ?: return null
    val fraction = parts[2].toLongOrNull() ?: return null
    val fractionMs = if (parts[2].length <= 2) fraction * 10L else fraction
    return (minutes * 60_000L + seconds.coerceIn(0L, 59L) * 1_000L + fractionMs.coerceIn(0L, 999L))
        .coerceAtLeast(0L)
}

private fun stampLrcLine(raw: String, positionMs: Long): String {
    val body = raw.replace(SYNC_PREFIX, "").trimStart()
    return "[${formatLrcTime(positionMs)}]$body"
}
