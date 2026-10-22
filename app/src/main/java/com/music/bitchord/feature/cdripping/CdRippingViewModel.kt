package com.music.bitchord.feature.cdripping

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.music.bitchord.data.settings.AppSettings
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal class CdRippingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${app.packageName}.action.CD_RIP_USB_PERMISSION"

    var devices by androidx.compose.runtime.mutableStateOf<List<UsbDevice>>(emptyList())
        private set
    var tracks by androidx.compose.runtime.mutableStateOf<List<CdTrack>>(emptyList())
        private set
    val selectedTrackNumbers = androidx.compose.runtime.mutableStateMapOf<Int, Boolean>()
    var driveName by androidx.compose.runtime.mutableStateOf("")
        private set
    var isScanning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isRipping by androidx.compose.runtime.mutableStateOf(false)
        private set
    var statusMessage by androidx.compose.runtime.mutableStateOf("")
        private set
    var progress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var currentTrackNumber by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var trackCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var completedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var skippedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var failedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var albumTitle by androidx.compose.runtime.mutableStateOf("")
    var albumArtist by androidx.compose.runtime.mutableStateOf("")
    var outputFolderName by androidx.compose.runtime.mutableStateOf("")
        private set

    private var drive: UsbScsiDrive? = null
    private var attachedUsbDevice: UsbDevice? = null
    private var attachedDeviceName: String? = null
    private var leadOutLba = 0
    private var job: Job? = null
    private var scanJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.usbDeviceExtra() ?: return
                    if (granted) connectTo(device) else statusMessage = "USB access was not granted"
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (device.deviceName == attachedDeviceName) {
                        closeDrive()
                        statusMessage = "CD drive disconnected"
                    }
                    refreshDevices()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        AppSettings.cdRippingOutputTreeUri.value?.let(::updateOutputFolderName)
        refreshDevices()
    }

    fun refreshDevices() {
        if (isScanning || isRipping) return
        scanJob = viewModelScope.launch {
            isScanning = true
            try {
                devices = withContext(Dispatchers.IO) {
                    usbManager.deviceList.values.filter(UsbScsiDrive::supports)
                }
                if (drive == null) {
                    statusMessage = if (devices.isEmpty()) {
                        "No compatible USB optical drive found"
                    } else {
                        "Select the connected CD drive"
                    }
                }
            } catch (error: Exception) {
                statusMessage = error.message ?: "Could not scan USB devices"
            } finally {
                isScanning = false
            }
        }
    }

    fun selectDevice(device: UsbDevice) {
        if (isScanning || isRipping) return
        if (usbManager.hasPermission(device)) {
            connectTo(device)
            return
        }
        statusMessage = "Waiting for USB permission"
        val intent = Intent(permissionAction).setPackage(app.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(app, device.deviceId, intent, flags)
        runCatching { usbManager.requestPermission(device, permissionIntent) }
            .onFailure { statusMessage = it.message ?: "Could not request USB access" }
    }

    private fun connectTo(device: UsbDevice) {
        if (isScanning || isRipping) return
        scanJob = viewModelScope.launch {
            isScanning = true
            closeDrive()
            try {
                val result = withContext(Dispatchers.IO) {
                    val opened = UsbScsiDrive.open(usbManager, device)
                    try {
                        coroutineContext.ensureActive()
                        opened.testUnitReady()
                        val (audioTracks, leadOut) = opened.readAudioToc()
                        coroutineContext.ensureActive()
                        Triple(opened, audioTracks to leadOut, opened.displayName)
                    } catch (error: Throwable) {
                        opened.close()
                        throw error
                    }
                }
                drive = result.first
                attachedUsbDevice = device
                attachedDeviceName = device.deviceName
                tracks = result.second.first
                leadOutLba = result.second.second
                selectedTrackNumbers.clear()
                tracks.forEach { selectedTrackNumbers[it.number] = true }
                driveName = result.third
                statusMessage = "${tracks.size} audio tracks found"
                progress = 0f
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                statusMessage = error.message ?: "Could not read this disc"
                tracks = emptyList()
            } finally {
                isScanning = false
            }
        }
    }

    fun setOutputTree(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                app.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main) {
                    statusMessage = app.getString(com.music.bitchord.R.string.cd_ripping_folder_permission_failed)
                }
                return@launch
            }
            val folder = DocumentFile.fromTreeUri(app, uri)
            if (folder?.isDirectory != true || !folder.canWrite()) {
                runCatching { app.contentResolver.releasePersistableUriPermission(uri, flags) }
                withContext(Dispatchers.Main) { statusMessage = "This folder cannot be written to" }
                return@launch
            }
            val previous = AppSettings.cdRippingOutputTreeUri.value
            AppSettings.setCdRippingOutputTreeUri(uri.toString())
            previous?.takeIf { it != uri.toString() }?.let { oldUri ->
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(Uri.parse(oldUri), flags)
                }
            }
            withContext(Dispatchers.Main) {
                outputFolderName = folder.name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
                statusMessage = "Output folder selected"
            }
        }
    }

    private fun updateOutputFolderName(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching { DocumentFile.fromTreeUri(app, Uri.parse(uriString))?.name }.getOrNull().orEmpty()
            withContext(Dispatchers.Main) { outputFolderName = name }
        }
    }

    fun toggleTrack(trackNumber: Int, selected: Boolean) {
        selectedTrackNumbers[trackNumber] = selected
    }

    fun selectAllTracks(selected: Boolean) {
        tracks.forEach { selectedTrackNumbers[it.number] = selected }
    }

    fun startRip() {
        val currentDrive = drive ?: run { statusMessage = "Connect a CD drive first"; return }
        val uriString = AppSettings.cdRippingOutputTreeUri.value
            ?: run { statusMessage = "Choose an output folder first"; return }
        val chosenTracks = tracks.filter { selectedTrackNumbers[it.number] == true }
        if (chosenTracks.isEmpty()) {
            statusMessage = "Select at least one audio track"
            return
        }
        if (isRipping) return
        job = viewModelScope.launch {
            isRipping = true
            progress = 0f
            currentTrackNumber = 0
            trackCount = chosenTracks.size
            completedCount = 0
            skippedCount = 0
            failedCount = 0
            statusMessage = "Preparing CD rip"
            val retryCount = AppSettings.cdRippingRetries.value.coerceIn(0, 5)
            val speedX = AppSettings.cdRippingSpeedX.value.coerceIn(1, 12)
            try {
                withContext(Dispatchers.IO) {
                    val tree = DocumentFile.fromTreeUri(app, Uri.parse(uriString))
                        ?.takeIf { it.isDirectory && it.canWrite() }
                        ?: throw IOException("The output folder is no longer available")
                    chosenTracks.forEachIndexed { index, track ->
                        coroutineContext.ensureActive()
                        if (usbManager.deviceList[attachedDeviceName] == null) {
                            throw IOException("The CD drive was disconnected")
                        }
                        currentTrackNumber = index + 1
                        val title = "Track %02d".format(track.number)
                        val requestedName = "%02d %s.wav".format(track.number, sanitizeFilename(title))
                        if (tree.findFile(requestedName) != null) {
                            skippedCount++
                            return@forEachIndexed
                        }
                        val temporaryName = ".${requestedName}.${UUID.randomUUID()}.part"
                        val partial = tree.createFile("application/octet-stream", temporaryName)
                            ?: throw IOException("Could not create a temporary output file")
                        var finalized = false
                        try {
                            val output = app.contentResolver.openOutputStream(partial.uri, "wt")
                                ?: throw IOException("Could not open the output file")
                            output.buffered(64 * 1024).use { stream ->
                                val pcmBytes = (track.endLba - track.startLba).toLong() * CD_SECTOR_BYTES
                                val tags = mapOf(
                                    "TITLE" to title,
                                    "ARTIST" to albumArtist.trim().ifBlank { "Unknown Artist" },
                                    "ALBUM" to albumTitle.trim().ifBlank { "Audio CD" },
                                    "TRACKNUMBER" to track.number.toString(),
                                )
                                val sink = WavSink(stream, pcmBytes, tags)
                                CdRipper(currentDrive, CdRipOptions(speedX, 4, retryCount)).rip(
                                    track = track,
                                    leadOut = leadOutLba,
                                    sink = sink,
                                    onProgress = { value ->
                                        progress = ((index + value) / chosenTracks.size).coerceIn(0f, 1f)
                                    },
                                    checkCancelled = { coroutineContext.ensureActive() },
                                )
                                sink.finish()
                            }
                            moveCompletedFile(tree, partial, requestedName)
                            finalized = true
                            completedCount++
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failedCount++
                            runCatching { partial.delete() }
                            statusMessage = error.message ?: "A track could not be read"
                            // A lost permission/device should stop the operation;
                            // a damaged sector only fails its current track.
                            val attached = attachedUsbDevice
                            if (attached == null || !usbManager.hasPermission(attached)) {
                                throw IOException("USB access to the CD drive was lost", error)
                            }
                        } finally {
                            if (!finalized) runCatching { partial.delete() }
                        }
                    }
                }
                progress = 1f
                statusMessage = "CD rip finished: $completedCount added, $skippedCount skipped, $failedCount failed"
            } catch (error: CancellationException) {
                statusMessage = "CD rip cancelled"
                throw error
            } catch (error: Exception) {
                statusMessage = error.message ?: "CD rip failed"
            } finally {
                isRipping = false
            }
        }
    }

    private fun moveCompletedFile(tree: DocumentFile, partial: DocumentFile, requestedName: String) {
        var finalName = requestedName
        var suffix = 2
        while (tree.findFile(finalName) != null) {
            val stem = requestedName.removeSuffix(".wav")
            finalName = "$stem ($suffix).wav"
            suffix++
        }
        if (partial.renameTo(finalName)) return

        // Some SAF providers cannot rename. Copy only the fully written temp
        // file; remove the visible destination again if the copy fails.
        val destination = tree.createFile("audio/wav", finalName)
            ?: throw IOException("The SAF provider cannot finalize the ripped file")
        try {
            val input = app.contentResolver.openInputStream(partial.uri)
                ?: throw IOException("Could not read the completed temporary file")
            val output = app.contentResolver.openOutputStream(destination.uri, "wt")
                ?: throw IOException("Could not write the completed track")
            input.use { source ->
                output.buffered(64 * 1024).use { destinationStream ->
                    source.copyTo(destinationStream, 64 * 1024)
                }
            }
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        } finally {
            runCatching { partial.delete() }
        }
    }

    private fun sanitizeFilename(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]"), "_")
        .trim()
        .take(96)
        .ifBlank { "Track" }

    fun cancelRip() {
        job?.cancel()
    }

    fun releaseDrive() {
        job?.cancel()
        scanJob?.cancel()
        isScanning = false
        isRipping = false
        closeDrive()
    }

    private fun closeDrive() {
        val oldDrive = drive
        drive = null
        attachedUsbDevice = null
        attachedDeviceName = null
        tracks = emptyList()
        driveName = ""
        selectedTrackNumbers.clear()
        if (oldDrive != null) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { oldDrive.close() }
        }
    }

    override fun onCleared() {
        runCatching { app.unregisterReceiver(receiver) }
        job?.cancel()
        scanJob?.cancel()
        val oldDrive = drive
        drive = null
        attachedUsbDevice = null
        if (oldDrive != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { oldDrive.close() }
        super.onCleared()
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val CD_SECTOR_BYTES = 2352L
    }
}
