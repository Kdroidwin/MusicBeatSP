package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Local-only fallback used by CTC alignment when Android's MediaCodec cannot decode a file. */
internal object CtcFfmpegDecoder {

    suspend fun decode(
        context: Context,
        song: Song,
        uri: Uri,
        startSeconds: Double,
        endSeconds: Double,
    ): Pair<AudioDecoder.Pcm, Double> {
        val operationContext = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            var temporarySource: File? = null
            val pcmFile = File.createTempFile("ctc-pcm-", ".s16le", context.cacheDir)
            try {
                operationContext.ensureActive()
                val source = resolveSourceFile(context, song, uri).also { (file, temporary) ->
                    if (temporary) temporarySource = file
                }
                val (inputFile, _) = source
                val arguments = arrayListOf("-y", "-i", inputFile.absolutePath, "-vn", "-map", "0:a:0")
                if (startSeconds > 0.0) {
                    // Output seeking keeps the cut accurate even for formats without useful seek indexes.
                    arguments += listOf("-ss", startSeconds.ffmpegTime())
                }
                if (endSeconds > startSeconds) {
                    arguments += listOf("-t", (endSeconds - startSeconds).ffmpegTime())
                }
                arguments += listOf(
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    pcmFile.absolutePath,
                )

                val session = try {
                    FFmpegKit.executeWithArguments(arguments.toTypedArray())
                } catch (error: LinkageError) {
                    throw IOException("この端末用のFFmpegデコーダーを読み込めませんでした。", error)
                }
                operationContext.ensureActive()
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    val details = (session.failStackTrace ?: session.allLogsAsString)
                        .orEmpty()
                        .takeLast(800)
                    throw IOException(
                        "FFmpegで音源をデコードできませんでした。${details.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()}",
                    )
                }

                val byteCount = pcmFile.length()
                if (byteCount <= 0L || byteCount % 2L != 0L) {
                    throw IOException("FFmpegのデコード結果が空か、不完全でした。")
                }
                val sampleCount = byteCount / 2L
                if (sampleCount > Int.MAX_VALUE) throw IOException("音源が長すぎて同期歌詞を生成できません。")
                val samples = FloatArray(sampleCount.toInt())
                BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sampleIndex = 0
                    while (sampleIndex < samples.size) {
                        operationContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        var byteIndex = 0
                        while (byteIndex + 1 < count) {
                            val pcm16 = ((buffer[byteIndex + 1].toInt() shl 8) or (buffer[byteIndex].toInt() and 0xff)).toShort()
                            samples[sampleIndex++] = pcm16 / 32768f
                            byteIndex += 2
                        }
                    }
                    if (sampleIndex != samples.size) throw IOException("FFmpegのPCMデータを最後まで読み取れませんでした。")
                }
                AudioDecoder.Pcm(samples, 16_000.0) to startSeconds
            } finally {
                temporarySource?.delete()
                pcmFile.delete()
            }
        }
    }

    private fun resolveSourceFile(context: Context, song: Song, uri: Uri): Pair<File, Boolean> {
        val localPath = song.localPath?.takeIf(String::isNotBlank)?.let(::File)
        val uriPath = uri.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?.path?.takeIf(String::isNotBlank)?.let(::File)
        val direct = listOfNotNull(localPath, uriPath).firstOrNull { it.isFile && it.canRead() }
        if (direct != null) return direct to false

        val copied = File.createTempFile("ctc-audio-", ".media", context.cacheDir)
        try {
            val source = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: localPath?.takeIf { it.isFile && it.canRead() }?.let(::FileInputStream)
                ?: throw IOException("端末の音源ファイルを開けませんでした。")
            source.use { input ->
                BufferedOutputStream(FileOutputStream(copied), 64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (copied.length() == 0L) throw IOException("端末の音源ファイルが空です。")
            return copied to true
        } catch (error: Exception) {
            copied.delete()
            throw error
        }
    }

    private fun Double.ffmpegTime(): String = String.format(Locale.US, "%.6f", coerceAtLeast(0.0))
}
