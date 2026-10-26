package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.playback.equalizer.BiquadUtils
import com.music.bitchord.playback.equalizer.BiquadUtils.BiquadCoefficients
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * High-precision 64-bit floating-point in-app Parametric Equalizer & DSP processor
 * for decoded PCM. Modeled after RootlessJamesDSP & EEL2 LiveProg:
 * - Supports Peaking (PK), Low-Shelf (LSC), High-Shelf (HSC), LPF, HPF, BPF, Notch, All-Pass
 * - Supports Global bands (both channels) + Left/Right independent bands
 * - Supports Global Preamp + L/R Preamp + per-channel gain offsets
 * - Supports Left <-> Right stereo channel swap (pre-EQ or post-EQ)
 * - With no active EQ, swap or balance change, the input is copied byte-for-byte unchanged.
 */
@UnstableApi
class ParametricEqAudioProcessor : BaseAudioProcessor() {

    private class BiquadState {
        var x1: Double = 0.0
        var x2: Double = 0.0
        var y1: Double = 0.0
        var y2: Double = 0.0

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun process(xn: Double, c: BiquadCoefficients): Double {
            var yn = c.b0 * xn + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1
            x1 = xn
            y2 = y1
            if (!yn.isFinite()) {
                reset()
                return 0.0
            }
            if (abs(yn) < 1e-30) yn = 0.0
            y1 = yn
            return yn
        }
    }

    @Volatile
    private var config: ParametricEqConfig = ParametricEqConfig()
    @Volatile
    private var balance = 0f
    @Volatile
    private var bypassAll = false
    @Volatile
    private var tpdfDitherEnabled = false
    private var ditherRandomState = 0x4D595DF4D0F33173L

    private var sampleRateHz = 0
    private var channelCount = 0

    private var appliedConfig: ParametricEqConfig? = null
    private var leftLinearGain: Double = 1.0
    private var rightLinearGain: Double = 1.0

    private var globalCoeffs: Array<BiquadCoefficients> = emptyArray()
    private var leftCoeffs: Array<BiquadCoefficients> = emptyArray()
    private var rightCoeffs: Array<BiquadCoefficients> = emptyArray()

    // Per-channel states for global filters: globalStates[ch][bandIdx]
    private var globalStates: Array<Array<BiquadState>> = emptyArray()
    // Left channel (ch 0) specific filter states
    private var leftStates: Array<BiquadState> = emptyArray()
    // Right channel (ch 1) specific filter states
    private var rightStates: Array<BiquadState> = emptyArray()
    private var frameSamples = DoubleArray(0)
    private var currentBalance = 0f
    private var balanceGlideFrames = 0

    fun setConfig(newConfig: ParametricEqConfig, balance: Float = this.balance, bypassAll: Boolean = false) {
        config = newConfig.sanitized()
        this.balance = balance.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        this.bypassAll = bypassAll
    }

    fun setTpdfDitherEnabled(enabled: Boolean) {
        tpdfDitherEnabled = enabled
    }

