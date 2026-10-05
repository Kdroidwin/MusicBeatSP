package com.music.bitchord.playback

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

private const val REPLAYGAIN_CODEC_TIMEOUT_US = 10_000L
private const val REPLAYGAIN_WINDOW_BLOCKS = 4
private const val REPLAYGAIN_ABSOLUTE_GATE_LUFS = -70.0
private const val REPLAYGAIN_RELATIVE_GATE_DB = 10.0

data class ReplayGainScanState(
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val wasCancelled: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val analyzed: Int = 0,
    val alreadyTagged: Int = 0,
    val skipped: Int = 0,
    val errorMessage: String? = null,
)

/**
 * Non-destructive ReplayGain scanner for local tracks. It decodes audio on an
 * IO worker, stores measured values in the app-private database, and never
 * edits the user's audio files or their existing tags.
 */
object ReplayGainScanner {
    private const val TAG = "ReplayGainScanner"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow(ReplayGainScanState())
    val state: StateFlow<ReplayGainScanState> = mutableState.asStateFlow()
    private var scanJob: Job? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (scanJob?.isActive == true) return
            val appContext = context.applicationContext
            mutableState.value = ReplayGainScanState(isRunning = true)
            scanJob = scope.launch {
                try {
                    val libraryTracks = LocalMediaRepository.getLocalMusic(appContext)
                    val downloadedTracks = Downloads.getDownloadedSongs(appContext)
                    val songs = (libraryTracks + downloadedTracks)
                        .distinctBy { it.localUri ?: it.localPath ?: it.videoId }
                    if (songs.isEmpty()) {
                        mutableState.value = ReplayGainScanState(
                            errorMessage = appContext.getString(R.string.replaygain_scan_no_tracks),
                        )
                        return@launch
                    }

                    var processed = 0
                    var analyzed = 0
                    var alreadyTagged = 0
                    var skipped = 0
                    mutableState.value = ReplayGainScanState(isRunning = true, total = songs.size)

                    for (song in songs) {
                        currentCoroutineContext().ensureActive()
                        try {
                            when {
                                ReplayGainScanStore.read(appContext, song) != null -> Unit
                                ReplayGainReader.hasReplayGainTags(appContext, song) -> alreadyTagged++
                                else -> {
                                    val result = decodeAndMeasure(appContext, song)
                                    if (result != null) {
                                        ReplayGainScanStore.write(appContext, song, result)
                                        analyzed++
                                    } else {
                                        skipped++
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // One unreadable or unsupported file should never stop the
                            // scan for the rest of the library.
                            Log.w(TAG, "Unable to analyze a local audio file", failure)
                            skipped++
                        }
                        processed++
                        mutableState.value = ReplayGainScanState(
                            isRunning = true,
                            processed = processed,
                            total = songs.size,
                            analyzed = analyzed,
                            alreadyTagged = alreadyTagged,
                            skipped = skipped,
                        )
                    }
                    mutableState.value = ReplayGainScanState(
                        isComplete = true,
                        processed = songs.size,
                        total = songs.size,
                        analyzed = analyzed,
                        alreadyTagged = alreadyTagged,
                        skipped = skipped,
                    )
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(isRunning = false, wasCancelled = true)
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e(TAG, "ReplayGain scan failed", failure)
                    mutableState.value = mutableState.value.copy(
                        isRunning = false,
                        errorMessage = appContext.getString(R.string.replaygain_scan_failed),
                    )
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) { scanJob?.cancel() }
    }

    internal fun cachedProperties(context: Context, song: Song): Map<String, Array<String>>? =
        ReplayGainScanStore.read(context, song)?.let { result ->
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to arrayOf("${result.gainDb} dB"),
                "REPLAYGAIN_TRACK_PEAK" to arrayOf(result.samplePeak.toString()),
            )
        }

    private data class Measurement(val gainDb: Float, val samplePeak: Float)

    private suspend fun decodeAndMeasure(context: Context, song: Song): Measurement? {
        val rawSource = song.localUri ?: song.localPath ?: return null
        val uri = Uri.parse(rawSource).let { parsed ->
            if (parsed.scheme == null) Uri.fromFile(File(rawSource)) else parsed
        }
        var extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") {
                val path = song.localPath?.takeIf { it.isNotBlank() } ?: uri.path ?: return null
                if (!File(path).isFile || !File(path).canRead()) return null
                extractor.setDataSource(path)
            } else {
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (providerFailure: Exception) {
                    val fallbackPath = song.localPath?.takeIf { path ->
                        path.isNotBlank() && File(path).isFile && File(path).canRead()
                    } ?: throw providerFailure
                    runCatching { extractor.release() }
                    extractor = MediaExtractor()
                    extractor.setDataSource(fallbackPath)
                }
            }

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }
            if (audioTrack < 0) return null
            val format = requireNotNull(inputFormat)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var analyzer: IntegratedLoudnessAnalyzer? = null
            var sampleRate = format.integerOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = format.integerOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var iterations = 0

            while (!outputEnded) {
                if (++iterations % 64 == 0) currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(REPLAYGAIN_CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Decoder returned no input buffer")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, REPLAYGAIN_CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.integerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outputFormat.integerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            outputFormat.integerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        if (sampleRate > 0 && channels > 0) {
                            analyzer = IntegratedLoudnessAnalyzer(sampleRate, channels)
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            val activeAnalyzer = analyzer ?: if (sampleRate > 0 && channels > 0) {
                                IntegratedLoudnessAnalyzer(sampleRate, channels).also { analyzer = it }
                            } else null
                            if (activeAnalyzer != null) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                activeAnalyzer.consume(output.slice().order(ByteOrder.LITTLE_ENDIAN), pcmEncoding)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            val measured = analyzer?.finish() ?: return null
            val loudnessLufs = measured.integratedLufs ?: return null
            // ReplayGain 2.0's nominal playback reference is -18 LUFS. The
            // stored gain remains in dB and is fed through the same existing
            // playback processor and clipping guard as file tags.
            val gainDb = (-18.0 - loudnessLufs).coerceIn(-24.0, 12.0).toFloat()
            return Measurement(gainDb, measured.peak.coerceAtLeast(0f))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.integerOr(key: String, default: Int): Int =
        runCatching { if (containsKey(key)) getInteger(key) else default }.getOrDefault(default)

    private class IntegratedLoudnessAnalyzer(
        private val sampleRate: Int,
        val channels: Int,
    ) {
        private val filters = Array(channels) { ChannelFilter(sampleRate) }
        private val channelWeights = DoubleArray(channels) { channel ->
            // BS.1770 gives surround channels a +1.5 dB weight. For 5.1/7.1
            // layouts the fourth channel is LFE and contributes no loudness.
            when {
                channels >= 6 && channel == 3 -> 0.0
                channels >= 4 && channel >= 4 -> 1.4125375446
                else -> 1.0
            }
        }
        private val currentWindow = ArrayDeque<Double>(4)
        private val gatedWindows = ArrayList<Double>()
        private val targetFramesPerBlock = max(1, (sampleRate * 0.1).roundToInt())
        private var blockFrames = 0
        private var blockEnergy = 0.0
        var peak: Float = 0f
            private set

        fun addFrame(samples: FloatArray) {
            var weightedEnergy = 0.0
            for (channel in 0 until min(channels, samples.size)) {
                val sample = samples[channel].toDouble().coerceIn(-1.0, 1.0)
                peak = max(peak, abs(sample).toFloat())
                val weighted = filters[channel].process(sample)
                weightedEnergy += channelWeights[channel] * weighted * weighted
            }
            blockEnergy += weightedEnergy
            blockFrames++
            if (blockFrames >= targetFramesPerBlock) {
                pushBlock(blockEnergy / blockFrames)
                blockEnergy = 0.0
                blockFrames = 0
            }
        }

        fun finish(): LoudnessResult {
            if (blockFrames > 0) pushBlock(blockEnergy / blockFrames)
            val absoluteGate = energyForLufs(REPLAYGAIN_ABSOLUTE_GATE_LUFS)
            val absoluteWindows = gatedWindows.filter { it >= absoluteGate }
            if (absoluteWindows.isEmpty()) return LoudnessResult(null, peak)
            val preliminaryLufs = lufs(absoluteWindows.average())
            val relativeGate = energyForLufs(preliminaryLufs - REPLAYGAIN_RELATIVE_GATE_DB)
            val finalWindows = absoluteWindows.filter { it >= relativeGate }
            return LoudnessResult(finalWindows.takeIf { it.isNotEmpty() }?.average()?.let(::lufs), peak)
        }

        private fun pushBlock(energy: Double) {
            currentWindow.addLast(energy)
            while (currentWindow.size > REPLAYGAIN_WINDOW_BLOCKS) currentWindow.removeFirst()
            if (currentWindow.size == REPLAYGAIN_WINDOW_BLOCKS) {
                gatedWindows += currentWindow.average()
            }
        }
    }

    private data class LoudnessResult(val integratedLufs: Double?, val peak: Float)

    private class ChannelFilter(sampleRate: Int) {
        private val shelf = Biquad.highShelf(sampleRate, 1681.974450955533, 0.7071752369554196, 3.99984385397)
        private val highPass = Biquad.highPass(sampleRate, 38.13547087602444, 0.5003270373238773)
        fun process(value: Double): Double = highPass.process(shelf.process(value))
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }

        companion object {
            fun highPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
                val omega = 2.0 * Math.PI * frequency / sampleRate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 + cosine) / 2.0) / a0,
                    b1 = (-(1.0 + cosine)) / a0,
                    b2 = ((1.0 + cosine) / 2.0) / a0,
                    a1 = (-2.0 * cosine) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun highShelf(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
                val amplitude = 10.0.pow(gainDb / 40.0)
                val omega = 2.0 * Math.PI * frequency / sampleRate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * q)
                val root = 2.0 * sqrt(amplitude) * alpha
                val a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + root
                return Biquad(
                    b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + root) / a0,
                    b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0,
                    b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - root) / a0,
                    a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0,
                    a2 = ((amplitude + 1.0) - (amplitude - 1.0) * cosine - root) / a0,
                )
            }
        }
    }

    private fun IntegratedLoudnessAnalyzer.consume(buffer: ByteBuffer, encoding: Int) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> return
        }
        val frameSize = channels * bytesPerSample
        if (frameSize <= 0) return
        val frames = buffer.remaining() / frameSize
        val sampleFrame = FloatArray(channels)
        repeat(frames) {
            for (channel in 0 until channels) {
                sampleFrame[channel] = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xff
                        val b1 = buffer.get().toInt() and 0xff
                        val b2 = buffer.get().toInt()
                        val value = (b2 shl 16) or (b1 shl 8) or b0
                        (if (value and 0x800000 != 0) value or -0x1000000 else value) / 8_388_608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
                    else -> 0f
                }
            }
            addFrame(sampleFrame)
        }
    }

    private class ReplayGainScanStore(context: Context) : SQLiteOpenHelper(
        context.applicationContext,
        "replaygain_scan.db",
        null,
        1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE scan_result (track_key TEXT PRIMARY KEY NOT NULL, gain_db REAL NOT NULL, sample_peak REAL NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        companion object {
            @Volatile private var instance: ReplayGainScanStore? = null
            private fun get(context: Context): ReplayGainScanStore = instance ?: synchronized(this) {
                instance ?: ReplayGainScanStore(context).also { instance = it }
            }

            fun read(context: Context, song: Song): Measurement? {
                val key = trackKey(context, song) ?: return null
                val database = get(context).readableDatabase
                return database.query(
                    "scan_result",
                    arrayOf("gain_db", "sample_peak"),
                    "track_key = ?",
                    arrayOf(key),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) Measurement(cursor.getFloat(0), cursor.getFloat(1)) else null
                }
            }

            fun write(context: Context, song: Song, result: Measurement) {
                val key = trackKey(context, song) ?: return
                val values = android.content.ContentValues().apply {
                    put("track_key", key)
                    put("gain_db", result.gainDb)
                    put("sample_peak", result.samplePeak)
                }
                get(context).writableDatabase.insertWithOnConflict(
                    "scan_result",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }

            private fun trackKey(context: Context, song: Song): String? {
                val raw = song.localUri ?: song.localPath ?: return null
                val uri = Uri.parse(raw).let { if (it.scheme == null) Uri.fromFile(File(raw)) else it }
                var path = song.localPath?.takeIf(String::isNotBlank) ?: uri.takeIf { it.scheme == "file" }?.path
                var modified = 0L
                var length = 0L
                if (path.isNullOrBlank() && uri.scheme == "content") {
                    runCatching {
                        context.contentResolver.query(
                            uri.buildUpon().clearQuery().build(),
                            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                                if (pathIndex >= 0) path = cursor.getString(pathIndex)
                                if (modifiedIndex >= 0) modified = cursor.getLong(modifiedIndex)
                                if (sizeIndex >= 0) length = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                    modified = modified.takeIf { it > 0L }
                        ?: uri.getQueryParameter("t")?.toLongOrNull()
                        ?: 0L
                }
                val file = path?.let(::File)?.takeIf { it.isFile }
                if (file != null) {
                    path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    modified = file.lastModified()
                    length = file.length()
                }
                val identity = buildString {
                    append(path?.takeIf(String::isNotBlank) ?: uri.buildUpon().clearQuery().build().normalizeScheme())
                    append('|').append(modified).append('|').append(length)
                }
                return MessageDigest.getInstance("SHA-256")
                    .digest(identity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
        }
    }

    private fun energyForLufs(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)

    private fun lufs(energy: Double): Double = -0.691 + 10.0 * log10(energy.coerceAtLeast(1e-15))

    private fun Double.pow(power: Double): Double = exp(power * ln(this))
}
