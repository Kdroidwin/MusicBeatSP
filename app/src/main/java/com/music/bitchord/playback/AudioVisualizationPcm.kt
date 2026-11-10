package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

internal data class AudioVisualizationFrame(
    val peak: Float = 0f,
    val spectrum: FloatArray = FloatArray(32),
    val capturedAtMs: Long = 0L,
    val hasSignal: Boolean = false,
)

/** Latest PCM-derived levels shared by the settings screen and both playback sinks. */
internal object AudioVisualizationData {
    private val latest = AtomicReference(AudioVisualizationFrame())

    fun current(): AudioVisualizationFrame = latest.get()

    fun publish(peak: Float? = null, spectrum: FloatArray? = null) {
        while (true) {
            val previous = latest.get()
            val next = AudioVisualizationFrame(
                peak = peak?.coerceIn(0f, 1f) ?: previous.peak,
                spectrum = spectrum ?: previous.spectrum,
                capturedAtMs = SystemClock.elapsedRealtime(),
                hasSignal = true,
            )
            if (latest.compareAndSet(previous, next)) return
        }
    }
}

/**
 * Reads a copy of decoded PCM for the meters. It never changes a sample sent to
 * the output: the normal Media3 path uses a pass-through processor, and the
 * direct DAC path calls the same analyzer on a duplicate of its input buffer.
 */
internal class AudioVisualizationPcmAnalyzer {
    private val sampleWindow = FloatArray(FFT_SIZE)
    private val real = DoubleArray(FFT_SIZE)
    private val imaginary = DoubleArray(FFT_SIZE)
    private var windowPosition = 0

    fun process(
        input: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        capturePeak: Boolean,
        captureSpectrum: Boolean,
    ) {
        if ((!capturePeak && !captureSpectrum) || channelCount <= 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channelCount
        val source = input.duplicate().order(
            if (encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN) {
                ByteOrder.BIG_ENDIAN
            } else {
                ByteOrder.LITTLE_ENDIAN
            },
        )
        val frameCount = source.remaining() / frameBytes
        if (frameCount == 0) return

        var blockPeak = 0f
        repeat(frameCount) {
            var loudest = 0f
            repeat(channelCount) {
                val value = readSample(source, encoding)
                blockPeak = max(blockPeak, abs(value))
                if (abs(value) > abs(loudest)) loudest = value
            }
            if (captureSpectrum) {
                sampleWindow[windowPosition++] = loudest
                if (windowPosition == FFT_SIZE) {
                    publishSpectrum()
                    windowPosition = 0
                }
            }
        }
        if (capturePeak) AudioVisualizationData.publish(peak = blockPeak)
    }

    private fun readSample(source: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> source.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = source.get().toInt() and 0xff
            val b1 = source.get().toInt() and 0xff
            val b2 = source.get().toInt() and 0xff
            var value = b0 or (b1 shl 8) or (b2 shl 16)
            if (value and 0x800000 != 0) value = value or -0x1000000
            value / 8_388_608f
        }
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val b0 = source.get().toInt() and 0xff
            val b1 = source.get().toInt() and 0xff
            val b2 = source.get().toInt() and 0xff
            var value = (b0 shl 16) or (b1 shl 8) or b2
            if (value and 0x800000 != 0) value = value or -0x1000000
            value / 8_388_608f
        }
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN ->
            (source.int / 2_147_483_648.0).toFloat()
        C.ENCODING_PCM_FLOAT -> source.float.takeIf(Float::isFinite) ?: 0f
        else -> 0f
    }.coerceIn(-1f, 1f)

    private fun publishSpectrum() {
        for (index in 0 until FFT_SIZE) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))
            real[index] = sampleWindow[index] * window
            imaginary[index] = 0.0
        }

        // In-place radix-2 FFT; the 512-point transform is small enough to run
        // once per PCM block without doing work or allocating on the UI thread.
        var reversed = 0
        for (index in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var size = 2
        while (size <= FFT_SIZE) {
            val angle = -2.0 * PI / size
            val stepReal = cos(angle)
            val stepImaginary = kotlin.math.sin(angle)
            val half = size / 2
            var start = 0
            while (start < FFT_SIZE) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextTwiddleReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
                start += size
            }
            size = size shl 1
        }

        val bars = FloatArray(SPECTRUM_BARS)
        val maxBin = FFT_SIZE / 2
        for (bar in bars.indices) {
            val low = exp(bar.toDouble() / bars.size * ln(maxBin.toDouble()))
                .toInt().coerceIn(1, maxBin - 1)
            val high = exp((bar + 1.0) / bars.size * ln(maxBin.toDouble()))
                .toInt().coerceIn(low + 1, maxBin + 1)
            var magnitude = 0.0
            for (bin in low until high) {
                magnitude = max(magnitude, sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]))
            }
            val normalized = magnitude / (FFT_SIZE / 2.0)
            val db = 20.0 * ln(max(normalized, 1e-7)) / ln(10.0)
            bars[bar] = ((db + 68.0) / 68.0).toFloat().coerceIn(0f, 1f)
        }
        AudioVisualizationData.publish(spectrum = bars)
    }

    private companion object {
        const val FFT_SIZE = 512
        const val SPECTRUM_BARS = 32
    }
}
