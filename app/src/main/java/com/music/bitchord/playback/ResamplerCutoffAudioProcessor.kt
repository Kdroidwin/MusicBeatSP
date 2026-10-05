package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp

/**
 * Optional one-pole low-pass before the AudioTrack output conversion.
 * A cutoff of zero is a byte-for-byte pass-through, which is the default.
 */
@UnstableApi
class ResamplerCutoffAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var cutoffHz: Int = 0
        set(value) { field = value.coerceIn(0, 22_000) }

    private var sampleRateHz = 0
    private var channelCount = 0
    private var previousSamples = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET

        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        previousSamples = FloatArray(channelCount.coerceAtLeast(1))
        return inputAudioFormat
    }

    override fun onFlush() {
        previousSamples.fill(0f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val cutoff = cutoffHz
        val nyquistHz = sampleRateHz / 2
        if (cutoff == 0 || cutoff >= nyquistHz || channelCount <= 0) {
            if (cutoff == 0) previousSamples.fill(0f)
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        val alpha = exp(-2.0 * PI * cutoff / sampleRateHz).toFloat()
        val keepPrevious = alpha
        val takeInput = 1f - alpha
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var channel = 0
                while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                    val source = inputBuffer.short.toFloat()
                    val filtered = takeInput * source + keepPrevious * previousSamples[channel]
                    previousSamples[channel] = filtered
                    output.putShort(filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    channel = (channel + 1) % channelCount
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var channel = 0
                while (inputBuffer.remaining() >= Float.SIZE_BYTES) {
                    val source = inputBuffer.float.takeIf { it.isFinite() } ?: 0f
                    val filtered = takeInput * source + keepPrevious * previousSamples[channel]
                    previousSamples[channel] = filtered
                    output.putFloat(filtered.takeIf(Float::isFinite) ?: 0f)
                    channel = (channel + 1) % channelCount
                }
            }
            else -> output.put(inputBuffer)
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }
}
