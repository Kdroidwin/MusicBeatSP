package com.music.bitchord.data

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Reads artist metadata from RIFF/WAVE chunks when MediaStore and TagLib miss it. */
internal object WavArtistTagReader {
    private const val MAX_ID3_TAG_BYTES = 32 * 1024 * 1024
    private const val MAX_TEXT_FRAME_BYTES = 128 * 1024
    private val latin1 = Charset.forName("ISO-8859-1")

    fun readArtist(context: Context, song: Song): String? = runCatching {
        val uri = song.localUri?.let(Uri::parse)
            ?: song.localPath?.let { Uri.fromFile(File(it)) }
            ?: return null
        context.contentResolver.openInputStream(uri)?.use(::readArtist)
    }.getOrNull()

    private fun readArtist(input: InputStream): String? {
        val riffHeader = input.readExactly(12) ?: return null
        val riff = riffHeader.copyOfRange(0, 4).toAscii()
        val wave = riffHeader.copyOfRange(8, 12).toAscii()
        if ((riff != "RIFF" && riff != "RF64") || wave != "WAVE") return null

        var remainingRiff = (riffHeader.uint32le(4) - 4).coerceAtLeast(0)
        repeat(4096) {
            if (remainingRiff < 8) return null
            val chunkHeader = input.readExactly(8) ?: return null
            remainingRiff -= 8
            val chunkId = chunkHeader.copyOfRange(0, 4).toAscii()
            val chunkSize = chunkHeader.uint32le(4)
            if (chunkSize > remainingRiff) return null

            val artist = when (chunkId) {
                "id3 ", "ID3 " -> readId3Artist(input, chunkSize)
                "LIST" -> readInfoArtist(input, chunkSize)
                else -> {
                    input.skipExactly(chunkSize)
                    null
                }
            }
            if (artist != null) return artist

            remainingRiff -= chunkSize
            if (chunkSize and 1L != 0L) {
                if (!input.skipExactly(1)) return null
                remainingRiff -= 1
            }
        }
        return null
    }

    private fun readId3Artist(input: InputStream, chunkSize: Long): String? {
        if (chunkSize < 10 || chunkSize > MAX_ID3_TAG_BYTES) {
            input.skipExactly(chunkSize)
            return null
        }
        val header = input.readExactly(10) ?: return null
        val version = header[3].toInt() and 0xff
        if (header.copyOfRange(0, 3).toAscii() != "ID3" || version !in 2..4) {
            input.skipExactly(chunkSize - 10)
            return null
        }
        val declaredBodySize = header.syncSafeInt(6)
        val bodySize = declaredBodySize?.toLong()?.coerceAtMost(chunkSize - 10) ?: 0L
        val flags = header[5].toInt() and 0xff
        val artist = if (declaredBodySize != null && (version != 2 || flags and 0x40 == 0)) {
            readId3Frames(input, version, flags, bodySize)
        } else {
            input.skipExactly(bodySize)
            null
        }
        input.skipExactly(chunkSize - 10 - bodySize)
        return artist
    }

