package com.music.bitchord.feature.tageditor.ui

import android.app.SearchManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.tageditor.ui.components.TagEditorArtworkSection
import com.music.bitchord.feature.tageditor.ui.components.TagEditorFormFields
import com.music.bitchord.feature.tageditor.ui.components.TagEditorFolderSection
import com.music.bitchord.feature.tageditor.ui.components.TagEditorGenreDialog
import com.music.bitchord.feature.tageditor.ui.components.TagEditorImageOptionsDialog
import com.music.bitchord.feature.tageditor.ui.components.TagEditorSaveFab
import com.music.bitchord.feature.tageditor.ui.components.TagEditorTopBar
import com.music.bitchord.feature.tageditor.ui.viewmodel.TagEditorViewModel
import com.music.bitchord.feature.tageditor.domain.model.TagField
import androidx.compose.ui.graphics.luminance
import com.music.bitchord.ui.theme.SystemBarIcons

@Composable
fun TagEditorScreen(
    song: Song,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TagEditorViewModel = viewModel(),
    onTagsSaved: ((com.music.bitchord.feature.tageditor.domain.model.SongTagData) -> Unit)? = null,
    batchSongs: List<Song>? = null,
    batchSongsWithSubfolders: List<Song>? = null,
    onBatchTagsSaved: ((List<Song>, com.music.bitchord.feature.tageditor.domain.model.SongTagData, Set<TagField>) -> Unit)? = null,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SystemBarIcons(dark = !isDarkTheme)

    val context = LocalContext.current
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    val mixed by viewModel.mixed.collectAsStateWithLifecycle()
    val batchLoadFailed by viewModel.batchLoadFailed.collectAsStateWithLifecycle()
    val includeSubfolders by viewModel.includeSubfolders.collectAsStateWithLifecycle()
    val toggledFields by viewModel.toggledFields.collectAsStateWithLifecycle()
    var confirmTargets by remember { mutableStateOf<List<Song>?>(null) }
    var pendingPermissionTargets by remember { mutableStateOf<List<Song>?>(null) }
    var pendingPermissionUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var approvedPermissionUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requestWritePermission: (List<Song>) -> Unit = {}

    var showImageOptions by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.updateArtworkFromUri(uri)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val targets = pendingPermissionTargets
        if (result.resultCode == android.app.Activity.RESULT_OK && targets != null) {
            approvedPermissionUris = approvedPermissionUris + pendingPermissionUris.map { it.toString() }
            pendingPermissionTargets = null
            pendingPermissionUris = emptyList()
            requestWritePermission(targets)
        } else {
            pendingPermissionTargets = null
            pendingPermissionUris = emptyList()
            Toast.makeText(context, R.string.tag_editor_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    requestWritePermission = fun(targets: List<Song>) {
        val uniqueTargets = targets.distinctBy {
            it.localUri?.takeIf(String::isNotBlank) ?: it.localPath?.takeIf(String::isNotBlank) ?: it.videoId
        }
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            val mediaUris = uniqueTargets.mapNotNull { target ->
                val raw = target.localUri ?: target.videoId
                if (!raw.startsWith("content://")) return@mapNotNull null
                runCatching { android.net.Uri.parse(raw) }.getOrNull()
                    ?.takeIf { it.authority == android.provider.MediaStore.AUTHORITY }
            }.distinct()
            val needsPermission = mediaUris.filter { uri ->
                uri.toString() !in approvedPermissionUris && context.checkUriPermission(
                    uri,
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needsPermission.isNotEmpty()) {
                // Android limits one MediaStore write request to 2,000 items.
                val chunk = needsPermission.take(MAX_MEDIASTORE_WRITE_REQUEST_URIS)
                val pendingIntent = runCatching {
                    android.provider.MediaStore.createWriteRequest(context.contentResolver, chunk)
                }.getOrNull()
                if (pendingIntent != null) {
                    pendingPermissionTargets = uniqueTargets
                    pendingPermissionUris = chunk
                    permissionLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                    )
                } else {
                    Toast.makeText(context, R.string.tag_editor_permission_denied, Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        viewModel.save(song)
    }

    fun handleSave() {
        val targets = viewModel.allTargets(song)
        if (targets.size > 1) confirmTargets = targets else requestWritePermission(targets)
    }

    LaunchedEffect(song, batchSongs, batchSongsWithSubfolders) {
        if (batchSongs != null) viewModel.loadBatch(batchSongs, batchSongsWithSubfolders ?: batchSongs)
        else viewModel.loadTags(song)
    }

    LaunchedEffect(Unit) {
        viewModel.saveEvent.collect { result ->
            val message = when {
                result.total == 1 && result.success -> context.getString(R.string.tag_editor_saved)
                result.total > 1 && result.success -> context.getString(R.string.tag_result_all, result.written)
                result.total > 1 -> context.getString(
                    R.string.tag_result_some,
                    result.written,
                    result.total,
                    result.failedTitles.joinToString("、").ifBlank { "—" },
                )
                else -> context.getString(R.string.tag_editor_save_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (result.written > 0) {
                if (batch != null && onBatchTagsSaved != null) {
                    onBatchTagsSaved.invoke(result.writtenSongs, viewModel.tags.value, viewModel.fieldsForBatch())
                } else {
                    onTagsSaved?.invoke(viewModel.tags.value)
                }
            }
            if (result.success && result.total > 0) onNavigateBack()
        }
    }

    LaunchedEffect(batchLoadFailed) {
        if (batchLoadFailed) {
            Toast.makeText(context, R.string.tag_batch_read_failed, Toast.LENGTH_LONG).show()
            onNavigateBack()
        }
    }

    confirmTargets?.let { targets ->
        val fields = viewModel.fieldsForBatch().map { context.getString(it.label) }.joinToString("、")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmTargets = null },
            title = { Text(stringResource(R.string.tag_confirm_title, targets.size)) },
            text = { Text(stringResource(R.string.tag_confirm_body, fields.ifBlank { "—" })) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmTargets = null
                    requestWritePermission(targets)
                }) { Text(stringResource(R.string.tag_write)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmTargets = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    fun openWebSearch(query: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
            }
            context.startActivity(intent)
        }
    }

    Scaffold(
        topBar = {
            TagEditorTopBar(
                onBackClick = onNavigateBack,
                onSearchOnlineImage = {
                    openWebSearch("${tags.artist} ${tags.album.ifEmpty { tags.title }} album cover")
                },
                onResetTags = { viewModel.resetTags() },
            )
        },
        floatingActionButton = {
            TagEditorSaveFab(
                isSaving = isSaving,
                onSaveClick = { handleSave() },
                enabled = !isLoading && (batch == null || viewModel.fieldsForBatch().isNotEmpty()),
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp),
            ) {
                item {
                    TagEditorArtworkSection(
                        artworkBitmap = tags.artworkBitmap,
                        thumbnailUrl = song.thumbnailUrl,
                        onClick = { showImageOptions = true },
                    )
                }

                item {
                    TagEditorFormFields(
                        tags = tags,
                        onTagsChange = { viewModel.updateTags(it) },
                        onOpenGenrePicker = { showGenreDialog = true },
                        mixed = mixed,
                    )
                }

                if (batch != null) {
                    item {
                        val changed = viewModel.changedFields()
                        TagEditorFolderSection(
                            songCount = batch!!.direct.size,
                            subfolderSongCount = batch!!.withSubfolders.size,
                            includeSubfolders = includeSubfolders,
                            onIncludeSubfoldersChange = { viewModel.includeSubfolders.value = it },
                            changed = changed,
                            excluded = viewModel.excludedFields(changed, toggledFields),
                            onToggleField = viewModel::toggleField,
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
    }

    if (showImageOptions) {
        TagEditorImageOptionsDialog(
            onDismissRequest = { showImageOptions = false },
            onPickFromStorage = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onSearchOnline = {
                openWebSearch("${tags.artist} ${tags.album.ifEmpty { tags.title }} album cover")
            },
            onRestoreDefault = { viewModel.restoreArtwork() },
            onRemoveCover = { viewModel.deleteArtwork() },
        )
    }

    if (showGenreDialog) {
        TagEditorGenreDialog(
            onDismissRequest = { showGenreDialog = false },
            onGenreSelected = { selected ->
                val current = tags.genre.trim()
                val updated = if (current.isEmpty()) selected else "$current; $selected"
                viewModel.updateTags(tags.copy(genre = updated))
            },
        )
    }
}

private const val MAX_MEDIASTORE_WRITE_REQUEST_URIS = 2_000
