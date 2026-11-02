package com.music.bitchord.feature.localmusic.domain.model

import kotlinx.serialization.Serializable

/** A rule-based playlist is evaluated against the current local library when opened. */
@Serializable
data class SmartPlaylistDefinition(
    val matchMode: SmartPlaylistMatchMode = SmartPlaylistMatchMode.ALL,
    val rules: List<SmartPlaylistRule> = emptyList(),
    val maxTracks: Int = 200,
    val sort: SmartPlaylistSort = SmartPlaylistSort.TITLE,
)

@Serializable
data class SmartPlaylistRule(
    val field: SmartPlaylistField = SmartPlaylistField.TITLE,
    val operator: SmartPlaylistOperator = SmartPlaylistOperator.CONTAINS,
    val value: String = "",
)

@Serializable
enum class SmartPlaylistMatchMode { ALL, ANY }

@Serializable
enum class SmartPlaylistField { TITLE, ARTIST, ALBUM, DURATION_MINUTES, PLAY_COUNT, FAVORITE }

@Serializable
enum class SmartPlaylistOperator {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    AT_LEAST,
    AT_MOST,
    IS_FAVORITE,
    IS_NOT_FAVORITE,
}

@Serializable
enum class SmartPlaylistSort { TITLE, RECENTLY_ADDED, MOST_PLAYED, RECENTLY_PLAYED }
