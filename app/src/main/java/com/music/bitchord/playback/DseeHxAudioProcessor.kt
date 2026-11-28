package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.dsp.Upsampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/** Loads the optional DSEE HX kernel lazily, so ordinary playback never depends on it. */
internal object DseeHxNative {
    @Volatile
    private var loadAttempted = false
    @Volatile
    private var loadSucceeded = false

    @Synchronized
    fun isAvailable(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            loadSucceeded = runCatching { System.loadLibrary("bitchord_dsee") }.isSuccess
        }
        return loadSucceeded
    }

    @JvmStatic external fun nativeCreate(
        codecName: String,
        inputRate: Int,
        inputBits: Int,
        outputRate: Int,
        bitRate: Int,
    ): Long

    @JvmStatic external fun nativeReset(handle: Long): Boolean
    @JvmStatic external fun nativeInputHopFrames(handle: Long): Int
    @JvmStatic external fun nativeOutputHopFrames(handle: Long): Int
    @JvmStatic external fun nativeProcess(handle: Long, input: IntArray, hops: Int, output: FloatArray): Boolean
    @JvmStatic external fun nativeRelease(handle: Long)
}

/**
 * Opt-in stereo PCM bridge to the Music Center-aligned HEQ/DSX kernel.
 *
 * Only codecs accepted by the native kernel and identified from a local file
 * or the selected stream's declared format are sent through DSEE. In
 * particular, YouTube AAC can use the kernel while YouTube Opus remains a
 * normal PCM-float pass-through because the kernel has no Opus profile.
 * Unsupported rates or native errors fall back to the existing high-quality
 * FIR upsampler, never to silence. Bit-perfect routing disables this processor
 * in PlaybackService.
 */
