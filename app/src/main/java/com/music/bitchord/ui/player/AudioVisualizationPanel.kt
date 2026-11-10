package com.music.bitchord.ui.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.playback.AudioVisualizationData
import com.music.bitchord.playback.AudioVisualizationFrame
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Live display of PCM levels sampled inside the playback audio path. */
@Composable
internal fun AudioVisualizationPanel(
    showPeakMeter: Boolean,
    showSpectrum: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showPeakMeter && !showSpectrum) return
    var frame by remember { mutableStateOf(AudioVisualizationData.current()) }
    LaunchedEffect(Unit) {
        while (true) {
            frame = AudioVisualizationData.current()
            delay(33)
        }
    }

    val ageMs = (SystemClock.elapsedRealtime() - frame.capturedAtMs).coerceAtLeast(0L)
    val decay = (if (!frame.hasSignal || ageMs >= SIGNAL_HOLD_MS + SIGNAL_DECAY_MS) {
        0f
    } else if (ageMs <= SIGNAL_HOLD_MS) {
        1f
    } else {
        1f - (ageMs - SIGNAL_HOLD_MS).toFloat() / SIGNAL_DECAY_MS
    }).coerceIn(0f, 1f)
    val peak = frame.peak * decay
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (showPeakMeter) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.width(60.dp).height(34.dp)) {
                    val center = Offset(size.width / 2f, size.height * 0.92f)
                    val radius = size.width * 0.46f
                    drawArc(
                        color = secondary.copy(alpha = 0.50f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                    for (tick in 0..8) {
                        val angle = PI + PI * tick / 8.0
                        val inner = radius * 0.78f
                        val outer = radius * 0.96f
                        drawLine(
                            secondary.copy(alpha = 0.55f),
                            Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                            Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val needleAngle = PI + PI * peak
                    drawLine(
                        accent,
                        center,
                        Offset(center.x + cos(needleAngle).toFloat() * radius * 0.72f, center.y + sin(needleAngle).toFloat() * radius * 0.72f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(accent, radius = 2.2.dp.toPx(), center = center)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.analog_digital_peak_meter),
                            color = secondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val db = if (peak <= 0.0001f) "−∞" else {
                            "%.1f".format((-20f * kotlin.math.log10(peak)).coerceAtLeast(0f).let { -it })
                        }
                        Text(text = "$db dBFS", color = secondary, fontSize = 10.sp)
                    }
                    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                        val filled = size.width * peak
                        drawRoundRect(secondary.copy(alpha = 0.18f), cornerRadius = CornerRadius(size.height))
                        if (filled > 0f) {
                            drawRoundRect(accent, size = Size(filled, size.height), cornerRadius = CornerRadius(size.height))
                        }
                    }
                }
            }
        }
        if (showSpectrum) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.spectrum_analyzer),
                    color = secondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Canvas(Modifier.weight(1f).height(30.dp)) {
                    val gap = 2.dp.toPx()
                    val barWidth = ((size.width - gap * (frame.spectrum.size - 1)) / frame.spectrum.size).coerceAtLeast(1f)
                    frame.spectrum.forEachIndexed { index, level ->
                        val height = size.height * level * decay
                        drawRoundRect(
                            color = accent.copy(alpha = 0.28f + level * decay * 0.72f),
                            topLeft = Offset(index * (barWidth + gap), size.height - height),
                            size = Size(barWidth, height.coerceAtLeast(1.dp.toPx())),
                            cornerRadius = CornerRadius(barWidth / 2f),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(
                if (decay == 0f) R.string.audio_visualization_waiting
                else R.string.audio_visualization_preview_hint,
            ),
            color = secondary.copy(alpha = 0.75f),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp),
        )
    }
}

private const val SIGNAL_HOLD_MS = 90L
private const val SIGNAL_DECAY_MS = 420f
