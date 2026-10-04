package com.music.bitchord.feature.localmusic.domain.model

import kotlinx.serialization.Serializable

/**
 * User-created offline local music playlist.
 *
 * @property id Unique playlist identifier
 * @property name User-assigned playlist title
 * @property createdAt Timestamp of creation in epoch millis
 * @property songIds Ordered list of track identifiers (localUri or videoId)
 * @property coverUrl Optional artwork URL derived from constituent tracks
 */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songIds: List<String> = emptyList(),
    val coverUrl: String? = null,
    /** Optional user-selected image; [coverUrl] remains the automatic track artwork fallback. */
    val customCoverUrl: String? = null,
    /** Imported display tags for playable URIs that are not present in MediaStore. */
    val songMetadata: Map<String, LocalPlaylistSongMetadata> = emptyMap(),
)

@Serializable
data class LocalPlaylistSongMetadata(
    val title: String,
    val artist: String = "",
)
