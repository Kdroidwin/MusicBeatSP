package com.music.bitchord.playback

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import jp.pioneer.itp.android.stellanovaplayer.common.MSRUtility
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/** Loads the optional ARM-only DSP without making unsupported builds fail at class initialization. */
internal object PioneerMsrNative {
    private val usesSupportedPageSize = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE) == 4_096L
    }.getOrDefault(false)
    val isAvailable: Boolean = usesSupportedPageSize && Build.SUPPORTED_ABIS.any {
        it == "arm64-v8a" || it == "armeabi-v7a"
    }

    @Volatile
    private var loadAttempted = false
    @Volatile
    private var loadSucceeded = false

    @Synchronized
    fun create(): MSRUtility? {
        if (!isAvailable || (loadAttempted && !loadSucceeded)) return null
        if (!loadAttempted) {
            loadAttempted = true
            loadSucceeded = runCatching { System.loadLibrary("mastersoundreviveSDK") }.isSuccess
        }
        return if (loadSucceeded) runCatching { MSRUtility() }.getOrNull() else null
    }
}

/**
 * Optional Pioneer MSR PCM stage. The native API consumes interleaved float
 * samples in half-second batches and has a 2,000-frame look-ahead. Smaller
 * Media3 buffers are accumulated to that contract. Every consumed frame is
 * emitted: if the DSP is not ready, fails, or produces an invalid batch, the
 * same audio is resampled to the negotiated 24-bit/96 kHz output instead.
 */
