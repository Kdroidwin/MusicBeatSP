/*
 * USB CD-DA extraction core adapted from Uyatame CD Ripping Tool (UCRT).
 * Copyright (C) UCRT contributors. Licensed under GPL-3.0-or-later.
 * The original project is available at:
 * https://github.com/Uyatame-CD-Ripping-Tool-UCRT
 *
 * This fork keeps only USB Bulk-Only SCSI/MMC commands needed to read audio
 * CD tracks. Disc-writing commands and unrelated device functionality are not
 * included.
 */
package com.music.bitchord.feature.cdripping

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CdScsiException(
    val senseKey: Int,
    val asc: Int = 0,
    val ascq: Int = 0,
    message: String,
) : Exception(message)

internal data class CdTrack(val number: Int, val startLba: Int, val endLba: Int)

internal class UsbScsiDrive private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    val displayName: String,
) : AutoCloseable {

    private var nextTag = 1
    private var sectorType = 0x04

    companion object {
        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355
        private const val MAX_COMMAND_BYTES = 1_000_000
        private const val MAX_SECTORS_PER_READ = 8
        private const val CD_SECTOR_BYTES = 2352

        fun supports(device: UsbDevice): Boolean = findInterface(device) != null

        private fun findInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                    candidate.interfaceProtocol != 0x50
                ) continue
                var hasIn = false
                var hasOut = false
                for (endpointIndex in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
                }
                if (hasIn && hasOut) return candidate
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbScsiDrive {
            check(manager.hasPermission(device)) { "USB device permission is required" }
            val usbInterface = findInterface(device)
                ?: throw IllegalArgumentException("This USB drive does not support Bulk-Only SCSI")
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            val endpointIn = input ?: throw IllegalStateException("USB input endpoint is unavailable")
            val endpointOut = output ?: throw IllegalStateException("USB output endpoint is unavailable")
            val connection = manager.openDevice(device)
                ?: throw IllegalStateException("Could not open the USB drive")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IllegalStateException("Could not claim the USB drive interface")
            }
            runCatching { connection.setInterface(usbInterface) }
            val name = listOfNotNull(device.manufacturerName, device.productName)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .ifBlank { device.deviceName }
            return UsbScsiDrive(connection, usbInterface, endpointIn, endpointOut, name)
        }
    }

    @Synchronized
    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    private fun cdb(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun clearHalt(endpoint: UsbEndpoint) {
        connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, 2_000)
    }

    private fun resetRecovery() {
        runCatching { connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 2_000) }
        runCatching { clearHalt(endpointIn) }
        runCatching { clearHalt(endpointOut) }
    }

    @Synchronized
    private fun execute(command: ByteArray, inputBytes: Int, timeoutMs: Int = 30_000, requestSense: Boolean = true): ByteArray {
        require(command.size in 1..16) { "Invalid SCSI command length" }
        require(inputBytes in 0..MAX_COMMAND_BYTES) { "Invalid SCSI transfer size" }
        val tag = nextTag++
        val wrapper = ByteArray(31)
        ByteBuffer.wrap(wrapper).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(tag)
            putInt(inputBytes)
            put(if (inputBytes > 0) 0x80.toByte() else 0)
            put(0)
            put(command.size.toByte())
            put(command)
        }
        if (connection.bulkTransfer(endpointOut, wrapper, wrapper.size, timeoutMs) != wrapper.size) {
            resetRecovery()
            throw CdScsiException(-1, message = "USB command transfer failed")
        }

        val data = ByteArray(inputBytes)
        var received = 0
        var transferFailed = false
        while (received < inputBytes) {
            val requested = minOf(16_384, inputBytes - received)
            val count = connection.bulkTransfer(endpointIn, data, received, requested, timeoutMs)
            if (count < 0) {
                transferFailed = true
                runCatching { clearHalt(endpointIn) }
                break
            }
            received += count
            if (count < requested) break
        }

        val statusWrapper = ByteArray(13)
        var statusRead = connection.bulkTransfer(endpointIn, statusWrapper, statusWrapper.size, timeoutMs)
        if (statusRead != statusWrapper.size) {
            runCatching { clearHalt(endpointIn) }
            statusRead = connection.bulkTransfer(endpointIn, statusWrapper, statusWrapper.size, timeoutMs)
        }
        if (statusRead != statusWrapper.size) {
            resetRecovery()
            throw CdScsiException(-1, message = "USB drive response was incomplete")
        }

        val status = ByteBuffer.wrap(statusWrapper).order(ByteOrder.LITTLE_ENDIAN)
        if (status.int != CSW_SIGNATURE || status.int != tag) {
            resetRecovery()
            throw CdScsiException(-1, message = "USB drive returned an invalid response")
        }
        val residue = status.int
        val commandStatus = statusWrapper[12].toInt() and 0xFF
        if (residue < 0 || residue > inputBytes || commandStatus == 2) {
            resetRecovery()
            throw CdScsiException(-1, message = "USB drive reported a transfer error")
        }
        if (commandStatus != 0 || transferFailed) {
            if (!requestSense) throw CdScsiException(-1, message = "SCSI command failed")
            val sense = runCatching { execute(cdb(0x03, 0, 0, 0, 18, 0), 18, requestSense = false) }
                .getOrElse { throw CdScsiException(-1, message = "SCSI command failed") }
            if (sense.size < 14) throw CdScsiException(-1, message = "SCSI command failed")
            val key = sense[2].toInt() and 0x0F
            val asc = sense[12].toInt() and 0xFF
            val ascq = sense[13].toInt() and 0xFF
            throw CdScsiException(key, asc, ascq, "SCSI error ($key/$asc/$ascq)")
        }
        return if (received == data.size) data else data.copyOf(received)
    }

    fun testUnitReady() {
        execute(cdb(0x00, 0, 0, 0, 0, 0), 0)
    }

    /** Reads the MMC TOC and returns audio tracks with exclusive end LBAs. */
    fun readAudioToc(): Pair<List<CdTrack>, Int> {
        var msf = false
        val response = try {
            execute(cdb(0x43, 0, 0, 0, 0, 0, 1, 0x03, 0x24, 0), 804)
        } catch (error: CdScsiException) {
            if (error.senseKey < 0) throw error
            msf = true
            execute(cdb(0x43, 0x02, 0, 0, 0, 0, 1, 0x03, 0x24, 0), 804)
        }
        if (response.size < 4) throw IllegalStateException("The disc TOC is incomplete")
        val declaredLength = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        val descriptorCount = ((declaredLength - 2).coerceAtLeast(0) / 8)
            .coerceAtMost((response.size - 4) / 8)
        val entries = ArrayList<Triple<Int, Int, Int>>(descriptorCount)
        repeat(descriptorCount) { index ->
            val offset = 4 + index * 8
            val trackNumber = response[offset + 2].toInt() and 0xFF
            val control = response[offset + 1].toInt() and 0x0F
            val lba = if (msf) {
                val minutes = response[offset + 5].toInt() and 0xFF
                val seconds = response[offset + 6].toInt() and 0xFF
                val frames = response[offset + 7].toInt() and 0xFF
                ((minutes * 60 + seconds) * 75 + frames - 150)
            } else {
                ByteBuffer.wrap(response, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            entries += Triple(trackNumber, control, lba)
        }
        val leadOut = entries.firstOrNull { it.first == 0xAA }?.third
            ?: throw IllegalStateException("The disc TOC has no lead-out address")
        val data = entries.filter { it.first in 1..99 }.sortedBy { it.first }
        if (data.isEmpty()) throw IllegalStateException("No tracks were found on the disc")
        val audioTracks = data.mapIndexedNotNull { index, entry ->
            if ((entry.second and 0x04) != 0) return@mapIndexedNotNull null
            val end = data.getOrNull(index + 1)?.third ?: leadOut
            if (entry.third < 0 || end <= entry.third || end > leadOut) return@mapIndexedNotNull null
            CdTrack(entry.first, entry.third, end)
        }
        if (audioTracks.isEmpty()) throw IllegalStateException("This disc contains no audio tracks")
        return audioTracks to leadOut
    }

    fun setReadSpeed(speedX: Int) {
        val speed = if (speedX <= 0) 0xFFFF else (speedX * 176).coerceAtMost(0xFFFF)
        runCatching { execute(cdb(0xBB, 0, (speed shr 8) and 0xFF, speed and 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0), 0) }
    }

    fun readCd(lba: Int, sectors: Int): ByteArray {
        require(lba >= 0 && sectors in 1..MAX_SECTORS_PER_READ) { "Invalid CD sector range" }
        val byteCount = sectors * CD_SECTOR_BYTES
        return try {
            execute(cdb(
                0xBE, sectorType,
                (lba ushr 24) and 0xFF, (lba ushr 16) and 0xFF, (lba ushr 8) and 0xFF, lba and 0xFF,
                (sectors ushr 16) and 0xFF, (sectors ushr 8) and 0xFF, sectors and 0xFF,
                0x10, 0, 0,
            ), byteCount, timeoutMs = 60_000)
        } catch (error: CdScsiException) {
            // Some drives reject the explicit CD-DA sector type; UCRT retries
            // the same safe read using the drive's default sector type.
            if (error.senseKey == 5 && error.asc == 0x24 && sectorType != 0) {
                sectorType = 0
                execute(cdb(
                    0xBE, sectorType,
                    (lba ushr 24) and 0xFF, (lba ushr 16) and 0xFF, (lba ushr 8) and 0xFF, lba and 0xFF,
                    (sectors ushr 16) and 0xFF, (sectors ushr 8) and 0xFF, sectors and 0xFF,
                    0x10, 0, 0,
                ), byteCount, timeoutMs = 60_000)
            } else throw error
        }.also { if (it.size != byteCount) throw CdScsiException(-1, message = "CD data transfer was incomplete") }
    }
}
