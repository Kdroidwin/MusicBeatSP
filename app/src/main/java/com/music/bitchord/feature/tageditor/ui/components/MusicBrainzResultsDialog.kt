package com.music.bitchord.feature.tageditor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.feature.tageditor.data.musicbrainz.MusicBrainzCandidate
import com.music.bitchord.feature.tageditor.ui.viewmodel.MusicBrainzSearchState

@Composable
fun MusicBrainzResultsDialog(
    state: MusicBrainzSearchState,
    onSelect: (MusicBrainzCandidate) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.musicbrainz_results_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isSearching) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.musicbrainz_searching))
                    }
                } else if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                } else if (state.results.isEmpty()) {
                    Text(stringResource(R.string.musicbrainz_no_results))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.results, key = MusicBrainzCandidate::recordingId) { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(result) }
                                    .padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(result.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        result.artist.takeIf(String::isNotBlank),
                                        result.album.takeIf(String::isNotBlank),
                                        result.year.takeIf { it > 0 }?.toString(),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                result.disambiguation?.let { note ->
                                    Text(note, style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
