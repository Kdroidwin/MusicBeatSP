package com.music.bitchord.feature.localsearch.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.feature.localsearch.domain.model.LocalSearchFilter

@Composable
fun LocalSearchFilterChips(
    selectedFilter: LocalSearchFilter,
    onFilterSelect: (LocalSearchFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LocalSearchFilter.entries.forEach { filter ->
            val selected = filter == selectedFilter
            val labelRes = when (filter) {
                LocalSearchFilter.ALL -> R.string.search_filter_all
                LocalSearchFilter.SONGS -> R.string.search_filter_songs
                LocalSearchFilter.ALBUMS -> R.string.search_filter_albums
                LocalSearchFilter.ARTISTS -> R.string.search_filter_artists
                LocalSearchFilter.FOLDERS -> R.string.search_filter_folders
            }
            FilterChip(
                selected = selected,
                onClick = { onFilterSelect(filter) },
                label = {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = null,
            )
        }
    }
}
