package com.music.bitchord.ui.player

import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class AudioVisualizationFrame(
    val peak: Float = 0f,
    val spectrum: FloatArray = FloatArray(32),
)

/** Passive view of this app's active playback session. The callback never stores or exports audio. */
@Composable
internal fun AudioVisualizationPanel(
    audioSessionId: Int,
    showPeakMeter: Boolean,
    showSpectrum: Boolean,
    capturePeakMeter: Boolean = showPeakMeter,
    captureSpectrum: Boolean = showSpectrum,
    previewOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissionGranted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (!showPeakMeter && !showSpectrum) return
    val canCapture = audioSessionId > 0 && permissionGranted &&
        (capturePeakMeter || captureSpectrum)

    val frameRef = remember(audioSessionId) { AtomicReference(AudioVisualizationFrame()) }
    var frame by remember(audioSessionId) { mutableStateOf(AudioVisualizationFrame()) }

    DisposableEffect(audioSessionId, capturePeakMeter, captureSpectrum, canCapture) {
        val effect = if (canCapture) runCatching { Visualizer(audioSessionId) }.getOrNull() else null
        if (effect != null) {
            runCatching {
                val captureRange = Visualizer.getCaptureSizeRange()
                effect.captureSize = min(captureRange.last(), 1024)
                effect.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (!capturePeakMeter) return
                            val samples = waveform ?: return
                            if (samples.isEmpty()) return
                            var peak = 0f
                            for (sample in samples) {
                                peak = max(peak, abs(sample.toInt()).toFloat() / 128f)
                            }
                            val previous = frameRef.get()
                            frameRef.set(previous.copy(peak = peak.coerceIn(0f, 1f)))
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (!captureSpectrum) return
                            val samples = fft ?: return
                            if (samples.isEmpty()) return
                            val bins = samples.size / 2
                            if (bins < 3) return
                            val bars = FloatArray(32)
                            for (bar in bars.indices) {
                                val low = (1.0 * (bins - 1).powFraction(bar.toDouble() / bars.size)).toInt().coerceIn(1, bins - 1)
                                val high = (1.0 * (bins - 1).powFraction((bar + 1.0) / bars.size)).toInt().coerceIn(low + 1, bins)
                                var magnitude = 0.0
                                for (bin in low until high) {
                                    val real = samples[bin * 2].toInt()
                                    val imaginary = samples[bin * 2 + 1].toInt()
                                    magnitude = max(magnitude, sqrt((real * real + imaginary * imaginary).toDouble()))
                                }
                                val db = 20.0 * ln(max(magnitude, 0.0001)) / ln(10.0)
                                bars[bar] = ((db + 55.0) / 55.0).toFloat().coerceIn(0f, 1f)
                            }
                            val previous = frameRef.get()
                            frameRef.set(previous.copy(spectrum = bars))
                        }
                    },
                    (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(2_000),
                    capturePeakMeter,
                    captureSpectrum,
                )
                effect.enabled = true
            }.onFailure { runCatching { effect.release() } }
        }
        onDispose {
            effect?.let {
                runCatching { it.enabled = false }
                runCatching { it.release() }
            }
        }
    }

    LaunchedEffect(audioSessionId, canCapture) {
        if (!canCapture) {
            frame = AudioVisualizationFrame()
            return@LaunchedEffect
        }
        while (true) {
            frame = frameRef.get()
            delay(33)
        }
    }

    val previewFrame = remember {
        val peaks = floatArrayOf(0.18f, 0.45f, 0.72f, 0.36f, 0.60f)
        AudioVisualizationFrame(
            peak = 0.58f,
            spectrum = FloatArray(32) { index ->
                val nearest = peaks.indices.minOf { abs(index - peaks[it] * 30f) }
                (0.14f + (1f - nearest / 8f).coerceAtLeast(0f) * 0.72f).coerceIn(0.08f, 0.86f)
            },
        )
    }
    val visibleFrame = if (previewOnly) {
        AudioVisualizationFrame(
            peak = if (canCapture && capturePeakMeter) frame.peak else previewFrame.peak,
            spectrum = if (canCapture && captureSpectrum) frame.spectrum else previewFrame.spectrum,
        )
    } else frame

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
                    val needleAngle = PI + PI * visibleFrame.peak
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
                        val db = if (visibleFrame.peak <= 0.0001f) "−∞" else "%.0f".format((-20f * kotlin.math.log10(visibleFrame.peak)).coerceAtLeast(0f).let { -it })
                        Text(text = "$db dB", color = secondary, fontSize = 10.sp)
                    }
                    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                        val filled = size.width * visibleFrame.peak
                        drawRoundRect(secondary.copy(alpha = 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
                        if (filled > 0f) {
                            drawRoundRect(accent, size = Size(filled, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
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
                    val barWidth = ((size.width - gap * (visibleFrame.spectrum.size - 1)) / visibleFrame.spectrum.size).coerceAtLeast(1f)
                    visibleFrame.spectrum.forEachIndexed { index, level ->
                        val height = size.height * level
                        drawRoundRect(
                            color = accent.copy(alpha = 0.28f + level * 0.72f),
                            topLeft = Offset(index * (barWidth + gap), size.height - height),
                            size = Size(barWidth, height.coerceAtLeast(1.dp.toPx())),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                        )
                    }
                }
            }
        }
        if (previewOnly) {
            Text(
                text = stringResource(R.string.audio_visualization_preview_hint),
                color = secondary.copy(alpha = 0.75f),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp, top = 1.dp),
            )
        }
    }
}

private fun Int.powFraction(power: Double): Double = Math.pow(toDouble(), power)
