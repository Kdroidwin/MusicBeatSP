package com.music.bitchord.data.lyrics

import android.content.Context
import android.icu.text.Transliterator
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.LocalAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

internal object CtcForcedLyricsAligner {
    // Alignment flow and edge-star CTC path are adapted from CTCLRCdroid
    // (AGPL-3.0-or-later); see that project for the original Android implementation.
    private const val SAMPLE_RATE = 16_000
    private const val LEAD_IN_MS = 345L
    private const val FRAME_SAMPLES = 320 // MMS-300M emits one frame every 20 ms.
    private const val WINDOW_SAMPLES = 20 * SAMPLE_RATE
    private const val CONTEXT_SAMPLES = 2 * SAMPLE_RATE
    private const val BLANK = 0
    private const val STAR = 31
    private val timestampPattern = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val vocab = mapOf(
        'a' to 4, 'i' to 5, 'e' to 6, 'n' to 7, 'o' to 8, 'u' to 9,
        't' to 10, 's' to 11, 'r' to 12, 'm' to 13, 'k' to 14, 'l' to 15,
        'd' to 16, 'g' to 17, 'h' to 18, 'y' to 19, 'b' to 20, 'p' to 21,
        'w' to 22, 'c' to 23, 'v' to 24, 'j' to 25, 'z' to 26, 'f' to 27,
        '\'' to 28, 'q' to 29, 'x' to 30,
    )

    enum class Stage { DownloadingModel, DecodingAudio, Aligning }

