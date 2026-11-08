package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.dsp.Upsampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UCRT's multi-stage FIR upsampler adapted to Media3's PCM chain.
 * It only runs on the normal PCM path; bit-perfect output bypasses it in
 * [PlaybackService]. No codec or source bytes are modified.
 */
@UnstableApi
class DarPcmUpsamplingAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    /** Highest output rate reported by the current route; conservative 192 kHz fallback. */
    @Volatile
    var maxOutputSampleRateHz: Int = 192_000
        set(value) { field = value.coerceIn(0, 768_000) }

    /** Empty means the route did not report exact rates; otherwise use only advertised values. */
    @Volatile
    var supportedOutputSampleRates: Set<Int> = emptySet()

    private var factor = 1
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var upsampler: Upsampler? = null
    private var inputFloats = FloatArray(0)
    private var outputFloats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount !in 1..8) {
            factor = 1
            upsampler = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supportedEncoding) return AudioProcessor.AudioFormat.NOT_SET

        val rateLimit = maxOutputSampleRateHz.coerceAtLeast(inputAudioFormat.sampleRate)
        factor = listOf(4, 2).firstOrNull { multiplier ->
            val targetRate = inputAudioFormat.sampleRate.toLong() * multiplier
            targetRate <= rateLimit && (supportedOutputSampleRates.isEmpty() || targetRate.toInt() in supportedOutputSampleRates)
        } ?: 1
        if (factor == 1) return AudioProcessor.AudioFormat.NOT_SET

        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        upsampler = Upsampler(factor, channels)
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate * factor,
            channels,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun onFlush() {
        if (factor > 1 && channels > 0) upsampler = Upsampler(factor, channels)
    }

    override fun onReset() {
        factor = 1
        channels = 0
        encoding = C.ENCODING_INVALID
        upsampler = null
        inputFloats = FloatArray(0)
        outputFloats = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (factor <= 1 || channels <= 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) return
        val inputCount = frames * channels
        val outputCount = inputCount * factor
        if (inputFloats.size < inputCount) inputFloats = FloatArray(inputCount)
        if (outputFloats.size < outputCount) outputFloats = FloatArray(outputCount)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until inputCount) {
            inputFloats[index] = when (encoding) {
                C.ENCODING_PCM_16BIT -> inputBuffer.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = inputBuffer.get().toInt() and 0xff
                    val b1 = inputBuffer.get().toInt() and 0xff
                    val b2 = inputBuffer.get().toInt() and 0xff
                    var sample = b0 or (b1 shl 8) or (b2 shl 16)
                    if (sample and 0x800000 != 0) sample = sample or -0x1000000
                    sample / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> (inputBuffer.int / 2_147_483_648.0).toFloat()
                C.ENCODING_PCM_FLOAT -> inputBuffer.float.takeIf(Float::isFinite) ?: 0f
                else -> 0f
            }
        }

        upsampler?.process(inputFloats, frames, outputFloats)
        val output = replaceOutputBuffer(outputCount * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until outputCount) output.putFloat(outputFloats[index])
        output.flip()
    }
}
