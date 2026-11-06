package com.music.bitchord.feature.tageditor.domain.model

import androidx.annotation.StringRes
import com.music.bitchord.R

/** A single editable tag and the key TagLib stores it under. */
enum class TagField(
    val propertyKey: String?,
    @param:StringRes val label: Int,
    val perSong: Boolean = false,
) {
    TITLE("TITLE", R.string.tag_field_title, perSong = true),
    ALBUM("ALBUM", R.string.tag_field_album),
    ARTIST("ARTIST", R.string.tag_field_artist),
    ALBUM_ARTIST("ALBUMARTIST", R.string.tag_field_album_artist),
    COMPOSER("COMPOSER", R.string.tag_field_composer),
    PRODUCER("PRODUCER", R.string.tag_field_producer),
    PUBLISHER("COPYRIGHT", R.string.tag_field_publisher),
    GENRE("GENRE", R.string.tag_field_genre),
    YEAR("DATE", R.string.tag_field_year),
    TRACK("TRACKNUMBER", R.string.tag_field_track, perSong = true),
    TRACK_TOTAL("TRACKTOTAL", R.string.tag_field_track_total),
    DISC("DISCNUMBER", R.string.tag_field_disc),
    DISC_TOTAL("DISCTOTAL", R.string.tag_field_disc_total),
    LYRICS("LYRICS", R.string.tag_field_lyrics, perSong = true),
    LYRICIST("LYRICIST", R.string.tag_field_lyricist),
    ARRANGER("ARRANGER", R.string.tag_field_arranger),
    COMMENT("COMMENT", R.string.tag_field_comment),
    ARTWORK(null, R.string.tag_editor_cover_art);

    fun get(data: SongTagData): String = when (this) {
        TITLE -> data.title
        ALBUM -> data.album
        ARTIST -> data.artist
        ALBUM_ARTIST -> data.albumArtist
        COMPOSER -> data.composer
        PRODUCER -> data.conductor
        PUBLISHER -> data.publisher
        GENRE -> data.genre
        YEAR -> data.year
        TRACK -> data.trackNumber
        TRACK_TOTAL -> data.trackTotal
        DISC -> data.discNumber
        DISC_TOTAL -> data.discTotal
        LYRICS -> data.lyrics
        LYRICIST -> data.lyricist
        ARRANGER -> data.arranger
        COMMENT -> data.comment
        ARTWORK -> ""
    }

    fun set(data: SongTagData, value: String): SongTagData = when (this) {
        TITLE -> data.copy(title = value)
        ALBUM -> data.copy(album = value)
        ARTIST -> data.copy(artist = value)
        ALBUM_ARTIST -> data.copy(albumArtist = value)
        COMPOSER -> data.copy(composer = value)
        PRODUCER -> data.copy(conductor = value)
        PUBLISHER -> data.copy(publisher = value)
        GENRE -> data.copy(genre = value)
        YEAR -> data.copy(year = value)
        TRACK -> data.copy(trackNumber = value)
        TRACK_TOTAL -> data.copy(trackTotal = value)
        DISC -> data.copy(discNumber = value)
        DISC_TOTAL -> data.copy(discTotal = value)
        LYRICS -> data.copy(lyrics = value)
        LYRICIST -> data.copy(lyricist = value)
        ARRANGER -> data.copy(arranger = value)
        COMMENT -> data.copy(comment = value)
        ARTWORK -> data
    }

    companion object {
        fun changed(original: SongTagData, edited: SongTagData): Set<TagField> =
            entries.filterTo(LinkedHashSet()) { field ->
                if (field == ARTWORK) edited.artworkChanged
                else field.get(edited).trim() != field.get(original).trim()
            }
    }
}
