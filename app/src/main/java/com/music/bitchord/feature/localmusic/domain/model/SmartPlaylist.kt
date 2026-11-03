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
enum class SmartPlaylistField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    FOLDER,
    FILE_TYPE,
    DURATION_MINUTES,
    PLAY_COUNT,
    SKIP_COUNT,
    DATE_ADDED_DAYS_AGO,
    LAST_PLAYED_DAYS_AGO,
    FAVORITE,
    NEVER_PLAYED,
    MISSING_ARTWORK,
}

@Serializable
enum class SmartPlaylistOperator {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    AT_LEAST,
    AT_MOST,
    IS_FAVORITE,
    IS_NOT_FAVORITE,
    DOES_NOT_CONTAIN,
    IS_NEVER_PLAYED,
    HAS_BEEN_PLAYED,
    IS_MISSING,
    IS_PRESENT,
}

@Serializable
enum class SmartPlaylistSort {
    TITLE,
    RECENTLY_ADDED,
    MOST_PLAYED,
    RECENTLY_PLAYED,
    MOST_SKIPPED,
    LEAST_PLAYED,
    LONGEST_DURATION,
    SHORTEST_DURATION,
}