@UnstableApi
class DseeHxAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    /** `null` means that this item does not have a positively identified codec. */
    @Volatile
    var sourceCodec: String? = null
        set(value) {
            if (field != value) {
                field = value
                nativeFailed = false
                nativeSuccessLogged = false
                lastBypassCodec = null
                sourceGeneration.incrementAndGet()
            }
        }

    /** Media id used to read the resolver's format for a remote stream. */
    @Volatile
    var sourceMediaId: String? = null
        set(value) {
            if (field != value) {
                field = value
                nativeFailed = false
                nativeSuccessLogged = false
                lastBypassCodec = null
                sourceGeneration.incrementAndGet()
            }
        }

    private val sourceGeneration = AtomicInteger(0)

    @Volatile
    var supportedOutputSampleRates: Set<Int> = emptySet()

    @Volatile
    var maxOutputSampleRateHz: Int = 48_000
        set(value) { field = value.coerceAtLeast(0) }

    private var inputRate = 0
    private var outputRate = 0
    private var channels = 0
    private var inputEncoding = C.ENCODING_INVALID
    private var inputBits = 0
    private var inputFrameBytes = 0
    private var framesPerHopIn = 0
    private var framesPerHopOut = 0
    private var outputFactor = 1
    private var nativeHandle = 0L
    private var nativeCodec: String? = null
    private var nativeBitRateBps = 0
    private var nativeSourceGeneration = -1
    private var nativeFailed = false
    private var nativeSuccessLogged = false
    private var lastBypassCodec: String? = null
    private var pendingBytes = ByteArray(0)
    private var pendingByteCount = 0
    private var intInput = IntArray(0)
    private var nativeOutput = FloatArray(0)
    private var floatInput = FloatArray(0)
    private var fallbackOutput = FloatArray(0)
    private var fallbackUpsampler: Upsampler? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        releaseNative()
        clearBuffers()
        if (!enabled || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val bytesPerSample = bytesPerSample(inputAudioFormat.encoding)
        if (bytesPerSample == 0) return AudioProcessor.AudioFormat.NOT_SET

        inputRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        inputEncoding = inputAudioFormat.encoding
        inputBits = when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
        inputFrameBytes = bytesPerSample * channels

        // Unknown online codecs remain at their input rate. A recognized
        // stream format can select the highest route-advertised rate in its
        // 44.1/48 kHz family.
        val (codec, _) = resolvedSourceFormat()
        outputRate = if (codec == null) inputRate else chooseOutputRate(inputRate)
        outputFactor = (outputRate / inputRate).takeIf { outputRate % inputRate == 0 } ?: 1
        if (outputFactor !in setOf(1, 2, 4)) {
            outputRate = inputRate
            outputFactor = 1
        }

        if (codec != null && DseeHxNative.isAvailable()) {
            prepareNativeForCurrentSource(reset = false)
            if (nativeHandle == 0L) {
                outputRate = inputRate
                outputFactor = 1
            }
        } else if (codec != null) {
            outputRate = inputRate
            outputFactor = 1
        }
        fallbackUpsampler = if (outputFactor > 1) Upsampler(outputFactor, channels) else null
        return AudioProcessor.AudioFormat(outputRate, channels, C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush() {
        pendingByteCount = 0
        nativeFailed = false
        fallbackUpsampler = if (outputFactor > 1 && channels > 0) Upsampler(outputFactor, channels) else null
        prepareNativeForCurrentSource(reset = true)
    }

    override fun onQueueEndOfStream() {
        if (pendingByteCount <= 0 || inputFrameBytes <= 0) return
        val frames = pendingByteCount / inputFrameBytes
        val completeBytes = frames * inputFrameBytes
        if (frames <= 0) {
            pendingByteCount = 0
            return
        }

        ensureFloatCapacity(frames * channels)
        val input = ByteBuffer.wrap(pendingBytes, 0, completeBytes).order(ByteOrder.LITTLE_ENDIAN)
        decodeFloats(input, floatInput, frames)

        val emittedFrames = frames * outputFactor
        ensureOutputCapacity(emittedFrames * channels)
        if (nativeHandle != 0L && !nativeFailed) {
            // The DSEE kernel consumes fixed hop sizes. Pad only its final hop,
            // then crop the produced tail back to the original media duration.
            ensureNativeCapacity(framesPerHopIn * channels, framesPerHopOut * channels)
            intInput.fill(0, 0, framesPerHopIn * channels)
            for (i in 0 until frames * channels) intInput[i] = floatToQ31(floatInput[i])
            if (runNative(1)) {
                nativeOutput.copyInto(fallbackOutput, 0, 0, emittedFrames * channels)
            } else {
                emitFallback(floatInput, frames, emittedFrames)
            }
        } else {
            emitFallback(floatInput, frames, emittedFrames)
        }
        writeOutput(fallbackOutput, emittedFrames * channels)
        pendingByteCount = 0
    }

    override fun onReset() {
        releaseNative()
        clearBuffers()
        inputRate = 0
        outputRate = 0
        channels = 0
        inputEncoding = C.ENCODING_INVALID
        inputBits = 0
        inputFrameBytes = 0
        framesPerHopIn = 0
        framesPerHopOut = 0
        outputFactor = 1
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputFrameBytes <= 0 || channels != 2) return
        prepareNativeForCurrentSource(reset = false)

        val incoming = inputBuffer.remaining()
        val totalBytes = pendingByteCount + incoming
        ensurePendingCapacity(totalBytes)
        if (pendingByteCount > 0) {
            System.arraycopy(pendingBytes, 0, pendingBytes, 0, pendingByteCount)
        }
        inputBuffer.get(pendingBytes, pendingByteCount, incoming)
        val completeFrames = totalBytes / inputFrameBytes
        val framesToProcess = if (nativeHandle != 0L && !nativeFailed && framesPerHopIn > 0) {
            completeFrames / framesPerHopIn * framesPerHopIn
        } else {
            completeFrames
        }
        if (framesToProcess <= 0) {
            pendingByteCount = totalBytes
            return
        }

        val processedBytes = framesToProcess * inputFrameBytes
        ensureFloatCapacity(framesToProcess * channels)
        val source = ByteBuffer.wrap(pendingBytes, 0, processedBytes).order(ByteOrder.LITTLE_ENDIAN)
        decodeFloats(source, floatInput, framesToProcess)
        val emittedFrames = framesToProcess * outputFactor
        ensureOutputCapacity(emittedFrames * channels)

        val canRunNative = nativeHandle != 0L && !nativeFailed &&
            framesToProcess % framesPerHopIn == 0
        if (canRunNative) {
            val hops = framesToProcess / framesPerHopIn
            ensureNativeCapacity(
                hops * framesPerHopIn * channels,
                hops * framesPerHopOut * channels,
            )
            val sampleBuffer = ByteBuffer.wrap(pendingBytes, 0, processedBytes).order(ByteOrder.LITTLE_ENDIAN)
            decodeQ31(sampleBuffer, intInput, framesToProcess)
            if (runNative(hops)) {
                nativeOutput.copyInto(fallbackOutput, 0, 0, emittedFrames * channels)
            } else {
                emitFallback(floatInput, framesToProcess, emittedFrames)
            }
        } else {
            emitFallback(floatInput, framesToProcess, emittedFrames)
        }

        writeOutput(fallbackOutput, emittedFrames * channels)
        val remaining = totalBytes - processedBytes
        if (remaining > 0) System.arraycopy(pendingBytes, processedBytes, pendingBytes, 0, remaining)
        pendingByteCount = remaining
    }

    private fun prepareNativeForCurrentSource(reset: Boolean) {
        val (codec, bitRateBps) = resolvedSourceFormat()
        val generation = sourceGeneration.get()
        if (!enabled || inputRate <= 0 || channels != 2 || !DseeHxNative.isAvailable()) {
            releaseNative()
            return
        }
        if (codec == null) {
            releaseNative()
            val declaredCodec = sourceMediaId
                ?.let { NerdStats.declaredFormat(it) }
                ?.codec
                ?.lowercase()
            if (!declaredCodec.isNullOrBlank() && lastBypassCodec != declaredCodec) {
                lastBypassCodec = declaredCodec
                TrackLog.i(
                    "DSEE",
                    "bypassing unsupported stream codec=$declaredCodec; ordinary playback retained",
                    about = sourceMediaId,
                )
            }
            return
        }
        lastBypassCodec = null
        if (nativeFailed) return
        if (nativeHandle != 0L && nativeCodec == codec && nativeBitRateBps == bitRateBps) {
            if (reset || nativeSourceGeneration != generation) {
                if (runCatching { DseeHxNative.nativeReset(nativeHandle) }.getOrDefault(false)) {
                    nativeSourceGeneration = generation
                    nativeSuccessLogged = false
                } else {
                    releaseNative()
                    nativeFailed = true
                }
            }
            return
        }
        releaseNative()
        nativeFailed = false
        val handle = runCatching {
            DseeHxNative.nativeCreate(codec, inputRate, inputBits, outputRate, bitRateBps)
        }.getOrDefault(0L)
        if (handle != 0L) {
            val inputHop = runCatching { DseeHxNative.nativeInputHopFrames(handle) }.getOrDefault(0)
            val outputHop = runCatching { DseeHxNative.nativeOutputHopFrames(handle) }.getOrDefault(0)
            if (inputHop > 0 && outputHop > 0 && outputHop == inputHop * outputFactor) {
                nativeHandle = handle
                nativeCodec = codec
                nativeBitRateBps = bitRateBps
                nativeSourceGeneration = generation
                framesPerHopIn = inputHop
                framesPerHopOut = outputHop
            } else {
                runCatching { DseeHxNative.nativeRelease(handle) }
                nativeFailed = true
            }
        } else {
            nativeFailed = true
            TrackLog.w(
                "DSEE",
                "native engine initialization failed for codec=$codec; ordinary playback retained",
                about = sourceMediaId,
            )
        }
    }

    private fun runNative(hops: Int): Boolean {
        val handle = nativeHandle
        if (handle == 0L || hops <= 0) return false
        val succeeded = runCatching {
            DseeHxNative.nativeProcess(handle, intInput, hops, nativeOutput)
        }.getOrDefault(false)
        if (succeeded && !nativeSuccessLogged) {
            nativeSuccessLogged = true
            TrackLog.i(
                "DSEE",
                "native processing active codec=$nativeCodec input=${inputRate}Hz output=${outputRate}Hz",
                about = sourceMediaId,
            )
        } else if (!succeeded) {
            nativeFailed = true
            TrackLog.w(
                "DSEE",
                "native processing failed for codec=$nativeCodec; ordinary playback retained",
                about = sourceMediaId,
            )
            releaseNative()
        }
        return succeeded
    }

    /** Only formats present in the resolver's selected-stream facts are trusted for remote input. */
    private fun resolvedSourceFormat(): Pair<String?, Int> {
        val declared = sourceMediaId?.let { NerdStats.declaredFormat(it) }
        val explicitCodec = normalizedCodec(sourceCodec)
        val declaredCodec = normalizedCodec(declared?.codec)
        val codec = explicitCodec ?: declaredCodec
        val bitRateBps = if (codec != null && codec == declaredCodec) {
            declared?.kbps
                ?.takeIf { it > 0 }
                ?.coerceAtMost(1_020)
                ?.times(1_000)
                ?: 0
        } else {
            0
        }
        return codec to bitRateBps
    }

    private fun emitFallback(input: FloatArray, frames: Int, emittedFrames: Int) {
        if (outputFactor > 1) {
            fallbackUpsampler?.process(input, frames, fallbackOutput)
        } else {
            input.copyInto(fallbackOutput, 0, 0, frames * channels)
        }
        // If a native error occurs mid-track, replace any non-finite sample
        // from a failed native call with the deterministic fallback output.
        for (index in 0 until emittedFrames * channels) {
            if (!fallbackOutput[index].isFinite()) fallbackOutput[index] = 0f
        }
    }

    private fun writeOutput(samples: FloatArray, count: Int) {
        val output = replaceOutputBuffer(count * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) output.putFloat(samples[index].takeIf(Float::isFinite) ?: 0f)
        output.flip()
    }

    private fun decodeFloats(input: ByteBuffer, output: FloatArray, frames: Int) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        val count = frames * channels
        for (index in 0 until count) {
            output[index] = when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> input.short / 32768f
                C.ENCODING_PCM_24BIT -> readPcm24(input) / 8_388_608f
                C.ENCODING_PCM_32BIT -> (input.int / 2_147_483_648.0).toFloat()
                C.ENCODING_PCM_FLOAT -> input.float.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                else -> 0f
            }
        }
    }

    private fun decodeQ31(input: ByteBuffer, output: IntArray, frames: Int) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        val count = frames * channels
        for (index in 0 until count) {
            output[index] = when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> input.short.toInt() shl 16
                C.ENCODING_PCM_24BIT -> readPcm24(input) shl 8
                C.ENCODING_PCM_32BIT -> input.int
                C.ENCODING_PCM_FLOAT -> floatToQ31(input.float)
                else -> 0
            }
        }
    }

    private fun readPcm24(input: ByteBuffer): Int {
        val b0 = input.get().toInt() and 0xff
        val b1 = input.get().toInt() and 0xff
        val b2 = input.get().toInt() and 0xff
        val value = b0 or (b1 shl 8) or (b2 shl 16)
        return if (value and 0x800000 != 0) value or -0x1000000 else value
    }

    private fun floatToQ31(sample: Float): Int {
        val normalized = sample.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        return (normalized.toDouble() * 2_147_483_648.0)
            .roundToLong()
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun chooseOutputRate(rate: Int): Int {
        if (rate !in SUPPORTED_DSEE_RATES) return rate
        val candidates = intArrayOf(rate * 4, rate * 2, rate)
            .filter { it <= 192_000 && it % rate == 0 }
        return candidates.firstOrNull { candidate ->
            if (supportedOutputSampleRates.isNotEmpty()) candidate in supportedOutputSampleRates
            else candidate <= maxOutputSampleRateHz
        } ?: rate
    }

    private fun normalizedCodec(codec: String?): String? = when (codec?.lowercase()?.substringBefore('.')) {
        "flac" -> "flac"
        "mp3" -> "mp3"
        "aac" -> "aac"
        "pcm", "wav", "pcm_s16le", "pcm_s24le", "pcm_s32le" -> "pcm_s16le"
        else -> null
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun ensurePendingCapacity(required: Int) {
        if (pendingBytes.size < required) pendingBytes = ByteArray(required.coerceAtLeast(4096))
    }

    private fun ensureFloatCapacity(required: Int) {
        if (floatInput.size < required) floatInput = FloatArray(required)
    }

    private fun ensureOutputCapacity(required: Int) {
        if (fallbackOutput.size < required) fallbackOutput = FloatArray(required)
        if (nativeOutput.size < required) nativeOutput = FloatArray(required)
    }

    private fun ensureNativeCapacity(inputSamples: Int, outputSamples: Int) {
        if (intInput.size < inputSamples) intInput = IntArray(inputSamples)
        if (nativeOutput.size < outputSamples) nativeOutput = FloatArray(outputSamples)
    }

    private fun releaseNative() {
        val handle = nativeHandle
        nativeHandle = 0L
        nativeCodec = null
        nativeBitRateBps = 0
        nativeSourceGeneration = -1
        nativeSuccessLogged = false
        framesPerHopIn = 0
        framesPerHopOut = 0
        if (handle != 0L) runCatching { DseeHxNative.nativeRelease(handle) }
    }

    private fun clearBuffers() {
        pendingBytes = ByteArray(0)
        pendingByteCount = 0
        intInput = IntArray(0)
        nativeOutput = FloatArray(0)
        floatInput = FloatArray(0)
        fallbackOutput = FloatArray(0)
        fallbackUpsampler = null
        nativeFailed = false
    }

    private companion object {
        val SUPPORTED_DSEE_RATES = setOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    }
}
