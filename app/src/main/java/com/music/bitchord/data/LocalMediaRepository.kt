package com.music.bitchord.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.music.bitchord.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.feature.localmusic.domain.model.LocalFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "BitChord"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    private val localMusicExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "webm",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/ui/",
        "/call_recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /**
     * Checks if the app has All Files Access (MANAGE_EXTERNAL_STORAGE).
     * On Android 11+ (API 30+), this allows tag editing and lyrics embedding without system prompts.
     * On Android 10 and below, checks READ_EXTERNAL_STORAGE.
     */
    fun hasAllFilesPermission(context: Context? = null): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (context != null) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Returns an Intent to open system settings for All Files Access.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun createAllFilesAccessIntent(context: Context): Intent {
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        return if (appIntent.resolveActivity(context.packageManager) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /**
     * Opens system settings for the user to grant All Files Access to BitChord.
     */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        if (hasAllFilesPermission(context)) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/BitChord` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                    add(MediaStore.Audio.Media.ALBUM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("album_artist")
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.DATE_MODIFIED)
                }.toTypedArray()
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumArtistCol = cursor.getColumnIndex("album_artist")
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val dateModified = cursor.getLong(dateModifiedCol)
                        val trackArtworkUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            .buildUpon()
                            .apply {
                                if (albumId > 0) appendQueryParameter("albumId", albumId.toString())
                                if (dateModified > 0) appendQueryParameter("t", dateModified.toString())
                            }
                            .build()
                            .toString()
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            albumArtist = albumArtistCol.takeIf { it >= 0 }
                                ?.let(cursor::getString)
                                .cleanTag(),
                            artworkUrl = trackArtworkUri,
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = dateModified,
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            if (uriStr !in knownUris) {
                                val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                extraSongs.add(
                                    buildSongFromUri(
                                        context,
                                        uriStr,
                                        file.name,
                                        ScannedTags(null, null, modified, modified),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/BitChord directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                albumArtist = song.albumArtist ?: tags.albumArtist,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
        val albumArtist: String? = null,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/BitChord alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            // MediaStore exposed ALBUM_ARTIST on newer Android releases. Do
            // not request the column on older versions: one unsupported
            // projection would otherwise make the whole local scan fail.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("album_artist")
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
        }.toTypedArray()

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val selection = if (filterNonMusic) {
            buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
                append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                }
            }
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val selectionArgs = if (filterNonMusic) {
            arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        // Query each mounted volume separately on Android 10+. The aggregate
        // EXTERNAL_CONTENT_URI may report VOLUME_NAME="external" and omit DATA
        // on scoped-storage devices. In that case the song still appears in
        // the library, but has no localFolderPath, so the Folder tab has no
        // directory names to build. A per-volume URI supplies the real volume
        // name without requesting broader storage access.
        val volumeTargets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                MediaStore.getExternalVolumeNames(context)
                    .filter { it.isNotBlank() && it != MediaStore.VOLUME_EXTERNAL }
                    .map { volumeName -> volumeName to MediaStore.Audio.Media.getContentUri(volumeName) }
            }.getOrDefault(emptyList())
        } else emptyList()
        val queryTargets = volumeTargets.ifEmpty {
            listOf(null to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        fun queryVolume(knownVolumeName: String?, collectionUri: Uri): Boolean {
            var cursorReturned = false
            runCatching {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                )?.use { cursor ->
                    cursorReturned = true
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumArtistCol = cursor.getColumnIndex("album_artist")
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val volumeNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val displayName = cursor.getString(displayNameCol).orEmpty()
                        val rawTitle = cursor.getString(titleCol)
                        val rawArtist = cursor.getString(artistCol)
                        val rawAlbum = cursor.getString(albumCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val durationMs = cursor.getLong(durationCol)
                        val path = dataCol.takeIf { it >= 0 }?.let(cursor::getString)
                        if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue
                        val relativePath = relativePathCol.takeIf { it >= 0 }?.let(cursor::getString)
                        val rowVolumeName = volumeNameCol.takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            ?.takeIf(String::isNotBlank)
                        val volumeName = when {
                            rowVolumeName == null || rowVolumeName.equals(MediaStore.VOLUME_EXTERNAL, ignoreCase = true) ->
                                knownVolumeName ?: rowVolumeName
                            else -> rowVolumeName
                        }
                        // Prefer the MediaStore-relative location on scoped-storage
                        // Android. DATA can be null, inaccessible, or inconsistent
                        // between the aggregate and per-volume MediaStore views;
                        // using it first made folder rows and their songs disagree.
                        val folderPath = resolveMediaStoreFolderPath(context, relativePath, volumeName)
                            ?: path?.let(::folderForMediaPath)
                        if (AppSettings.isFolderBlacklisted(folderPath)) continue

                        val contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
                        val aggregateContentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ).toString()
                        val title = rawTitle.cleanTag()?.removeAudioExtension()
                            ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                            ?: "Track $id"
                        val artist = rawArtist.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown Artist"
                        val albumArtist = albumArtistCol.takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            .cleanTag()
                        val albumName = rawAlbum.takeUnless { it.isNullOrBlank() || it == "<unknown>" }
                        val dateModified = cursor.getLong(dateModifiedCol)
                        val artworkUrl = ContentUris.withAppendedId(collectionUri, id)
                            .buildUpon()
                            .apply {
                                if (albumId > 0) appendQueryParameter("albumId", albumId.toString())
                                if (dateModified > 0) appendQueryParameter("t", dateModified.toString())
                            }
                            .build()
                            .toString()
                        val durationText = formatDuration(durationMs)
                        val savedVideoId = videoIdByUri[contentUri]
                            ?: if (volumeName.equals("external_primary", ignoreCase = true) || knownVolumeName == null) {
                                videoIdByUri[aggregateContentUri]
                            } else {
                                null
                            }

                        songs.add(
                            Song(
                                videoId = savedVideoId ?: contentUri,
                                title = title,
                                artist = artist,
                                thumbnailUrl = artworkUrl,
                                durationText = durationText,
                                albumName = albumName,
                                albumArtist = albumArtist,
                                trackNumber = cursor.getInt(trackCol).takeIf { it > 0 },
                                localUri = contentUri,
                                localPath = path,
                                localFolderPath = folderPath,
                                localDateAddedSeconds = cursor.getLong(dateAddedCol),
                                localDateModifiedSeconds = cursor.getLong(dateModifiedCol),
                            )
                        )
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed scanning device local music from $knownVolumeName: ${it.message}")
            }
            return cursorReturned
        }

        var anyVolumeQueryReturned = false
        queryTargets.forEach { (volumeName, uri) ->
            if (queryVolume(volumeName, uri)) anyVolumeQueryReturned = true
        }
        // Some vendor MediaStore providers do not implement per-volume query
        // URIs. Fall back to the long-standing aggregate query only when none
        // of the individual queries returned a cursor, avoiding duplicate rows
        // on providers that support per-volume access.
        if (!anyVolumeQueryReturned && queryTargets.size > 1) {
            queryVolume(null, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        songs
    }

    /**
     * DATA is still available on most devices, but Android may omit it for a
     * MediaStore row. RELATIVE_PATH and VOLUME_NAME are enough to build a path
     * for folder display without pretending it is a playable raw-file path.
     */
    private fun resolveMediaStoreFolderPath(context: Context, relativePath: String?, volumeName: String?): String? {
        val relative = relativePath?.trim('/')?.takeIf(String::isNotBlank) ?: return null
        val storageRoot = when {
            volumeName.equals("external_primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            !volumeName.isNullOrBlank() && !volumeName.equals("external", ignoreCase = true) ->
                resolveExternalVolumeRoot(context, volumeName)
            else -> return null
        } ?: return null
        return normalizeFolderPath(File(storageRoot, relative).path)
    }

    private fun resolveExternalVolumeRoot(context: Context, volumeName: String): String? {
        val volumeId = volumeName.trim('/')
        if (!SAFE_EXTERNAL_VOLUME_ID.matches(volumeId)) return null

        // getExternalFilesDirs is available on the app's minimum API and gives
        // the mounted root for removable volumes without requesting broad
        // storage access. Fall back to Android's standard mount point only if
        // the volume is temporarily absent from that list.
        val mountedRoot = runCatching {
            context.getExternalFilesDirs(null)
                .asSequence()
                .filterNotNull()
                .mapNotNull { directory ->
                    val path = normalizeFolderPath(directory.absolutePath) ?: return@mapNotNull null
                    val marker = "/Android/data/"
                    val markerIndex = path.indexOf(marker, ignoreCase = true)
                    if (markerIndex <= 0) return@mapNotNull null
                    path.substring(0, markerIndex).takeIf {
                        it.substringAfterLast('/').equals(volumeId, ignoreCase = true)
                    }
                }
                .firstOrNull()
        }.getOrNull()
        return mountedRoot ?: "/storage/$volumeId"
    }

    private fun folderForMediaPath(path: String): String? {
        val normalized = normalizeFolderPath(path)?.takeIf { it.startsWith('/') } ?: return null
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return null
        return normalized.substring(0, lastSlash).ifBlank { "/" }
    }

    private fun normalizeFolderPath(path: String): String? {
        val normalized = path.replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/')
        return normalized.takeIf { it.isNotBlank() && it.startsWith('/') }
    }

    /**
     * Discovers all physical storage folders containing playable music,
     * along with their track counts and blacklist status.
     */
    suspend fun getAllDiscoveredFolders(context: Context): List<LocalFolder> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()
        val counts = mutableMapOf<String, Int>()
        val folderDisplayNames = mutableMapOf<String, String>()
        val projection = buildList {
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
                // Some providers retain a bucket label even when DATA is
                // omitted. It is only a last-resort display name below.
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val volumeUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                MediaStore.getExternalVolumeNames(context)
                    .filter { it != MediaStore.VOLUME_EXTERNAL }
                    .map { volumeName -> volumeName to MediaStore.Audio.Media.getContentUri(volumeName) }
            }.getOrDefault(emptyList())
        } else emptyList()
        val queryTargets = volumeUris.ifEmpty {
            listOf(null to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        queryTargets.forEach { (knownVolumeName, contentUri) ->
            runCatching {
                context.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    null,
                    null,
                )?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val volumeNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val dur = cursor.getLong(durCol)
                        val displayName = cursor.getString(nameCol).orEmpty()
                        val rawPath = dataCol.takeIf { it >= 0 }?.let(cursor::getString)
                        if (!isEligibleLocalMusic(dur, displayName, rawPath)) continue
                        val relativePath = relativePathCol.takeIf { it >= 0 }?.let(cursor::getString)
                        val rowVolumeName = volumeNameCol.takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            ?.takeIf(String::isNotBlank)
                        val volumeName = when {
                            rowVolumeName == null || rowVolumeName.equals(MediaStore.VOLUME_EXTERNAL, ignoreCase = true) ->
                                knownVolumeName ?: rowVolumeName
                            else -> rowVolumeName
                        }
                        val folderPath = resolveMediaStoreFolderPath(context, relativePath, volumeName)
                            ?: rawPath?.let(::folderForMediaPath)
                            ?: continue
                        if (AppSettings.isFolderBlacklisted(folderPath)) continue
                        val count = counts[folderPath] ?: 0
                        counts[folderPath] = count + 1
                        val relativeName = relativePath
                            ?.replace('\\', '/')
                            ?.trimEnd('/')
                            ?.substringAfterLast('/')
                            ?.takeIf(String::isNotBlank)
                        // Provider labels vary between Android versions and
                        // removable volumes. The resolved folder path is the
                        // stable source of the name shown in the Folders tab.
                        val pathName = folderPath.trimEnd('/').substringAfterLast('/')
                            .takeIf(String::isNotBlank)
                        val name = pathName
                            ?: relativeName
                            ?: bucketNameCol.takeIf { it >= 0 }
                                ?.let(cursor::getString)
                                ?.takeIf(String::isNotBlank)
                            ?: folderPath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
                            ?: folderPath
                        folderDisplayNames[folderPath] = name
                    }
                }
            }.onFailure { Log.w(TAG, "Failed discovering local folders from $knownVolumeName: ${it.message}") }
        }

        counts.map { (folderPath, count) ->
            val name = folderDisplayNames[folderPath]
                ?.takeIf(String::isNotBlank)
                ?: folderPath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
                ?: folderPath
            val parent = folderPath.substringBeforeLast('/').takeIf { it.isNotBlank() && it != folderPath }
            LocalFolder(
                path = folderPath,
                name = name,
                parentPath = parent,
                songCount = count,
                isBlacklisted = AppSettings.isFolderBlacklisted(folderPath),
            )
        }.sortedWith(compareBy({ it.isBlacklisted }, { it.name.lowercase(Locale.ROOT) }))
    }

    private val SAFE_EXTERNAL_VOLUME_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs < MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriStr))
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            if (!metaTitle.isNullOrBlank()) title = metaTitle
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            albumName = metaAlbum.cleanTag()
            if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
            retriever.release()
        }

        val trackArtwork = scanned?.artworkUrl ?: run {
            val mod = scanned?.dateModifiedSeconds
            if (mod != null && mod > 0) {
                runCatching {
                    Uri.parse(uriStr).buildUpon().appendQueryParameter("t", mod.toString()).build().toString()
                }.getOrDefault(uriStr)
            } else {
                uriStr
            }
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = trackArtwork,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
