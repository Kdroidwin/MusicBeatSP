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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional, on-device-only music mood analysis for Home. The model set is
 * fetched only after the listener opts in; no audio is sent off-device.
 *
 * Discogs-EffNet is trained on music rather than environmental audio. The
 * model and MTG mood heads are published by Essentia/UPF under CC BY-NC-SA 4.0.
 * Its music features use a 128x96 MusiCNN mel patch and produce 1280-wide
 * embeddings for the happy, party, and relaxed classification heads.
 */
object LocalHomeAi {
    private const val PREFS = "local_home_ai"
    private const val KEY_MODEL_DECISION = "model_download_decision"
    private const val MODEL_VERSION = "essentia-discogs-effnet-moods-v2"
    private const val LEGACY_MODEL_VERSION = "essentia-yamnet-mood-v1"
    private const val MODEL_HOST = "essentia.upf.edu"
    private const val ANALYSIS_WINDOW_SECONDS = 4.2
    private const val SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 512
    private const val FRAME_SIZE = 512
    private const val FRAME_STEP = 256
    private const val MEL_BANDS = 96
    private const val PATCH_FRAMES = 128
    private const val PATCH_HOP_FRAMES = 62
    private const val DOWNLOAD_LIMIT_BYTES = 20_000_000L
    // Bump this when the inference pipeline changes so tracks skipped by a
    // previous broken tensor shape are retried automatically.
    private const val KEY_FAILED_TRACKS = "${MODEL_VERSION}_failed_tracks_v2"
    private const val MAX_FAILED_TRACKS = 512
    private const val TAG = "LocalHomeAi"

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
            name = "discogs-effnet-bsdynamic-1.onnx",
            url = "https://essentia.upf.edu/models/music-style-classification/discogs-effnet/discogs-effnet-bsdynamic-1.onnx",
            bytes = 18_027_718L,
            sha256 = "a280825b334797cf677939db8cd5762c0392aedd0ca6415dbc1cd083f045e43c",
        ),
        ModelFile(
            name = "mood_happy-discogs-effnet-1.onnx",
            url = "https://essentia.upf.edu/models/classification-heads/mood_happy/mood_happy-discogs-effnet-1.onnx",
            bytes = 514_097L,
            sha256 = "0ca322819ef137b4b87e9866bffe7370a630e6f1165184ec106326cef6f81e06",
        ),
        ModelFile(
            name = "mood_party-discogs-effnet-1.onnx",
            url = "https://essentia.upf.edu/models/classification-heads/mood_party/mood_party-discogs-effnet-1.onnx",
            bytes = 514_097L,
            sha256 = "c50ac2106ec2f209dd04ad48756582df0e3f3512235310d1a4a3fcc453745f04",
        ),
        ModelFile(
            name = "mood_relaxed-discogs-effnet-1.onnx",
            url = "https://essentia.upf.edu/models/classification-heads/mood_relaxed/mood_relaxed-discogs-effnet-1.onnx",
            bytes = 514_101L,
            sha256 = "8ba6515a1e5943a72b3b475e3a25fc7a2ff04142c3eaa6aa0716fca371efdfff",
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
            File(context.noBackupFilesDir, "home_ai_models/$LEGACY_MODEL_VERSION")
                .deleteRecursively()
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
        // Keep the three sampled song regions, but use one center patch from
        // each region. A 4.2 second window can produce three overlapping
        // patches; analyzing all of them multiplied native ONNX work by 3.
        val patches = audioSegments.mapNotNull { samples ->
            melPatches(samples).let { candidates -> candidates.getOrNull(candidates.size / 2) }
        }
        if (patches.isEmpty()) {
            markAnalysisFailure(context, song)
            return@withContext null
        }
        val score = withModels(context) { runtime -> runtime.predict(patches) }
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
        val effnet: OrtSession,
        val happy: OrtSession,
        val party: OrtSession,
        val relaxed: OrtSession,
    ) {
        fun close() {
            runCatching(effnet::close)
            runCatching(happy::close)
            runCatching(party::close)
            runCatching(relaxed::close)
        }

        fun predict(patches: List<FloatArray>): MoodScores {
            val env = OrtEnvironment.getEnvironment()
            var upliftingTotal = 0.0
            var calmTotal = 0.0
            var count = 0
            patches.chunked(MAX_INFERENCE_BATCH).forEach { patchBatch ->
                val features = inferEffnetBatch(env, patchBatch)
                if (features.isEmpty()) return@forEach
                val happyScores = inferMoodHeadBatch(env, happy, features, 0)
                val partyScores = inferMoodHeadBatch(env, party, features, 1)
                val calmScores = inferMoodHeadBatch(env, relaxed, features, 1)
                features.indices.forEach { index ->
                    val happyScore = happyScores.getOrElse(index) { Float.NaN }
                    val partyScore = partyScores.getOrElse(index) { Float.NaN }
                    val calmScore = calmScores.getOrElse(index) { Float.NaN }
                    if (happyScore.isFinite() && partyScore.isFinite() && calmScore.isFinite()) {
                        upliftingTotal += (happyScore + partyScore) / 2.0
                        calmTotal += calmScore
                        count++
                    }
                }
            }
            check(count > 0) { "Mood model returned no usable scores" }
            return MoodScores(
                uplifting = (upliftingTotal / count).toFloat().coerceIn(0f, 1f),
                calm = (calmTotal / count).toFloat().coerceIn(0f, 1f),
            )
        }

        private fun inferEffnetBatch(env: OrtEnvironment, patches: List<FloatArray>): List<FloatArray> {
            val inputName = effnet.inputNames.first()
            val inputShape = (effnet.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            val inputRank = inputShape?.size ?: error("EffNet input shape is unavailable")
            check(inputRank == 3 || inputRank == 4) { "Unexpected EffNet input rank: $inputRank" }
            val fixedBatchSize = inputShape?.firstOrNull()
            val canBatch = fixedBatchSize == null || fixedBatchSize < 0L || fixedBatchSize == patches.size.toLong()
            if (!canBatch || patches.size == 1) return patches.mapNotNull { inferEffnetSingle(env, it) }

            val inputValues = FloatArray(patches.size * PATCH_FRAMES * MEL_BANDS)
            patches.forEachIndexed { index, patch ->
                patch.copyInto(inputValues, index * patch.size, endIndex = min(patch.size, PATCH_FRAMES * MEL_BANDS))
            }
            return OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(inputValues),
                if (inputRank == 3) {
                    longArrayOf(patches.size.toLong(), PATCH_FRAMES.toLong(), MEL_BANDS.toLong())
                } else {
                    longArrayOf(patches.size.toLong(), 1L, PATCH_FRAMES.toLong(), MEL_BANDS.toLong())
                },
            ).use { input ->
                effnet.run(mapOf(inputName to input)).use { output ->
                    val embeddingIndex = effnet.outputNames.indexOfFirst { name ->
                        (effnet.outputInfo[name]?.info as? ai.onnxruntime.TensorInfo)
                            ?.shape?.lastOrNull() == 1280L
                    }.coerceAtLeast(0)
                    val embeddings = flattenFloat(output[embeddingIndex].value)
                    if (embeddings.size < patches.size * 1280) emptyList()
                    else List(patches.size) { index -> embeddings.copyOfRange(index * 1280, (index + 1) * 1280) }
                }
            }
        }

        private fun inferEffnetSingle(env: OrtEnvironment, patch: FloatArray): FloatArray? {
            val inputName = effnet.inputNames.first()
            val inputRank = (effnet.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape?.size
                ?: error("EffNet input shape is unavailable")
            check(inputRank == 3 || inputRank == 4) { "Unexpected EffNet input rank: $inputRank" }
            return OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(patch),
                if (inputRank == 3) {
                    longArrayOf(1L, PATCH_FRAMES.toLong(), MEL_BANDS.toLong())
                } else {
                    longArrayOf(1L, 1L, PATCH_FRAMES.toLong(), MEL_BANDS.toLong())
                },
            ).use { input ->
                effnet.run(mapOf(inputName to input)).use { output ->
                    val embeddingIndex = effnet.outputNames.indexOfFirst { name ->
                        (effnet.outputInfo[name]?.info as? ai.onnxruntime.TensorInfo)
                            ?.shape?.lastOrNull() == 1280L
                    }.coerceAtLeast(0)
                    flattenFloat(output[embeddingIndex].value).takeIf { it.size >= 1280 }?.copyOf(1280)
                }
            }
        }

        private fun inferMoodHeadBatch(
            env: OrtEnvironment,
            session: OrtSession,
            embeddings: List<FloatArray>,
            classIndex: Int,
        ): FloatArray {
            val inputName = session.inputNames.first()
            val inputShape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            val fixedBatchSize = inputShape?.firstOrNull()
            val canBatch = inputShape?.size == 2 && (fixedBatchSize == null || fixedBatchSize < 0L || fixedBatchSize == embeddings.size.toLong())
            if (!canBatch || embeddings.size == 1) {
                return FloatArray(embeddings.size) { index -> inferMoodHeadSingle(env, session, embeddings[index], classIndex) }
            }

            val inputValues = FloatArray(embeddings.size * 1280)
            embeddings.forEachIndexed { index, embedding -> embedding.copyInto(inputValues, index * 1280, endIndex = min(embedding.size, 1280)) }
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(inputValues), longArrayOf(embeddings.size.toLong(), 1280L)).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    val values = flattenFloat(result[0].value)
                    val classesPerRow = (values.size / embeddings.size).takeIf { it > classIndex } ?: return@use FloatArray(embeddings.size) { Float.NaN }
                    FloatArray(embeddings.size) { index -> values.getOrElse(index * classesPerRow + classIndex) { Float.NaN } }
                }
            }
        }

        private fun inferMoodHeadSingle(env: OrtEnvironment, session: OrtSession, embedding: FloatArray, classIndex: Int): Float {
            val inputName = session.inputNames.first()
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(embedding), longArrayOf(1L, embedding.size.toLong())).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    flattenFloat(result[0].value).getOrElse(classIndex) { Float.NaN }
                }
            }
        }

        companion object {
            private const val MAX_INFERENCE_BATCH = 3

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
                    val effnet = environment.createSession(File(directory, models[0].name).absolutePath, options).also(opened::add)
                    val happy = environment.createSession(File(directory, models[1].name).absolutePath, options).also(opened::add)
                    val party = environment.createSession(File(directory, models[2].name).absolutePath, options).also(opened::add)
                    val relaxed = environment.createSession(File(directory, models[3].name).absolutePath, options).also(opened::add)
                    Runtime(effnet, happy, party, relaxed).also { runtime = it }
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

    /** Converts 16 kHz mono PCM into the 128x96 MusiCNN mel patches EffNet was trained on. */
    private fun melPatches(samples: FloatArray): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val frameCount = max(PATCH_FRAMES, 1 + kotlin.math.ceil((samples.size - FRAME_SIZE / 2.0) / FRAME_STEP).toInt())
        val filter = slaneyMelFilters()
        val spectrogram = FloatArray(frameCount * MEL_BANDS)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        for (frame in 0 until frameCount) {
            real.fill(0.0)
            imaginary.fill(0.0)
            // Essentia's FrameCutter centers the first frame, padding 256 samples at the front.
            val start = frame * FRAME_STEP - FRAME_SIZE / 2
            for (index in 0 until FRAME_SIZE) {
                val sampleIndex = start + index
                val sample = if (sampleIndex in samples.indices) samples[sampleIndex] else 0f
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / (FRAME_SIZE - 1))
                real[index] = sample * window
            }
            fft(real, imaginary)
            val power = DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                magnitude * magnitude
            }
            for (band in 0 until MEL_BANDS) {
                var energy = 0.0
                val weights = filter[band]
                for (bin in weights.indices) energy += power[bin] * weights[bin]
                spectrogram[frame * MEL_BANDS + band] = kotlin.math.log10(max(0.0, energy) * 10_000.0 + 1.0).toFloat()
            }
        }
        val patches = ArrayList<FloatArray>()
        var patchStart = 0
        while (patchStart + PATCH_FRAMES <= frameCount) {
            val patch = FloatArray(PATCH_FRAMES * MEL_BANDS)
            spectrogram.copyInto(destination = patch, startIndex = patchStart * MEL_BANDS, endIndex = (patchStart + PATCH_FRAMES) * MEL_BANDS)
            patches += patch
            patchStart += PATCH_HOP_FRAMES
        }
        if (patches.isEmpty()) patches += spectrogram.copyOf(PATCH_FRAMES * MEL_BANDS)
        return patches
    }

    /** Essentia uses Slaney mel bands, unit-area triangles, power, and log10(10000*x+1). */
    private fun slaneyMelFilters(): Array<DoubleArray> {
        val lowMel = hzToSlaneyMel(0.0)
        val highMel = hzToSlaneyMel(SAMPLE_RATE / 2.0)
        val edges = DoubleArray(MEL_BANDS + 2) { index ->
            slaneyMelToHz(lowMel + (highMel - lowMel) * index / (MEL_BANDS + 1))
        }
        val frequencies = DoubleArray(FFT_SIZE / 2 + 1) { bin -> bin * SAMPLE_RATE.toDouble() / FFT_SIZE }
        return Array(MEL_BANDS) { band ->
            val triangle = DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val frequency = frequencies[bin]
                when {
                    frequency < edges[band] || frequency > edges[band + 2] -> 0.0
                    frequency <= edges[band + 1] -> (frequency - edges[band]) / max(1e-12, edges[band + 1] - edges[band])
                    else -> (edges[band + 2] - frequency) / max(1e-12, edges[band + 2] - edges[band + 1])
                }.coerceAtLeast(0.0)
            }
            val area = triangle.sum()
            if (area > 0.0) DoubleArray(triangle.size) { triangle[it] / area } else triangle
        }
    }

    private fun hzToSlaneyMel(hz: Double): Double = if (hz < 1_000.0) hz / (200.0 / 3.0) else 15.0 + kotlin.math.ln(hz / 1_000.0) / (kotlin.math.ln(6.4) / 27.0)

    private fun slaneyMelToHz(mel: Double): Double = if (mel < 15.0) mel * (200.0 / 3.0) else 1_000.0 * kotlin.math.exp((kotlin.math.ln(6.4) / 27.0) * (mel - 15.0))

    /** In-place radix-2 FFT; EffNet input uses the 257 non-negative frequency bins. */
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
