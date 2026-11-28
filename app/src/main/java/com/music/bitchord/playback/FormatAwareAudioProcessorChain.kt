package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

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
    silenceThresholdLevel: Short,
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

    val skippedFrames: Long get() = delegate.skippedFrames

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        delegate.setEnabled(enabled)
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        delegate.reset()
        delegate.setEnabled(enabled)
        configuredForPcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        if (!configuredForPcm16) return AudioProcessor.AudioFormat.NOT_SET
        return delegate.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = configuredForPcm16 && delegate.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (configuredForPcm16) delegate.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        if (configuredForPcm16) delegate.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer =
        if (configuredForPcm16) delegate.output else AudioProcessor.EMPTY_BUFFER

    override fun isEnded(): Boolean = !configuredForPcm16 || delegate.isEnded

    override fun flush() {
        if (configuredForPcm16) delegate.flush()
    }

    override fun reset() {
        delegate.reset()
        delegate.setEnabled(enabled)
        configuredForPcm16 = false
    }
}
