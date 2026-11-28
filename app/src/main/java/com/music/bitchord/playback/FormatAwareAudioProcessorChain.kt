package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Keeps Media3's silence skipper out of formats it cannot parse.
 *
 * Media3's SilenceSkippingAudioProcessor only accepts signed 16-bit PCM. The
 * app's optional DSEE HX and MSR processors can output float or 24-bit PCM, and
 * Media3 otherwise throws during sink configuration before playback starts.
 * For those formats the silence skipper is an inactive pass-through; ordinary
 * 16-bit PCM keeps the stock silence-skipping behavior and thresholds.
 */
internal class FormatAwareAudioProcessorChain(
    audioProcessors: Array<AudioProcessor>,
    private val silenceSkippingAudioProcessor: FormatAwareSilenceSkippingAudioProcessor,
    private val sonicAudioProcessor: SonicAudioProcessor,
) : DefaultAudioSink.AudioProcessorChain {
    private val audioProcessorChain = audioProcessors + silenceSkippingAudioProcessor + sonicAudioProcessor

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessorChain

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonicAudioProcessor.setSpeed(playbackParameters.speed)
        sonicAudioProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(enabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(enabled)
        return enabled
    }

    override fun getMediaDuration(durationUs: Long): Long =
        if (sonicAudioProcessor.isActive) sonicAudioProcessor.getMediaDuration(durationUs) else durationUs

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.skippedFrames
}

internal class FormatAwareSilenceSkippingAudioProcessor(
    minimumSilenceDurationUs: Long,
    silenceRetentionRatio: Float,
    maxSilenceToKeepDurationUs: Long,
    minVolumeToKeepPercentage: Int,
    private val silenceThresholdLevel: Short,
) : AudioProcessor {
    private val delegate = SilenceSkippingAudioProcessor(
        minimumSilenceDurationUs,
        silenceRetentionRatio,
        maxSilenceToKeepDurationUs,
        minVolumeToKeepPercentage,
        silenceThresholdLevel,
    )

    private var configuredForPcm16 = false
    private var enabled = false
    private var leadingAudioSeen = false
    private var passThroughOutputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var passThroughBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    val skippedFrames: Long get() = delegate.skippedFrames

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        delegate.setEnabled(enabled)
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        delegate.reset()
        delegate.setEnabled(enabled)
        configuredForPcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        leadingAudioSeen = false
        passThroughOutputBuffer = AudioProcessor.EMPTY_BUFFER
        if (!configuredForPcm16) return AudioProcessor.AudioFormat.NOT_SET
        return delegate.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = configuredForPcm16 && enabled && delegate.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!configuredForPcm16) return

        if (!leadingAudioSeen) {
            val containsAudibleSample = containsSampleAboveSilenceThreshold(inputBuffer)
            val inputSize = inputBuffer.remaining()
            if (inputSize > 0) {
                if (passThroughBuffer.capacity() < inputSize) {
                    passThroughBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
                }
                passThroughBuffer.clear()
                passThroughBuffer.limit(inputSize)
                passThroughBuffer.put(inputBuffer)
                passThroughBuffer.flip()
                passThroughOutputBuffer = passThroughBuffer
            }
            // Preserve the whole PCM block containing the first audible sample.
            // The skipper starts on the next block, retaining its normal behavior
            // for silence later in the track.
            if (containsAudibleSample) leadingAudioSeen = true
            return
        }

        delegate.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        if (configuredForPcm16) delegate.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer =
        when {
            passThroughOutputBuffer.hasRemaining() -> passThroughOutputBuffer.also {
                passThroughOutputBuffer = AudioProcessor.EMPTY_BUFFER
            }
            configuredForPcm16 -> delegate.output
            else -> AudioProcessor.EMPTY_BUFFER
        }

    override fun isEnded(): Boolean =
        !configuredForPcm16 || (!passThroughOutputBuffer.hasRemaining() && delegate.isEnded)

    override fun flush() {
        passThroughOutputBuffer = AudioProcessor.EMPTY_BUFFER
        leadingAudioSeen = false
        if (configuredForPcm16) delegate.flush()
    }

    override fun reset() {
        delegate.reset()
        delegate.setEnabled(enabled)
        configuredForPcm16 = false
        leadingAudioSeen = false
        passThroughOutputBuffer = AudioProcessor.EMPTY_BUFFER
        passThroughBuffer = AudioProcessor.EMPTY_BUFFER
    }

    private fun containsSampleAboveSilenceThreshold(inputBuffer: ByteBuffer): Boolean {
        val samples = inputBuffer.duplicate()
        val threshold = silenceThresholdLevel.toInt()
        while (samples.remaining() >= Short.SIZE_BYTES) {
            // AudioProcessor PCM16 buffers use Android's native byte order (little-endian).
            val lowByte = samples.get().toInt() and 0xFF
            val highByte = samples.get().toInt()
            val sample = (highByte shl 8) or lowByte
            if (abs(sample) > threshold) return true
        }
        return false
    }
}
