package com.music.bitchord.feature.lyrics

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.LrcParser
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

/** Optional, on-device PLaMo 3 Translate Q4_K_M runtime and line-preserving lyric translator. */
internal object PlamoLyricsTranslation {
    // Exact size of the official Q4_K_M artifact at the pinned Hugging Face revision.
    const val MODEL_BYTES = 573_126_848L
    private const val MODEL_NAME = "plamo-3-translate-0.8b-Q4_K_M.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/1cf96ae02133958a20074bfdfab76517bb737d00/$MODEL_NAME?download=true"
    private const val MODEL_VERSION = "plamo3-translate-0.8b-q4km-b11960-v2"
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    private val operationMutex = Mutex()
    private val activeCall = AtomicReference<Call?>(null)

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_NAME)

    fun hasModel(context: Context): Boolean {
        val model = modelFile(context)
        if (!model.isFile || model.length() != MODEL_BYTES) return false
        return runCatching {
            model.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            }
        }.getOrDefault(false)
    }

    fun canDownload(context: Context): Boolean =
        !AppSettings.offlineMode.value && !context.packageName.endsWith(".nointernet")

    fun downloadPartialBytes(context: Context): Long =
        File(modelDirectory(context), "$MODEL_NAME.part").length().coerceAtMost(MODEL_BYTES)

    /** Downloads only from the official Hugging Face repository over HTTPS and resumes safely. */
    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit) {
        operationMutex.withLock {
            check(canDownload(context)) { "このアプリの配布版またはオフライン設定ではダウンロードできません。" }
            withContext(Dispatchers.IO) {
                val directory = modelDirectory(context)
                directory.mkdirs()
                val model = modelFile(context)
                if (hasModel(context)) {
                    onProgress(MODEL_BYTES, MODEL_BYTES)
                    return@withContext
                }
                val partial = File(directory, "$MODEL_NAME.part")
                if (partial.length() > MODEL_BYTES) partial.delete()
                val offset = partial.length()
                val free = directory.usableSpace
                if (free < MODEL_BYTES - offset) {
                    throw IOException("モデルの保存に空き容量が足りません（約573 MB必要です）。")
                }
                val request = Request.Builder().url(MODEL_URL).apply {
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) header("Range", "bytes=$offset-")
                }.build()
                val client = Http.lyricsClient.newBuilder()
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                awaitDownload(client, client.newCall(request), partial, offset, onProgress)
                if (partial.length() != MODEL_BYTES || !hasGgufMagic(partial)) {
                    throw IOException("取得したモデルのサイズまたはGGUF形式が正しくありません。未完了ファイルは次回再開できます。")
                }
                if (model.exists() && !model.delete()) throw IOException("旧モデルを置き換えられませんでした。")
                if (!partial.renameTo(model)) throw IOException("モデルを保存できませんでした。")
                onProgress(MODEL_BYTES, MODEL_BYTES)
            }
        }
    }

    suspend fun deleteModel(context: Context) {
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = modelDirectory(context)
                val files = listOf(modelFile(context), File(directory, "$MODEL_NAME.part"))
                val failures = files.filter { it.exists() && !it.delete() }
                if (failures.isNotEmpty()) throw IOException("モデルファイルを削除できませんでした。")
                File(directory, "translations").deleteRecursively()
                File(directory, "corrections").deleteRecursively()
            }
        }
    }

    fun cancelDownload() {
        activeCall.get()?.cancel()
    }

    /** Returns translations keyed by lyric row and voice; source lines and timing are never mutated. */
    suspend fun translate(
        context: Context,
        lines: List<LyricLine>,
        targetLanguage: String = Locale.getDefault().language.ifBlank { "en" },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, String> = operationMutex.withLock {
        if (lines.isEmpty() || !withContext(Dispatchers.IO) { hasModel(context) }) return@withLock emptyMap()

        val units = buildUnits(lines)
        if (units.isEmpty()) return@withLock emptyMap()
        val normalizedLanguage = normalizeTargetLanguage(targetLanguage)
        val cacheFile = withContext(Dispatchers.IO) { translationCacheFile(context, lines, normalizedLanguage) }
        val cached = withContext(Dispatchers.IO) { readCache(cacheFile) }.toMutableMap()
        val pending = units.filterNot { cached.containsKey(it.cacheKey) }
        if (pending.isEmpty()) {
            onProgress(units.size, units.size)
            return@withLock cached.takeIf { units.all { unit -> it.containsKey(unit.cacheKey) } }.orEmpty()
        }

        val model = modelFile(context)
        check(hasModel(context)) { "PLaMoモデルがありません。設定からダウンロードしてください。" }
        val handle = withContext(Dispatchers.IO) {
            ensureNativeLoaded()
            PlamoNativeBridge.nativeCreateModel(
                model.absolutePath,
                Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }
        if (handle == 0L) throw IOException("PLaMoモデルを読み込めませんでした。端末の空きメモリを確認してください。")

        try {
            val total = units.size
            var completed = cached.size
            onProgress(completed, total)
            for (unit in pending) {
                currentCoroutineContext().ensureActive()
                val prompt = translationPrompt(unit.text, normalizedLanguage)
                val response = withContext(Dispatchers.Default) {
                    PlamoNativeBridge.nativeGenerate(handle, prompt, 512).orEmpty()
                }
                val translated = parseTranslationResponse(response)
                if (translated != null) {
                    cached[unit.cacheKey] = translated
                    withContext(Dispatchers.IO) { writeCache(cacheFile, cached) }
                }
                completed = cached.size
                onProgress(completed, total)
            }
            cached.takeIf { units.all { unit -> it.containsKey(unit.cacheKey) } }.orEmpty()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                PlamoNativeBridge.nativeReleaseModel(handle)
            }
        }
    }

    /**
     * Corrects Japanese lyric orthography that contains Simplified Chinese forms.
     * The editor text is reconstructed from the original row prefixes and line
     * endings, so LRC and inline word timestamps are retained byte-for-byte.
     * Results are only returned after every row and protected timestamp marker
     * has been recovered; callers should preview before applying them.
     */
    suspend fun correctJapaneseOrthography(
        context: Context,
        source: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): String? = operationMutex.withLock {
        if (source.isBlank() || !withContext(Dispatchers.IO) { hasModel(context) }) return@withLock null
        val rows = splitEditorRows(source)
        val units = buildCorrectionUnits(rows)
        if (units.isEmpty()) return@withLock null

        val cacheFile = withContext(Dispatchers.IO) { correctionCacheFile(context, source) }
        val cached = withContext(Dispatchers.IO) { readCache(cacheFile) }.toMutableMap()
        val pending = units.filterNot { cached.containsKey(it.id) }

        if (pending.isNotEmpty()) {
            val handle = withContext(Dispatchers.IO) {
                ensureNativeLoaded()
                PlamoNativeBridge.nativeCreateModel(
                    modelFile(context).absolutePath,
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                )
            }
            if (handle == 0L) throw IOException("PLaMoモデルを読み込めませんでした。端末の空きメモリを確認してください。")
            try {
                var completed = cached.size
                onProgress(completed, units.size)
                for (chunk in pending.chunked(4)) {
                    currentCoroutineContext().ensureActive()
                    val response = withContext(Dispatchers.Default) {
                        PlamoNativeBridge.nativeGenerate(handle, correctionPrompt(chunk), 512).orEmpty()
                    }
                    val corrected = parseCorrectionResponse(response, chunk) ?: return@withLock null
                    cached.putAll(corrected)
                    withContext(Dispatchers.IO) { writeCache(cacheFile, cached) }
                    completed = cached.size
                    onProgress(completed, units.size)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    PlamoNativeBridge.nativeReleaseModel(handle)
                }
            }
        } else {
            onProgress(units.size, units.size)
        }

        val correctedRows = linkedMapOf<Int, String>()
        units.forEach { unit ->
            val answer = cached[unit.id] ?: return@withLock null
            val returnedMarkers = inlineMarkerRegex.findAll(answer).map { it.value }.toList()
            if (returnedMarkers != unit.timeMarkers.map { it.first }) return@withLock null
            var body = answer
            unit.timeMarkers.forEach { (marker, timestamp) -> body = body.replace(marker, timestamp) }
            correctedRows[unit.rowIndex] = unit.leadingWhitespace + body + unit.trailingWhitespace
        }
        buildString(source.length) {
            rows.forEach { row ->
                append(row.prefix)
                append(correctedRows[row.index] ?: row.body)
                append(row.lineEnding)
            }
        }
    }

    private fun modelDirectory(context: Context) = File(context.noBackupFilesDir, "plamo_lyrics")

    private data class EditorRow(
        val index: Int,
        val prefix: String,
        val body: String,
        val lineEnding: String,
    )

    private data class CorrectionUnit(
        val id: String,
        val rowIndex: Int,
        val protectedBody: String,
        val timeMarkers: List<Pair<String, String>>,
        val leadingWhitespace: String,
        val trailingWhitespace: String,
    )

    private val leadingLrcTimestamps = Regex(
        """^([ \t]*(?:\[\d{1,3}:\d{2}(?:\.\d{1,3})?\][ \t]*)+)(.*)$"""
    )
    private val inlineWordTimestamp = Regex("""<\d{1,3}:\d{2}(?:\.\d{1,3})?>""")
    private val inlineMarkerRegex = Regex("""\[\[WORDTIME_\d{4}]]""")
    private val metadataHeader = Regex("""^\s*\[[A-Za-z]{2,}(?:[-_][A-Za-z]+)?:.*]$""")

    private fun normalizeTargetLanguage(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag.replace('_', '-'))
        if (locale.language == "zh") {
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
            return if (traditional) "zh-TW" else "zh-CN"
        }
        return locale.language.ifBlank { "en" }
    }

    private fun splitEditorRows(source: String): List<EditorRow> {
        val rows = mutableListOf<EditorRow>()
        var start = 0
        while (start < source.length) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline
            val hasCarriageReturn = end > start && source[end - 1] == '\r'
            val contentEnd = if (hasCarriageReturn) end - 1 else end
            val lineEnding = when {
                newline < 0 && hasCarriageReturn -> "\r"
                newline < 0 -> ""
                hasCarriageReturn -> "\r\n"
                else -> "\n"
            }
            val content = source.substring(start, contentEnd)
            val match = leadingLrcTimestamps.matchEntire(content)
            rows += EditorRow(
                index = rows.size,
                prefix = match?.groupValues?.get(1).orEmpty(),
                body = match?.groupValues?.get(2) ?: content,
                lineEnding = lineEnding,
            )
            if (newline < 0) break
            start = newline + 1
        }
        return rows
    }

    private fun buildCorrectionUnits(rows: List<EditorRow>): List<CorrectionUnit> = buildList {
        rows.forEach { row ->
            val body = row.body
            if (body.isBlank() || metadataHeader.matches(body) || body.trimStart().startsWith("{")) return@forEach
            val leadingWhitespace = body.takeWhile(Char::isWhitespace)
            val trailingWhitespace = body.takeLastWhile(Char::isWhitespace)
            val lyric = body.substring(leadingWhitespace.length, body.length - trailingWhitespace.length)
            if (lyric.isBlank()) return@forEach
            val markers = mutableListOf<Pair<String, String>>()
            val protected = inlineWordTimestamp.replace(lyric) { match ->
                val marker = "[[WORDTIME_${markers.size.toString().padStart(4, '0')}]]"
                markers += marker to match.value
                marker
            }
            if (protected.replace(inlineMarkerRegex, "").isBlank()) return@forEach
            add(
                CorrectionUnit(
                    id = "C${row.index.toString().padStart(6, '0')}",
                    rowIndex = row.index,
                    protectedBody = protected,
                    timeMarkers = markers,
                    leadingWhitespace = leadingWhitespace,
                    trailingWhitespace = trailingWhitespace,
                )
            )
        }
    }

    private fun correctionPrompt(units: List<CorrectionUnit>): String {
        val input = units.joinToString("\n") { "[[LYRIC_${it.id}]] ${it.protectedBody.replace('\n', ' ')}" }
        return "<|plamo:tag|>dataset<|plamo:msg|>translation" +
            "<|plamo:tag|>input<|plamo:msg|>These are Japanese song lyrics that may contain Simplified Chinese character forms or Chinese-only word choices. " +
            "Correct only those forms into natural Japanese orthography, using each line's context. " +
            "Do not translate, paraphrase, reorder, or change Japanese words, names, punctuation, spaces, or meaning. " +
            "If a line is already natural Japanese, reproduce it unchanged. " +
            "Keep every supplied line marker and word-time marker exactly as written, exactly once, and in the same order. " +
            "Return one output line per lyric marker and no explanation.\n$input" +
            "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>"
    }

    private fun parseCorrectionResponse(
        response: String,
        units: List<CorrectionUnit>,
    ): Map<String, String>? {
        if (response.isBlank() || response.length > MAX_RESPONSE_BYTES) return null
        val expected = units.associateBy { it.id }
        val pattern = Regex("""^\[\[LYRIC_(C\d{6})]]\s*(.*)$""")
        val output = linkedMapOf<String, String>()
        response.lineSequence().forEach { raw ->
            val match = pattern.matchEntire(raw) ?: return@forEach
            val id = match.groupValues[1]
            val answer = match.groupValues[2].trim()
            val unit = expected[id] ?: return null
            val markers = inlineMarkerRegex.findAll(answer).map { it.value }.toList()
            if (markers != unit.timeMarkers.map { it.first } || answer.isBlank() || answer.length > 2_000) return null
            if (output.put(id, answer) != null) return null
        }
        return output.takeIf { it.keys == expected.keys }
    }

    private fun correctionCacheFile(context: Context, source: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${MODEL_VERSION}\u001fja\u001f$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(modelDirectory(context), "corrections")
        return File(directory, "$digest.json")
    }

    private fun hasGgufMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
    }.getOrDefault(false)

    private suspend fun awaitDownload(
        client: OkHttpClient,
        initialCall: Call,
        partial: File,
        offset: Long,
        onProgress: (Long, Long) -> Unit,
    ) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        fun enqueue(call: Call, depth: Int) {
            if (!continuation.isActive) {
                call.cancel()
                return
            }
            activeCall.set(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { res ->
                            val host = res.request.url.host
                            if (!isHuggingFaceHost(host)) throw IOException("モデルの配布先が公式ホストではありません。")
                            if (res.code in 300..399) {
                                if (depth >= 5) throw IOException("モデル配布先の転送回数が多すぎます。")
                                val location = res.header("Location") ?: throw IOException("モデル配布先が見つかりません。")
                                val next = res.request.url.resolve(location)
                                    ?: throw IOException("モデル配布先URLが正しくありません。")
                                if (next.scheme != "https" || !isHuggingFaceHost(next.host)) {
                                    throw IOException("モデル配布先が公式HTTPSホストではありません。")
                                }
                                val nextRequest = Request.Builder().url(next)
                                    .header("Accept-Encoding", "identity")
                                    .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                                    .build()
                                // The next request uses the verified HTTPS redirect target.
                                enqueue(client.newCall(nextRequest), depth + 1)
                                return
                            }
                            if (!res.isSuccessful || (res.code != 200 && res.code != 206)) {
                                throw IOException("モデルのダウンロードに失敗しました（HTTP ${res.code}）。")
                            }
                            val append = offset > 0L && res.code == 206
                            if (append) {
                                val range = res.header("Content-Range") ?: throw IOException("再開位置を検証できませんでした。")
                                val rangeMatch = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range)
                                val rangeStart = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                                val rangeEnd = rangeMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
                                val rangeTotal = rangeMatch?.groupValues?.getOrNull(3)?.toLongOrNull()
                                if (rangeStart != offset || rangeTotal != MODEL_BYTES || rangeEnd == null || rangeEnd >= MODEL_BYTES) {
                                    throw IOException("モデルの再開位置または全体サイズが一致しません。")
                                }
                            }
                            val startingBytes = if (append) offset else 0L
                            val body = res.body ?: throw IOException("モデルの応答が空です。")
                            BufferedInputStream(body.byteStream(), 64 * 1024).use { input ->
                                val outputFile = FileOutputStream(partial, append)
                                BufferedOutputStream(outputFile, 64 * 1024).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var received = startingBytes
                                    while (true) {
                                        if (!continuation.isActive) throw CancellationException("ダウンロードをキャンセルしました。")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (received + count > MODEL_BYTES) throw IOException("モデルのサイズが想定より大きいため停止しました。")
                                        output.write(buffer, 0, count)
                                        received += count
                                        onProgress(received, MODEL_BYTES)
                                    }
                                    output.flush()
                                    outputFile.fd.sync()
                                }
                            }
                            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                        }
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) continuation.cancel(cancelled)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        activeCall.compareAndSet(call, null)
                    }
                }
            })
        }
        continuation.invokeOnCancellation {
            activeCall.getAndSet(null)?.cancel()
        }
        enqueue(initialCall, 0)
    }

    private fun isHuggingFaceHost(host: String): Boolean =
        host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co")

    private data class TranslationUnit(val cacheKey: String, val text: String)

    private fun buildUnits(lines: List<LyricLine>): List<TranslationUnit> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank() && !LrcParser.isSectionHeader(line.text)) {
                add(TranslationUnit("line:$index", line.text))
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { backing ->
                add(TranslationUnit("backing:$index", backing.text))
            }
        }
    }

    /** PFML3 is a translation task format, not an instruction-following chat format. */
    private fun translationPrompt(text: String, language: String): String = buildString {
        append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        append("<|plamo:tag|>input<|plamo:msg|>")
        append(text.replace('\n', ' '))
        append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>")
        append(language)
        if (language == "ja") append("<|plamo:key|>form<|plamo:val|>plain")
        append("<|plamo:msg|>")
    }

    private fun parseTranslationResponse(response: String): String? {
        // llama.cpp may return the PFML3 boundary token when generation reaches
        // its limit. It is metadata, not part of the translated lyric.
        val text = response.trim()
            .substringBefore("<|plamo:tag|")
            .substringBefore("<|plamo:bos|")
            .trim()
        return text.takeIf {
            it.isNotEmpty() && it.length <= 2_000 && it.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES
        }
    }

    private fun translationCacheFile(context: Context, lines: List<LyricLine>, language: String): File {
        val content = buildString {
            append(MODEL_VERSION).append('\n').append(language).append('\n')
            lines.forEach { line ->
                append(line.timeMs).append('\u001f').append(line.text).append('\u001e')
                line.background?.let { append(it.timeMs).append('\u001f').append(it.text) }
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(modelDirectory(context), "translations").apply { mkdirs() }
        return File(directory, "$digest.json")
    }

    private fun readCache(file: File): Map<String, String> = runCatching {
        if (!file.isFile || file.length() > 512 * 1024) return@runCatching emptyMap()
        val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key).takeIf { it.isNotBlank() && it.length < 2_000 }
                if (key.length < 32 && value != null) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(file: File, values: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = org.json.JSONObject()
            values.forEach { (key, value) -> if (key.length < 32 && value.length < 2_000) json.put(key, value) }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            temporary.renameTo(file)
        }
    }

    private fun ensureNativeLoaded() {
        if (!nativeLoaded) synchronized(this) {
            if (!nativeLoaded) {
                System.loadLibrary("bitchord_plamo")
                nativeLoaded = true
            }
        }
    }

    @Volatile private var nativeLoaded = false
}

@androidx.annotation.Keep
internal object PlamoNativeBridge {
    @JvmStatic external fun nativeCreateModel(modelPath: String, requestedThreads: Int): Long
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String?
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeReleaseModel(handle: Long)
}
