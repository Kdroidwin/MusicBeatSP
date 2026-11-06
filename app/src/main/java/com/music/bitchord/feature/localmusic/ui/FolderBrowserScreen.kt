package com.music.bitchord.feature.localmusic.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.localmusic.domain.FolderNode
import com.music.bitchord.feature.localmusic.domain.FolderTreeBuilder
import com.music.bitchord.feature.localmusic.data.ZipAudioArchive
import com.music.bitchord.feature.localmusic.data.ZipAudioEntry
import com.music.bitchord.feature.localmusic.data.AppleMusicAutoRename
import com.music.bitchord.feature.localmusic.data.AutoRenameRow
import com.music.bitchord.feature.localsongactions.ui.components.LocalAddToPlaylistSheet
import com.music.bitchord.ui.components.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FolderPathStackSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

private fun folderPathStack(root: FolderNode, targetPath: String): List<String>? {
    fun normalized(path: String) = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
    val target = normalized(targetPath)
    if (normalized(root.path) == target) return emptyList()

    fun find(node: FolderNode, ancestors: List<String>): List<String>? {
        node.subfolders.forEach { child ->
            val path = ancestors + child.path
            if (normalized(child.path) == target) return path
            find(child, path)?.let { return it }
        }
        return null
    }
    return find(root, emptyList())
}

