package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.ParametricEqBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
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
    private data class Tuning(
        val enabled: Boolean,
        val bands: List<ParametricEqBand>,
        val swapChannels: Boolean,
        val balance: Float,
        val bypassAll: Boolean,
    )

    private data class Coefficients(
        val b0: Float = 1f,
        val b1: Float = 0f,
        val b2: Float = 0f,
        val a1: Float = 0f,
        val a2: Float = 0f,
    )

    @Volatile
    private var tuning = Tuning(false, ParametricEqBand.DEFAULTS, swapChannels = false, balance = 0f, bypassAll = false)

    private var sampleRateHz = 0
    private var channelCount = 0
    private var coefficients = Array(ParametricEqBand.DEFAULTS.size) { Coefficients() }
    private var coefficientsFor: List<ParametricEqBand> = emptyList()
    private var x1 = FloatArray(0)
    private var x2 = FloatArray(0)
    private var y1 = FloatArray(0)
    private var y2 = FloatArray(0)
    private var currentBalance = 0f
    private var balanceGlideFrame = 0

    fun setTuning(
        enabled: Boolean,
        bands: List<ParametricEqBand>,
        swapChannels: Boolean = false,
        balance: Float = 0f,
        bypassAll: Boolean = false,
    ) {
        val safeBands = ParametricEqBand.DEFAULTS.mapIndexed { index, fallback ->
            bands.getOrNull(index)?.sanitized(fallback) ?: fallback
        }
        val safeBalance = balance.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        tuning = Tuning(
            enabled = enabled && !bypassAll,
            bands = safeBands,
            swapChannels = swapChannels && !bypassAll,
            balance = if (bypassAll) 0f else safeBalance,
            bypassAll = bypassAll,
        )
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
        currentBalance = tuning.balance
        balanceGlideFrame = 0
        return inputAudioFormat
    }

    override fun onFlush() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
        coefficientsFor = emptyList()
        currentBalance = tuning.balance
        balanceGlideFrame = 0
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        coefficientsFor = emptyList()
        x1 = FloatArray(0)
        x2 = FloatArray(0)
        y1 = FloatArray(0)
        y2 = FloatArray(0)
        currentBalance = 0f
        balanceGlideFrame = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return
        val output = replaceOutputBuffer(byteCount)
        val current = tuning
        if (current.bypassAll) {
            currentBalance = 0f
            output.put(inputBuffer)
            output.flip()
            return
        }
        val applyEq = current.enabled && current.bands.any { it.gainDb != 0f }
        val swapChannels = current.swapChannels && channelCount >= 2
        val targetBalance = if (channelCount == 2) current.balance else 0f
        val applyBalance = channelCount == 2 &&
            (targetBalance != 0f || currentBalance != 0f)
        if (!applyEq && !swapChannels && !applyBalance) {
            // Keep disabled and flat playback byte-for-byte identical.
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (applyEq && coefficientsFor != current.bands) updateCoefficients(current.bands)
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

        val frameBytes = bytesPerSample * channelCount
        val frame = FloatArray(channelCount)
        while (inputBuffer.remaining() >= frameBytes) {
            if (channelCount == 2 && balanceGlideFrame == 0) {
                currentBalance += (targetBalance - currentBalance) * BALANCE_GLIDE_RATE
                if (abs(targetBalance - currentBalance) < BALANCE_SETTLED_THRESHOLD) {
                    currentBalance = targetBalance
                }
                balanceGlideFrame = BALANCE_GLIDE_FRAMES - 1
            } else if (channelCount == 2) {
                balanceGlideFrame--
            }
            for (channel in 0 until channelCount) {
                frame[channel] = when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_16BIT -> inputBuffer.short.toFloat() / 32768f
                    else -> inputBuffer.float.takeIf(Float::isFinite) ?: 0f
                }
            }
            for (outputChannel in 0 until channelCount) {
                val sourceChannel = when {
                    !swapChannels -> outputChannel
                    outputChannel == 0 -> 1
                    outputChannel == 1 -> 0
                    else -> outputChannel
                }
                var sample = frame[sourceChannel]
                if (applyEq) {
                    for (band in coefficients.indices) {
                        val stateIndex = outputChannel * coefficients.size + band
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
                }
                if (channelCount == 2) {
                    val balanceGain = if (outputChannel == 0) 1f - currentBalance else 1f + currentBalance
                    sample *= balanceGain.coerceIn(0f, 1f)
                }
                when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_16BIT -> output.putShort(
                        (sample * 32768f).roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort(),
                    )
                    C.ENCODING_PCM_FLOAT -> output.putFloat(sample)
                }
            }
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    private companion object {
        const val BALANCE_GLIDE_FRAMES = 64
        const val BALANCE_GLIDE_RATE = 0.08f
        const val BALANCE_SETTLED_THRESHOLD = 0.0005f
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
