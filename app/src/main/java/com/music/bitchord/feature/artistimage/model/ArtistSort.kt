package com.music.bitchord.feature.artistimage.model

/**
 * Sorting modes specific to the Artists library page.
 */
enum class ArtistSort {
    /** Sort by number of tracks descending, tie-breaking alphabetically. */
    MOST_SONGS,

    /** Sort alphabetically ascending (A to Z). */
    NAME_ASC,

    /** Sort alphabetically descending (Z to A). */
    NAME_DESC,

    /** Keep the order chosen by dragging library entries. */
    MANUAL;

    fun label(): String = when (this) {
        MOST_SONGS -> "Most Songs"
        NAME_ASC -> "A to Z"
        NAME_DESC -> "Z to A"
        MANUAL -> "Custom order"
    }
}
