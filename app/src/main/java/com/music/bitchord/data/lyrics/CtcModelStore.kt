package com.music.bitchord.data.lyrics

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * On-demand MMS-300M model storage for CTC forced alignment.
 * The ONNX weights are distributed by romara-labs from MahmoudAshraf's
 * mms-300m-1130-forced-aligner and remain under CC-BY-NC-4.0.
 */
internal object CtcModelStore {
    const val MODEL_BYTES = 1_262_408_964L
    const val LICENSE = "CC-BY-NC-4.0"
    private const val SHA256 = "26b2c0ba8f11ed045424ae1a70bc0b76f080952fb62fab5a37b3b39d82f08391"
    private const val MODEL_URL = "https://huggingface.co/romara-labs/mms-300m-1130-forced-aligner-ONNX/resolve/main/model.fp32.onnx?download=true"

    fun file(context: Context): File = File(
        File(context.filesDir, "models").apply { mkdirs() },
        "mms-300m-align-fp32.onnx",
    )

    suspend fun ready(context: Context): Boolean = withContext(Dispatchers.IO) {
        val model = file(context)
        model.length() == MODEL_BYTES && sha256(model) == SHA256
    }

    /** Resumes a partial download, verifies the complete SHA-256, then atomically promotes it. */
    suspend fun ensure(context: Context, progress: (Int) -> Unit): File {
        if (ready(context)) {
            progress(100)
            return file(context)
        }
        check(!context.packageName.endsWith(".nointernet")) {
            "この版では通信できないため、モデルを初回ダウンロードできません。"
        }
        check(!AppSettings.offlineMode.value) { "オフラインモードを解除してからもう一度実行してください。" }

        return withContext(Dispatchers.IO) {
            val destination = file(context)
            val partial = File(destination.parentFile, destination.name + ".part")
            if (partial.length() > MODEL_BYTES) partial.delete()
            val initialOffset = partial.length()
            val available = destination.parentFile?.usableSpace ?: 0L
            if (available < MODEL_BYTES - initialOffset) {
                throw IOException("モデル用の空き容量が不足しています（約1.26 GB必要です）。")
            }

            val request = Request.Builder().url(MODEL_URL).apply {
                if (initialOffset > 0L) header("Range", "bytes=$initialOffset-")
            }.build()
            val client = Http.lyricsClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try {
                            response.use { res ->
                                if (!res.isSuccessful || res.code !in 200..206) {
                                    throw IOException("モデルの取得に失敗しました（HTTP ${res.code}）。")
                                }
                                var offset = initialOffset
                                val append = initialOffset > 0L && res.code == 206
                                if (append) {
                                    val range = res.header("Content-Range")
                                        ?: throw IOException("再開位置を確認できない応答でした。")
                                    val start = Regex("bytes (\\d+)-").find(range)?.groupValues?.getOrNull(1)?.toLongOrNull()
                                    if (start != initialOffset) throw IOException("モデルの再開位置が一致しません。")
                                } else if (initialOffset > 0L) {
                                    // The host ignored Range; start a clean download instead of duplicating bytes.
                                    offset = 0L
                                }
                                val body = res.body ?: throw IOException("モデルの応答が空です。")
                                BufferedInputStream(body.byteStream(), 64 * 1024).use { input ->
                                    val fileOutput = FileOutputStream(partial, append)
                                    BufferedOutputStream(fileOutput, 64 * 1024).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var copied = offset
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            output.write(buffer, 0, count)
                                            copied += count
                                            progress((copied * 100 / MODEL_BYTES).toInt().coerceIn(0, 99))
                                        }
                                        output.flush()
                                        fileOutput.fd.sync()
                                    }
                                }
                                if (partial.length() != MODEL_BYTES) {
                                    throw IOException("モデルのダウンロードが途中で止まりました。次回は続きから再開します。")
                                }
                                if (sha256(partial) != SHA256) {
                                    partial.delete()
                                    throw IOException("モデルのSHA-256検証に失敗しました。もう一度お試しください。")
                                }
                                if (destination.exists()) destination.delete()
                                if (!partial.renameTo(destination)) throw IOException("モデルを保存できませんでした。")
                                progress(100)
                                if (continuation.isActive) continuation.resumeWith(Result.success(destination))
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
