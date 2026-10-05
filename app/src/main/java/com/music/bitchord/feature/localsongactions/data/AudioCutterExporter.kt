package com.music.bitchord.feature.localsongactions.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exports a trimmed AAC copy through app cache and the user-selected SAF destination. */
object AudioCutterExporter {
    suspend fun export(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
    ): Long {
        require(startMs >= 0 && endMs - startMs >= MINIMUM_CLIP_MS) {
            "Select at least one second of audio."
        }

        val appContext = context.applicationContext
        val temporaryFile = withContext(Dispatchers.IO) {
            File.createTempFile("musicbeat-cut-", ".m4a", appContext.cacheDir)
        }
        try {
            exportToFile(appContext, source, temporaryFile, startMs, endMs)
            val byteCount = withContext(Dispatchers.IO) {
                if (!temporaryFile.isFile || temporaryFile.length() <= 0L) {
                    throw IOException("The audio export produced no data.")
                }
                val output = appContext.contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("The selected destination cannot be written.")
                output.use { sink ->
                    temporaryFile.inputStream().use { input -> input.copyTo(sink, COPY_BUFFER_BYTES) }
                }
            }
            if (byteCount <= 0L) throw IOException("The audio export produced no data.")
            return byteCount
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // Only our cache file is safe to remove automatically. A SAF provider can
                // return a pre-existing destination after the user confirms its name, so
                // deleting a partial destination here could destroy their existing file.
                temporaryFile.delete()
            }
        }
    }

    private suspend fun exportToFile(
        context: Context,
        source: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
    ) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(clipping)
                .build()
            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
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
                transformer.start(editedItem, output.absolutePath)
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private const val MINIMUM_CLIP_MS = 1_000L
    private const val COPY_BUFFER_BYTES = 128 * 1024
    private const val AAC_BITRATE_BPS = 256_000
}
