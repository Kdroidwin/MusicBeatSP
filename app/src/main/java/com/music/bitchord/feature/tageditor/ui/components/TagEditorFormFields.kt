package com.music.bitchord.feature.tageditor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.feature.tageditor.domain.model.SongTagData
import com.music.bitchord.feature.tageditor.domain.model.TagField

@Composable
fun TagEditorFormFields(
    tags: SongTagData,
    onTagsChange: (SongTagData) -> Unit,
    onOpenGenrePicker: () -> Unit,
    modifier: Modifier = Modifier,
    mixed: Set<TagField> = emptySet(),
) {
    val fieldShape = RoundedCornerShape(12.dp)

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // Title
        OutlinedTextField(
            value = tags.title,
            onValueChange = { onTagsChange(tags.copy(title = it)) },
            label = { Text(stringResource(R.string.tag_field_title)) },
            supportingText = mixedNote(TagField.TITLE, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Album
        OutlinedTextField(
            value = tags.album,
            onValueChange = { onTagsChange(tags.copy(album = it)) },
            label = { Text(stringResource(R.string.tag_field_album)) },
            supportingText = mixedNote(TagField.ALBUM, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Artist
        OutlinedTextField(
            value = tags.artist,
            onValueChange = { onTagsChange(tags.copy(artist = it)) },
            label = { Text(stringResource(R.string.tag_field_artist)) },
            supportingText = mixedNote(TagField.ARTIST, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Album Artist
        OutlinedTextField(
            value = tags.albumArtist,
            onValueChange = { onTagsChange(tags.copy(albumArtist = it)) },
            label = { Text(stringResource(R.string.tag_field_album_artist)) },
            supportingText = mixedNote(TagField.ALBUM_ARTIST, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Composer
        OutlinedTextField(
            value = tags.composer,
            onValueChange = { onTagsChange(tags.copy(composer = it)) },
            label = { Text(stringResource(R.string.tag_field_composer)) },
            supportingText = mixedNote(TagField.COMPOSER, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Conductor / Producer
        OutlinedTextField(
            value = tags.conductor,
            onValueChange = { onTagsChange(tags.copy(conductor = it)) },
            label = { Text(stringResource(R.string.tag_field_producer)) },
            supportingText = mixedNote(TagField.PRODUCER, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Publisher / Copyright
        OutlinedTextField(
            value = tags.publisher,
            onValueChange = { onTagsChange(tags.copy(publisher = it)) },
            label = { Text(stringResource(R.string.tag_field_publisher)) },
            supportingText = mixedNote(TagField.PUBLISHER, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Genre with picker dropdown icon
        OutlinedTextField(
            value = tags.genre,
            onValueChange = { onTagsChange(tags.copy(genre = it)) },
            label = { Text(stringResource(R.string.tag_field_genre)) },
            supportingText = mixedNote(TagField.GENRE, tags, mixed),
            trailingIcon = {
                IconButton(onClick = onOpenGenrePicker) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = stringResource(R.string.tag_editor_select_genre),
                    )
                }
            },
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Year / Date
        OutlinedTextField(
            value = tags.year,
            onValueChange = { onTagsChange(tags.copy(year = it)) },
            label = { Text(stringResource(R.string.tag_field_year)) },
            supportingText = mixedNote(TagField.YEAR, tags, mixed),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Track number and total
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = tags.trackNumber,
                onValueChange = { onTagsChange(tags.copy(trackNumber = it)) },
                label = { Text(stringResource(R.string.tag_field_track)) },
                supportingText = mixedNote(TagField.TRACK, tags, mixed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = fieldShape,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tags.trackTotal,
                onValueChange = { onTagsChange(tags.copy(trackTotal = it)) },
                label = { Text(stringResource(R.string.tag_field_track_total)) },
                supportingText = mixedNote(TagField.TRACK_TOTAL, tags, mixed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = fieldShape,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Disc number and total
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = tags.discNumber,
                onValueChange = { onTagsChange(tags.copy(discNumber = it)) },
                label = { Text(stringResource(R.string.tag_field_disc)) },
                supportingText = mixedNote(TagField.DISC, tags, mixed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = fieldShape,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tags.discTotal,
                onValueChange = { onTagsChange(tags.copy(discTotal = it)) },
                label = { Text(stringResource(R.string.tag_field_disc_total)) },
                supportingText = mixedNote(TagField.DISC_TOTAL, tags, mixed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = fieldShape,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Lyrics
        OutlinedTextField(
            value = tags.lyrics,
            onValueChange = { onTagsChange(tags.copy(lyrics = it)) },
            label = { Text(stringResource(R.string.tag_field_lyrics)) },
            supportingText = mixedNote(TagField.LYRICS, tags, mixed),
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 220.dp),
        )

        // Lyricist
        OutlinedTextField(
            value = tags.lyricist,
            onValueChange = { onTagsChange(tags.copy(lyricist = it)) },
            label = { Text(stringResource(R.string.tag_field_lyricist)) },
            supportingText = mixedNote(TagField.LYRICIST, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Arranger
        OutlinedTextField(
            value = tags.arranger,
            onValueChange = { onTagsChange(tags.copy(arranger = it)) },
            label = { Text(stringResource(R.string.tag_field_arranger)) },
            supportingText = mixedNote(TagField.ARRANGER, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Comment
        OutlinedTextField(
            value = tags.comment,
            onValueChange = { onTagsChange(tags.copy(comment = it)) },
            label = { Text(stringResource(R.string.tag_field_comment)) },
            supportingText = mixedNote(TagField.COMMENT, tags, mixed),
            shape = fieldShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun mixedNote(
    field: TagField,
    tags: SongTagData,
    mixed: Set<TagField>,
): (@Composable () -> Unit)? = if (field in mixed && field.get(tags).isEmpty()) {
    { Text(stringResource(R.string.tag_mixed)) }
} else null
