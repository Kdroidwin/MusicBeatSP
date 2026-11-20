package com.music.bitchord.ui.screens.equalizer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.data.settings.ParametricEqFilterType
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun GlobalGraphicEqControls(
    config: ParametricEqConfig,
    onConfigChange: (ParametricEqConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCount = config.graphicEqBands.size.takeIf { it in BAND_COUNTS } ?: 10
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(R.string.eq_graphic_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.eq_graphic_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.graphicEqEnabled,
                    onCheckedChange = { value ->
                        val bands = config.graphicEqBands.ifEmpty { makeBands(10, emptyList()) }
                        onConfigChange(
                            config.copy(
                                graphicEqEnabled = value,
                                graphicEqBands = bands,
                                activePresetId = null,
                            ),
                        )
                    },
                )
            }

            if (config.graphicEqEnabled) {
                Text(
                    stringResource(R.string.eq_graphic_band_count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BAND_COUNTS.forEach { count ->
                        FilterChip(
                            selected = selectedCount == count,
                            onClick = {
                                onConfigChange(
                                    config.copy(
                                        graphicEqEnabled = true,
                                        graphicEqBands = makeBands(count, config.graphicEqBands),
                                        activePresetId = null,
                                    ),
                                )
                            },
                            label = { Text(count.toString()) },
                        )
                    }
                }
                config.graphicEqBands.forEachIndexed { index, band ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = frequencyLabel(band.frequencyHz),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(58.dp),
                        )
                        Slider(
                            value = band.gainDb.coerceIn(-12f, 12f),
                            onValueChange = { gain ->
                                val updated = config.graphicEqBands.toMutableList()
                                updated[index] = band.copy(gainDb = gain, enabled = true)
                                onConfigChange(config.copy(graphicEqBands = updated, activePresetId = null))
                            },
                            valueRange = -12f..12f,
                            steps = 47,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "%+.1f", band.gainDb),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val BAND_COUNTS = listOf(5, 10, 15, 20)

private fun makeBands(count: Int, previous: List<ParametricEqBand>): List<ParametricEqBand> {
    val centers = when (count) {
        5 -> listOf(60f, 250f, 1_000f, 4_000f, 16_000f)
        10 -> listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
        else -> {
            val min = ln(31.0)
            val max = ln(16_000.0)
            List(count) { index ->
                exp(min + index * (max - min) / (count - 1)).toFloat()
            }
        }
    }
    return centers.map { frequency ->
        val nearest = previous.minByOrNull { abs(ln(it.frequencyHz.toDouble() / frequency.toDouble())) }
        ParametricEqBand(
            frequencyHz = frequency,
            q = 1f,
            gainDb = nearest?.gainDb ?: 0f,
            filterType = ParametricEqFilterType.PEAKING,
            enabled = true,
            id = UUID.randomUUID().toString(),
        )
    }
}

private fun frequencyLabel(value: Float): String = if (value >= 1_000f) {
    val khz = value / 1_000f
    if (abs(khz - khz.roundToInt()) < 0.05f) "${khz.roundToInt()} kHz"
    else String.format(Locale.getDefault(), "%.1f kHz", khz)
} else "${value.roundToInt()} Hz"
