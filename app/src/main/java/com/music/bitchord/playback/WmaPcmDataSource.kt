package com.music.bitchord.playback

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Decodes local WMA only when its MediaItem explicitly carries [WMA_PCM_CACHE_KEY_PREFIX].
 * Other sources continue through the exact upstream DataSource they used before.
 * The original file is read-only; decoded PCM is kept in the disposable app cache.
 */
@UnstableApi
internal class WmaPcmDataSource private constructor(
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
        val requestedUri = dataSpec.uri
        val cacheKey = dataSpec.key.orEmpty()
        val explicitlyWma = cacheKey.startsWith(WMA_PCM_CACHE_KEY_PREFIX)
        val needsMediaStoreProbe = cacheKey.startsWith(WMA_MEDIASTORE_PROBE_CACHE_KEY_PREFIX) &&
            requestedUri.scheme == "content" && requestedUri.authority == "media"
        if (requestedUri.scheme !in setOf("file", "content") || (!explicitlyWma && !needsMediaStoreProbe)) {
            active = upstream
            return upstream.open(dataSpec)
        }
        if (needsMediaStoreProbe && !WmaPcmCache.isMediaStoreWma(appContext, requestedUri)) {
            active = upstream
            return upstream.open(dataSpec)
        }

        val pcmFile = WmaPcmCache.decode(appContext, requestedUri)
        WmaPcmCache.acquire(pcmFile)
        val fileSource = FileDataSource().also { source ->
            listeners.forEach(source::addTransferListener)
        }
        decodedFile = pcmFile
        active = fileSource
        return try {
            fileSource.open(dataSpec.buildUpon().setUri(Uri.fromFile(pcmFile)).build())
        } catch (error: IOException) {
            decodedFile = null
            active = upstream
            WmaPcmCache.release(pcmFile)
            throw error
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        val openDecodedFile = decodedFile
        decodedFile = null
        try {
            active.close()
        } finally {
            active = upstream
            if (openDecodedFile != null) WmaPcmCache.release(openDecodedFile)
        }
    }

    class Factory(
        private val context: Context,
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = WmaPcmDataSource(context, upstream.createDataSource())
    }
}

private object WmaPcmCache {
    private const val TAG = "WmaPcmDataSource"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private val lock = Any()
    private val activeFiles = mutableMapOf<String, Int>()

    @Throws(IOException::class)
    fun decode(context: Context, sourceUri: Uri): File = synchronized(lock) {
        val cacheDir = File(context.cacheDir, "decoded-wma").apply {
            if (!exists() && !mkdirs()) throw IOException("WMA用の一時フォルダーを作成できませんでした。")
        }
        val fingerprint = sourceFingerprint(context, sourceUri)
        val output = File(cacheDir, "${fingerprint.sha256()}.wav")
        if (output.isFile && output.length() > 44L && hasWaveHeader(output)) return output

        output.delete()
        val (input, temporaryInput) = prepareInput(context, sourceUri, cacheDir)
        val partial = File.createTempFile("wma-decode-", ".wav", cacheDir)
        try {
            val arguments = arrayOf(
                "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", input.absolutePath,
                "-map", "0:a:0", "-vn", "-sn", "-dn",
                // Decoding to 32-bit PCM adds no lossy encoding step and keeps
                // WMA Lossless/high-resolution samples intact for Media3.
                "-c:a", "pcm_s32le", "-f", "wav", partial.absolutePath,
            )
            val session = try {
                FFmpegKit.executeWithArguments(arguments)
            } catch (error: LinkageError) {
                throw IOException("この端末用のWMAデコーダーを読み込めませんでした。", error)
            } catch (error: RuntimeException) {
                throw IOException("WMA音源をデコードできませんでした。", error)
            }
            if (!ReturnCode.isSuccess(session.returnCode) || partial.length() <= 44L || !hasWaveHeader(partial)) {
                val details = (session.failStackTrace ?: session.allLogsAsString).orEmpty().takeLast(900)
                throw IOException(
                    "WMA音源をデコードできませんでした。" +
                        details.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
                )
            }
            if (!partial.renameTo(output)) {
                partial.copyTo(output, overwrite = true)
                partial.delete()
            }
            output.setLastModified(System.currentTimeMillis())
            return output
        } finally {
            temporaryInput?.delete()
            partial.delete()
        }
    }

    fun acquire(file: File) = synchronized(lock) {
        val key = file.absolutePath
        activeFiles[key] = (activeFiles[key] ?: 0) + 1
    }

    fun release(file: File) = synchronized(lock) {
        val key = file.absolutePath
        val count = activeFiles[key] ?: 0
        if (count <= 1) activeFiles.remove(key) else activeFiles[key] = count - 1
        prune(file.parentFile)
    }

    fun isMediaStoreWma(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            cursor.moveToFirst() && nameColumn >= 0 &&
                cursor.getString(nameColumn)?.endsWith(".wma", ignoreCase = true) == true
        } == true
    }.getOrDefault(false)

    private fun prepareInput(context: Context, uri: Uri, cacheDir: File): Pair<File, File?> {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
                ?: throw IOException("WMAファイルの場所を読み取れませんでした。")
            if (!file.isFile || !file.canRead()) throw IOException("WMAファイルを開けませんでした。")
            return file to null
        }

        val input = File.createTempFile("wma-source-", ".wma", cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                input.outputStream().buffered().use { target -> source.copyTo(target) }
            } ?: throw IOException("WMAファイルを読み取れませんでした。")
            if (input.length() <= 0L) throw IOException("WMAファイルが空です。")
            return input to input
        } catch (error: Exception) {
            input.delete()
            if (error is IOException) throw error
            throw IOException("WMAファイルを読み取れませんでした。", error)
        }
    }

    private fun sourceFingerprint(context: Context, uri: Uri): String {
        val metadata = when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.let { "${it.length()}:${it.lastModified()}" }.orEmpty()
            "content" -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor -> cursor.fingerprintValues() }.orEmpty()
            }.getOrDefault("")
            else -> ""
        }
        return "$uri|$metadata"
    }

    private fun Cursor.fingerprintValues(): String {
        if (!moveToFirst()) return ""
        val size = getColumnIndex(MediaStore.MediaColumns.SIZE).takeIf { it >= 0 }?.let(::getLong)
        val modified = getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 }?.let(::getLong)
        return "$size:$modified"
    }

    private fun hasWaveHeader(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            if (input.read(header) != header.size) return@use false
            header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                header.copyOfRange(8, 12).decodeToString() == "WAVE"
        }
    }.getOrDefault(false)

    private fun prune(directory: File?) {
        val files = directory?.listFiles { file -> file.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy(File::lastModified)
            ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            if (activeFiles.containsKey(file.absolutePath)) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal const val WMA_PCM_CACHE_KEY_PREFIX = "musicbeatsp:wma-pcm:"
internal const val WMA_MEDIASTORE_PROBE_CACHE_KEY_PREFIX = "musicbeatsp:wma-probe:"
