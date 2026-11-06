package com.music.bitchord.feature.tageditor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.feature.tageditor.domain.model.TagField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorFolderSection(
    songCount: Int,
    subfolderSongCount: Int,
    includeSubfolders: Boolean,
    onIncludeSubfoldersChange: (Boolean) -> Unit,
    changed: Set<TagField>,
    excluded: Set<TagField>,
    onToggleField: (TagField) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(top = 18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.tag_batch_title, if (includeSubfolders) subfolderSongCount else songCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.tag_batch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subfolderSongCount > songCount) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.tag_include_subfolders, subfolderSongCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = includeSubfolders, onCheckedChange = onIncludeSubfoldersChange)
                }
            }
            Text(stringResource(R.string.tag_fields_to_apply), style = MaterialTheme.typography.labelLarge)
            if (changed.isEmpty()) {
                Text(
                    stringResource(R.string.tag_fields_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    changed.forEach { field ->
                        FilterChip(
                            selected = field !in excluded,
                            onClick = { onToggleField(field) },
                            label = { Text(stringResource(field.label)) },
                        )
                    }
                }
            }
        }
    }
}
