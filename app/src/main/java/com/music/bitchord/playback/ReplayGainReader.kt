package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Reads common ReplayGain tags from local files. Missing or unreadable tags mean unity gain. */
object ReplayGainReader {
    private const val MAX_CACHED_FILES = 256
    private val cacheMutex = Mutex()
    private val propertyCache = object : LinkedHashMap<String, Map<String, Array<String>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Array<String>>>?): Boolean =
            size > MAX_CACHED_FILES
    }

    suspend fun readGainDb(
        context: Context,
        song: Song,
        useAlbumGain: Boolean,
        preampDb: Float,
        preventClipping: Boolean,
    ): Float = withContext(Dispatchers.IO) {
        runCatching {
            val source = song.localUri ?: song.localPath ?: return@runCatching 0f
            val uri = Uri.parse(source).let { parsed ->
                if (parsed.scheme == null) Uri.fromFile(File(source)) else parsed
            }
            val filePath = song.localPath ?: uri.path
            val file = filePath?.let(::File)
            val cacheKey = buildString {
                append(song.localUri ?: source)
                file?.let {
                    append('|').append(it.lastModified()).append('|').append(it.length())
                }
            }
            val properties = cacheMutex.withLock {
                propertyCache[cacheKey] ?: run {
                    val descriptor = when (uri.scheme) {
                        "file" -> ParcelFileDescriptor.open(
                            File(requireNotNull(uri.path)),
                            ParcelFileDescriptor.MODE_READ_ONLY,
                        )
                        else -> context.contentResolver.openFileDescriptor(uri, "r")
                    } ?: return@withLock emptyMap()
                    val read = descriptor.use { pfd ->
                        TagLib.getMetadata(pfd.dup().detachFd(), readPictures = false)?.propertyMap.orEmpty()
                    }
                    if (read.isNotEmpty()) propertyCache[cacheKey] = read
                    read
                }
            }
            ReplayGainValues.calculateDb(properties, useAlbumGain, preampDb, preventClipping)
        }.getOrDefault(0f)
    }
}
