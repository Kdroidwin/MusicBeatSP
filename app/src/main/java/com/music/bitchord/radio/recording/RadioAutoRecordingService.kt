package com.music.bitchord.radio.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.music.bitchord.R
import com.music.bitchord.data.Http
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import com.music.bitchord.radio.RadioPlaybackSession
import com.music.bitchord.radio.data.RadioRepository
import com.music.bitchord.radio.model.StationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/** Captures the authenticated live HLS stream, then exports it as an AAC M4A. */
class RadioAutoRecordingService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val jobs = linkedMapOf<String, Job>()
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var destroying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        if (intent?.action == ACTION_CANCEL) {
            val ids = jobs.keys.toList()
            ids.forEach { RadioAutoRecordingScheduler.cancel(this, it) }
            jobs.values.toList().forEach(Job::cancel)
            jobs.clear()
            releaseCpu()
            postNotification(getString(R.string.radio_recording_cancelled), ongoing = false)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val ids = intent?.getStringExtra(RadioAutoRecordingScheduler.EXTRA_RECORDING_ID)
            ?.let(::listOf)
            ?: RadioAutoRecordingStore.activeIds(this).toList()
        ids.forEach(::startRecording)
        if (jobs.isEmpty()) {
            releaseCpu()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        keepCpuAwake()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        jobs.keys.toList().forEach { RadioAutoRecordingScheduler.cancel(this, it) }
        jobs.values.toList().forEach(Job::cancel)
        jobs.clear()
        releaseCpu()
        postNotification(getString(R.string.radio_recording_background_limit), ongoing = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun startRecording(id: String) {
        if (jobs.containsKey(id)) return
        val recording = RadioAutoRecordingStore.recording(this, id) ?: return
        RadioAutoRecordingStore.setActive(this, id, true)
        postNotification(getString(R.string.radio_recording_in_progress, recording.title), ongoing = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                record(recording)
                postNotification(getString(R.string.radio_recording_saved, recording.title), ongoing = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                postNotification(getString(R.string.radio_recording_failed, recording.title), ongoing = false)
            } finally {
                jobs.remove(id)
                if (!destroying) {
                    RadioAutoRecordingStore.setActive(this@RadioAutoRecordingService, id, false)
                    RadioAutoRecordingStore.remove(this@RadioAutoRecordingService, id)
                    if (jobs.isEmpty()) {
                        releaseCpu()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    } else {
                        val next = jobs.keys.firstOrNull()?.let { RadioAutoRecordingStore.recording(this@RadioAutoRecordingService, it) }
                        if (next != null) postNotification(getString(R.string.radio_recording_in_progress, next.title), ongoing = true)
                    }
                }
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun record(recording: RadioAutoRecording) {
        val now = System.currentTimeMillis()
        if (recording.endTimeMs <= now) throw IOException(getString(R.string.radio_recording_time_passed))
        if (recording.startTimeMs > now) delay(recording.startTimeMs - now)

        val folderUri = RadioAutoRecordingStore.folderUri(this)
            ?: throw IOException(getString(R.string.radio_recording_folder_missing))
        val station = StationRegistry.getStation(recording.stationId)
            ?: throw IOException(getString(R.string.radio_recording_station_missing))
        val root = File(filesDir, "radio-auto-recording/${recording.id.hashCode().toUInt().toString(16)}")
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not prepare recording storage")
        try {
            val capture = captureStream(recording, station, root)
            val output = File(root, "recording.m4a")
            val durationMs = capture.totalDurationMs
            val clipStart = capture.firstProgramDateTimeMs?.let { (recording.startTimeMs - it).coerceAtLeast(0L) } ?: 0L
            val clipEnd = capture.firstProgramDateTimeMs?.let { (recording.endTimeMs - it).coerceIn(0L, durationMs) }
                ?: minOf(durationMs, recording.endTimeMs - recording.startTimeMs)
            if (clipEnd - clipStart < 1_000L) throw IOException("No radio audio was captured")
            exportM4a(capture.playlist, output, clipStart, clipEnd)
            embedProgramArtwork(recording, output)
            saveToFolder(folderUri, recording, output)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { root.deleteRecursively() }
        }
    }

    private data class ParsedSegment(
        val uri: String,
        val durationSeconds: Double,
        val tags: List<String>,
        val programDateTimeMs: Long?,
        val mapUri: String?,
    )

    private data class CapturedSegment(
        val sourceUrl: String,
        val localName: String,
        val durationSeconds: Double,
        val tags: List<String>,
        val programDateTimeMs: Long?,
        val mapLocalName: String?,
    )

    private data class CaptureResult(
        val playlist: File,
        val firstProgramDateTimeMs: Long?,
        val totalDurationMs: Long,
    )

    private suspend fun captureStream(
        recording: RadioAutoRecording,
        station: com.music.bitchord.radio.model.Station,
        root: File,
    ): CaptureResult = withContext(Dispatchers.IO) {
        val stateFile = File(root, "capture-state.json")
        val captured = readCaptured(stateFile).toMutableList()
        val maps = readMaps(stateFile).toMutableMap()
        val seen = captured.mapTo(mutableSetOf(), CapturedSegment::sourceUrl)
        var stream = RadioRepository.prepareLiveStream(station, recording.areaId, updatePlaybackSession = false)
        var authenticatedAt = System.currentTimeMillis()
        val captureUntilWithoutPdt = recording.endTimeMs + END_TAIL_MS
        var reachedEndWithPdt = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val currentTime = System.currentTimeMillis()
            if (reachedEndWithPdt || currentTime >= captureUntilWithoutPdt) break
            if (currentTime - authenticatedAt >= AUTH_REFRESH_MS) {
                stream = RadioRepository.prepareLiveStream(station, recording.areaId, updatePlaybackSession = false)
                authenticatedAt = currentTime
            }
            try {
                val mediaPlaylistUrl = resolveMediaPlaylist(stream.streamUrl, stream.authHeaders)
                val playlist = getText(mediaPlaylistUrl, stream.authHeaders)
                val playlistReceivedAt = System.currentTimeMillis()
                if (playlist.contains("#EXT-X-KEY:") && Regex("#EXT-X-KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(playlist)) {
                    throw IOException("Encrypted radio streams cannot be written to M4A")
                }
                val segments = parseSegments(playlist, mediaPlaylistUrl)
                val latestProgramTime = segments.lastOrNull()?.programDateTimeMs
                val hasProgramDateTimes = segments.any { it.programDateTimeMs != null }
                val segmentsToCapture = if (hasProgramDateTimes) {
                    segments
                } else {
                    val lastKnownIndex = segments.indexOfLast { it.uri in seen }
                    if (lastKnownIndex >= 0) {
                        segments.drop(lastKnownIndex + 1)
                    } else {
                        segments.dropLast(1).forEach { seen += it.uri }
                        segments.takeLast(1)
                    }
                }
                for (segment in segmentsToCapture) {
                    val segmentEndMs = segment.programDateTimeMs?.plus((segment.durationSeconds * 1000).toLong())
                    if (segment.programDateTimeMs != null && segmentEndMs != null && segmentEndMs <= recording.startTimeMs) continue
                    if (segment.programDateTimeMs != null && segment.programDateTimeMs >= recording.endTimeMs) continue
                    if (segment.uri in seen) continue
                    val mapLocal = segment.mapUri?.let { mapUrl ->
                        maps[mapUrl] ?: run {
                            val name = "init-${maps.size.toString().padStart(3, '0')}.mp4"
                            download(mapUrl, stream.authHeaders, File(root, name))
                            maps[mapUrl] = name
                            name
                        }
                    }
                    val name = "segment-${captured.size.toString().padStart(5, '0')}.bin"
                    download(segment.uri, stream.authHeaders, File(root, name))
                    captured += CapturedSegment(
                        sourceUrl = segment.uri,
                        localName = name,
                        durationSeconds = segment.durationSeconds,
                        tags = segment.tags,
                        programDateTimeMs = segment.programDateTimeMs ?: if (!hasProgramDateTimes && captured.isEmpty()) {
                            playlistReceivedAt - (segment.durationSeconds * 1000).toLong()
                        } else {
                            null
                        },
                        mapLocalName = mapLocal,
                    )
                    seen += segment.uri
                    writeState(stateFile, captured, maps)
                }
                if (latestProgramTime != null && latestProgramTime >= recording.endTimeMs) {
                    reachedEndWithPdt = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // A brief connection loss is retried; the recording remains alive through the scheduled end.
            }
            if (!reachedEndWithPdt && System.currentTimeMillis() < captureUntilWithoutPdt) delay(2_000L)
        }

        if (captured.isEmpty()) throw IOException("No radio HLS segments were received")
        val playlistFile = File(root, "recording.m3u8")
        val maxDuration = captured.maxOf { it.durationSeconds }
        val output = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:${ceil(maxDuration).toInt().coerceAtLeast(1)}")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            var previousMap: String? = null
            captured.forEach { item ->
                if (item.mapLocalName != previousMap && item.mapLocalName != null) {
                    appendLine("#EXT-X-MAP:URI=\"${item.mapLocalName}\"")
                    previousMap = item.mapLocalName
                }
                item.tags.forEach(::appendLine)
                appendLine("#EXTINF:${String.format(Locale.US, "%.3f", item.durationSeconds)},")
                appendLine(item.localName)
            }
            appendLine("#EXT-X-ENDLIST")
        }
        playlistFile.writeText(output)
        CaptureResult(
            playlist = playlistFile,
            firstProgramDateTimeMs = captured.firstOrNull()?.programDateTimeMs,
            totalDurationMs = captured.sumOf { (it.durationSeconds * 1000.0).toLong() },
        )
    }

    private fun resolveMediaPlaylist(url: String, headers: Map<String, String>): String {
        val text = getTextBlocking(url, headers)
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        val variant = text.lineSequence().map(String::trim).windowed(2).firstOrNull { pair ->
            pair[0].startsWith("#EXT-X-STREAM-INF") && pair[1].isNotEmpty() && !pair[1].startsWith('#')
        }?.lastOrNull() ?: throw IOException("Radio master playlist has no stream")
        return URI(url).resolve(variant).toString()
    }

    private fun parseSegments(text: String, baseUrl: String): List<ParsedSegment> {
        var duration: Double? = null
        var dateTime: Long? = null
        var nextDateTime: Long? = null
        var mapUri: String? = null
        val pendingTags = mutableListOf<String>()
        val result = mutableListOf<ParsedSegment>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val ref = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (ref != null) mapUri = URI(baseUrl).resolve(ref).toString()
                }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                    val value = line.substringAfter(':').trim()
                    dateTime = parseDateTime(value)
                    nextDateTime = dateTime
                    pendingTags += line
                }
                line == "#EXT-X-DISCONTINUITY" -> pendingTags += line
                line.startsWith("#") || line.isEmpty() -> Unit
                duration != null -> {
                    val length = duration
                    val resolved = URI(baseUrl).resolve(line).toString()
                    val startAt = dateTime ?: nextDateTime
                    result += ParsedSegment(resolved, length, pendingTags.toList(), startAt, mapUri)
                    nextDateTime = startAt?.plus((length * 1000).toLong())
                    duration = null
                    dateTime = null
                    pendingTags.clear()
                }
            }
        }
        return result
    }

    private fun parseDateTime(value: String): Long? = runCatching {
        runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
    }.getOrNull()

    private fun getText(url: String, headers: Map<String, String>): String = getTextBlocking(url, headers)

    private fun getTextBlocking(url: String, headers: Map<String, String>): String {
        val request = radioRequest(url, headers)
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Radio playlist returned HTTP ${response.code}")
            response.body.string().takeIf(String::isNotBlank) ?: throw IOException("Radio playlist was empty")
        }
    }

    private fun download(url: String, headers: Map<String, String>, destination: File) {
        val request = radioRequest(url, headers)
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Radio segment returned HTTP ${response.code}")
            val body = response.body
            destination.outputStream().use { output -> body.byteStream().use { it.copyTo(output, COPY_BUFFER_SIZE) } }
        }
    }

    private fun radioRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        if (RadioPlaybackSession.isRadikoUri(Uri.parse(url))) {
            headers.forEach { (key, value) -> builder.header(key, value) }
        }
        return builder.get().build()
    }

    private fun readCaptured(stateFile: File): List<CapturedSegment> = runCatching {
        val rows = JSONArray(JSONObject(stateFile.readText()).optString("segments", "[]"))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val tags = row.optJSONArray("tags") ?: JSONArray()
            CapturedSegment(
                sourceUrl = row.getString("sourceUrl"),
                localName = row.getString("localName"),
                durationSeconds = row.getDouble("durationSeconds"),
                tags = List(tags.length(), tags::getString),
                programDateTimeMs = row.takeIf { it.has("programDateTimeMs") }?.getLong("programDateTimeMs"),
                mapLocalName = row.optString("mapLocalName").takeIf(String::isNotBlank),
            )
        }
    }.getOrDefault(emptyList())

    private fun readMaps(stateFile: File): Map<String, String> = runCatching {
        val maps = JSONObject(stateFile.readText()).optJSONObject("maps") ?: JSONObject()
        maps.keys().asSequence().associateWith { maps.getString(it) }
    }.getOrDefault(emptyMap())

    private fun writeState(stateFile: File, segments: List<CapturedSegment>, maps: Map<String, String>) {
        val segmentRows = JSONArray()
        segments.forEach { segment ->
            val tags = JSONArray().apply { segment.tags.forEach(::put) }
            segmentRows.put(
                JSONObject()
                    .put("sourceUrl", segment.sourceUrl)
                    .put("localName", segment.localName)
                    .put("durationSeconds", segment.durationSeconds)
                    .put("tags", tags)
                    .put("programDateTimeMs", segment.programDateTimeMs)
                    .put("mapLocalName", segment.mapLocalName),
            )
        }
        val mapRows = JSONObject().apply { maps.forEach { (source, local) -> put(source, local) } }
        val temporary = File(stateFile.parentFile, "capture-state.tmp")
        temporary.writeText(JSONObject().put("segments", segmentRows).put("maps", mapRows).toString())
        temporary.renameTo(stateFile)
    }

    private suspend fun exportM4a(source: File, output: File, startMs: Long, endMs: Long) =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(source))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setClippingConfiguration(clipping)
                    .build()
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }
                val transformer = Transformer.Builder(this@RadioAutoRecordingService)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(this@RadioAutoRecordingService)
                            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(AAC_BITRATE_BPS).build())
                            .build(),
                    )
                    .addListener(listener)
                    .build()
                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                try {
                    transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), output.absolutePath)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }

    /** Artwork is best-effort; a missing image must never discard a completed recording. */
    private suspend fun embedProgramArtwork(recording: RadioAutoRecording, output: File) = withContext(Dispatchers.IO) {
        val artworkUrl = recording.artworkUrl?.takeIf(String::isNotBlank) ?: return@withContext
        val artworkFile = File(output.parentFile, "program-artwork.tmp")
        try {
            val request = Request.Builder().url(artworkUrl).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Program artwork returned HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > MAX_PROGRAM_ARTWORK_BYTES) throw IOException("Program artwork was too large")
                body.byteStream().use { input ->
                    artworkFile.outputStream().use { sink ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_PROGRAM_ARTWORK_BYTES) throw IOException("Program artwork was too large")
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            }
            val prepared = TagLibWriter.prepareArtwork(this@RadioAutoRecordingService, Uri.fromFile(artworkFile))
                ?: return@withContext
            TagLibWriter.writeArtworkFile(output, prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Audio export and SAF save continue with the already complete M4A.
        } finally {
            artworkFile.delete()
        }
    }

    private suspend fun saveToFolder(folderUri: Uri, recording: RadioAutoRecording, source: File) = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(this@RadioAutoRecordingService, folderUri)
            ?: throw IOException(getString(R.string.radio_recording_folder_missing))
        if (!folder.canWrite()) throw IOException(getString(R.string.radio_recording_folder_unavailable))
        val stamp = recording.startAt.let { "${it.take(8)}_${it.substring(8, 10)}${it.substring(10, 12)}" }
        val baseName = "${stamp}_${recording.title.fileSafe().take(64)}"
        var filename = "$baseName.m4a"
        var suffix = 2
        while (folder.findFile(filename)?.exists() == true) filename = "${baseName}_${suffix++}.m4a"
        val destination = folder.createFile("audio/mp4", filename)
            ?: throw IOException(getString(R.string.radio_recording_folder_unavailable))
        val output = contentResolver.openOutputStream(destination.uri, "w")
            ?: throw IOException(getString(R.string.radio_recording_folder_unavailable))
        output.use { sink -> source.inputStream().use { it.copyTo(sink, COPY_BUFFER_SIZE) } }
        if (destination.length() <= 0L) throw IOException("M4A output was empty")
    }

    private fun String.fileSafe(): String = replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().trimEnd('.')

    private fun promoteForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.radio_recording_starting), ongoing = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun postNotification(message: String, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(message, ongoing))
    }

    private fun buildNotification(message: String, ongoing: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setContentTitle(getString(R.string.radio_recording_notification_title))
        .setContentText(message)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setSilent(true)
        .apply {
            if (ongoing) {
                val cancel = PendingIntent.getService(
                    this@RadioAutoRecordingService,
                    90317,
                    Intent(this@RadioAutoRecordingService, RadioAutoRecordingService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                addAction(0, getString(R.string.cancel), cancel)
            }
        }
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.radio_recording_notification_title), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        destroying = true
        releaseCpu()
        scope.cancel()
        super.onDestroy()
    }

    private fun keepCpuAwake() {
        val current = recordingWakeLock
        if (current?.isHeld == true) return
        recordingWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitChord:RadioAutoRecording")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseCpu() {
        recordingWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        recordingWakeLock = null
    }

    private companion object {
        const val CHANNEL_ID = "radio_auto_recordings"
        const val NOTIFICATION_ID = 0x8176
        const val ACTION_CANCEL = "com.music.bitchord.radio.CANCEL_RECORDING"
        const val END_TAIL_MS = 45_000L
        const val AUTH_REFRESH_MS = 55L * 60L * 1000L
        const val COPY_BUFFER_SIZE = 128 * 1024
        const val MAX_PROGRAM_ARTWORK_BYTES = 24L * 1024L * 1024L
        const val AAC_BITRATE_BPS = 192_000
    }
}
