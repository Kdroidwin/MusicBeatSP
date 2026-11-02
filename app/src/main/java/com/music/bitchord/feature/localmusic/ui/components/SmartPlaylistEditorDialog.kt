package com.music.bitchord.feature.localmusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistDefinition
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistField
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistMatchMode
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistOperator
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistRule
import com.music.bitchord.feature.localmusic.domain.model.SmartPlaylistSort

@Composable
fun SmartPlaylistEditorDialog(
    initialName: String = "",
    initialDefinition: SmartPlaylistDefinition? = null,
    onDismiss: () -> Unit,
    onSave: (String, SmartPlaylistDefinition) -> Unit,
) {
    var name by remember(initialName, initialDefinition) { mutableStateOf(initialName) }
    var matchMode by remember(initialDefinition) {
        mutableStateOf(initialDefinition?.matchMode ?: SmartPlaylistMatchMode.ALL)
    }
    val rules = remember(initialDefinition) {
        mutableStateListOf<SmartPlaylistRule>().apply {
            addAll(initialDefinition?.rules.orEmpty().ifEmpty { listOf(SmartPlaylistRule()) })
        }
    }
    var maxTracks by remember(initialDefinition) {
        mutableStateOf((initialDefinition?.maxTracks ?: 200).coerceIn(1, 500).toString())
    }
    var sort by remember(initialDefinition) {
        mutableStateOf(initialDefinition?.sort ?: SmartPlaylistSort.TITLE)
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.84f
    val hasValidRules = rules.isNotEmpty() && rules.all { rule ->
        when (rule.field) {
            SmartPlaylistField.FAVORITE -> true
            SmartPlaylistField.DURATION_MINUTES,
            SmartPlaylistField.PLAY_COUNT -> rule.value.toLongOrNull() != null
            else -> rule.value.isNotBlank()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(
                        if (initialDefinition == null) R.string.create_smart_playlist
                        else R.string.edit_smart_playlist
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.smart_playlist_match), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = matchMode == SmartPlaylistMatchMode.ALL,
                        onClick = { matchMode = SmartPlaylistMatchMode.ALL },
                        label = { Text(stringResource(R.string.smart_playlist_match_all)) },
                    )
                    FilterChip(
                        selected = matchMode == SmartPlaylistMatchMode.ANY,
                        onClick = { matchMode = SmartPlaylistMatchMode.ANY },
                        label = { Text(stringResource(R.string.smart_playlist_match_any)) },
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = maxHeight * 0.44f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rules.forEachIndexed { index, rule ->
                        SmartPlaylistRuleEditor(
                            rule = rule,
                            onChange = { rules[index] = it },
                            onRemove = { if (rules.size > 1) rules.removeAt(index) },
                            canRemove = rules.size > 1,
                        )
                    }
                }
                TextButton(
                    onClick = { rules.add(SmartPlaylistRule()) },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.smart_playlist_add_rule))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = maxTracks,
                        onValueChange = { maxTracks = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.smart_playlist_max_tracks)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    SmartPlaylistChoice(
                        label = stringResource(R.string.smart_playlist_sort),
                        value = sort,
                        choices = SmartPlaylistSort.entries.toList(),
                        choiceLabel = { stringResource(it.labelRes) },
                        onChoose = { sort = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        enabled = name.isNotBlank() && hasValidRules && maxTracks.toIntOrNull() != null,
                        onClick = {
                            onSave(
                                name.trim(),
                                SmartPlaylistDefinition(
                                    matchMode = matchMode,
                                    rules = rules.toList(),
                                    maxTracks = (maxTracks.toIntOrNull() ?: 200).coerceIn(1, 500),
                                    sort = sort,
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
private fun SmartPlaylistRuleEditor(
    rule: SmartPlaylistRule,
    onChange: (SmartPlaylistRule) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmartPlaylistChoice(
                label = stringResource(R.string.smart_playlist_field),
                value = rule.field,
                choices = SmartPlaylistField.entries.toList(),
                choiceLabel = { stringResource(it.labelRes) },
                onChoose = { field ->
                    val operator = when (field) {
                        SmartPlaylistField.TITLE,
                        SmartPlaylistField.ARTIST,
                        SmartPlaylistField.ALBUM -> SmartPlaylistOperator.CONTAINS
                        SmartPlaylistField.DURATION_MINUTES,
                        SmartPlaylistField.PLAY_COUNT -> SmartPlaylistOperator.AT_LEAST
                        SmartPlaylistField.FAVORITE -> SmartPlaylistOperator.IS_FAVORITE
                    }
                    onChange(SmartPlaylistRule(field, operator, ""))
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.remove))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmartPlaylistChoice(
                label = stringResource(R.string.smart_playlist_operator),
                value = rule.operator,
                choices = rule.field.operators,
                choiceLabel = { stringResource(it.labelRes) },
                onChoose = { onChange(rule.copy(operator = it)) },
                modifier = Modifier.weight(0.9f),
            )
            if (rule.field != SmartPlaylistField.FAVORITE) {
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = rule.value,
                    onValueChange = { onChange(rule.copy(value = it)) },
                    label = {
                        Text(
                            stringResource(
                                if (rule.field == SmartPlaylistField.DURATION_MINUTES) R.string.smart_playlist_minutes
                                else if (rule.field == SmartPlaylistField.PLAY_COUNT) R.string.smart_playlist_plays
                                else R.string.smart_playlist_value
                            ),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (rule.field == SmartPlaylistField.DURATION_MINUTES ||
                            rule.field == SmartPlaylistField.PLAY_COUNT
                        ) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.weight(1.1f),
                )
            }
        }
    }
}

@Composable
private fun <T> SmartPlaylistChoice(
    label: String,
    value: T,
    choices: List<T>,
    choiceLabel: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(choiceLabel(value), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choiceLabel(choice)) },
                    onClick = {
                        expanded = false
                        onChoose(choice)
                    },
                )
            }
        }
    }
}

private val SmartPlaylistField.labelRes: Int
    get() = when (this) {
        SmartPlaylistField.TITLE -> R.string.smart_playlist_title
        SmartPlaylistField.ARTIST -> R.string.smart_playlist_artist
        SmartPlaylistField.ALBUM -> R.string.smart_playlist_album
        SmartPlaylistField.DURATION_MINUTES -> R.string.smart_playlist_duration
        SmartPlaylistField.PLAY_COUNT -> R.string.smart_playlist_play_count
        SmartPlaylistField.FAVORITE -> R.string.smart_playlist_favorite
    }

private val SmartPlaylistOperator.labelRes: Int
    get() = when (this) {
        SmartPlaylistOperator.CONTAINS -> R.string.smart_playlist_contains
        SmartPlaylistOperator.EQUALS -> R.string.smart_playlist_equals
        SmartPlaylistOperator.STARTS_WITH -> R.string.smart_playlist_starts_with
        SmartPlaylistOperator.AT_LEAST -> R.string.smart_playlist_at_least
        SmartPlaylistOperator.AT_MOST -> R.string.smart_playlist_at_most
        SmartPlaylistOperator.IS_FAVORITE -> R.string.smart_playlist_is_favorite
        SmartPlaylistOperator.IS_NOT_FAVORITE -> R.string.smart_playlist_is_not_favorite
    }

private val SmartPlaylistSort.labelRes: Int
    get() = when (this) {
        SmartPlaylistSort.TITLE -> R.string.smart_playlist_sort_title
        SmartPlaylistSort.RECENTLY_ADDED -> R.string.smart_playlist_sort_recently_added
        SmartPlaylistSort.MOST_PLAYED -> R.string.smart_playlist_sort_most_played
        SmartPlaylistSort.RECENTLY_PLAYED -> R.string.smart_playlist_sort_recently_played
    }

private val SmartPlaylistField.operators: List<SmartPlaylistOperator>
    get() = when (this) {
        SmartPlaylistField.TITLE,
        SmartPlaylistField.ARTIST,
        SmartPlaylistField.ALBUM -> listOf(
            SmartPlaylistOperator.CONTAINS,
            SmartPlaylistOperator.EQUALS,
            SmartPlaylistOperator.STARTS_WITH,
        )
        SmartPlaylistField.DURATION_MINUTES,
        SmartPlaylistField.PLAY_COUNT -> listOf(SmartPlaylistOperator.AT_LEAST, SmartPlaylistOperator.AT_MOST)
        SmartPlaylistField.FAVORITE -> listOf(
            SmartPlaylistOperator.IS_FAVORITE,
            SmartPlaylistOperator.IS_NOT_FAVORITE,
        )
    }
