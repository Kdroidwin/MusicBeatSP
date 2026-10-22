/*
 * CD-DA sector extraction adapted from Uyatame CD Ripping Tool (UCRT).
 * Copyright (C) UCRT contributors. Licensed under GPL-3.0-or-later.
 * Original project: https://github.com/Uyatame-CD-Ripping-Tool-UCRT
 */
package com.music.bitchord.feature.cdripping

import java.io.IOException

internal data class CdRipOptions(
    val speedX: Int = 4,
    val sectorsPerRead: Int = 4,
    val retries: Int = 2,
    val offsetSamples: Int = 0,
)

internal data class CdRipResult(val errorSectors: Int)

/** Reads CD-DA using the guarded, bounded READ CD path adapted from UCRT. */
internal class CdRipper(
    private val drive: UsbScsiDrive,
    private val options: CdRipOptions,
) {
    private var errors = 0
    private var checkCancelled: () -> Unit = {}

    fun rip(
        track: CdTrack,
        leadOut: Int,
        sink: AudioSink,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit,
    ): CdRipResult {
        require(track.startLba >= 0 && track.endLba > track.startLba && track.endLba <= leadOut) {
            "Invalid audio track boundaries"
        }
        this.checkCancelled = checkCancelled
        errors = 0
        drive.setReadSpeed(options.speedX.coerceIn(1, 12))
        val sectorBytes = CD_SECTOR_BYTES.toLong()
        val startByte = track.startLba.toLong() * sectorBytes + options.offsetSamples.coerceIn(-588, 588) * 4L
        val endByte = track.endLba.toLong() * sectorBytes + options.offsetSamples.coerceIn(-588, 588) * 4L
        val firstSector = Math.floorDiv(startByte, sectorBytes)
        val lastSector = Math.floorDiv(endByte + sectorBytes - 1, sectorBytes)
        val skipFirst = (startByte - firstSector * sectorBytes).toInt()
        val totalBytes = endByte - startByte
        require(totalBytes > 0 && totalBytes <= MAX_RIFF_DATA_BYTES) { "This track is too large for a WAV file" }

        var remaining = totalBytes
        var currentSector = firstSector
        var first = true
        while (currentSector < lastSector && remaining > 0) {
            checkCancelled()
            val count = minOf(options.sectorsPerRead.coerceIn(1, MAX_SECTORS_PER_READ).toLong(), lastSector - currentSector).toInt()
            val data = readSectors(currentSector, count, leadOut)
            val skip = if (first) skipFirst else 0
            first = false
            val length = minOf(remaining, (count.toLong() * CD_SECTOR_BYTES - skip).coerceAtLeast(0)).toInt()
            if (length > 0) sink.write(data, skip, length)
            remaining -= length
            currentSector += count
            onProgress((1.0 - remaining.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f))
        }
        checkCancelled()
        return CdRipResult(errors)
    }

    private fun readSectors(lba: Long, count: Int, leadOut: Int): ByteArray {
        val output = ByteArray(count * CD_SECTOR_BYTES)
        val first = maxOf(lba, 0L)
        val last = minOf(lba + count, leadOut.toLong())
        if (last <= first) return output
        val actualCount = (last - first).toInt()
        val bytes = readChunk(first.toInt(), actualCount)
        System.arraycopy(bytes, 0, output, ((first - lba) * CD_SECTOR_BYTES).toInt(), actualCount * CD_SECTOR_BYTES)
        return output
    }

    private fun readChunk(lba: Int, count: Int): ByteArray {
        val last = tryRead(lba, count)
        if (last != null) return last
        if (count > 1) {
            val joined = ByteArray(count * CD_SECTOR_BYTES)
            for (index in 0 until count) {
                checkCancelled()
                val sector = readChunk(lba + index, 1)
                System.arraycopy(sector, 0, joined, index * CD_SECTOR_BYTES, CD_SECTOR_BYTES)
            }
            return joined
        }
        errors++
        throw IOException("Could not read CD sector $lba after retries")
    }

    private fun tryRead(lba: Int, count: Int): ByteArray? {
        repeat(options.retries.coerceIn(0, 5) + 1) {
            checkCancelled()
            try {
                return drive.readCd(lba, count)
            } catch (error: CdScsiException) {
                if (error.senseKey < 0) throw error
            }
        }
        return null
    }

    private companion object {
        const val CD_SECTOR_BYTES = 2352
        const val MAX_SECTORS_PER_READ = 8
        const val MAX_RIFF_DATA_BYTES = 0xFFFF_FFFFL - 64L
    }
}
