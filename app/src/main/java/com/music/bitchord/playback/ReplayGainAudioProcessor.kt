package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Applies ReplayGain to decoded PCM only; encoded audio and stream resolution are untouched. */
@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var gainDb: Float = 0f
        set(value) { field = value.coerceIn(-24f, 12f) }

    /** Independent user boost, kept separate from per-track ReplayGain metadata. */
    @Volatile
    var volumeBoostDb: Float = 0f
        set(value) { field = value.takeIf { it.isFinite() }?.coerceIn(0f, 12f) ?: 0f }

    @Volatile
    var preventClipping: Boolean = true

    /** Shared headroom trim used only while two players overlap. */
    @Volatile
    var blendTrim: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Media3 item id currently decoded by this player's chain. */
    @Volatile
    var normalizationMediaId: String? = null

    /** YouTube loudness gain, queried from the existing resolver cache on the audio thread. */
    @Volatile
    var normalizationGainDbFor: (String) -> Float? = { null }

    /** Strict bypass for bit-perfect mode; it also resets any in-flight gain glide. */
    @Volatile
    var normalizationBypassed: Boolean = false
        set(value) {
            field = value
            if (value) currentNormalizationGain = 1f
        }

    @Volatile
    private var currentNormalizationGain = 1f
    private var normalizationGlideCoefficient = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        normalizationGlideCoefficient = if (inputAudioFormat.sampleRate <= 0) 1f else
            (1.0 - exp(-1.0 / (0.04 * inputAudioFormat.sampleRate))).toFloat()
        return inputAudioFormat
    }

    override fun onFlush() {
        currentNormalizationGain = 1f
    }

    override fun onReset() {
        currentNormalizationGain = 1f
        normalizationGlideCoefficient = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val boostDb = volumeBoostDb
        val replayGain = 10f.pow(gainDb / 20f) * 10f.pow(boostDb / 20f) * blendTrim
        val mediaId = normalizationMediaId
        val targetNormalizationGain = if (normalizationBypassed || mediaId.isNullOrBlank()) 1f else {
            runCatching { normalizationGainDbFor(mediaId) }
                .getOrNull()
                ?.takeIf { it.isFinite() }
                ?.coerceIn(-15f, 3f)
                ?.let { 10f.pow(it / 20f) }
                ?: 1f
        }
        if (normalizationBypassed) currentNormalizationGain = 1f
        if (replayGain == 1f && targetNormalizationGain == 1f && currentNormalizationGain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                while (inputBuffer.remaining() >= Short.SIZE_BYTES * channels) {
                    currentNormalizationGain = glideNormalizationGain(targetNormalizationGain)
                    val frameGain = replayGain * currentNormalizationGain
                    repeat(channels) {
                        val scaled = inputBuffer.short * frameGain
                        output.putShort(scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    }
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                while (inputBuffer.remaining() >= Float.SIZE_BYTES * channels) {
                    currentNormalizationGain = glideNormalizationGain(targetNormalizationGain)
                    val frameGain = replayGain * currentNormalizationGain
                    repeat(channels) {
                        val sample = inputBuffer.float
                        val scaled = if (sample.isFinite()) sample * frameGain else 0f
                        output.putFloat(if (preventClipping || boostDb > 0f) scaled.coerceIn(-1f, 1f) else scaled)
                    }
                }
            }
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    private fun glideNormalizationGain(target: Float): Float =
        currentNormalizationGain + (target - currentNormalizationGain) * normalizationGlideCoefficient
}