    suspend fun generate(
        context: Context,
        song: Song,
        plainLyrics: String,
        onProgress: (Stage, Int?) -> Unit,
    ): String {
        require(!timestampPattern.containsMatchIn(plainLyrics)) {
            "貼り付けた歌詞にtimestampがあります。timestampのない歌詞を使ってください。"
        }
        val lines = parseLines(plainLyrics)
        require(lines.isNotEmpty()) { "歌詞が空です。歌詞欄に歌詞を貼り付けてください。" }
        val audioUri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: song.localPath?.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)) }
        require(audioUri != null && LocalAudioSource.isLocal(audioUri)) {
            "同期歌詞の自動生成には、この端末に保存された音源が必要です。"
        }

        onProgress(Stage.DecodingAudio, null)
        val source = LocalAudioSource.open(context.contentResolver, audioUri)
            ?: throw IllegalStateException("端末の音源ファイルを開けませんでした。")
        val pcmAndStart = source.use { dataSource ->
            val fileDuration = AudioDecoder.containerDurationSeconds(dataSource)
                ?: (song.durationMillis().takeIf { it > 0L }?.div(1000.0))
                ?: throw IllegalStateException("音源の長さを読み取れませんでした。")
            val start = (song.localStartMs ?: 0L).coerceAtLeast(0L) / 1000.0
            val end = (song.localEndMs?.div(1000.0) ?: fileDuration).coerceIn(start, fileDuration)
            require(end > start) { "音源の再生範囲を読み取れませんでした。" }
            AudioDecoder.decodeRegion(dataSource, start, end)
                ?: throw IllegalStateException("この音源形式をデコードできませんでした。")
        }
        val pcm = resample(pcmAndStart.first.samples, pcmAndStart.first.sampleRate)
        require(pcm.isNotEmpty()) { "音源のデコード結果が空でした。" }

        onProgress(Stage.DownloadingModel, 0)
        val model = CtcModelStore.ensure(context.applicationContext) { onProgress(Stage.DownloadingModel, it) }
        onProgress(Stage.Aligning, null)
        val raw = infer(model, pcm)
        val romanized = lines.map { romanize(it.text) }
        val timeByLine = align(romanized, raw)
        var synchronizedLines = 0
        return buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0 && line.sectionBefore) append('\n')
                val frame = timeByLine[index]
                if (frame != null) {
                    val milliseconds = (pcmAndStart.second * 1000.0 + frame * 20.0 - LEAD_IN_MS)
                        .toLong().coerceAtLeast(0L)
                    append(formatTimestamp(milliseconds))
                    synchronizedLines++
                }
                append(line.text).append('\n')
            }
        }.trimEnd().also {
            check(synchronizedLines > 0) { "歌詞を音源に合わせられませんでした。曲の歌詞を確認してください。" }
        }
    }

    private data class LyricLine(val text: String, val sectionBefore: Boolean)

    private fun parseLines(text: String): List<LyricLine> {
        var pendingSection = false
        return buildList {
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isBlank()) {
                    if (isNotEmpty()) pendingSection = true
                } else {
                    add(LyricLine(line, pendingSection && isNotEmpty()))
                    pendingSection = false
                }
            }
        }
    }

    /** ICU's device transliterator handles Japanese kana and Chinese characters without extra assets. */
    private fun romanize(text: String): String {
        val roman = Transliterator.getInstance("Any-Latin; Latin-ASCII")
            .transliterate(text.lowercase(Locale.ROOT))
        return roman.filter { it in 'a'..'z' }
    }

    private fun resample(samples: FloatArray, inputRate: Double): FloatArray {
        require(inputRate.isFinite() && inputRate > 0.0) { "音源のサンプルレートを読み取れませんでした。" }
        if (inputRate == SAMPLE_RATE.toDouble()) return samples
        val outputCount = (samples.size * SAMPLE_RATE / inputRate).toInt().coerceAtLeast(1)
        return FloatArray(outputCount) { index ->
            val position = index * inputRate / SAMPLE_RATE
            val lower = floor(position).toInt().coerceIn(0, samples.lastIndex)
            val upper = (lower + 1).coerceAtMost(samples.lastIndex)
            val mix = (position - lower).toFloat()
            samples[lower] * (1f - mix) + samples[upper] * mix
        }
    }

    private suspend fun infer(model: File, waveform: FloatArray): Array<FloatArray> = withContext(Dispatchers.Default) {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        val session = environment.createSession(model.absolutePath, options)
        try {
            val emissions = ArrayList<FloatArray>()
            if (waveform.size < WINDOW_SAMPLES) {
                runChunk(session, environment, waveform, emissions)
            } else {
                val blocks = ceil(waveform.size / WINDOW_SAMPLES.toDouble()).toInt()
                val extension = blocks * WINDOW_SAMPLES - waveform.size
                val padded = FloatArray(CONTEXT_SAMPLES + waveform.size + CONTEXT_SAMPLES + extension)
                waveform.copyInto(padded, CONTEXT_SAMPLES)
                repeat(blocks) { block ->
                    val offset = block * WINDOW_SAMPLES
                    val chunk = padded.copyOfRange(offset, offset + WINDOW_SAMPLES + 2 * CONTEXT_SAMPLES)
                    val local = ArrayList<FloatArray>()
                    runChunk(session, environment, chunk, local)
                    emissions.addAll(local.subList(CONTEXT_SAMPLES / FRAME_SAMPLES, (CONTEXT_SAMPLES + WINDOW_SAMPLES) / FRAME_SAMPLES))
                }
                repeat(extension / FRAME_SAMPLES) { if (emissions.isNotEmpty()) emissions.removeAt(emissions.lastIndex) }
            }
            emissions.toTypedArray()
        } finally {
            session.close()
            options.close()
        }
    }

    private fun runChunk(
        session: OrtSession,
        environment: OrtEnvironment,
        chunk: FloatArray,
        output: MutableList<FloatArray>,
    ) {
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(chunk), longArrayOf(1, chunk.size.toLong()))
        val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(chunk.size) { 1L }), longArrayOf(1, chunk.size.toLong()))
        try {
            val values = mutableMapOf("input_values" to input)
            if (session.inputNames.contains("attention_mask")) values["attention_mask"] = mask
            session.run(values).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                output.addAll(logits)
            }
        } finally {
            input.close()
            mask.close()
        }
    }

    private fun align(romanizedLines: List<String>, raw: Array<FloatArray>): List<Int?> {
        require(raw.isNotEmpty()) { "音源から音声特徴を取得できませんでした。" }
        val target = ArrayList<Int>().apply { add(STAR) }
        val ranges = ArrayList<IntRange>()
        romanizedLines.forEach { line ->
            val first = target.size
            line.forEach { char -> vocab[char]?.let(target::add) }
            ranges += first until target.size
        }
        target += STAR
        require(target.size > 2) { "歌詞をローマ字に変換できませんでした。" }
        val path = force(raw, target.toIntArray())
        return ranges.map { range ->
            val frame = path.indexOfFirst { state -> state % 2 == 1 && state / 2 in range }
            if (frame < 0) null else {
                var firstBlank = frame
                while (firstBlank > 0 && path[firstBlank - 1] % 2 == 0) firstBlank--
                if (firstBlank == frame) frame else (firstBlank + frame - 1) / 2
            }
        }
    }

    private fun force(raw: Array<FloatArray>, target: IntArray): IntArray {
        val states = IntArray(target.size * 2 + 1) { if (it % 2 == 0) BLANK else target[it / 2] }
        require(states.size < Short.MAX_VALUE) { "歌詞が長すぎます。" }
        val logp = raw.map { row ->
            val max = row.maxOrNull() ?: 0f
            var sum = 0.0
            row.forEach { sum += exp((it - max).toDouble()) }
            val logZ = max + ln(sum).toFloat()
            FloatArray(row.size) { row[it] - logZ }
        }
        require(logp.all { it.size > STAR }) { "CTCモデルの出力形式が不正です。" }
        val back = Array(logp.size) { ShortArray(states.size) { -1 } }
        var previous = FloatArray(states.size) { Float.NEGATIVE_INFINITY }
        previous[0] = logProbability(logp[0], BLANK)
        previous[1] = logProbability(logp[0], states[1])
        for (time in 1 until logp.size) {
            val current = FloatArray(states.size) { Float.NEGATIVE_INFINITY }
            for (state in states.indices) {
                var from = state
                var best = previous[state]
                if (state > 0 && previous[state - 1] > best) {
                    from = state - 1
                    best = previous[from]
                }
                if (state > 1 && states[state] != BLANK && states[state] != states[state - 2] && previous[state - 2] > best) {
                    from = state - 2
                    best = previous[from]
                }
                current[state] = best + logProbability(logp[time], states[state])
                back[time][state] = from.toShort()
            }
            previous = current
        }
        var state = if (previous.last() > previous[previous.lastIndex - 1]) previous.lastIndex else previous.lastIndex - 1
        return IntArray(logp.size).also { path ->
            for (time in logp.lastIndex downTo 0) {
                path[time] = state
                if (time > 0) state = back[time][state].toInt()
            }
        }
    }

    private fun logProbability(row: FloatArray, id: Int): Float = if (id == STAR) 0f else row[id]

    private fun formatTimestamp(milliseconds: Long): String = String.format(
        Locale.US,
        "[%02d:%05.2f]",
        milliseconds / 60_000,
        (milliseconds % 60_000) / 1000.0,
    )
}
