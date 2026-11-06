package com.music.bitchord.feature.tageditor.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import com.music.bitchord.feature.tageditor.domain.model.SongTagData
import com.music.bitchord.feature.tageditor.domain.model.TagField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Folder scope used by the batch editor. */
data class FolderScope(val direct: List<Song>, val withSubfolders: List<Song>)

/** Summary of a tag write, including partial failure information. */
data class TagSaveResult(
    val total: Int,
    val written: Int,
    val failedTitles: List<String>,
    val writtenSongs: List<Song> = emptyList(),
) {
    val success: Boolean get() = written == total
}

class TagEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _tags = MutableStateFlow(SongTagData())
    val tags: StateFlow<SongTagData> = _tags.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveEvent = MutableSharedFlow<TagSaveResult>()
    val saveEvent: SharedFlow<TagSaveResult> = _saveEvent.asSharedFlow()

    private val _batch = MutableStateFlow<FolderScope?>(null)
    val batch: StateFlow<FolderScope?> = _batch.asStateFlow()

    private val _mixed = MutableStateFlow<Set<TagField>>(emptySet())
    val mixed: StateFlow<Set<TagField>> = _mixed.asStateFlow()

    private val _batchLoadFailed = MutableStateFlow(false)
    val batchLoadFailed: StateFlow<Boolean> = _batchLoadFailed.asStateFlow()

    val includeSubfolders = MutableStateFlow(false)
    val toggledFields = MutableStateFlow<Set<TagField>>(emptySet())

    private var initialTags = SongTagData()
    private var batchLoadJob: kotlinx.coroutines.Job? = null

    fun loadTags(song: Song) {
        batchLoadJob?.cancel()
        _batch.value = null
        _mixed.value = emptySet()
        _batchLoadFailed.value = false
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val data = TagLibWriter.readTags(getApplication(), song)
                initialTags = data
                _tags.value = data
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Load one folder's tracks, reading no cover images to keep large batches bounded. */
    fun loadBatch(directSongs: List<Song>, allSongs: List<Song>) {
        batchLoadJob?.cancel()
        val direct = directSongs.distinctBy(::songKey)
        val all = allSongs.distinctBy(::songKey).ifEmpty { direct }
        if (direct.isEmpty()) {
            _batch.value = null
            _batchLoadFailed.value = true
            return
        }

        _batch.value = FolderScope(direct, all)
        includeSubfolders.value = false
        toggledFields.value = emptySet()
        _mixed.value = emptySet()
        _batchLoadFailed.value = false
        batchLoadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val (mergedTags, differingFields) = withContext(Dispatchers.IO) {
                    val firstTags = TagLibWriter.readTags(
                        getApplication(),
                        direct.first(),
                        readArtwork = false,
                        strict = true,
                    )
                    var merged = firstTags
                    val differing = linkedSetOf<TagField>()
                    for (song in direct.drop(1)) {
                        val tags = TagLibWriter.readTags(
                            getApplication(),
                            song,
                            readArtwork = false,
                            strict = true,
                        )
                        for (field in TagField.entries) {
                            if (field == TagField.ARTWORK || field in differing) continue
                            if (field.get(firstTags).trim() != field.get(tags).trim()) {
                                differing += field
                                merged = field.set(merged, "")
                            }
                        }
                    }
                    merged to differing
                }
                initialTags = mergedTags.copy(artworkChanged = false, artworkDeleted = false)
                _tags.value = initialTags
                _mixed.value = differingFields
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A batch only proceeds after every current tag has been read;
                // otherwise a fallback value could overwrite real metadata.
                _batch.value = null
                _batchLoadFailed.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateTags(newTags: SongTagData) {
        _tags.value = newTags
    }

    fun toggleField(field: TagField) {
        toggledFields.value = if (field in toggledFields.value) {
            toggledFields.value - field
        } else {
            toggledFields.value + field
        }
    }

    fun updateArtworkFromUri(uri: Uri) {
        viewModelScope.launch {
            val isBatch = _batch.value != null
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    val maxDimension = if (isBatch) 1600 else 4096
                    var sampleSize = 1
                    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
                        sampleSize *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull()
            }
            if (bitmap != null) {
                _tags.value = _tags.value.copy(
                    artworkBitmap = bitmap,
                    artworkChanged = true,
                    artworkDeleted = false,
                )
            }
        }
    }

    fun deleteArtwork() {
        _tags.value = _tags.value.copy(artworkBitmap = null, artworkChanged = true, artworkDeleted = true)
    }

    fun restoreArtwork() {
        _tags.value = _tags.value.copy(
            artworkBitmap = initialTags.artworkBitmap,
            artworkChanged = false,
            artworkDeleted = false,
        )
    }

    fun resetTags() {
        _tags.value = initialTags
    }

    fun changedFields(): Set<TagField> = TagField.changed(initialTags, _tags.value)

    fun excludedFields(changed: Set<TagField> = changedFields(), toggled: Set<TagField> = toggledFields.value): Set<TagField> =
        changed.filterTo(LinkedHashSet()) { field -> field.perSong xor (field in toggled) }

    fun fieldsForBatch(): Set<TagField> {
        val changed = changedFields()
        return changed - excludedFields(changed)
    }

    fun targetSongs(): List<Song> = _batch.value?.let { scope ->
        if (includeSubfolders.value) scope.withSubfolders else scope.direct
    }.orEmpty().distinctBy(::songKey)

    fun allTargets(song: Song): List<Song> = _batch.value?.let { targetSongs() } ?: listOf(song)

    fun save(song: Song) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val current = _tags.value
                val batchScope = _batch.value
                val targets = if (batchScope != null) targetSongs() else listOf(song)
                val fields = if (batchScope != null) fieldsForBatch() else null
                if (targets.isEmpty() || (fields != null && fields.isEmpty())) {
                    _saveEvent.emit(TagSaveResult(total = 0, written = 0, failedTitles = emptyList()))
                    return@launch
                }

                var written = 0
                val failed = ArrayList<String>()
                val writtenSongs = ArrayList<Song>()
                if (batchScope == null) {
                    val metadataChanged = changedFields().any { it.propertyKey != null }
                    val success = TagLibWriter.writeTags(
                        context = getApplication(),
                        song = song,
                        data = current,
                        writeMetadata = metadataChanged,
                    )
                    if (success) {
                        written++
                        writtenSongs += song
                    } else failed += song.title
                } else {
                    for (target in targets) {
                        val success = TagLibWriter.writeTags(
                            context = getApplication(),
                            song = target,
                            data = current,
                            writeMetadata = true,
                            fields = fields,
                        )
                        if (success) {
                            written++
                            writtenSongs += target
                        } else failed += target.title
                    }
                }
                _saveEvent.emit(TagSaveResult(targets.size, written, failed.take(5), writtenSongs))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun songKey(song: Song): String =
        song.localUri?.takeIf(String::isNotBlank)
            ?: song.localPath?.takeIf(String::isNotBlank)
            ?: song.videoId
}
