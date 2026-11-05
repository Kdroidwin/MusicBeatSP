package com.music.bitchord.data.lyrics

/** Which track details automatic lyric providers receive. */
enum class LyricsStrictness(
    val useArtist: Boolean,
    val useAlbum: Boolean,
    val useDuration: Boolean,
) {
    STRICT(useArtist = true, useAlbum = true, useDuration = true),
    STANDARD(useArtist = true, useAlbum = false, useDuration = true),
    LOOSE(useArtist = true, useAlbum = false, useDuration = false),
    TITLE_ONLY(useArtist = false, useAlbum = false, useDuration = false),
    ;

    companion object {
        fun fromPreference(value: String): LyricsStrictness =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
