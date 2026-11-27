package com.music.bitchord.playback

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import jp.pioneer.itp.android.stellanovaplayer.common.MSRUtility
import java.io.ByteArrayOutputStream
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
 * Optional Pioneer MSR PCM stage. It only negotiates stereo PCM up to 48 kHz
 * to 24-bit/96 kHz when the current route advertises 96 kHz. It stays outside
 * the bit-perfect path and does not touch compressed, multichannel, or high-rate
 * source formats.
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
    private var pendingOutputFrames = 0L
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
        native = engine
        return AudioProcessor.AudioFormat(OUTPUT_RATE_HZ, inputChannels, C.ENCODING_PCM_24BIT)
    }

    override fun onFlush() {
        pendingOutputFrames = 0L
        frameRemainder = 0L
        nativeFailed = false
        runCatching {
            native?.reset0()
            native?.setMode0(MODE_PCM)
            native?.setSamplingRate0(inputRateHz)
        }.onFailure { nativeFailed = true }
    }

    override fun onReset() {
        clearNativeState()
    }

    private fun clearNativeState() {
        native = null
        nativeFailed = false
        pendingOutputFrames = 0L
        frameRemainder = 0L
        inputRateHz = 0
        inputChannels = 0
        inputEncoding = C.ENCODING_INVALID
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val engine = native ?: return
        val bytesPerSample = bytesPerSample(inputEncoding)
        val frameBytes = bytesPerSample * inputChannels
        val inputFrames = inputBuffer.remaining() / frameBytes
        if (inputFrames <= 0) return

        val samples = decodeInput(inputBuffer, inputFrames)
        val requestedOutputFrames = ((inputFrames.toLong() * OUTPUT_RATE_HZ) + frameRemainder) / inputRateHz
        frameRemainder = ((inputFrames.toLong() * OUTPUT_RATE_HZ) + frameRemainder) % inputRateHz

        if (nativeFailed) {
            emitFallback(samples, inputFrames, requestedOutputFrames.toInt())
            return
        }

        try {
            engine.setBuffer(samples, inputFrames)
            pendingOutputFrames = (pendingOutputFrames + requestedOutputFrames).coerceAtMost(MAX_PENDING_OUTPUT_FRAMES)
            val encoded = ByteArrayOutputStream()
            while (pendingOutputFrames > 0L && engine.isWriteBuffer(inputRateHz)) {
                val frames = minOf(pendingOutputFrames, MAX_NATIVE_CHUNK_FRAMES.toLong()).toInt()
                val processed = DoubleArray(frames * inputChannels)
                if (!engine.execute0(processed, frames)) break
                writePcm24(encoded, processed, applyMsrGain = true)
                pendingOutputFrames -= frames
            }
            val bytes = encoded.toByteArray()
            if (bytes.isNotEmpty()) {
                val output = replaceOutputBuffer(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                output.put(bytes)
                output.flip()
            }
            if (pendingOutputFrames >= MAX_PENDING_OUTPUT_FRAMES) {
                throw IllegalStateException("MSR output buffer did not become ready")
            }
        } catch (_: Exception) {
            nativeFailed = true
            pendingOutputFrames = 0L
            emitFallback(samples, inputFrames, requestedOutputFrames.toInt())
        } catch (_: LinkageError) {
            nativeFailed = true
            pendingOutputFrames = 0L
            emitFallback(samples, inputFrames, requestedOutputFrames.toInt())
        }
    }

    private fun decodeInput(buffer: ByteBuffer, frames: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames * inputChannels)
        for (index in samples.indices) {
            samples[index] = when (inputEncoding) {
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
        return samples
    }

    private fun emitFallback(samples: FloatArray, inputFrames: Int, outputFrames: Int) {
        if (inputFrames <= 0 || outputFrames <= 0) return
        val output = replaceOutputBuffer(outputFrames * inputChannels * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until outputFrames) {
            val position = frame.toDouble() * inputRateHz / OUTPUT_RATE_HZ
            val left = floor(position).toInt().coerceIn(0, inputFrames - 1)
            val right = (left + 1).coerceAtMost(inputFrames - 1)
            val fraction = (position - left).toFloat().coerceIn(0f, 1f)
            for (channel in 0 until inputChannels) {
                val a = samples[left * inputChannels + channel]
                val b = samples[right * inputChannels + channel]
                writeSample24(output, a + (b - a) * fraction)
            }
        }
        output.flip()
    }

    private fun writePcm24(output: ByteArrayOutputStream, samples: DoubleArray, applyMsrGain: Boolean) {
        for (sample in samples) {
            val gain = if (applyMsrGain) MSR_OUTPUT_GAIN else 1.0
            val normalized = (sample.takeIf(Double::isFinite) ?: 0.0) * gain
            val value = (normalized.coerceIn(-1.0, MAX_24BIT_NORMALIZED) * MAX_24BIT_INT).roundToInt()
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            output.write((value ushr 16) and 0xff)
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
        const val MAX_NATIVE_CHUNK_FRAMES = 8_192
        const val MAX_PENDING_OUTPUT_FRAMES = 96_000L
        const val MSR_OUTPUT_GAIN = 1.26
        const val MAX_24BIT_INT = 8_388_607.0
        const val MAX_24BIT_NORMALIZED = 0.9999998807907104
    }
}
