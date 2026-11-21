package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Only these lossless/container formats opt into FFmpeg; ordinary codecs keep the native path. */
internal val FFMPEG_ONLY_AUDIO_EXTENSIONS = setOf(
    "ape", "wv", "tta", "tak", "mpc", "mka", "caf", "au", "snd", "aif", "aiff", "ac3", "dts",
)

internal const val UNSUPPORTED_AUDIO_PCM_CACHE_KEY_PREFIX = "musicbeat-ffmpeg-pcm:"

/**
 * Converts only an explicitly tagged local source to disposable PCM WAV.
 * Everything else is passed to the same upstream DataSource unchanged.
 */
@UnstableApi
internal class UnsupportedAudioPcmDataSource private constructor(
    context: Context,
    private val upstream: DataSource,
) : DataSource {

    private val appContext = context.applicationContext
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource = upstream
    private var decodedFile: File? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key.orEmpty()
        val requestedUri = dataSpec.uri
        if (!AppSettings.ffmpegUnsupportedAudioTranscodeEnabled.value ||
            !key.startsWith(UNSUPPORTED_AUDIO_PCM_CACHE_KEY_PREFIX) ||
            requestedUri.scheme !in setOf("file", "content")
        ) {
            active = upstream
            return upstream.open(dataSpec)
        }

        val sourceUri = runCatching {
            Uri.parse(Uri.decode(key.removePrefix(UNSUPPORTED_AUDIO_PCM_CACHE_KEY_PREFIX)))
        }.getOrNull()?.takeIf { it.scheme in setOf("file", "content") }
            ?: throw IOException("音源ファイルの場所を確認できませんでした。")
        val output = UnsupportedAudioPcmCache.decodeAndAcquire(appContext, sourceUri)
        val fileSource = FileDataSource().also { source -> listeners.forEach(source::addTransferListener) }
        decodedFile = output
        active = fileSource
        return try {
            // Preserve the extractor's range/seek into the generated WAV. Its byte
            // offsets belong to the decoded file, not the compressed source.
            fileSource.open(dataSpec.buildUpon().setUri(Uri.fromFile(output)).build())
        } catch (error: IOException) {
            decodedFile = null
            active = upstream
            UnsupportedAudioPcmCache.release(output)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        val opened = decodedFile
        decodedFile = null
        try {
            active.close()
        } finally {
            active = upstream
            if (opened != null) UnsupportedAudioPcmCache.release(opened)
        }
    }

    class Factory(
        private val context: Context,
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = UnsupportedAudioPcmDataSource(context, upstream.createDataSource())
    }
}

private object UnsupportedAudioPcmCache {
    private const val MAX_CACHE_BYTES = 768L * 1024L * 1024L
    private const val MAX_SOURCE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    private const val OUTPUT_LIMIT_TOLERANCE_BYTES = 64L * 1024L
    private val lock = Any()
    private val activeFiles = mutableMapOf<String, Int>()

    @Throws(IOException::class)
    fun decodeAndAcquire(context: Context, uri: Uri): File = synchronized(lock) {
        val dir = File(context.cacheDir, "ffmpeg-audio-pcm").apply {
            if (!exists() && !mkdirs()) throw IOException("一時音源フォルダーを作成できませんでした。")
        }
        prune(dir)
        val source = sourceFingerprint(context, uri)
        if (source.size > MAX_SOURCE_BYTES) throw IOException("音源ファイルが大きすぎるため一時変換できません。")
        val output = File(dir, "${source.id}.wav")
        if (output.isFile && output.length() > 44L && hasWaveHeader(output)) {
            output.setLastModified(System.currentTimeMillis())
            acquireLocked(output)
            return output
        }

        output.delete()
        val (input, temporaryInput) = prepareInput(context, uri, dir)
        val partial = File.createTempFile("decode-", ".wav", dir)
        try {
            val session = try {
                FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-i", input.absolutePath,
                        "-map", "0:a:0", "-vn", "-sn", "-dn",
                        "-c:a", "pcm_s32le", "-f", "wav", "-fs", MAX_OUTPUT_BYTES.toString(), partial.absolutePath,
                    ),
                )
            } catch (error: LinkageError) {
                throw IOException("この端末ではFFmpeg音源変換を利用できません。", error)
            } catch (error: RuntimeException) {
                throw IOException("音源をPCMへ変換できませんでした。", error)
            }
            if (!ReturnCode.isSuccess(session.returnCode) || partial.length() <= 44L ||
                partial.length() >= MAX_OUTPUT_BYTES - OUTPUT_LIMIT_TOLERANCE_BYTES || !hasWaveHeader(partial)
            ) {
                throw IOException("音源をPCMへ変換できませんでした。")
            }
            if (!partial.renameTo(output)) {
                partial.copyTo(output, overwrite = true)
                partial.delete()
            }
            output.setLastModified(System.currentTimeMillis())
            acquireLocked(output)
            return output
        } finally {
            temporaryInput?.delete()
            partial.delete()
        }
    }

    private fun acquireLocked(file: File) {
        val key = file.absolutePath
        activeFiles[key] = (activeFiles[key] ?: 0) + 1
    }

    fun release(file: File) = synchronized(lock) {
        val key = file.absolutePath
        val count = activeFiles[key] ?: 0
        if (count <= 1) activeFiles.remove(key) else activeFiles[key] = count - 1
        prune(file.parentFile)
    }

    private data class SourceFingerprint(val id: String, val size: Long)

    private fun sourceFingerprint(context: Context, uri: Uri): SourceFingerprint {
        val source = when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf { it.isFile }
                ?: throw IOException("音源ファイルを開けませんでした。")
            "content" -> null
            else -> throw IOException("この音源の場所には対応していません。")
        }
        if (source != null && !source.canRead()) throw IOException("音源ファイルを読み取れませんでした。")
        val size = source?.length() ?: runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0) cursor.getLong(column).coerceAtLeast(0L) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
        val modified = source?.lastModified() ?: 0L
        val identity = "${uri.normalizeScheme()}|$size|$modified"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SourceFingerprint(hash, size)
    }

    private fun prepareInput(context: Context, uri: Uri, dir: File): Pair<File, File?> {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)?.canonicalFile
                ?: throw IOException("音源ファイルの場所を読み取れませんでした。")
            if (!file.isFile || !file.canRead()) throw IOException("音源ファイルを開けませんでした。")
            return file to null
        }
        val input = File.createTempFile("source-", ".audio", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_SOURCE_BYTES) throw IOException("音源ファイルが大きすぎるため一時変換できません。")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("音源ファイルを読み取れませんでした。")
            if (input.length() <= 0L) throw IOException("音源ファイルが空です。")
            return input to input
        } catch (error: Exception) {
            input.delete()
            if (error is IOException) throw error
            throw IOException("音源ファイルを読み取れませんでした。", error)
        }
    }

    private fun hasWaveHeader(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            input.read(header) == header.size &&
                (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) in setOf("RIFF", "RF64")) &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    private fun prune(dir: File?) = synchronized(lock) {
        val files = dir?.listFiles()?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified) ?: return@synchronized
        var total = files.sumOf(File::length)
        files.asReversed().forEach { file ->
            if (total > MAX_CACHE_BYTES && file.absolutePath !in activeFiles) {
                total -= file.length()
                file.delete()
            }
        }
    }
}
