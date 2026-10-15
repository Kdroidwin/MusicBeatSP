package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.ParametricEqBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Three-band peaking parametric EQ for decoded PCM. The disabled path copies
 * the input bytes unchanged; unsupported encodings are left to Media3.
 */
@UnstableApi
class ParametricEqAudioProcessor : BaseAudioProcessor() {
    private data class Tuning(val enabled: Boolean, val bands: List<ParametricEqBand>)

    private data class Coefficients(
        val b0: Float = 1f,
        val b1: Float = 0f,
        val b2: Float = 0f,
        val a1: Float = 0f,
        val a2: Float = 0f,
    )

    @Volatile
    private var tuning = Tuning(false, ParametricEqBand.DEFAULTS)

    private var sampleRateHz = 0
    private var channelCount = 0
    private var coefficients = Array(ParametricEqBand.DEFAULTS.size) { Coefficients() }
    private var coefficientsFor: List<ParametricEqBand> = emptyList()
    private var x1 = FloatArray(0)
    private var x2 = FloatArray(0)
    private var y1 = FloatArray(0)
    private var y2 = FloatArray(0)

    fun setTuning(enabled: Boolean, bands: List<ParametricEqBand>) {
        val safeBands = ParametricEqBand.DEFAULTS.mapIndexed { index, fallback ->
            bands.getOrNull(index)?.sanitized(fallback) ?: fallback
        }
        tuning = Tuning(enabled, safeBands)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        val stateCount = channelCount * ParametricEqBand.DEFAULTS.size
        x1 = FloatArray(stateCount)
        x2 = FloatArray(stateCount)
        y1 = FloatArray(stateCount)
        y2 = FloatArray(stateCount)
        coefficientsFor = emptyList()
        return inputAudioFormat
    }

    override fun onFlush() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
        coefficientsFor = emptyList()
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        coefficientsFor = emptyList()
        x1 = FloatArray(0)
        x2 = FloatArray(0)
        y1 = FloatArray(0)
        y2 = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return
        val output = replaceOutputBuffer(byteCount)
        val current = tuning
        if (!current.enabled || current.bands.all { it.gainDb == 0f }) {
            // Keep disabled and flat playback byte-for-byte identical.
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (coefficientsFor != current.bands) updateCoefficients(current.bands)
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            C.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> {
                output.put(inputBuffer)
                output.flip()
                return
            }
        }

        var channel = 0
        while (inputBuffer.remaining() >= bytesPerSample) {
            val source = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> inputBuffer.short.toFloat() / 32768f
                else -> inputBuffer.float.takeIf(Float::isFinite) ?: 0f
            }
            var sample = source
            for (band in coefficients.indices) {
                val stateIndex = channel * coefficients.size + band
                val coefficient = coefficients[band]
                val filtered = coefficient.b0 * sample + coefficient.b1 * x1[stateIndex] +
                    coefficient.b2 * x2[stateIndex] - coefficient.a1 * y1[stateIndex] -
                    coefficient.a2 * y2[stateIndex]
                x2[stateIndex] = x1[stateIndex]
                x1[stateIndex] = sample
                y2[stateIndex] = y1[stateIndex]
                sample = filtered.takeIf(Float::isFinite) ?: 0f
                y1[stateIndex] = if (kotlin.math.abs(sample) < 1e-20f) 0f else sample
            }
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> output.putShort(
                    (sample * 32768f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort(),
                )
                C.ENCODING_PCM_FLOAT -> output.putFloat(sample)
            }
            channel = (channel + 1) % channelCount
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    private fun updateCoefficients(bands: List<ParametricEqBand>) {
        val nyquistSafeMaximum = (sampleRateHz * 0.45f).coerceAtLeast(ParametricEqBand.MIN_FREQUENCY_HZ)
        coefficients = bands.map { band ->
            val frequency = band.frequencyHz.coerceIn(
                ParametricEqBand.MIN_FREQUENCY_HZ,
                nyquistSafeMaximum,
            )
            val q = band.q.coerceIn(ParametricEqBand.MIN_Q, ParametricEqBand.MAX_Q)
            val amplitude = 10.0.pow((band.gainDb / 40.0).toDouble()).toFloat()
            val omega = (2.0 * PI * frequency / sampleRateHz).toFloat()
            val alpha = sin(omega) / (2f * q)
            val cosine = cos(omega)
            val a0 = 1f + alpha / amplitude
            if (!a0.isFinite() || a0 <= 0f) {
                Coefficients()
            } else {
                Coefficients(
                    b0 = (1f + alpha * amplitude) / a0,
                    b1 = (-2f * cosine) / a0,
                    b2 = (1f - alpha * amplitude) / a0,
                    a1 = (-2f * cosine) / a0,
                    a2 = (1f - alpha / amplitude) / a0,
                )
            }
        }.toTypedArray()
        coefficientsFor = bands.toList()
    }
}
