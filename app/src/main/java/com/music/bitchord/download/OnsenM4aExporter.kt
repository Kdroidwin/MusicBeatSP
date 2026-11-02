package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Converts an Onsen HLS audio stream into a portable AAC/M4A document. */
internal object OnsenM4aExporter {

    suspend fun write(
        context: Context,
        streamUrl: String,
        destination: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ) {
        val source = Uri.parse(streamUrl)
        require(source.scheme.equals("https", ignoreCase = true) && !source.host.isNullOrBlank()) {
            "Onsen returned an invalid audio URL"
        }
        val appContext = context.applicationContext
        val temporary = withContext(Dispatchers.IO) {
            File.createTempFile("onsen-audio-", ".m4a", appContext.cacheDir)
        }
        try {
            exportToFile(appContext, source, temporary)
            withContext(Dispatchers.IO) {
                val total = temporary.length()
                if (!temporary.isFile || total <= 0L) throw IOException("The M4A export produced no audio")
                temporary.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        written += count
                        onProgress(written, total)
                    }
                }
                destination.flush()
            }
        } finally {
            withContext(Dispatchers.IO) { temporary.delete() }
        }
    }

    private suspend fun exportToFile(context: Context, source: Uri, output: File) =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val item = MediaItem.Builder()
                    .setUri(source)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder()
                            .setBitrate(AAC_BITRATE_BPS)
                            .build(),
                    )
                    .build()
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()
                continuation.invokeOnCancellation {
                    val mainHandler = Handler(Looper.getMainLooper())
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        runCatching { transformer.cancel() }
                    } else {
                        mainHandler.post { runCatching { transformer.cancel() } }
                    }
                }
                try {
                    transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), output.absolutePath)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }

    private const val AAC_BITRATE_BPS = 256_000
    private const val COPY_BUFFER_BYTES = 128 * 1024
}
