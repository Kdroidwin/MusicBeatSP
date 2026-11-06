package com.music.bitchord.feature.lyricseditor.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricsSource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LyricsSearchProviderChips(
    providers: List<LyricsSource>,
    selectedProviders: List<LyricsSource>,
    compact: Boolean,
    maxHeight: Dp,
    onToggle: (LyricsSource, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipListModifier = modifier
        .fillMaxWidth()
        .then(
            if (compact) {
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            } else {
                Modifier
            },
        )

    if (compact) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            ProviderChipFlow(
                providers = providers,
                selectedProviders = selectedProviders,
                compact = true,
                modifier = chipListModifier,
                onToggle = onToggle,
            )
        }
    } else {
        ProviderChipFlow(
            providers = providers,
            selectedProviders = selectedProviders,
            compact = false,
            modifier = chipListModifier,
            onToggle = onToggle,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChipFlow(
    providers: List<LyricsSource>,
    selectedProviders: List<LyricsSource>,
    compact: Boolean,
    modifier: Modifier,
    onToggle: (LyricsSource, Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
        modifier = modifier,
    ) {
        providers.forEach { provider ->
            val selected = provider in selectedProviders
            FilterChip(
                selected = selected,
                onClick = { onToggle(provider, selected) },
                label = {
                    Text(
                        text = provider.label,
                        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        modifier = if (compact) Modifier.padding(horizontal = 1.dp) else Modifier,
                    )
                },
                modifier = if (compact) Modifier.height(32.dp) else Modifier,
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 12.dp else 16.dp),
                        )
                    }
                } else null,
                shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}
