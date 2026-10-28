package com.music.bitchord.feature.tageditor.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import com.music.bitchord.feature.tageditor.domain.model.SongTagData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _tags = MutableStateFlow(SongTagData())
    val tags: StateFlow<SongTagData> = _tags.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveEvent = MutableSharedFlow<Boolean>()
    val saveEvent: SharedFlow<Boolean> = _saveEvent.asSharedFlow()

    private var initialTags = SongTagData()

    fun loadTags(song: Song) {
        viewModelScope.launch {
            _isLoading.value = true
            val data = TagLibWriter.readTags(getApplication(), song)
            initialTags = data
            _tags.value = data
            _isLoading.value = false
        }
    }

    fun updateTags(newTags: SongTagData) {
        _tags.value = newTags
    }

    fun updateArtworkFromUri(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
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
        _tags.value = _tags.value.copy(
            artworkBitmap = null,
            artworkChanged = true,
            artworkDeleted = true,
        )
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

    fun save(song: Song) {
        viewModelScope.launch {
            _isSaving.value = true
            val current = _tags.value
            val metadataChanged = current.hasDifferentMetadataThan(initialTags)
            val success = TagLibWriter.writeTags(
                context = getApplication(),
                song = song,
                data = current,
                writeMetadata = metadataChanged,
            )
            _isSaving.value = false
            _saveEvent.emit(success)
        }
    }

    private fun SongTagData.hasDifferentMetadataThan(other: SongTagData): Boolean =
        title != other.title ||
            album != other.album ||
            artist != other.artist ||
            albumArtist != other.albumArtist ||
            composer != other.composer ||
            conductor != other.conductor ||
            publisher != other.publisher ||
            genre != other.genre ||
            year != other.year ||
            trackNumber != other.trackNumber ||
            trackTotal != other.trackTotal ||
            discNumber != other.discNumber ||
            discTotal != other.discTotal ||
            lyrics != other.lyrics ||
            lyricist != other.lyricist ||
            arranger != other.arranger ||
            comment != other.comment
}
