package com.music.bitchord.playback.dsp.dsd

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exposes DSF/DSDIFF as a streamed 24-bit PCM WAV to Media3. This reuses
 * UCRT's DSD parser/decimator and keeps the existing Media3 sinks and controls.
 * DST-compressed DFF and Native DSD/DoP output are intentionally not claimed.
 */
@UnstableApi
class DsdPcmDataSource(
    private val context: Context,
    private val downstream: DataSource,
) : DataSource {
    private var activeDsd = false
    private var openedUri: Uri? = null
    private var file: RandomFile? = null
    private var info: DsdInfo? = null
    private var reader: DsdReader? = null
    private var converter: DsdToPcm? = null
    private var raw = emptyArray<ByteArray>()
    private var pcm = FloatArray(0)
    private var outputFrames = 0L
    private var totalFrames = 0L
    private var bytesPerPcmFrame = 0
    private var position = 0L
    private var endPosition = 0L
    private var header = ByteArray(0)
    private var pending = ByteArray(0)
    private var pendingOffset = 0
    private var pendingLength = 0
    private var dropPendingBytes = 0

    override fun addTransferListener(transferListener: TransferListener) {
        downstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        clearDsdState()
        activeDsd = false
        openedUri = dataSpec.uri
        if (!AppSettings.dsdPlaybackEnabled.value || !isDsdUri(dataSpec.uri)) {
            return downstream.open(dataSpec)
        }

        val pfd = openDescriptor(dataSpec.uri) ?: throw IOException("Could not read DSD file: ${dataSpec.uri}")
        val openedFile = RandomFile(pfd)
        try {
            val parsed = DsdFile.parse(openedFile)
            if (parsed.compressed) throw IOException("DST-compressed DFF playback is not supported")
            if (parsed.channels !in 1..8 || parsed.rate <= 0 || parsed.bytesPerChannel <= 0) {
                throw IOException("Unsupported DSD channel count or sample rate")
            }
            val pcmConverter = DsdToPcm(parsed.rate, parsed.channels)
            val byteStride = parsed.rate / 8 / pcmConverter.outRate
            if (byteStride <= 0 || pcmConverter.outRate <= 0) throw IOException("Invalid DSD rate")
            val frames = parsed.bytesPerChannel / byteStride
            val pcmFrameBytes = parsed.channels * 3
            val dataBytes = frames * pcmFrameBytes
            if (dataBytes > 0xffff_ffffL - WAV_HEADER_BYTES) {
                throw IOException("Converted DSD audio exceeds the WAV size limit")
            }

            file = openedFile
            info = parsed
            val openedReader = DsdReader(openedFile, parsed)
            reader = openedReader
            converter = pcmConverter
            totalFrames = frames
            bytesPerPcmFrame = pcmFrameBytes
            raw = Array(parsed.channels) { ByteArray(DSD_CHUNK_BYTES) }
            pcm = FloatArray(pcmConverter.maxOut(DSD_CHUNK_BYTES) * parsed.channels)
            header = makeWavHeader(parsed.channels, pcmConverter.outRate, dataBytes)
            pending = ByteArray(0)
            pendingOffset = 0
            pendingLength = 0

            val start = dataSpec.position.coerceAtLeast(0)
            val totalLength = WAV_HEADER_BYTES + dataBytes
            position = start.coerceAtMost(totalLength)
            endPosition = if (dataSpec.length == C.LENGTH_UNSET.toLong()) totalLength else
                (start + dataSpec.length).coerceAtMost(totalLength)
            if (position >= WAV_HEADER_BYTES) {
                val dataOffset = position - WAV_HEADER_BYTES
                outputFrames = (dataOffset / pcmFrameBytes).coerceIn(0, totalFrames)
                dropPendingBytes = (dataOffset % pcmFrameBytes).toInt()
                openedReader.seek(outputFrames * byteStride)
            } else {
                outputFrames = 0
                dropPendingBytes = 0
                openedReader.seek(0)
            }
            activeDsd = true
            return (endPosition - position).coerceAtLeast(0)
        } catch (error: Exception) {
            openedFile.close()
            if (error is IOException) throw error
            throw IOException("Could not decode DSD file: ${error.message}", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!activeDsd) return downstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= endPosition) return C.RESULT_END_OF_INPUT

        if (position < WAV_HEADER_BYTES) {
            val count = minOf(length.toLong(), WAV_HEADER_BYTES - position, endPosition - position).toInt()
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            position += count
            return count
        }

        while (pendingLength == 0) {
            if (!fillPcm()) return C.RESULT_END_OF_INPUT
            if (dropPendingBytes > 0) {
                val dropped = minOf(dropPendingBytes, pendingLength)
                pendingOffset += dropped
                pendingLength -= dropped
                dropPendingBytes -= dropped
            }
        }
        val count = minOf(length.toLong(), pendingLength.toLong(), endPosition - position).toInt()
        pending.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        pendingLength -= count
        position += count
        return count
    }

    private fun fillPcm(): Boolean {
        val currentReader = reader ?: return false
        val currentConverter = converter ?: return false
        val currentInfo = info ?: return false
        while (outputFrames < totalFrames) {
            val bytesRead = currentReader.read(raw, DSD_CHUNK_BYTES)
            if (bytesRead <= 0) return false
            val decodedFrames = currentConverter.process(raw, bytesRead, pcm)
            if (decodedFrames <= 0) continue
            val framesToWrite = minOf(decodedFrames.toLong(), totalFrames - outputFrames).toInt()
            val needed = framesToWrite * bytesPerPcmFrame
            if (pending.size < needed) pending = ByteArray(needed)
            var target = 0
            for (frame in 0 until framesToWrite) {
                for (channel in 0 until currentInfo.channels) {
                    val scaled = Math.round(pcm[frame * currentInfo.channels + channel] * 8_388_608.0)
                        .coerceIn(-8_388_608L, 8_388_607L).toInt()
                    pending[target++] = scaled.toByte()
                    pending[target++] = (scaled shr 8).toByte()
                    pending[target++] = (scaled shr 16).toByte()
                }
            }
            outputFrames += framesToWrite
            pendingOffset = 0
            pendingLength = needed
            return true
        }
        return false
    }

    override fun getUri(): Uri? = if (activeDsd) openedUri else downstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (activeDsd) emptyMap() else downstream.responseHeaders

    override fun close() {
        try {
            if (!activeDsd) downstream.close()
        } finally {
            clearDsdState()
            activeDsd = false
            openedUri = null
        }
    }

    private fun clearDsdState() {
        runCatching { file?.close() }
        file = null
        info = null
        reader = null
        converter = null
        raw = emptyArray()
        pcm = FloatArray(0)
        pending = ByteArray(0)
        pendingOffset = 0
        pendingLength = 0
        dropPendingBytes = 0
        position = 0
        endPosition = 0
    }

    private fun isDsdUri(uri: Uri): Boolean {
        val pathName = uri.lastPathSegment.orEmpty()
        if (DsdFile.isDsd(pathName)) return true
        if (uri.scheme != "content") return false
        return DsdFile.isDsd(displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            val queriedName: String? = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor: Cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0)
                        else null
                    }
            }.getOrNull()
            if (!queriedName.isNullOrBlank()) return queriedName
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun openDescriptor(uri: Uri): ParcelFileDescriptor? = when (uri.scheme) {
        "file" -> uri.path?.let { ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_ONLY) }
        "content" -> context.contentResolver.openFileDescriptor(uri, "r")
        else -> null
    }

    private fun makeWavHeader(channels: Int, sampleRate: Int, dataBytes: Long): ByteArray {
        val blockAlign = channels * 3
        return ByteBuffer.allocate(WAV_HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36L + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(24)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }

    class Factory(
        private val context: Context,
        private val downstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DsdPcmDataSource(context, downstream.createDataSource())
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val DSD_CHUNK_BYTES = 4096
    }
}
