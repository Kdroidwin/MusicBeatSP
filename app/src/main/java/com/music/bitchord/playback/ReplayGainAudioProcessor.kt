package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Applies ReplayGain to decoded PCM only; encoded audio and stream resolution are untouched. */
@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var gainDb: Float = 0f
        set(value) { field = value.coerceIn(-24f, 12f) }

    @Volatile
    var preventClipping: Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val gain = 10f.pow(gainDb / 20f)
        if (gain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                    val scaled = inputBuffer.short * gain
                    output.putShort(scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.remaining() >= Float.SIZE_BYTES) {
                    val sample = inputBuffer.float
                    val scaled = if (sample.isFinite()) sample * gain else 0f
                    output.putFloat(if (preventClipping) scaled.coerceIn(-1f, 1f) else scaled)
                }
            }
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }
}
