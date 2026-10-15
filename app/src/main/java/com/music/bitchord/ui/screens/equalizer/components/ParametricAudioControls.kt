package com.music.bitchord.ui.screens.equalizer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.settings.ParametricEqBand
import kotlin.math.exp
import kotlin.math.ln

@Composable
fun ParametricEqControls(
    enabled: Boolean,
    bands: List<ParametricEqBand>,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, ParametricEqBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.parametric_eq),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.parametric_eq_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (enabled) {
            bands.forEachIndexed { index, band ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.parametric_eq_band_number, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ValueSlider(
                        label = stringResource(R.string.parametric_eq_frequency),
                        value = frequencyToPosition(band.frequencyHz),
                        valueText = formatFrequency(band.frequencyHz),
                        onValueChange = { position ->
                            onBandChange(index, band.copy(frequencyHz = positionToFrequency(position)))
                        },
                    )
                    ValueSlider(
                        label = stringResource(R.string.parametric_eq_q),
                        value = band.q,
                        valueRange = ParametricEqBand.MIN_Q..ParametricEqBand.MAX_Q,
                        valueText = "%.2f".format(band.q),
                        onValueChange = { onBandChange(index, band.copy(q = it)) },
                    )
                    ValueSlider(
                        label = stringResource(R.string.parametric_eq_gain),
                        value = band.gainDb,
                        valueRange = ParametricEqBand.MIN_GAIN_DB..ParametricEqBand.MAX_GAIN_DB,
                        valueText = "%.1f dB".format(band.gainDb),
                        onValueChange = { onBandChange(index, band.copy(gainDb = it)) },
                    )
                }
            }
        }
    }
}

@Composable
fun ReverbControls(
    enabled: Boolean,
    amount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onAmountChange: (Int) -> Unit,
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
            Icon(Icons.Rounded.Waves, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.reverb),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.reverb_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.reverb_amount),
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$amount%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
        Slider(
            value = amount.toFloat(),
            onValueChange = { onAmountChange(it.toInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(68.dp),
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

private fun frequencyToPosition(frequencyHz: Float): Float {
    val minLog = ln(ParametricEqBand.MIN_FREQUENCY_HZ)
    val maxLog = ln(ParametricEqBand.MAX_FREQUENCY_HZ)
    return ((ln(frequencyHz.coerceIn(ParametricEqBand.MIN_FREQUENCY_HZ, ParametricEqBand.MAX_FREQUENCY_HZ)) - minLog) /
        (maxLog - minLog)).coerceIn(0f, 1f)
}

private fun positionToFrequency(position: Float): Float {
    val minLog = ln(ParametricEqBand.MIN_FREQUENCY_HZ)
    val maxLog = ln(ParametricEqBand.MAX_FREQUENCY_HZ)
    return exp(minLog + (maxLog - minLog) * position.coerceIn(0f, 1f))
}

@Composable
private fun formatFrequency(frequencyHz: Float): String = if (frequencyHz >= 1_000f) {
    stringResource(R.string.parametric_eq_frequency_khz, frequencyHz / 1_000f)
} else {
    stringResource(R.string.parametric_eq_frequency_hz, frequencyHz.toInt())
}
