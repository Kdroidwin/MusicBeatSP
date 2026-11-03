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
        SmartPlaylistSort.MOST_SKIPPED -> matches.sortedWith(
            compareByDescending<Song> { LocalPlayStatsStore.getStats(it, playStats).skippedCount }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        SmartPlaylistSort.LEAST_PLAYED -> matches.sortedWith(
            compareBy<Song> { LocalPlayStatsStore.getStats(it, playStats).playedCount }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        SmartPlaylistSort.LONGEST_DURATION -> matches.sortedByDescending { it.durationMillis() }
        SmartPlaylistSort.SHORTEST_DURATION -> matches.sortedBy { it.durationMillis() }
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
    SmartPlaylistField.ALBUM_ARTIST -> textMatches(song.albumArtist.orEmpty().ifBlank { song.artist })
    SmartPlaylistField.FOLDER -> textMatches(song.localFolderPath.orEmpty().ifBlank {
        song.localPath?.substringBeforeLast('/', "").orEmpty()
    })
    SmartPlaylistField.FILE_TYPE -> textMatches(fileType(song))
    SmartPlaylistField.DURATION_MINUTES -> numberMatches(song.durationMillis() / 60_000L)
    SmartPlaylistField.PLAY_COUNT -> numberMatches(stats.playedCount.toLong())
    SmartPlaylistField.SKIP_COUNT -> numberMatches(stats.skippedCount.toLong())
    SmartPlaylistField.DATE_ADDED_DAYS_AGO -> song.localDateAddedSeconds?.let { addedSeconds ->
        numberMatches(((System.currentTimeMillis() / 1_000L - addedSeconds).coerceAtLeast(0L)) / SECONDS_PER_DAY)
    } ?: false
    SmartPlaylistField.LAST_PLAYED_DAYS_AGO -> if (stats.lastPlayedTimestamp <= 0L) false else {
        numberMatches(((System.currentTimeMillis() - stats.lastPlayedTimestamp).coerceAtLeast(0L)) / MILLIS_PER_DAY)
    }
    SmartPlaylistField.FAVORITE -> {
        val ids = listOfNotNull(song.localUri, song.videoId, song.localPath)
        val favorite = ids.any { it in favoriteIds }
        when (operator) {
            SmartPlaylistOperator.IS_FAVORITE -> favorite
            SmartPlaylistOperator.IS_NOT_FAVORITE -> !favorite
            else -> false
        }
    }
    SmartPlaylistField.NEVER_PLAYED -> when (operator) {
        SmartPlaylistOperator.IS_NEVER_PLAYED -> stats.playedCount == 0
        SmartPlaylistOperator.HAS_BEEN_PLAYED -> stats.playedCount > 0
        else -> false
    }
    SmartPlaylistField.MISSING_ARTWORK -> when (operator) {
        SmartPlaylistOperator.IS_MISSING -> song.thumbnailUrl.isNullOrBlank()
        SmartPlaylistOperator.IS_PRESENT -> !song.thumbnailUrl.isNullOrBlank()
        else -> false
    }
}

private fun SmartPlaylistRule.textMatches(actual: String): Boolean {
    val expected = value.trim()
    if (expected.isEmpty()) return false
    return when (operator) {
        SmartPlaylistOperator.CONTAINS -> actual.contains(expected, ignoreCase = true)
        SmartPlaylistOperator.DOES_NOT_CONTAIN -> !actual.contains(expected, ignoreCase = true)
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
        SmartPlaylistOperator.EQUALS -> actual == expected
        else -> false
    }
}

private fun fileType(song: Song): String {
    val path = song.localPath
        ?: song.localUri?.substringBefore('?')?.substringBefore('#')
        ?: return song.downloadFormat.orEmpty()
    return path.substringAfterLast('/', path).substringAfterLast('.', "").ifBlank {
        song.downloadFormat.orEmpty()
    }
}

private const val MAX_SMART_PLAYLIST_TRACKS = 500
private const val SECONDS_PER_DAY = 86_400L
private const val MILLIS_PER_DAY = 86_400_000L
