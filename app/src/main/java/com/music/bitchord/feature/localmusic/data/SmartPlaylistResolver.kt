package com.music.bitchord.feature.localmusic.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistDefinition
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistField
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistMatchMode
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistOperator
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistSort
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistRule
import com.music.bitchord.feature.localsongactions.data.LocalPlayStatsStore
import com.music.bitchord.feature.localsongactions.domain.model.LocalPlayStats
import java.util.Locale

/** Applies a saved rule set to the current device library each time it is read. */
fun resolveSmartPlaylistSongs(
    definition: SmartPlaylistDefinition,
    librarySongs: List<Song>,
    playStats: Map<String, LocalPlayStats> = emptyMap(),
    favoriteIds: Set<String> = emptySet(),
): List<Song> {
    if (definition.rules.isEmpty()) return emptyList()
    val matches = librarySongs.filter { song ->
        val results = definition.rules.map { rule ->
            rule.matches(song, LocalPlayStatsStore.getStats(song, playStats), favoriteIds)
        }
        when (definition.matchMode) {
            SmartPlaylistMatchMode.ALL -> results.all { it }
            SmartPlaylistMatchMode.ANY -> results.any { it }
        }
    }
    val sorted = when (definition.sort) {
        SmartPlaylistSort.TITLE -> matches.sortedBy { it.title.lowercase(Locale.ROOT) }
        SmartPlaylistSort.RECENTLY_ADDED -> matches.sortedByDescending { it.localDateAddedSeconds ?: 0L }
        SmartPlaylistSort.MOST_PLAYED -> matches.sortedWith(
            compareByDescending<Song> { LocalPlayStatsStore.getStats(it, playStats).playedCount }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        SmartPlaylistSort.RECENTLY_PLAYED -> matches.sortedWith(
            compareByDescending<Song> { LocalPlayStatsStore.getStats(it, playStats).lastPlayedTimestamp }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }
    return sorted.take(definition.maxTracks.coerceIn(1, MAX_SMART_PLAYLIST_TRACKS))
}

private fun SmartPlaylistRule.matches(
    song: Song,
    stats: LocalPlayStats,
    favoriteIds: Set<String>,
): Boolean = when (field) {
    SmartPlaylistField.TITLE -> textMatches(song.title)
    SmartPlaylistField.ARTIST -> textMatches(song.artist)
    SmartPlaylistField.ALBUM -> textMatches(song.albumName.orEmpty())
    SmartPlaylistField.DURATION_MINUTES -> numberMatches(song.durationMillis() / 60_000L)
    SmartPlaylistField.PLAY_COUNT -> numberMatches(stats.playedCount.toLong())
    SmartPlaylistField.FAVORITE -> {
        val ids = listOfNotNull(song.localUri, song.videoId, song.localPath)
        val favorite = ids.any { it in favoriteIds }
        when (operator) {
            SmartPlaylistOperator.IS_FAVORITE -> favorite
            SmartPlaylistOperator.IS_NOT_FAVORITE -> !favorite
            else -> false
        }
    }
}

private fun SmartPlaylistRule.textMatches(actual: String): Boolean {
    val expected = value.trim()
    if (expected.isEmpty()) return false
    return when (operator) {
        SmartPlaylistOperator.CONTAINS -> actual.contains(expected, ignoreCase = true)
        SmartPlaylistOperator.EQUALS -> actual.equals(expected, ignoreCase = true)
        SmartPlaylistOperator.STARTS_WITH -> actual.startsWith(expected, ignoreCase = true)
        else -> false
    }
}

private fun SmartPlaylistRule.numberMatches(actual: Long): Boolean {
    val expected = value.trim().toLongOrNull() ?: return false
    return when (operator) {
        SmartPlaylistOperator.AT_LEAST -> actual >= expected
        SmartPlaylistOperator.AT_MOST -> actual <= expected
        else -> false
    }
}

private const val MAX_SMART_PLAYLIST_TRACKS = 500
