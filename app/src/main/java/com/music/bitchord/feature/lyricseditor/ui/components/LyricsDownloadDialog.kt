package com.music.bitchord.feature.lyricseditor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings

@Composable
fun LyricsDownloadDialog(
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String? = null,
    onDismissRequest: () -> Unit,
    onAutoDownloadClick: (title: String, artist: String, album: String?, providers: Set<LyricsSource>) -> Unit,
    onSearchAllClick: (title: String, artist: String, album: String?, providers: Set<LyricsSource>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var album by remember { mutableStateOf(initialAlbum.orEmpty()) }

    val orderedSources by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val enabledSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val compactLyricsSearchSources by AppSettings.compactLyricsSearchSources.collectAsStateWithLifecycle()
    val sourceChipsMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.5f - 40.dp).coerceAtLeast(80.dp)

    val availableProviders = remember(orderedSources) {
        orderedSources.ifEmpty { com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager.dynamicSources.value }
    }

    val selectedProviders = remember(availableProviders, enabledSources) {
        val initial = availableProviders.filter { it in enabledSources }
        mutableStateListOf<LyricsSource>().apply {
            addAll(if (initial.isNotEmpty()) initial else availableProviders)
        }
    }

    val canSearch = title.isNotBlank() || artist.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.lyrics_download_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.lyrics_editor_title_label)) },
                    placeholder = { Text(stringResource(R.string.lyrics_editor_title_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(R.string.lyrics_editor_artist_label)) },
                    placeholder = { Text(stringResource(R.string.lyrics_editor_artist_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(stringResource(R.string.lyrics_editor_album_label)) },
                    placeholder = { Text(stringResource(R.string.lyrics_editor_album_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.lyrics_editor_providers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LyricsSearchProviderChips(
                        providers = availableProviders,
                        selectedProviders = selectedProviders,
                        compact = compactLyricsSearchSources,
                        maxHeight = sourceChipsMaxHeight,
                        modifier = Modifier.padding(8.dp),
                        onToggle = { provider, isChecked ->
                            if (isChecked) {
                                if (selectedProviders.size > 1) selectedProviders.remove(provider)
                            } else {
                                selectedProviders.add(provider)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAutoDownloadClick(
                            title.trim(),
                            artist.trim(),
                            album.trim().ifEmpty { null },
                            selectedProviders.toSet(),
                        )
                    },
                    enabled = canSearch && selectedProviders.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.lyrics_auto_short))
                }

                Button(
                    onClick = {
                        onSearchAllClick(
                            title.trim(),
                            artist.trim(),
                            album.trim().ifEmpty { null },
                            selectedProviders.toSet(),
                        )
                    },
                    enabled = canSearch && selectedProviders.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.lyrics_search_results))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.lyrics_format_cancel))
            }
        },
        modifier = modifier,
    )
}