@UnstableApi
class PioneerMsrAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var outputSupports96k: Boolean = false

    private var inputRateHz = 0
    private var inputChannels = 0
    private var inputEncoding = C.ENCODING_INVALID
    private var native: MSRUtility? = null
    private var nativeFailed = false
    private var batchFrames = 0
    private var batchSamples = FloatArray(0)
    private var batchSamplesFilled = 0
    private var nativeOutput = DoubleArray(0)
    private var frameRemainder = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        clearNativeState()
        if (!enabled || !outputSupports96k || inputAudioFormat.sampleRate !in 32_000..48_000 ||
            inputAudioFormat.channelCount != 2 || !isPcmEncoding(inputAudioFormat.encoding)
        ) return AudioProcessor.AudioFormat.NOT_SET

        val engine = PioneerMsrNative.create() ?: return AudioProcessor.AudioFormat.NOT_SET
        val configured = runCatching {
            engine.reset0()
            engine.setMode0(MODE_PCM)
            engine.setSamplingRate0(inputAudioFormat.sampleRate)
        }.getOrDefault(false)
        if (!configured) return AudioProcessor.AudioFormat.NOT_SET

        inputRateHz = inputAudioFormat.sampleRate
        inputChannels = inputAudioFormat.channelCount
        inputEncoding = inputAudioFormat.encoding
        batchFrames = inputRateHz / 2
        batchSamples = FloatArray(batchFrames * inputChannels)
        native = engine
        return AudioProcessor.AudioFormat(OUTPUT_RATE_HZ, inputChannels, C.ENCODING_PCM_24BIT)
    }

    override fun onFlush() {
        batchSamplesFilled = 0
        frameRemainder = 0L
        nativeFailed = false
        runCatching {
            native?.reset0()
            native?.setMode0(MODE_PCM)
            native?.setSamplingRate0(inputRateHz)
        }.onFailure { nativeFailed = true }
    }

    override fun onQueueEndOfStream() {
        if (batchSamplesFilled <= 0 || inputRateHz <= 0 || inputChannels <= 0) return
        val frames = batchSamplesFilled / inputChannels
        val outputFrames = outputFramesFor(frames)
        emitFallback(batchSamples, frames, outputFrames)
        batchSamplesFilled = 0
    }

    override fun onReset() {
        clearNativeState()
    }

    private fun clearNativeState() {
        native = null
        nativeFailed = false
        batchFrames = 0
        batchSamples = FloatArray(0)
        batchSamplesFilled = 0
        nativeOutput = DoubleArray(0)
        frameRemainder = 0L
        inputRateHz = 0
        inputChannels = 0
        inputEncoding = C.ENCODING_INVALID
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputRateHz <= 0 || inputChannels <= 0) return
        val bytesPerSample = bytesPerSample(inputEncoding)
        val frameBytes = bytesPerSample * inputChannels
        if (frameBytes <= 0) return

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (nativeFailed) {
            val frames = inputBuffer.remaining() / frameBytes
            if (frames <= 0) return
            val samples = FloatArray(frames * inputChannels)
            decodeInput(inputBuffer, samples, frames)
            emitFallback(samples, frames, outputFramesFor(frames))
            return
        }

        while (inputBuffer.remaining() >= frameBytes) {
            val framesToCopy = minOf(
                inputBuffer.remaining() / frameBytes,
                (batchSamples.size - batchSamplesFilled) / inputChannels,
            )
            if (framesToCopy <= 0) break
            decodeInputInto(inputBuffer, batchSamples, batchSamplesFilled, framesToCopy)
            batchSamplesFilled += framesToCopy * inputChannels
            if (batchSamplesFilled == batchSamples.size) {
                processBatch(batchFrames)
                batchSamplesFilled = 0
                // BaseAudioProcessor has a single output buffer. Let Media3
                // drain this batch before accepting another full batch.
                return
            }
        }
    }

    private fun processBatch(frames: Int) {
        if (frames <= 0) return
        val outputFrames = outputFramesFor(frames)
        val engine = native
        if (engine == null || nativeFailed || frames <= LOOKAHEAD_FRAMES) {
            emitFallback(batchSamples, frames, outputFrames)
            return
        }

        val nativeInputFrames = frames - LOOKAHEAD_FRAMES
        val nativeOutputFrames = ((nativeInputFrames.toLong() * OUTPUT_RATE_HZ) / inputRateHz)
            .coerceIn(0L, outputFrames.toLong()).toInt()
        if (nativeOutputFrames <= 0) {
            emitFallback(batchSamples, frames, outputFrames)
            return
        }

        try {
            // The upstream decoder passes the interleaved float sample count
            // here (for stereo, twice the number of frames).
            if (!engine.isWriteBuffer(inputRateHz)) {
                emitFallback(batchSamples, frames, outputFrames)
                return
            }
            engine.setBuffer(batchSamples, batchSamples.size)
            val requiredNativeSamples = nativeOutputFrames * inputChannels
            if (nativeOutput.size < requiredNativeSamples) {
                nativeOutput = DoubleArray(requiredNativeSamples)
            }
            if (!engine.execute0(nativeOutput, nativeOutputFrames)) {
                nativeFailed = true
                emitFallback(batchSamples, frames, outputFrames)
                return
            }

            val output = replaceOutputBuffer(outputFrames * inputChannels * BYTES_PER_OUTPUT_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until requiredNativeSamples) {
                writeSample24(output, (nativeOutput[index] * MSR_OUTPUT_GAIN).toFloat())
            }
            emitFallbackTail(
                output = output,
                samples = batchSamples,
                frames = frames,
                inputStartFrame = nativeInputFrames,
                outputFrames = outputFrames - nativeOutputFrames,
            )
            output.flip()
        } catch (_: Exception) {
            nativeFailed = true
            emitFallback(batchSamples, frames, outputFrames)
        } catch (_: LinkageError) {
            nativeFailed = true
            emitFallback(batchSamples, frames, outputFrames)
        }
    }

    private fun decodeInput(buffer: ByteBuffer, destination: FloatArray, frames: Int) {
        decodeInputInto(buffer, destination, 0, frames)
    }

    private fun decodeInputInto(buffer: ByteBuffer, destination: FloatArray, destinationSampleOffset: Int, frames: Int) {
        val end = destinationSampleOffset + frames * inputChannels
        var index = destinationSampleOffset
        while (index < end) {
            destination[index++] = when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = buffer.get().toInt() and 0xff
                    val b1 = buffer.get().toInt() and 0xff
                    val b2 = buffer.get().toInt() and 0xff
                    var sample = b0 or (b1 shl 8) or (b2 shl 16)
                    if (sample and 0x800000 != 0) sample = sample or -0x1000000
                    sample / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> (buffer.int / 2_147_483_648.0).toFloat()
                C.ENCODING_PCM_FLOAT -> buffer.float.takeIf(Float::isFinite) ?: 0f
                else -> 0f
            }
        }
    }

    private fun outputFramesFor(inputFrames: Int): Int {
        if (inputRateHz <= 0 || inputFrames <= 0) return 0
        val scaledFrames = inputFrames.toLong() * OUTPUT_RATE_HZ + frameRemainder
        frameRemainder = scaledFrames % inputRateHz
        return (scaledFrames / inputRateHz).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun emitFallback(samples: FloatArray, inputFrames: Int, outputFrames: Int) {
        if (inputFrames <= 0 || outputFrames <= 0) return
        val output = replaceOutputBuffer(outputFrames * inputChannels * BYTES_PER_OUTPUT_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        emitFallbackRange(output, samples, inputFrames, 0, inputFrames, outputFrames)
        output.flip()
    }

    private fun emitFallbackTail(
        output: ByteBuffer,
        samples: FloatArray,
        frames: Int,
        inputStartFrame: Int,
        outputFrames: Int,
    ) {
        val tailFrames = (frames - inputStartFrame).coerceAtLeast(0)
        if (tailFrames <= 0 || outputFrames <= 0) return
        emitFallbackRange(output, samples, frames, inputStartFrame, tailFrames, outputFrames)
    }

    private fun emitFallbackRange(
        output: ByteBuffer,
        samples: FloatArray,
        totalInputFrames: Int,
        inputStartFrame: Int,
        inputFrames: Int,
        outputFrames: Int,
    ) {
        if (inputFrames <= 0 || outputFrames <= 0) return
        for (frame in 0 until outputFrames) {
            val position = frame.toDouble() * inputFrames / outputFrames
            val leftInRange = floor(position).toInt().coerceIn(0, inputFrames - 1)
            val rightInRange = (leftInRange + 1).coerceAtMost(inputFrames - 1)
            val fraction = (position - leftInRange).toFloat().coerceIn(0f, 1f)
            val left = inputStartFrame + leftInRange
            val right = inputStartFrame + rightInRange
            if (left !in 0 until totalInputFrames || right !in 0 until totalInputFrames) continue
            for (channel in 0 until inputChannels) {
                val a = samples[left * inputChannels + channel]
                val b = samples[right * inputChannels + channel]
                writeSample24(output, a + (b - a) * fraction)
            }
        }
    }

    private fun writeSample24(output: ByteBuffer, sample: Float) {
        val normalized = sample.takeIf(Float::isFinite)?.toDouble()?.coerceIn(-1.0, MAX_24BIT_NORMALIZED) ?: 0.0
        val value = (normalized * MAX_24BIT_INT).roundToInt()
        output.put((value and 0xff).toByte())
        output.put(((value ushr 8) and 0xff).toByte())
        output.put(((value ushr 16) and 0xff).toByte())
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun isPcmEncoding(encoding: Int): Boolean = bytesPerSample(encoding) > 0

    private companion object {
        const val MODE_PCM = 2
        const val OUTPUT_RATE_HZ = 96_000
        const val LOOKAHEAD_FRAMES = 2_000
        const val BYTES_PER_OUTPUT_SAMPLE = 3
        const val MSR_OUTPUT_GAIN = 1.26
        const val MAX_24BIT_INT = 8_388_607.0
        const val MAX_24BIT_NORMALIZED = 0.9999998807907104
    }
}
