package com.music.bitchord.ui.screens.equalizer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

@Composable
fun ReplayGainControls(
    enabled: Boolean,
    useAlbumGain: Boolean,
    preampDb: Float,
    preventClipping: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAlbumGainChange: (Boolean) -> Unit,
    onPreampChange: (Float) -> Unit,
    onPreventClippingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.replaygain), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.replaygain_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useAlbumGain,
                onClick = { onAlbumGainChange(false) },
                enabled = enabled,
                label = { Text(stringResource(R.string.track_gain)) },
            )
            FilterChip(
                selected = useAlbumGain,
                onClick = { onAlbumGainChange(true) },
                enabled = enabled,
                label = { Text(stringResource(R.string.album_gain)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain_preamp), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${"%.1f".format(preampDb)} dB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = preampDb,
            onValueChange = onPreampChange,
            valueRange = -12f..12f,
            steps = 23,
            enabled = enabled,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain_prevent_clipping), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = preventClipping, onCheckedChange = onPreventClippingChange, enabled = enabled)
        }
    }
}