/** Hierarchical browser for the device's indexed local music folders. */
@Composable
fun FolderBrowserScreen(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    contentPadding: PaddingValues,
    customRootPath: String? = null,
    rememberLastOpenedFolder: Boolean = true,
    lastOpenedFolderPath: String = "",
    onLastOpenedFolderPathChange: ((String) -> Unit)? = null,
    onArtworkPaletteChanged: ((com.music.bitchord.ui.theme.ArtworkPalette?) -> Unit)? = null,
    onNavigationChanged: ((Boolean, (() -> Unit)?) -> Unit)? = null,
    onLocalFilesChanged: (() -> Unit)? = null,
    onBatchTagEditor: ((List<Song>, List<Song>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zipEntries by remember { mutableStateOf<List<ZipAudioEntry>?>(null) }
    var zipArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var zipArchiveName by remember { mutableStateOf("") }
    var isOpeningZipTrack by remember { mutableStateOf(false) }
    var showAppleUrlDialog by remember { mutableStateOf(false) }
    var albumUrl by remember { mutableStateOf("") }
    var appleAlbumTitle by remember { mutableStateOf("") }
    var renameRows by remember { mutableStateOf<List<AutoRenameRow>?>(null) }
    var isPreparingRename by remember { mutableStateOf(false) }
    var isApplyingRename by remember { mutableStateOf(false) }
    var pendingRenamePermissionRows by remember { mutableStateOf<List<AutoRenameRow>?>(null) }
    var undoAvailable by remember { mutableStateOf(AppleMusicAutoRename.hasUndo(context)) }
    fun applyRenameRows(rows: List<AutoRenameRow>) {
        scope.launch {
            isApplyingRename = true
            val result = runCatching { AppleMusicAutoRename.apply(context, rows) }
                .getOrElse { com.music.bitchord.feature.localmusic.data.AutoRenameResult(0, rows.size, it.localizedMessage) }
            isApplyingRename = false
            undoAvailable = AppleMusicAutoRename.hasUndo(context)
            renameRows = null
            val resultText = result.message ?: context.getString(R.string.folder_auto_rename_result, result.changed, result.failed)
            Toast.makeText(context, resultText, Toast.LENGTH_LONG).show()
            if (result.changed > 0) onLocalFilesChanged?.invoke()
        }
    }
    val renameWritePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val rows = pendingRenamePermissionRows
        pendingRenamePermissionRows = null
        if (rows != null) {
            if (result.resultCode == Activity.RESULT_OK) applyRenameRows(rows)
            else Toast.makeText(context, R.string.bulk_tag_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val archiveName = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()
            }
            runCatching {
                withContext(Dispatchers.IO) { ZipAudioArchive.listAudioEntries(context, uri, archiveName) }
            }.onSuccess { entries ->
                if (entries.isEmpty()) {
                    Toast.makeText(context, R.string.folder_zip_no_tracks, Toast.LENGTH_SHORT).show()
                } else {
                    zipArchiveUri = uri
                    zipArchiveName = archiveName
                    zipEntries = entries
                }
            }.onFailure {
                Toast.makeText(context, R.string.folder_zip_open_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val tree = remember(songs, customRootPath) { FolderTreeBuilder.build(songs, customRootPath) }
    val rootKey = customRootPath ?: tree.path
    val pathStack = rememberSaveable(rootKey, saver = FolderPathStackSaver) {
        mutableStateListOf<String>()
    }
    val stackSnapshot = pathStack.toList()
    val currentNode by remember(tree, stackSnapshot) {
        mutableStateOf(stackSnapshot.lastOrNull()?.let(tree::findByPath) ?: tree)
    }
    LaunchedEffect(tree, rememberLastOpenedFolder, lastOpenedFolderPath) {
        if (!rememberLastOpenedFolder || pathStack.isNotEmpty() || lastOpenedFolderPath.isBlank()) {
            return@LaunchedEffect
        }
        val restoredPath = folderPathStack(tree, lastOpenedFolderPath)
        if (!restoredPath.isNullOrEmpty()) pathStack.addAll(restoredPath)
    }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    val selectingSongs = selectedSongIds.isNotEmpty() || selectedFolderPaths.isNotEmpty()
    val selectedSongs = remember(tree, currentNode.directSongs, selectedSongIds, selectedFolderPaths) {
        val folderTracks = selectedFolderPaths
            .asSequence()
            .mapNotNull(tree::findByPath)
            .flatMap { it.allSongs.asSequence() }
        (currentNode.directSongs.filter { (it.localUri ?: it.videoId) in selectedSongIds } + folderTracks)
            .distinctBy { it.localUri ?: it.localPath ?: it.videoId }
    }
    fun toggleSongSelection(song: Song) {
        val key = song.localUri ?: song.videoId
        selectedSongIds = if (key in selectedSongIds) selectedSongIds - key else selectedSongIds + key
    }
    fun toggleFolderSelection(folder: FolderNode) {
        selectedFolderPaths = if (folder.path in selectedFolderPaths) {
            selectedFolderPaths - folder.path
        } else {
            selectedFolderPaths + folder.path
        }
    }
    LaunchedEffect(tree, stackSnapshot) {
        if (stackSnapshot.isNotEmpty() && tree.findByPath(stackSnapshot.last()) == null) {
            pathStack.clear()
        }
    }
    LaunchedEffect(currentNode.path) {
        selectedSongIds = emptySet()
        selectedFolderPaths = emptySet()
    }

    val canGoBack = pathStack.isNotEmpty()
    val latestPathStack = rememberUpdatedState(pathStack)
    val latestTree = rememberUpdatedState(tree)
    val latestRememberLastOpenedFolder = rememberUpdatedState(rememberLastOpenedFolder)
    val latestLastFolderPathChange = rememberUpdatedState(onLastOpenedFolderPathChange)
    val popBack = remember {
        {
            val stack = latestPathStack.value
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.lastIndex)
                if (latestRememberLastOpenedFolder.value) {
                    latestLastFolderPathChange.value?.invoke(stack.lastOrNull() ?: latestTree.value.path)
                }
            }
        }
    }
    val latestNavigationCallback = rememberUpdatedState(onNavigationChanged)
    LaunchedEffect(canGoBack) {
        latestNavigationCallback.value?.invoke(canGoBack, popBack.takeIf { canGoBack })
    }
    DisposableEffect(Unit) {
        onDispose { latestNavigationCallback.value?.invoke(false, null) }
    }
    BackHandler(enabled = canGoBack, onBack = popBack)
    BackHandler(enabled = selectingSongs) {
        selectedSongIds = emptySet()
        selectedFolderPaths = emptySet()
    }
    SideEffect { onArtworkPaletteChanged?.invoke(null) }

    val scrollState = rememberLazyListState()
    LazyColumn(
        state = scrollState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectingSongs) {
            item(key = "folder-selection-toolbar:${currentNode.path}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedFolderPaths.isNotEmpty()) {
                            stringResource(
                                R.string.folder_selection_summary,
                                selectedFolderPaths.size,
                                selectedSongs.size,
                            )
                        } else {
                            "${selectedSongs.size} ${stringResource(R.string.selected)}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(
                        enabled = selectedSongs.isNotEmpty(),
                        onClick = {
                            addToPlaylistSongs = selectedSongs
                            selectedSongIds = emptySet()
                            selectedFolderPaths = emptySet()
                        },
                    ) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add_to_playlist))
                    }
                    IconButton(onClick = {
                        selectedSongIds = emptySet()
                        selectedFolderPaths = emptySet()
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            }
        }
        item(key = "folder-header:${currentNode.path}") {
            FolderHeader(
                node = currentNode,
                rootName = tree.name,
                onPlay = { if (currentNode.allSongs.isNotEmpty()) onSongClick(currentNode.allSongs, 0) },
                onShuffle = { if (currentNode.allSongs.isNotEmpty()) onShuffle(currentNode.allSongs) },
                onAutoRename = { showAppleUrlDialog = true },
                onOpenZip = {
                    zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (currentNode.subfolders.isEmpty() && currentNode.directSongs.isEmpty()) {
            item(key = "folder-empty:${currentNode.path}") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.folder_no_tracks),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(currentNode.subfolders, key = { "folder:${it.path}" }) { folder ->
            FolderEntry(
                folder = folder,
                selected = folder.path in selectedFolderPaths,
                onClick = {
                    if (selectingSongs) {
                        toggleFolderSelection(folder)
                    } else {
                        pathStack.add(folder.path)
                        if (rememberLastOpenedFolder) onLastOpenedFolderPathChange?.invoke(folder.path)
                    }
                },
                onLongPress = { toggleFolderSelection(folder) },
                onBatchTagEditor = if (!selectingSongs && onBatchTagEditor != null && folder.allSongs.isNotEmpty()) {
                    {
                        val ownSongs = folder.directSongs.ifEmpty { folder.allSongs }
                        onBatchTagEditor(ownSongs, folder.allSongs)
                    }
                } else null,
            )
        }

        itemsIndexed(currentNode.directSongs, key = { _, song -> song.localPath ?: song.localUri ?: song.videoId }) { index, song ->
            SongRow(
                song = song,
                isCurrent = currentSong?.isSameFolderTrack(song) == true,
                isPlaying = isPlaying && currentSong?.isSameFolderTrack(song) == true,
                onClick = {
                    if (selectingSongs) toggleSongSelection(song)
                    else onSongClick(currentNode.directSongs, index)
                },
                onLongPress = { toggleSongSelection(song) },
                // Long press selects tracks on this screen; the explicit
                // overflow menu keeps its existing single-track actions.
                onMore = { onSongLongPress(song) },
                selected = (song.localUri ?: song.videoId) in selectedSongIds,
                onSwipeToQueue = if (selectingSongs) null else { { onSongSwipe(song) } },
            )
        }
    }

    if (showAppleUrlDialog) {
        AlertDialog(
            onDismissRequest = { showAppleUrlDialog = false },
            title = { Text(stringResource(R.string.folder_auto_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.folder_auto_rename_instructions))
                    OutlinedTextField(
                        value = albumUrl,
                        onValueChange = { albumUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.folder_auto_rename_apple_url)) },
                    )
                    if (undoAvailable) {
                        TextButton(
                            enabled = !isApplyingRename,
                            onClick = {
                                scope.launch {
                                    isApplyingRename = true
                                    val result = AppleMusicAutoRename.undo(context)
                                    isApplyingRename = false
                                    undoAvailable = AppleMusicAutoRename.hasUndo(context)
                                    Toast.makeText(context, result.message ?: context.getString(R.string.folder_auto_rename_undone, result.changed), Toast.LENGTH_LONG).show()
                                    if (result.changed > 0) onLocalFilesChanged?.invoke()
                                }
                            },
                        ) { Text(stringResource(R.string.folder_auto_rename_undo)) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = albumUrl.isNotBlank(),
                    onClick = {
                        val folderSongs = currentNode.allSongs
                        if (folderSongs.isEmpty()) {
                            Toast.makeText(context, R.string.folder_auto_rename_failed, Toast.LENGTH_LONG).show()
                        } else {
                            val albumUrlToLoad = albumUrl.trim()
                            showAppleUrlDialog = false
                            isPreparingRename = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val album = AppleMusicAutoRename.fetchAlbum(albumUrlToLoad)
                                        album.title to AppleMusicAutoRename.preview(
                                            context.contentResolver,
                                            folderSongs,
                                            album.tracks,
                                            albumTitle = album.title,
                                        )
                                    }
                                }.onSuccess { (title, rows) ->
                                    appleAlbumTitle = title
                                    renameRows = rows
                                }.onFailure {
                                    Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.folder_auto_rename_failed), Toast.LENGTH_LONG).show()
                                }
                                isPreparingRename = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.folder_auto_rename_choose_folder)) }
            },
            dismissButton = { TextButton(onClick = { showAppleUrlDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (isPreparingRename) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.folder_auto_rename)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.folder_auto_rename_loading))
                }
            },
            confirmButton = {},
        )
    }

    renameRows?.let { rows ->
        val unmatched = rows.count { it.track == null || it.issue != null }
        val selectableCount = rows.count { it.track != null && it.issue == null && it.conflict == null }
        AlertDialog(
            onDismissRequest = { if (!isApplyingRename) renameRows = null },
            title = { Text(if (appleAlbumTitle.isBlank()) stringResource(R.string.folder_auto_rename_preview) else appleAlbumTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.folder_auto_rename_preview_summary, rows.size, unmatched))
                    if (unmatched > 0) {
                        Text(stringResource(R.string.folder_auto_rename_unmatched), color = MaterialTheme.colorScheme.error)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(rows, key = { _, row -> row.documentId }) { index, row ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = row.selected,
                                    enabled = !isApplyingRename && row.track != null && row.issue == null && row.conflict == null,
                                    onCheckedChange = { checked ->
                                        renameRows = rows.toMutableList().also { it[index] = row.copy(selected = checked) }
                                    },
                                )
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(
                                        text = row.newName?.let { "${row.oldName}  →  $it" } ?: row.oldName,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(row.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    row.track?.let { matched ->
                                        val sourceNumber = row.sourceTrackNumber?.let {
                                            row.sourceDiscNumber?.let { disc -> "$disc-" }.orEmpty() + it
                                        } ?: stringResource(R.string.folder_auto_rename_source_audio_tags)
                                        Text(
                                            stringResource(
                                                R.string.folder_auto_rename_match,
                                                sourceNumber,
                                                matched.disc,
                                                matched.track,
                                                matched.title,
                                                matched.artist,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    (row.issue ?: row.conflict)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isApplyingRename && unmatched == 0 && selectableCount > 0 && rows.any { it.selected },
                    onClick = {
                        val mediaStoreUris = rows.asSequence()
                            .filter { it.selected }
                            .map { it.uri }
                            .filter { it.authority == MediaStore.AUTHORITY }
                            .distinct()
                            .toList()
                        val needsApproval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            mediaStoreUris.filter { uri ->
                                context.checkUriPermission(
                                    uri,
                                    android.os.Process.myPid(),
                                    android.os.Process.myUid(),
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                ) != PackageManager.PERMISSION_GRANTED
                            }
                        } else emptyList()
                        if (needsApproval.isEmpty()) {
                            applyRenameRows(rows)
                        } else {
                            runCatching { MediaStore.createWriteRequest(context.contentResolver, needsApproval) }
                                .onSuccess { request ->
                                    pendingRenamePermissionRows = rows
                                    renameWritePermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                }
                                .onFailure {
                                    Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.bulk_tag_permission_denied), Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                ) {
                    if (isApplyingRename) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text(stringResource(R.string.folder_auto_rename_apply))
                }
            },
            dismissButton = {
                Row {
                    if (undoAvailable) {
                        TextButton(
                            enabled = !isApplyingRename,
                            onClick = {
                                scope.launch {
                                    isApplyingRename = true
                                    val result = AppleMusicAutoRename.undo(context)
                                    isApplyingRename = false
                                    undoAvailable = AppleMusicAutoRename.hasUndo(context)
                                    renameRows = null
                                    Toast.makeText(context, result.message ?: context.getString(R.string.folder_auto_rename_undone, result.changed), Toast.LENGTH_LONG).show()
                                    if (result.changed > 0) onLocalFilesChanged?.invoke()
                                }
                            },
                        ) { Text(stringResource(R.string.folder_auto_rename_undo)) }
                    }
                    TextButton(enabled = !isApplyingRename, onClick = { renameRows = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    addToPlaylistSongs?.let { selected ->
        LocalAddToPlaylistSheet(
            songs = selected,
            onDismissRequest = { addToPlaylistSongs = null },
        )
    }

    zipEntries?.let { entries ->
        AlertDialog(
            onDismissRequest = { if (!isOpeningZipTrack) zipEntries = null },
            title = { Text(stringResource(R.string.folder_zip_tracks, zipArchiveName)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(entries, key = { index, entry -> "$index:${entry.path}" }) { _, entry ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isOpeningZipTrack,
                            onClick = {
                                val archiveUri = zipArchiveUri ?: return@TextButton
                                scope.launch {
                                    isOpeningZipTrack = true
                                    val audio = runCatching {
                                        withContext(Dispatchers.IO) {
                                            ZipAudioArchive.extract(context, archiveUri, entry)
                                        }
                                    }.getOrNull()
                                    isOpeningZipTrack = false
                                    if (audio == null) {
                                        Toast.makeText(context, R.string.folder_zip_extract_failed, Toast.LENGTH_LONG).show()
                                    } else {
                                        zipEntries = null
                                        onSongClick(listOf(audio), 0)
                                    }
                                }
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    entry.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { zipEntries = null }, enabled = !isOpeningZipTrack) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun FolderHeader(
    node: FolderNode,
    rootName: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAutoRename: () -> Unit,
    onOpenZip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackCount = pluralStringResource(R.plurals.track_count_plural, node.totalSongCount, node.totalSongCount)
    val folderCount = if (node.subfolders.isNotEmpty()) stringResource(R.string.folder_count, node.subfolders.size) else null
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (node.relativePath.isBlank()) rootName else node.relativePath.replace('/', '›'),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(trackCount, folderCount).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (node.totalSongCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(onClick = onShuffle, enabled = node.totalSongCount > 1) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shuffle))
                }
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play))
                }
                FilledTonalButton(onClick = onAutoRename) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.folder_auto_rename))
                }
            }
        }
        OutlinedButton(onClick = onOpenZip, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.folder_open_zip))
        }
    }
}

@Composable
private fun FolderEntry(
    folder: FolderNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBatchTagEditor: (() -> Unit)? = null,
) {
    val trackCount = pluralStringResource(R.plurals.track_count_plural, folder.totalSongCount, folder.totalSongCount)
    val subfolderCount = if (folder.subfolders.isNotEmpty()) {
        stringResource(R.string.folder_subfolder_count, folder.subfolders.size)
    } else null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                folder.coverSongs.firstOrNull()?.thumbnailUrl?.let { artwork ->
                    AsyncImage(
                        model = artwork.artworkAt(ROW_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(trackCount, subfolderCount).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onBatchTagEditor != null) {
                var menuExpanded by remember(folder.path) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.folder_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_batch_tags)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onBatchTagEditor()
                            },
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun Song.isSameFolderTrack(other: Song): Boolean =
    if (!localPath.isNullOrBlank() && !other.localPath.isNullOrBlank()) {
        localPath.equals(other.localPath, ignoreCase = true)
    } else if (!localUri.isNullOrBlank() && !other.localUri.isNullOrBlank()) {
        localUri == other.localUri
    } else {
        videoId == other.videoId
    }
