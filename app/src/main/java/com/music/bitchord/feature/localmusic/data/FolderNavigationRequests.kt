package com.music.bitchord.feature.localmusic.data

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** One-shot in-app folder navigation requests from places such as Now Playing. */
object FolderNavigationRequests {
    data class Request(val id: Long, val path: String)

    private val ids = AtomicLong(0L)
    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()

    fun folderPathFor(song: Song): String? = song.localFolderPath?.takeIf(String::isNotBlank)
        ?: song.localPath?.replace('\\', '/')?.substringBeforeLast('/')?.takeIf(String::isNotBlank)

    fun request(path: String) {
        val normalized = path.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        _pending.value = Request(ids.incrementAndGet(), normalized)
    }

    @Synchronized
    fun handled(request: Request) {
        if (_pending.value?.id == request.id) _pending.value = null
    }
}
