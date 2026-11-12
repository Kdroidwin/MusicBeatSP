package com.music.bitchord.feature.homeai

import android.content.Context
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.music.bitchord.data.lyrics.CtcFfmpegDecoder
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.LocalAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional, on-device-only music mood analysis for Home. The model pair is
 * fetched only after the listener opts in; no audio is sent off-device.
 *
 * The 14.2 MB YAMNet embedding model plus the two small MTG mood heads are
 * published by Essentia/UPF under CC BY-NC-SA 4.0. Their metadata describes a
 * 96x64 log-mel input, a 1024-wide embedding, and the two mood probabilities.
 */
object LocalHomeAi {
    private const val PREFS = "local_home_ai"
    private const val KEY_MODEL_DECISION = "model_download_decision"
    private const val MODEL_VERSION = "essentia-yamnet-mood-v1"
    private const val MODEL_HOST = "essentia.upf.edu"
    private const val MAX_AUDIO_SECONDS = 18.0
    private const val MAX_PATCHES_PER_TRACK = 8
    private const val SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 512
    private const val FRAME_SIZE = 400
    private const val FRAME_STEP = 160
    private const val MEL_BANDS = 64
    private const val PATCH_FRAMES = 96
    private const val DOWNLOAD_LIMIT_BYTES = 16_000_000L

    data class MoodScores(val uplifting: Float, val calm: Float)
    data class DownloadProgress(val bytes: Long, val totalBytes: Long)

    private data class ModelFile(
        val name: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )

    private val models = listOf(
        ModelFile(
            name = "audioset-yamnet-1.onnx",
            url = "https://essentia.upf.edu/models/audio-event-recognition/yamnet/audioset-yamnet-1.onnx",
            bytes = 14_934_724L,
            sha256 = "9bc15ac91426e431527196ee6663de78dffcc7db53ac002d5afbda61429b456f",
        ),
        ModelFile(
            name = "mood_happy-audioset-yamnet-1.onnx",
            url = "https://essentia.upf.edu/models/classification-heads/mood_happy/mood_happy-audioset-yamnet-1.onnx",
            bytes = 411_698L,
            sha256 = "58a98eb81697410b42fbc62f24aca8e682b0401d5d327bf95e9737590a7cd6b3",
        ),
        ModelFile(
            name = "mood_relaxed-audioset-yamnet-1.onnx",
            url = "https://essentia.upf.edu/models/classification-heads/mood_relaxed/mood_relaxed-audioset-yamnet-1.onnx",
            bytes = 411_702L,
            sha256 = "fc0e5303c7b6c6d706c195b56badf2e2b46a5c859fad83c96e6e060af2423157",
        ),
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()
    @Volatile private var verificationFingerprint: String? = null
    @Volatile private var verificationResult: Boolean = false
    private val verificationLock = Any()

    /** null means not asked yet, true means opted in, false means declined. */
    fun modelDecision(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_MODEL_DECISION)) prefs.getBoolean(KEY_MODEL_DECISION, false) else null
    }

    fun setModelDecision(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MODEL_DECISION, accepted).apply()
    }

    fun isModelInstalled(context: Context): Boolean {
        val directory = modelDirectory(context)
        val fingerprint = models.joinToString("|") { model ->
            val file = File(directory, model.name)
            "${file.name}:${file.length()}:${file.lastModified()}:${file.isFile}"
        }
        if (fingerprint == verificationFingerprint) return verificationResult
        synchronized(verificationLock) {
            if (fingerprint == verificationFingerprint) return verificationResult
            val valid = models.all { model ->
                val file = File(directory, model.name)
                file.isFile && file.length() == model.bytes && sha256(file) == model.sha256
            }
            verificationFingerprint = fingerprint
            verificationResult = valid
            return valid
        }
    }

    suspend fun downloadModels(context: Context) = withContext(Dispatchers.IO) {
        val directory = modelDirectory(context).apply { mkdirs() }
        val total = models.sumOf(ModelFile::bytes)
        var completed = 0L
        try {
            models.forEach { model ->
                val destination = File(directory, model.name)
                if (destination.isFile && destination.length() == model.bytes && sha256(destination) == model.sha256) {
                    completed += model.bytes
                    _downloadProgress.value = DownloadProgress(completed, total)
                    return@forEach
                }
                destination.delete()
                val temporary = File(directory, "${model.name}.part")
                temporary.delete()
                val connection = URL(model.url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 45_000
                    // Keep downloads pinned to the fixed official host.
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "MusicBeatSP-LocalMood/1")
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Model download returned HTTP ${connection.responseCode}"
                    }
                    check(connection.url.host.equals(MODEL_HOST, ignoreCase = true)) {
                        "Model URL is not hosted by Essentia"
                    }
                    val contentLength = connection.contentLengthLong
                    check(contentLength < 0L || contentLength == model.bytes) { "Unexpected model size" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var modelBytes = 0L
                    var lastProgress = 0L
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                modelBytes += count
                                check(modelBytes <= model.bytes && modelBytes <= DOWNLOAD_LIMIT_BYTES) {
                                    "Model exceeded its verified size limit"
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                if (modelBytes - lastProgress >= 256 * 1024) {
                                    lastProgress = modelBytes
                                    _downloadProgress.value = DownloadProgress(completed + modelBytes, total)
                                }
                            }
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(modelBytes == model.bytes && actualHash == model.sha256) {
                        "Model verification failed; the incomplete file was discarded"
                    }
                    check(temporary.renameTo(destination)) { "Could not install the verified model" }
                    completed += model.bytes
                    _downloadProgress.value = DownloadProgress(completed, total)
                } finally {
                    connection.disconnect()
                    temporary.delete()
                }
            }
            check(isModelInstalled(context)) { "The model set is incomplete" }
        } catch (error: Throwable) {
            models.forEach { File(directory, "${it.name}.part").delete() }
            throw error
        } finally {
            _downloadProgress.value = null
        }
    }

    /** Returns at most one cached mood record per local song. */
    fun cachedScores(context: Context, songs: List<Song>): Map<String, MoodScores> {
        val prefs = context.getSharedPreferences("local_home_ai_mood_cache", Context.MODE_PRIVATE)
        return buildMap {
            songs.forEach { song ->
                val key = cacheKey(song)
                val uplifting = prefs.getFloat("${MODEL_VERSION}_${key}_up", Float.NaN)
                val calm = prefs.getFloat("${MODEL_VERSION}_${key}_calm", Float.NaN)
                if (uplifting.isFinite() && calm.isFinite()) put(key, MoodScores(uplifting, calm))
            }
        }
    }

    fun cachedScore(context: Context, song: Song): MoodScores? = cachedScores(context, listOf(song))[cacheKey(song)]

    fun saveScore(context: Context, song: Song, score: MoodScores) {
        val key = cacheKey(song)
        context.getSharedPreferences("local_home_ai_mood_cache", Context.MODE_PRIVATE)
            .edit()
            .putFloat("${MODEL_VERSION}_${key}_up", score.uplifting)
            .putFloat("${MODEL_VERSION}_${key}_calm", score.calm)
            .apply()
    }

    suspend fun classify(context: Context, song: Song): MoodScores? = withContext(Dispatchers.IO) {
        cachedScore(context, song)?.let { return@withContext it }
        val audioUri = song.localUri?.takeIf(String::isNotBlank)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: song.localPath?.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)) }
            ?: return@withContext null
        if (!LocalAudioSource.isLocal(audioUri)) return@withContext null

        val operationContext = currentCoroutineContext()
        val start = (song.localStartMs ?: 0L).coerceAtLeast(0L) / 1000.0
        val metadataDuration = song.durationMillis().takeIf { it > 0L }?.div(1000.0)
        val endHint = min(start + MAX_AUDIO_SECONDS, metadataDuration?.plus(start) ?: (start + MAX_AUDIO_SECONDS))
        val source = LocalAudioSource.open(context.contentResolver, audioUri)
        val pcmAndStart = if (source != null) {
            source.use { dataSource ->
                val duration = AudioDecoder.containerDurationSeconds(dataSource) ?: endHint
                val end = min(endHint, duration)
                val platform = AudioDecoder.decodeRegion(dataSource, start, end) { !operationContext.isActive }
                operationContext.ensureActive()
                platform ?: CtcFfmpegDecoder.decode(context, song, audioUri, start, end)
            }
        } else {
            val duration = metadataDuration?.plus(start) ?: return@withContext null
            CtcFfmpegDecoder.decode(context, song, audioUri, start, min(start + MAX_AUDIO_SECONDS, duration))
        }
        operationContext.ensureActive()
        val pcm16k = resample(pcmAndStart.first.samples, pcmAndStart.first.sampleRate)
        if (pcm16k.size < FRAME_SIZE) return@withContext null
        val patches = melPatches(pcm16k)
        if (patches.isEmpty()) return@withContext null
        val score = withModels(context) { runtime -> runtime.predict(patches) }
        saveScore(context, song, score)
        score
    }

    private fun modelDirectory(context: Context) = File(context.noBackupFilesDir, "home_ai_models/$MODEL_VERSION")

    private fun cacheKey(song: Song): String {
        val identity = song.localUri?.takeIf(String::isNotBlank)
            ?: song.localPath?.takeIf(String::isNotBlank)
            ?: song.videoId
        return digest(identity + "|" + song.durationText + "|" + song.localStartMs + "|" + song.localEndMs).take(32)
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class Runtime(
        val yamnet: OrtSession,
        val happy: OrtSession,
        val relaxed: OrtSession,
    ) {
        fun predict(patches: List<FloatArray>): MoodScores {
            val env = OrtEnvironment.getEnvironment()
            var happyTotal = 0.0
            var calmTotal = 0.0
            var count = 0
            patches.forEach { patch ->
                val embeddings = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(patch),
                    longArrayOf(1L, PATCH_FRAMES.toLong(), MEL_BANDS.toLong()),
                ).use { input ->
                    yamnet.run(mapOf(yamnet.inputNames.first() to input)).use { output ->
                        val embeddingName = yamnet.outputInfo.entries.firstOrNull { (_, info) ->
                            val tensor = info.info as? TensorInfo
                            tensor?.shape?.lastOrNull() == 1024L
                        }?.key ?: yamnet.outputNames.first()
                        val embeddingIndex = yamnet.outputNames.indexOf(embeddingName).coerceAtLeast(0)
                        flattenFloat(output[embeddingIndex].value)
                    }
                }
                if (embeddings.size < 1024) return@forEach
                val feature = embeddings.copyOf(1024)
                val happyScore = inferMoodHead(happy, feature, 0)
                val calmScore = inferMoodHead(relaxed, feature, 1)
                if (happyScore.isFinite() && calmScore.isFinite()) {
                    happyTotal += happyScore
                    calmTotal += calmScore
                    count++
                }
            }
            return if (count == 0) MoodScores(0.5f, 0.5f) else MoodScores(
                uplifting = (happyTotal / count).toFloat().coerceIn(0f, 1f),
                calm = (calmTotal / count).toFloat().coerceIn(0f, 1f),
            )
        }

        private fun inferMoodHead(session: OrtSession, embedding: FloatArray, classIndex: Int): Float {
            val env = OrtEnvironment.getEnvironment()
            val inputName = session.inputNames.first()
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(embedding), longArrayOf(1L, embedding.size.toLong())).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    flattenFloat(result[0].value).getOrElse(classIndex) { Float.NaN }
                }
            }
        }

        companion object {
            private fun flattenFloat(value: Any?): FloatArray = when (value) {
                is FloatArray -> value
                is Array<*> -> value.flatMap { flattenFloat(it).asIterable() }.toFloatArray()
                else -> FloatArray(0)
            }
        }
    }

    @Volatile private var runtime: Runtime? = null
    private val runtimeLock = Any()

    private suspend fun <T> withModels(context: Context, block: (Runtime) -> T): T = withContext(Dispatchers.IO) {
        check(isModelInstalled(context)) { "AI model files are missing or failed verification" }
        val active = runtime ?: synchronized(runtimeLock) {
            runtime ?: run {
                val directory = modelDirectory(context)
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                val environment = OrtEnvironment.getEnvironment()
                val opened = ArrayList<OrtSession>(models.size)
                try {
                    val yamnet = environment.createSession(File(directory, models[0].name).absolutePath, options).also(opened::add)
                    val happy = environment.createSession(File(directory, models[1].name).absolutePath, options).also(opened::add)
                    val relaxed = environment.createSession(File(directory, models[2].name).absolutePath, options).also(opened::add)
                    Runtime(yamnet, happy, relaxed).also { runtime = it }
                } catch (error: Throwable) {
                    opened.forEach { runCatching(it::close) }
                    throw error
                } finally {
                    options.close()
                }
            }
        }
        block(active)
    }

    private fun resample(samples: FloatArray, sourceRate: Double): FloatArray {
        if (samples.isEmpty() || sourceRate <= 0.0 || !sourceRate.isFinite()) return FloatArray(0)
        if (sourceRate.roundToInt() == SAMPLE_RATE) return samples
        val size = (samples.size * SAMPLE_RATE / sourceRate).roundToInt().coerceAtLeast(1)
        return FloatArray(size) { index ->
            val position = index * sourceRate / SAMPLE_RATE
            val before = position.toInt().coerceIn(0, samples.lastIndex)
            val after = min(before + 1, samples.lastIndex)
            val fraction = (position - before).toFloat()
            samples[before] * (1f - fraction) + samples[after] * fraction
        }
    }

    /** Converts 16 kHz mono PCM to YAMNet's 96x64 log-mel input patches. */
    private fun melPatches(samples: FloatArray): List<FloatArray> {
        val frameCount = max(PATCH_FRAMES, ceil(samples.size.toDouble() / FRAME_STEP).toInt())
        val framesToUse = min(frameCount, 2_000)
        val filter = melFilters()
        val spectrogram = FloatArray(framesToUse * MEL_BANDS)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        for (frame in 0 until framesToUse) {
            real.fill(0.0)
            imaginary.fill(0.0)
            val start = frame * FRAME_STEP
            for (index in 0 until FRAME_SIZE) {
                val sampleIndex = start + index
                val sample = if (sampleIndex < samples.size) samples[sampleIndex] else 0f
                // TensorFlow's STFT uses a periodic Hann window.
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / FRAME_SIZE)
                real[index] = sample * window
            }
            fft(real, imaginary)
            val magnitude = DoubleArray(FFT_SIZE / 2 + 1) { bin -> sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) }
            for (band in 0 until MEL_BANDS) {
                var energy = 0.0
                val weights = filter[band]
                for (bin in weights.indices) energy += magnitude[bin] * weights[bin]
                spectrogram[frame * MEL_BANDS + band] = ln(max(0.0, energy) + 0.001).toFloat()
            }
        }
        val patches = ArrayList<FloatArray>()
        var patchStart = 0
        while (patchStart < framesToUse && patches.size < MAX_PATCHES_PER_TRACK) {
            val patch = FloatArray(PATCH_FRAMES * MEL_BANDS)
            val availableFrames = min(PATCH_FRAMES, framesToUse - patchStart)
            spectrogram.copyInto(
                destination = patch,
                destinationOffset = 0,
                startIndex = patchStart * MEL_BANDS,
                endIndex = (patchStart + availableFrames) * MEL_BANDS,
            )
            patches += patch
            patchStart += PATCH_FRAMES / 2
        }
        return patches
    }

    private fun melFilters(): Array<DoubleArray> {
        val lowMel = hzToMel(125.0)
        val highMel = hzToMel(7_500.0)
        val edgeMels = DoubleArray(MEL_BANDS + 2) { index ->
            lowMel + (highMel - lowMel) * index / (MEL_BANDS + 1)
        }
        val binMels = DoubleArray(FFT_SIZE / 2 + 1) { bin ->
            hzToMel(bin * SAMPLE_RATE.toDouble() / FFT_SIZE)
        }
        return Array(MEL_BANDS) { band ->
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val mel = binMels[bin]
                when {
                    mel < edgeMels[band] || mel > edgeMels[band + 2] -> 0.0
                    mel <= edgeMels[band + 1] -> (mel - edgeMels[band]) / (edgeMels[band + 1] - edgeMels[band])
                    else -> (edgeMels[band + 2] - mel) / (edgeMels[band + 2] - edgeMels[band + 1])
                }.coerceAtLeast(0.0)
            }
        }
    }

    private fun hzToMel(hz: Double) = 1_127.0 * ln(1.0 + hz / 700.0)

    /** In-place radix-2 FFT; the YAMNet patch only needs 257 real bins. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realSwap = real[i]
                real[i] = real[j]
                real[j] = realSwap
                val imaginarySwap = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginarySwap
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (offset in 0 until size step length) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (index in 0 until length / 2) {
                    val even = offset + index
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
            }
            length = length shl 1
        }
    }
}