    /** Legacy 3-band entry point kept for compatibility. */
    fun setTuning(
        enabled: Boolean,
        bands: List<ParametricEqBand>,
        swapChannels: Boolean = config.swapChannels,
        balance: Float = this.balance,
        bypassAll: Boolean = false,
    ) = setConfig(
        config.copy(enabled = enabled, globalBands = bands, swapChannels = swapChannels),
        balance = balance,
        bypassAll = bypassAll,
    )

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supportedEncoding) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        appliedConfig = null
        frameSamples = DoubleArray(inputAudioFormat.channelCount)
        currentBalance = balance
        balanceGlideFrames = 0
        resetStates()
        return inputAudioFormat
    }

    override fun onFlush() {
        resetStates()
        currentBalance = balance
        balanceGlideFrames = 0
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        appliedConfig = null
        globalCoeffs = emptyArray()
        leftCoeffs = emptyArray()
        rightCoeffs = emptyArray()
        globalStates = emptyArray()
        leftStates = emptyArray()
        rightStates = emptyArray()
        frameSamples = DoubleArray(0)
        currentBalance = 0f
        balanceGlideFrames = 0
    }

    private fun resetStates() {
        for (chStates in globalStates) {
            for (s in chStates) s.reset()
        }
        for (s in leftStates) s.reset()
        for (s in rightStates) s.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return
        val output = replaceOutputBuffer(byteCount)
        val current = config
        if (bypassAll || sampleRateHz <= 0 || channelCount <= 0) {
            if (bypassAll) {
                currentBalance = 0f
                balanceGlideFrames = 0
            }
            output.put(inputBuffer)
            output.flip()
            return
        }

        val applyEq = current.enabled && (
            current.hasActiveFilters ||
                current.effectiveLeftPreampDb != 0f ||
                current.effectiveRightPreampDb != 0f
            )
        val swap = current.swapChannels && channelCount >= 2
        val targetBalance = if (channelCount == 2) balance else 0f
        val applyBalance = channelCount == 2 && (targetBalance != 0f || currentBalance != 0f)
        if (!applyEq && !swap && !applyBalance) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (applyEq && appliedConfig != current) {
            rebuildFilterPipeline(current)
        }

        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> {
                output.put(inputBuffer)
                output.flip()
                return
            }
        }

        val frameBytes = bytesPerSample * channelCount
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        val swapBefore = current.swapBeforeEq
        val gCoeffs = if (applyEq) globalCoeffs else emptyArray()
        val lCoeffs = if (applyEq) leftCoeffs else emptyArray()
        val rCoeffs = if (applyEq) rightCoeffs else emptyArray()
        val lGain = leftLinearGain
        val rGain = rightLinearGain
        val encoding = inputAudioFormat.encoding
        val addTpdfDither = tpdfDitherEnabled && applyEq && encoding != C.ENCODING_PCM_FLOAT

        if (channelCount == 2) {
            val gStateL = globalStates.getOrNull(0) ?: emptyArray()
            val gStateR = globalStates.getOrNull(1) ?: emptyArray()
            val lState = leftStates
            val rState = rightStates

            while (inputBuffer.remaining() >= frameBytes) {
                var spl0 = readSampleNormalized(inputBuffer, encoding)
                var spl1 = readSampleNormalized(inputBuffer, encoding)

                // 1. Swap Channels First (Left <-> Right)
                if (swap && swapBefore) {
                    val tmp = spl0
                    spl0 = spl1
                    spl1 = tmp
                }

                if (applyEq) {
                    // Apply total preamp and shared filters, then channel-specific filters.
                    spl0 *= lGain
                    spl1 *= rGain
                    for (b in gCoeffs.indices) {
                        val coeff = gCoeffs[b]
                        spl0 = gStateL[b].process(spl0, coeff)
                        spl1 = gStateR[b].process(spl1, coeff)
                    }
                    for (b in lCoeffs.indices) spl0 = lState[b].process(spl0, lCoeffs[b])
                    for (b in rCoeffs.indices) spl1 = rState[b].process(spl1, rCoeffs[b])
                }

                // Post-EQ channel swap if configured
                if (swap && !swapBefore) {
                    val tmp = spl0
                    spl0 = spl1
                    spl1 = tmp
                }

                if (applyBalance) {
                    if (balanceGlideFrames == 0) {
                        currentBalance += (targetBalance - currentBalance) * BALANCE_GLIDE_RATE
                        if (abs(targetBalance - currentBalance) < BALANCE_SETTLED_THRESHOLD) {
                            currentBalance = targetBalance
                        }
                        balanceGlideFrames = BALANCE_GLIDE_FRAMES - 1
                    } else {
                        balanceGlideFrames--
                    }
                    spl0 *= (1f - currentBalance).coerceIn(0f, 1f).toDouble()
                    spl1 *= (1f + currentBalance).coerceIn(0f, 1f).toDouble()
                }

                writeSampleNormalized(output, encoding, spl0, addTpdfDither)
                writeSampleNormalized(output, encoding, spl1, addTpdfDither)
            }
        } else {
            while (inputBuffer.remaining() >= frameBytes) {
                for (ch in 0 until channelCount) {
                    frameSamples[ch] = readSampleNormalized(inputBuffer, encoding)
                }
                if (swap && swapBefore) {
                    val tmp = frameSamples[0]
                    frameSamples[0] = frameSamples[1]
                    frameSamples[1] = tmp
                }
                for (ch in 0 until channelCount) {
                    var s = frameSamples[ch]
                    if (applyEq) {
                        s *= if (ch == 1) rGain else lGain
                        val gStateCh = globalStates[ch]
                        for (b in gCoeffs.indices) s = gStateCh[b].process(s, gCoeffs[b])
                        if (ch == 0) {
                            for (b in lCoeffs.indices) s = leftStates[b].process(s, lCoeffs[b])
                        } else if (ch == 1) {
                            for (b in rCoeffs.indices) s = rightStates[b].process(s, rCoeffs[b])
                        }
                    }
                    frameSamples[ch] = s
                }
                if (swap && !swapBefore) {
                    val tmp = frameSamples[0]
                    frameSamples[0] = frameSamples[1]
                    frameSamples[1] = tmp
                }
                for (ch in 0 until channelCount) {
                    writeSampleNormalized(output, encoding, frameSamples[ch], addTpdfDither)
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

    private fun readSampleNormalized(buf: ByteBuffer, encoding: Int): Double = when (encoding) {
        C.ENCODING_PCM_16BIT -> buf.short.toDouble() / 32768.0
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() // sign-extended
            val sampleInt = (b2 shl 16) or (b1 shl 8) or b0
            sampleInt.toDouble() / 8388608.0
        }
        C.ENCODING_PCM_32BIT -> buf.int.toDouble() / 2147483648.0
        C.ENCODING_PCM_FLOAT -> {
            val f = buf.float
            if (f.isFinite()) f.toDouble() else 0.0
        }
        else -> 0.0
    }

    private fun writeSampleNormalized(buf: ByteBuffer, encoding: Int, sample: Double, addDither: Boolean) {
        val finiteSample = if (sample.isFinite()) sample else 0.0
        val lsb = when (encoding) {
            C.ENCODING_PCM_16BIT -> 1.0 / 32768.0
            C.ENCODING_PCM_24BIT -> 1.0 / 8388608.0
            C.ENCODING_PCM_32BIT -> 1.0 / 2147483648.0
            else -> 0.0
        }
        val safe = if (addDither && lsb > 0.0) {
            finiteSample + (nextDitherUniform() - nextDitherUniform()) * lsb
        } else finiteSample
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val scaled = (safe * 32768.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buf.putShort(scaled.toShort())
            }
            C.ENCODING_PCM_24BIT -> {
                val scaled = (safe * 8388608.0).roundToInt().coerceIn(-8388608, 8388607)
                buf.put((scaled and 0xFF).toByte())
                buf.put(((scaled shr 8) and 0xFF).toByte())
                buf.put(((scaled shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val scaled = (safe * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                buf.putInt(scaled.toInt())
            }
            C.ENCODING_PCM_FLOAT -> {
                buf.putFloat(safe.toFloat())
            }
        }
    }

    /** Allocation-free xorshift PRNG for triangular dither on the audio thread. */
    private fun nextDitherUniform(): Double {
        var value = ditherRandomState
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        ditherRandomState = value
        return (value ushr 11).toDouble() * (1.0 / 9_007_199_254_740_992.0)
    }

    private fun rebuildFilterPipeline(target: ParametricEqConfig) {
        val sr = sampleRateHz.toDouble()
        leftLinearGain = 10.0.pow(target.effectiveLeftPreampDb.toDouble() / 20.0)
        rightLinearGain = 10.0.pow(target.effectiveRightPreampDb.toDouble() / 20.0)

        val activeG = target.activeGlobalBands
        val activeL = target.activeLeftBands
        val activeR = target.activeRightBands

        globalCoeffs = Array(activeG.size) { i -> BiquadUtils.computeCoefficients(activeG[i], sr) }
        leftCoeffs = Array(activeL.size) { i -> BiquadUtils.computeCoefficients(activeL[i], sr) }
        rightCoeffs = Array(activeR.size) { i -> BiquadUtils.computeCoefficients(activeR[i], sr) }

        if (globalStates.size != channelCount || globalStates.firstOrNull()?.size != activeG.size) {
            globalStates = Array(channelCount) { Array(activeG.size) { BiquadState() } }
        }
        if (leftStates.size != activeL.size) {
            leftStates = Array(activeL.size) { BiquadState() }
        }
        if (rightStates.size != activeR.size) {
            rightStates = Array(activeR.size) { BiquadState() }
        }

        appliedConfig = target
    }
}