    /** Consumes exactly [bodySize] bytes, skipping artwork and unrelated frames. */
    private fun readId3Frames(input: InputStream, version: Int, flags: Int, bodySize: Long): String? {
        var remaining = bodySize
        var artist: String? = null

        if (version >= 3 && flags and 0x40 != 0 && remaining >= 4) {
            val extendedHeaderSizeBytes = input.readExactly(4) ?: return null
            remaining -= 4
            val encodedSize = if (version == 4) extendedHeaderSizeBytes.syncSafeInt(0)
                else extendedHeaderSizeBytes.uint32be(0).takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
            val extensionSize = encodedSize?.let {
                if (version == 3) it.toLong() else (it - 4).coerceAtLeast(0).toLong()
            } ?: 0L
            val toSkip = extensionSize.coerceAtMost(remaining)
            if (!input.skipExactly(toSkip)) return null
            remaining -= toSkip
        }

        val frameHeaderSize = if (version == 2) 6 else 10
        while (remaining >= frameHeaderSize) {
            val frameHeader = input.readExactly(frameHeaderSize) ?: return null
            remaining -= frameHeaderSize
            val idLength = if (version == 2) 3 else 4
            val frameId = frameHeader.copyOfRange(0, idLength).toAscii()
            if (frameId.all { it == '\u0000' }) break
            val frameSize = when (version) {
                2 -> frameHeader.uint24be(3).toLong()
                3 -> frameHeader.uint32be(4)
                else -> frameHeader.syncSafeInt(4)?.toLong() ?: break
            }
            if (frameSize <= 0 || frameSize > remaining) break

            if ((frameId == "TPE1" || frameId == "TP1") && frameSize <= MAX_TEXT_FRAME_BYTES) {
                val frame = input.readExactly(frameSize.toInt()) ?: return null
                artist = decodeTextFrame(frame)
                    ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                remaining -= frameSize
                if (artist != null) {
                    input.skipExactly(remaining)
                    return artist
                }
            } else {
                if (!input.skipExactly(frameSize)) return null
                remaining -= frameSize
            }
        }
        input.skipExactly(remaining)
        return artist
    }

    /** Reads the standard RIFF LIST/INFO artist field (IART). */
    private fun readInfoArtist(input: InputStream, chunkSize: Long): String? {
        if (chunkSize < 4) {
            input.skipExactly(chunkSize)
            return null
        }
        val listType = input.readExactly(4)?.toAscii() ?: return null
        var remaining = chunkSize - 4
        if (listType != "INFO") {
            input.skipExactly(remaining)
            return null
        }
        repeat(256) {
            if (remaining < 8) {
                input.skipExactly(remaining)
                return null
            }
            val header = input.readExactly(8) ?: return null
            remaining -= 8
            val id = header.copyOfRange(0, 4).toAscii()
            val size = header.uint32le(4)
            if (size > remaining) {
                input.skipExactly(remaining)
                return null
            }
            if (id == "IART" && size in 1..MAX_TEXT_FRAME_BYTES.toLong()) {
                val value = input.readExactly(size.toInt()) ?: return null
                remaining -= size
                val artist = decodeInfoText(value)
                    ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                if (artist != null) {
                    input.skipExactly(remaining)
                    return artist
                }
            } else {
                input.skipExactly(size)
                remaining -= size
            }
            if (size and 1L != 0L) {
                if (!input.skipExactly(1)) return null
                remaining -= 1
            }
        }
        input.skipExactly(remaining)
        return null
    }

    private fun decodeTextFrame(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xff
        val charset = when (encoding) {
            0 -> latin1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        return runCatching { String(frame, 1, frame.size - 1, charset) }
            .getOrNull()
            ?.trim('\u0000', '\uFEFF', ' ', '\t', '\r', '\n')
            ?.substringBefore('\u0000')
            ?.trim()
    }

    private fun decodeInfoText(bytes: ByteArray): String? {
        val text = if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
            runCatching { String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE) }.getOrNull()
        } else {
            runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
                ?: runCatching { String(bytes, latin1) }.getOrNull()
        }
        return text?.substringBefore('\u0000')?.trim()
    }

    private fun InputStream.readExactly(count: Int): ByteArray? {
        if (count < 0) return null
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            if (read < 0) return null
            if (read == 0) continue
            offset += read
        }
        return result
    }

    private fun InputStream.skipExactly(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() < 0) return false
                remaining--
            }
        }
        return true
    }

    private fun ByteArray.toAscii(): String = String(this, StandardCharsets.US_ASCII)

    private fun ByteArray.uint32le(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun ByteArray.uint32be(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    private fun ByteArray.uint24be(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 16) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            (this[offset + 2].toInt() and 0xff)

    private fun ByteArray.syncSafeInt(offset: Int): Int? {
        if ((0..3).any { this[offset + it].toInt() and 0x80 != 0 }) return null
        return ((this[offset].toInt() and 0x7f) shl 21) or
            ((this[offset + 1].toInt() and 0x7f) shl 14) or
            ((this[offset + 2].toInt() and 0x7f) shl 7) or
            (this[offset + 3].toInt() and 0x7f)
    }
}
