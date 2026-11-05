package com.music.bitchord.data.settings

/** Grid pages whose column counts are stored independently. */
enum class GridKind(val key: String, val defaultColumns: Int) {
    SONGS("songs", 2),
    ALBUMS("albums", 2),
    ARTISTS("artists", 2),
    ALBUM_ARTISTS("album_artists", 2),
    LIBRARY("library", 2),
    COLLECTION_SONGS("collection_songs", 2);

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 5
    }
}
