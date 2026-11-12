package com.music.bitchord.feature.homeai

import android.content.Context
import android.net.Uri
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.data.lyrics.CtcFfmpegDecoder
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.LocalAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional, on-device-only music mood analysis for Home. The model set is
 * fetched only after the listener opts in; no audio is sent off-device.
 *
 * Music2Emo's valence/arousal head consumes layer-5/6 pooled embeddings from
 * MERT-v1-95M. Its optional chord/key context is represented as unknown/no
 * chord here; audio is never sent off-device.
 */
object LocalHomeAi {
    private const val PREFS = "local_home_ai"
    private const val MODEL_VERSION = "music2emo-mert95m-jall-v1"
    private const val KEY_MODEL_DECISION = "${MODEL_VERSION}_model_download_decision"
    private val LEGACY_MODEL_VERSIONS = listOf(
        "essentia-discogs-effnet-moods-v2",
        "essentia-yamnet-mood-v1",
        "essentia-deam-msd-musicnn-v1",
    )
    private const val MODEL_HOST = "raw.githubusercontent.com"
    private const val ANALYSIS_WINDOW_SECONDS = 4.2
    private const val SAMPLE_RATE = 24_000
    private const val MERT_FEATURE_SIZE = 768
    private const val MERT_LAYER_COUNT = 2
    private const val M2E_CHORD_SEQUENCE_LENGTH = 100
    private const val UNKNOWN_CHORD_ROOT = 0L // Music2Emo's chord_root.json maps N -> 0.
    private const val UNKNOWN_CHORD_ATTRIBUTE = 0L
    private const val MAJOR_MODE = 0f
    private const val DOWNLOAD_LIMIT_BYTES = 128_000_000L
    // Bump this when the inference pipeline changes so tracks skipped by a
    // previous broken tensor shape are retried automatically.
    private const val KEY_FAILED_TRACKS = "${MODEL_VERSION}_failed_tracks_v2"
    private const val MAX_FAILED_TRACKS = 512
    private const val TAG = "LocalHomeAi"
    // Official Music2Emo tag_list.npy mood/theme slice indices (127..182).
    private val UPLIFTING_TAG_INDICES = intArrayOf(18, 26, 29, 30, 35, 38, 39, 54, 55)
    private val CALM_TAG_INDICES = intArrayOf(5, 16, 32, 37, 41, 46, 47)

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
            name = "music2emo-mert-layers-5-6.onnx",
            url = "https://raw.githubusercontent.com/Kdroidwin/MusicBeatSP/main/model-assets/music2emo-mert-layers-5-6.onnx",
            bytes = 86_839_269L,
            sha256 = "2f35ec389db172fb3d6279b0650f59d0171229b62bd23df7b3cc0fe000b09a36",
        ),
        ModelFile(
            name = "music2emo-j-all-head.onnx",
            url = "https://raw.githubusercontent.com/Kdroidwin/MusicBeatSP/main/model-assets/music2emo-j-all-head.onnx",
            bytes = 4_327_266L,
            sha256 = "256c20d2af88c7fb15796ab0bc4a9d2775c164bc262c654bde5ae247dad782dc",
        ),
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()
    private val classificationMutex = Mutex()
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
                    connection.readTimeout = 120_000
                    // Keep downloads pinned to the fixed project asset host.
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "MusicBeatSP-LocalMood/1")
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Model download returned HTTP ${connection.responseCode}"
                    }
                    check(connection.url.host.equals(MODEL_HOST, ignoreCase = true)) {
                        "Model URL is not hosted by the pinned MusicBeatSP model-assets host"
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
            LEGACY_MODEL_VERSIONS.forEach { version ->
                File(context.noBackupFilesDir, "home_ai_models/$version").deleteRecursively()
            }
            context.getSharedPreferences("local_home_ai_mood_cache", Context.MODE_PRIVATE)
                .edit().clear().apply()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove("essentia-discogs-effnet-moods-v2_failed_tracks_v2")
                .remove("essentia-yamnet-mood-v1_failed_tracks_v2")
                .remove("essentia-deam-msd-musicnn-v1_failed_tracks_v2")
                .apply()
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

    fun hasAnalysisFailure(context: Context, song: Song): Boolean =
        cacheKey(song) in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAILED_TRACKS, emptySet()).orEmpty()

    fun markAnalysisFailure(context: Context, song: Song) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = LinkedHashSet(prefs.getStringSet(KEY_FAILED_TRACKS, emptySet()).orEmpty())
        updated.add(cacheKey(song))
        while (updated.size > MAX_FAILED_TRACKS) updated.remove(updated.first())
        prefs.edit().putStringSet(KEY_FAILED_TRACKS, updated).apply()
    }

    fun clearAnalysisFailure(context: Context, song: Song) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_FAILED_TRACKS, emptySet()).orEmpty() - cacheKey(song)
        prefs.edit().putStringSet(KEY_FAILED_TRACKS, updated).apply()
    }

    fun clearAnalysisFailures(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FAILED_TRACKS).apply()
    }

    suspend fun classify(context: Context, song: Song): MoodScores? = classificationMutex.withLock {
      withContext(Dispatchers.IO) {
       try {
        cachedScore(context, song)?.let { return@withContext it }
        val audioUri = song.localUri?.takeIf(String::isNotBlank)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: song.localPath?.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)) }
            ?: return@withContext null
        if (!LocalAudioSource.isLocal(audioUri)) return@withContext null

        val operationContext = currentCoroutineContext()
        val start = (song.localStartMs ?: 0L).coerceAtLeast(0L) / 1000.0
        val cueEnd = song.localEndMs?.takeIf { it > (song.localStartMs ?: 0L) }?.div(1000.0)
        val metadataDuration = song.durationMillis().takeIf { it > 0L }?.div(1000.0)
        val source = LocalAudioSource.open(context.contentResolver, audioUri)
        val audioSegments = if (source != null) {
            source.use { dataSource ->
                val containerDuration = AudioDecoder.containerDurationSeconds(dataSource)
                val end = listOfNotNull(containerDuration, cueEnd, metadataDuration?.let { start + it })
                    .minOrNull() ?: (start + ANALYSIS_WINDOW_SECONDS)
                analysisWindows(start, end).mapNotNull { (regionStart, regionEnd) ->
                    operationContext.ensureActive()
                    val decoded = AudioDecoder.decodeRegion(dataSource, regionStart, regionEnd) { !operationContext.isActive }
                        ?: CtcFfmpegDecoder.decode(context, song, audioUri, regionStart, regionEnd)
                        ?: return@mapNotNull null
                    resample(decoded.first.samples, decoded.first.sampleRate)
                }
            }
        } else {
            val end = min(
                cueEnd ?: (metadataDuration?.let { start + it } ?: (start + ANALYSIS_WINDOW_SECONDS)),
                metadataDuration?.let { start + it } ?: Double.POSITIVE_INFINITY,
            )
            analysisWindows(start, end).mapNotNull { (regionStart, regionEnd) ->
                operationContext.ensureActive()
                val decoded = CtcFfmpegDecoder.decode(context, song, audioUri, regionStart, regionEnd)
                    ?: return@mapNotNull null
                resample(decoded.first.samples, decoded.first.sampleRate)
            }
        }
        operationContext.ensureActive()
        // Music2Emo pools MERT layer 5 and 6 over each audio segment, then
        // averages the segment embeddings before its emotion head.
        if (audioSegments.isEmpty()) {
            markAnalysisFailure(context, song)
            return@withContext null
        }
        val score = withModels(context) { runtime -> runtime.predict(audioSegments) }
        saveScore(context, song, score)
        clearAnalysisFailure(context, song)
        score
       } catch (cancelled: CancellationException) {
            throw cancelled
       } catch (memoryError: OutOfMemoryError) {
            Log.e(TAG, "Mood analysis ran out of memory for ${song.title}; releasing ONNX sessions", memoryError)
            releaseRuntimeAfterMemoryPressure()
            throw memoryError
       } catch (error: Exception) {
            Log.w(TAG, "Skipping mood analysis for ${song.title}", error)
            markAnalysisFailure(context, song)
            null
       }
      }
    }

    private fun modelDirectory(context: Context) = File(context.noBackupFilesDir, "home_ai_models/$MODEL_VERSION")

    /** Samples distinct sections so a long intro or outro cannot dominate the mood estimate. */
    private fun analysisWindows(start: Double, end: Double): List<Pair<Double, Double>> {
        val duration = (end - start).takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
        val length = min(ANALYSIS_WINDOW_SECONDS, duration)
        val latestStart = (end - length).coerceAtLeast(start)
        val ratios = when {
            duration >= 30.0 -> listOf(0.02, 0.50, 0.96)
            duration > length * 1.7 -> listOf(0.02, 0.96)
            else -> listOf(0.02)
        }
        return ratios.map { ratio ->
            val regionStart = start + (latestStart - start) * ratio
            regionStart to min(regionStart + length, end)
        }.distinctBy { (regionStart, _) -> (regionStart * 10.0).roundToInt() }
    }

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
        val mert: OrtSession,
        val music2emo: OrtSession,
    ) {
        fun close() {
            runCatching(mert::close)
            runCatching(music2emo::close)
        }

        fun predict(segments: List<FloatArray>): MoodScores {
            val env = OrtEnvironment.getEnvironment()
            val mertEmbedding = meanMertSegments(env, segments)
            val output = inferMusic2Emo(env, mertEmbedding)
            val valence = output.valenceArousal.getOrNull(0)?.takeIf { it.isFinite() } ?: error("Music2Emo valence is invalid")
            val arousal = output.valenceArousal.getOrNull(1)?.takeIf { it.isFinite() } ?: error("Music2Emo arousal is invalid")
            val normalizedValence = ((valence.coerceIn(1f, 9f) - 1f) / 8f)
            val normalizedArousal = ((arousal.coerceIn(1f, 9f) - 1f) / 8f)
            val valenceArousalUplifting = (normalizedValence * 0.55f + normalizedArousal * 0.45f).coerceIn(0f, 1f)
            val valenceArousalCalm = ((1f - normalizedArousal) * 0.75f + normalizedValence * 0.25f).coerceIn(0f, 1f)
            val classUplifting = strongestTagMean(output.moodLogits, UPLIFTING_TAG_INDICES)
            val classCalm = strongestTagMean(output.moodLogits, CALM_TAG_INDICES)
            return MoodScores(
                uplifting = (valenceArousalUplifting * 0.6f + classUplifting * 0.4f).coerceIn(0f, 1f),
                calm = (valenceArousalCalm * 0.6f + classCalm * 0.4f).coerceIn(0f, 1f),
            )
        }

        private fun strongestTagMean(logits: FloatArray, indices: IntArray): Float {
            val probabilities = indices.asSequence().mapNotNull { index ->
                logits.getOrNull(index)?.takeIf(Float::isFinite)?.let { logit ->
                    (1f / (1f + kotlin.math.exp(-logit))).coerceIn(0f, 1f)
                }
            }.sortedDescending()
            if (probabilities.none()) return 0f
            return probabilities.take(3).average().toFloat()
        }

        private fun meanMertSegments(env: OrtEnvironment, segments: List<FloatArray>): FloatArray {
            val sum = FloatArray(MERT_FEATURE_SIZE * MERT_LAYER_COUNT)
            var count = 0
            segments.forEach { samples ->
                val embedding = inferMert(env, samples) ?: return@forEach
                for (index in sum.indices) sum[index] += embedding[index]
                count++
            }
            check(count > 0) { "MERT returned no usable embeddings" }
            return FloatArray(sum.size) { sum[it] / count }
        }

        private fun inferMert(env: OrtEnvironment, samples: FloatArray): FloatArray? {
            if (samples.isEmpty()) return null
            val normalized = normalizeWaveform(samples)
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(normalized), longArrayOf(1L, normalized.size.toLong())).use { input ->
                mert.run(mapOf("input_values" to input)).use { result ->
                    val layer5Index = mert.outputNames.indexOf("layer_norm_13")
                    val layer6Index = mert.outputNames.indexOf("layer_norm_15")
                    check(layer5Index >= 0 && layer6Index >= 0) { "MERT output layer order is invalid" }
                    val first = meanFrameEmbedding(result[layer5Index].value)
                    val second = meanFrameEmbedding(result[layer6Index].value)
                    if (first == null || second == null) null else first + second
                }
            }
        }

        private data class Music2EmoOutput(val moodLogits: FloatArray, val valenceArousal: FloatArray)

        private fun inferMusic2Emo(env: OrtEnvironment, mertEmbedding: FloatArray): Music2EmoOutput {
            check(mertEmbedding.size == MERT_FEATURE_SIZE * MERT_LAYER_COUNT) { "Music2Emo expects pooled MERT layers 5 and 6" }
            val roots = LongArray(M2E_CHORD_SEQUENCE_LENGTH) { UNKNOWN_CHORD_ROOT }
            val attributes = LongArray(M2E_CHORD_SEQUENCE_LENGTH) { UNKNOWN_CHORD_ATTRIBUTE }
            val mode = floatArrayOf(MAJOR_MODE)
            val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(mertEmbedding), longArrayOf(1L, mertEmbedding.size.toLong()))
            val rootInput = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(roots), longArrayOf(1L, roots.size.toLong()))
            val attrInput = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attributes), longArrayOf(1L, attributes.size.toLong()))
            val modeInput = OnnxTensor.createTensor(env, FloatBuffer.wrap(mode), longArrayOf(1L, 1L))
            return input.use { mertTensor -> rootInput.use { rootTensor -> attrInput.use { attrTensor -> modeInput.use { modeTensor ->
                music2emo.run(mapOf(
                    "mert_embedding" to mertTensor,
                    "chord_root" to rootTensor,
                    "chord_attr" to attrTensor,
                    "key_mode" to modeTensor,
                )).use { result ->
                    val moodsIndex = music2emo.outputNames.indexOf("mood_logits")
                    check(moodsIndex >= 0) { "Music2Emo mood output is missing" }
                    val outputIndex = music2emo.outputNames.indexOf("valence_arousal")
                    check(outputIndex >= 0) { "Music2Emo valence/arousal output is missing" }
                    val moods = flattenFloat(result[moodsIndex].value)
                    check(moods.size == 56) { "Music2Emo mood output has an unexpected number of labels" }
                    val valenceArousal = flattenFloat(result[outputIndex].value)
                    check(valenceArousal.size >= 2) { "Music2Emo returned no valence/arousal scores" }
                    Music2EmoOutput(moods, valenceArousal)
                }
            } } } }
        }

        private fun meanFrameEmbedding(value: Any?): FloatArray? {
            val flattened = flattenFloat(value)
            if (flattened.isEmpty() || flattened.size % MERT_FEATURE_SIZE != 0) return null
            val frames = flattened.size / MERT_FEATURE_SIZE
            if (frames <= 0) return null
            val mean = FloatArray(MERT_FEATURE_SIZE)
            for (frame in 0 until frames) {
                val offset = frame * MERT_FEATURE_SIZE
                for (dimension in 0 until MERT_FEATURE_SIZE) mean[dimension] += flattened[offset + dimension]
            }
            for (dimension in mean.indices) mean[dimension] /= frames
            return mean
        }

        companion object {
            private fun normalizeWaveform(samples: FloatArray): FloatArray {
                val mean = samples.average().toFloat()
                var variance = 0.0
                samples.forEach { sample ->
                    val centered = sample - mean
                    variance += centered * centered
                }
                val scale = kotlin.math.sqrt(variance / samples.size + 1e-7).toFloat()
                return FloatArray(samples.size) { (samples[it] - mean) / scale }
            }

            private fun flattenFloat(value: Any?): FloatArray = when (value) {
                is FloatArray -> value
                is Array<*> -> value.flatMap { flattenFloat(it).asIterable() }.toFloatArray()
                else -> FloatArray(0)
            }
        }
    }

    @Volatile private var runtime: Runtime? = null
    private val runtimeLock = Any()

    private fun releaseRuntimeAfterMemoryPressure() {
        synchronized(runtimeLock) {
            runtime?.close()
            runtime = null
        }
    }

    private suspend fun <T> withModels(context: Context, block: (Runtime) -> T): T = withContext(Dispatchers.IO) {
        check(isModelInstalled(context)) { "AI model files are missing or failed verification" }
        val active = runtime ?: synchronized(runtimeLock) {
            runtime ?: run {
                val directory = modelDirectory(context)
                val options = OrtSession.SessionOptions().apply {
                    // Keep background inference from multiplying native worker stacks on lower-memory phones.
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                val environment = OrtEnvironment.getEnvironment()
                val opened = ArrayList<OrtSession>(models.size)
                try {
                    val mert = environment.createSession(File(directory, models[0].name).absolutePath, options).also(opened::add)
                    val music2emo = environment.createSession(File(directory, models[1].name).absolutePath, options).also(opened::add)
                    check(mert.inputNames.contains("input_values") && mert.outputNames.containsAll(listOf("layer_norm_13", "layer_norm_15"))) {
                        "The downloaded MERT graph does not expose Music2Emo's required hidden layers"
                    }
                    check(music2emo.inputNames.containsAll(listOf("mert_embedding", "chord_root", "chord_attr", "key_mode")) && music2emo.outputNames.contains("valence_arousal")) {
                        "The downloaded Music2Emo head has unexpected inputs or outputs"
                    }
                    Runtime(mert, music2emo).also { runtime = it }
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

}
