/*
 * The AudioSink shape follows Uyatame CD Ripping Tool (UCRT), GPL-3.0-or-later.
 * Original project: https://github.com/Uyatame-CD-Ripping-Tool-UCRT
 */
package com.music.bitchord.feature.cdripping

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface AudioSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun finish()
}

/** Lossless 16-bit/44.1 kHz/stereo WAV writer with a small RIFF INFO tag list. */
internal class WavSink(
    private val output: OutputStream,
    private val expectedPcmBytes: Long,
    tags: Map<String, String>,
) : AudioSink {
    private val info = encodeInfo(tags)
    private var written = 0L
    private var finished = false

    init {
        require(expectedPcmBytes in 4..(0xFFFF_FFFFL - 64L) && expectedPcmBytes % 4L == 0L) {
            "Invalid WAV data size"
        }
        val riffSize = 44L + info.size + expectedPcmBytes
        require(riffSize <= 0xFFFF_FFFFL) { "WAV exceeds the RIFF format limit" }
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32(riffSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeUInt32(16)
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)
            putShort(2)
            putInt(44_100)
            putInt(176_400)
            putShort(4)
            putShort(16)
        }.array()
        output.write(format)
        output.write("LIST".toByteArray(Charsets.US_ASCII))
        writeUInt32(info.size.toLong())
        output.write(info)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeUInt32(expectedPcmBytes)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "WAV writer is already finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        require(length % 4 == 0) { "CD-DA PCM data must be frame-aligned" }
        require(written <= expectedPcmBytes - length) { "Too much PCM data" }
        output.write(bytes, offset, length)
        written += length
    }

    override fun finish() {
        check(!finished) { "WAV writer is already finished" }
        check(written == expectedPcmBytes) { "CD read ended before the WAV track was complete" }
        output.flush()
        finished = true
    }

    private fun writeUInt32(value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
    }

    private fun encodeInfo(tags: Map<String, String>): ByteArray {
        val idByTag = mapOf(
            "TITLE" to "INAM",
            "ARTIST" to "IART",
            "ALBUM" to "IPRD",
            "DATE" to "ICRD",
            "TRACKNUMBER" to "ITRK",
        )
        val body = java.io.ByteArrayOutputStream()
        body.write("INFO".toByteArray(Charsets.US_ASCII))
        tags.forEach { (tag, rawValue) ->
            val id = idByTag[tag] ?: return@forEach
            val value = rawValue.replace('\u0000', ' ').toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.size).array())
            body.write(value)
            if (value.size % 2 != 0) body.write(0)
        }
        val bytes = body.toByteArray()
        require(bytes.size <= 65_536) { "WAV metadata is too large" }
        return bytes
    }
}
