package com.music.bitchord.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.radio.RadioPlaybackSession
import com.music.bitchord.radio.data.RadioRepository
import com.music.bitchord.radio.model.PrefectureData
import com.music.bitchord.radio.model.ProgramEntry
import com.music.bitchord.radio.model.Station
import com.music.bitchord.radio.model.StationRegistry
import com.music.bitchord.radio.model.logoUrl
import kotlinx.coroutines.launch

@Composable
fun RadioScreen(
    controller: MediaController?,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
    val amoledBlack = themeMode == ThemeMode.AMOLED
    val selectedAreaId by AppSettings.radioAreaId.collectAsStateWithLifecycle()
    var stationDetail by remember { mutableStateOf<Station?>(null) }
    var showRegionPicker by remember { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<ProgramEntry?>(null) }
    var query by remember { mutableStateOf("") }
    var weeklyPrograms by remember(stationDetail?.id) { mutableStateOf<List<ProgramEntry>>(emptyList()) }
    var isLoadingPrograms by remember(stationDetail?.id) { mutableStateOf(false) }
    var hasProgramError by remember(stationDetail?.id) { mutableStateOf(false) }
    var playingStationId by remember { mutableStateOf<String?>(null) }
    var startingStationId by remember { mutableStateOf<String?>(null) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val networkUnavailable = offlineMode || context.packageName.endsWith(".nointernet")
    val currentStationId = currentSong?.takeIf { it.isLiveRadio }
        ?.videoId?.removePrefix("radiko:")

    LaunchedEffect(currentStationId) {
        playingStationId = currentStationId
    }

    LaunchedEffect(stationDetail?.id, selectedAreaId, networkUnavailable) {
        val station = stationDetail ?: return@LaunchedEffect
        if (networkUnavailable) {
            weeklyPrograms = emptyList()
            hasProgramError = true
            isLoadingPrograms = false
            return@LaunchedEffect
        }
        isLoadingPrograms = true
        hasProgramError = false
        runCatching { RadioRepository.fetchWeeklyPrograms(station.id) }
            .onSuccess { programs ->
                weeklyPrograms = programs
                selectedDay = RadioRepository.defaultProgramDay(programs)
            }
            .onFailure {
                hasProgramError = true
                weeklyPrograms = emptyList()
            }
        isLoadingPrograms = false
    }

    val startStation: (Station) -> Unit = { station ->
        if (station.id == currentStationId && controller != null) {
            if (isPlaying) controller.pause() else controller.play()
        } else if (networkUnavailable) {
            Toast.makeText(context, R.string.radio_offline_unavailable, Toast.LENGTH_SHORT).show()
        } else if (controller == null) {
            Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                startingStationId = station.id
                runCatching {
                    val live = RadioRepository.prepareLiveStream(station, selectedAreaId)
                    val currentProgram = if (stationDetail?.id == station.id) {
                        RadioRepository.currentProgram(weeklyPrograms)
                    } else {
                        runCatching {
                            RadioRepository.currentProgram(RadioRepository.fetchWeeklyPrograms(station.id))
                        }.getOrNull()
                    }
                    controller.beginRadioQueue()
                    val song = Song(
                        videoId = "radiko:${station.id}",
                        title = currentProgram?.title ?: station.name,
                        artist = station.name,
                        thumbnailUrl = currentProgram?.imageUrl ?: station.logoUrl,
                        radioName = station.name,
                        isLiveRadio = true,
                        radioStreamUrl = live.streamUrl,
                    )
                    controller.playSongs(listOf(song), 0)
                }.onFailure {
                    RadioPlaybackSession.clear()
                    Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_LONG).show()
                }
                startingStationId = null
            }
        }
    }

    if (showRegionPicker) {
        RadioRegionDialog(
            selectedAreaId = selectedAreaId,
            amoledBlack = amoledBlack,
            onSelect = { areaId ->
                AppSettings.setRadioAreaId(areaId)
                showRegionPicker = false
            },
            onDismiss = { showRegionPicker = false },
        )
    }

    selectedProgram?.let { program ->
        RadioProgramDialog(program = program, amoledBlack = amoledBlack, onDismiss = { selectedProgram = null })
    }

    val station = stationDetail
    if (station == null) {
        val areaName = PrefectureData.prefecture(selectedAreaId)?.name.orEmpty()
        val stations = remember(selectedAreaId, query) {
            StationRegistry.search(query, selectedAreaId)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "radio-controls") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.radio_station_list),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.radio_area_current, areaName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showRegionPicker = true }) {
                            Icon(Icons.Rounded.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.radio_change_area))
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.radio_search_station)) },
                    )
                    if (networkUnavailable) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceVariant,
                        )) {
                            Text(
                                text = stringResource(R.string.radio_offline_unavailable),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    currentSong?.takeIf { it.isLiveRadio }?.let { song ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.radio_now_playing), style = MaterialTheme.typography.labelMedium)
                                    Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            if (stations.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.radio_no_stations),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(stations, key = { it.id }) { item ->
                    RadioStationRow(
                        station = item,
                        amoledBlack = amoledBlack,
                        isPlaying = item.id == playingStationId,
                        isStarting = item.id == startingStationId,
                        onDetails = { stationDetail = item },
                        onPlay = { startStation(item) },
                    )
                }
            }
        }
    } else {
        RadioStationDetail(
            station = station,
            amoledBlack = amoledBlack,
            isPlaying = station.id == playingStationId && isPlaying,
            isStarting = station.id == startingStationId,
            programs = weeklyPrograms,
            selectedDay = selectedDay,
            isLoading = isLoadingPrograms,
            hasError = hasProgramError,
            networkUnavailable = networkUnavailable,
            contentPadding = contentPadding,
            onBack = { stationDetail = null },
            onPlay = { startStation(station) },
            onSelectDay = { selectedDay = it },
            onSelectProgram = { selectedProgram = it },
            onRetry = {
                hasProgramError = false
                stationDetail = station
                // Re-keying the same station makes the request restart without creating another player.
                scope.launch {
                    isLoadingPrograms = true
                    runCatching { RadioRepository.fetchWeeklyPrograms(station.id) }
                        .onSuccess {
                            weeklyPrograms = it
                            selectedDay = RadioRepository.defaultProgramDay(it)
                        }
                        .onFailure { hasProgramError = true }
                    isLoadingPrograms = false
                }
            },
        )
    }
}

@Composable
private fun RadioStationRow(
    station: Station,
    amoledBlack: Boolean,
    isPlaying: Boolean,
    isStarting: Boolean,
    onDetails: () -> Unit,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = station.logoUrl,
                contentDescription = station.name,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(
                    if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceVariant,
                ),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(station.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isPlaying) Text(stringResource(R.string.radio_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onPlay, enabled = !isStarting) {
                if (isStarting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play))
            }
        }
    }
}

@Composable
private fun RadioStationDetail(
    station: Station,
    amoledBlack: Boolean,
    isPlaying: Boolean,
    isStarting: Boolean,
    programs: List<ProgramEntry>,
    selectedDay: String?,
    isLoading: Boolean,
    hasError: Boolean,
    networkUnavailable: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onSelectDay: (String) -> Unit,
    onSelectProgram: (ProgramEntry) -> Unit,
    onRetry: () -> Unit,
) {
    val days = remember(programs) { programs.map { RadioRepository.programDay(it.startAt) }.distinct().sorted() }
    val dayPrograms = remember(programs, selectedDay) { programs.filter { RadioRepository.programDay(it.startAt) == selectedDay } }
    val currentProgram = remember(programs) { RadioRepository.currentProgram(programs) }
    val dayListState = rememberLazyListState()
    var hasPositionedInitialDay by remember(station.id) { mutableStateOf(false) }
    LaunchedEffect(days, selectedDay) {
        val selectedIndex = selectedDay?.let(days::indexOf) ?: -1
        if (!hasPositionedInitialDay && selectedIndex >= 0) {
            dayListState.scrollToItem(selectedIndex)
            hasPositionedInitialDay = true
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "station-detail-heading") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                    Text(station.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = station.logoUrl,
                            contentDescription = station.name,
                            modifier = Modifier.size(70.dp).clip(RoundedCornerShape(16.dp)).background(
                                if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(station.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            currentProgram?.let {
                                Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.radio_on_air), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Button(onClick = onPlay, enabled = !isStarting && !networkUnavailable) {
                            if (isStarting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.radio_program_schedule), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (days.isNotEmpty()) {
                    LazyRow(
                        state = dayListState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(days, key = { it }) { day ->
                            FilterChip(
                                selected = day == selectedDay,
                                onClick = { onSelectDay(day) },
                                label = {
                                    Text(
                                        RadioRepository.formatProgramDay(day),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        when {
            networkUnavailable -> item { RadioScheduleMessage(stringResource(R.string.radio_offline_unavailable)) }
            isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            hasError -> item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.radio_schedule_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            dayPrograms.isEmpty() -> item { RadioScheduleMessage(stringResource(R.string.radio_schedule_empty)) }
            else -> items(dayPrograms, key = { "${it.stationId}-${it.startAt}" }) { program ->
                RadioScheduleRow(
                    program = program,
                    amoledBlack = amoledBlack,
                    isCurrent = currentProgram?.startAt == program.startAt,
                    onClick = { onSelectProgram(program) },
                )
            }
        }
    }
}

@Composable
private fun RadioScheduleRow(program: ProgramEntry, isCurrent: Boolean, amoledBlack: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (amoledBlack) {
                if (isCurrent) Color(0xFF171717) else Color.Black
            } else if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${RadioRepository.formatProgramTime(program.startAt)}–${RadioRepository.formatProgramTime(program.endAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(104.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(program.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                program.performer?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RadioScheduleMessage(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioProgramDialog(program: ProgramEntry, amoledBlack: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(program.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                program.imageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column {
                    Text(
                        RadioRepository.formatProgramDay(program.startAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        "${RadioRepository.formatProgramTime(program.startAt)}–${RadioRepository.formatProgramTime(program.endAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                program.performer?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                program.description.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                program.info?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            Row {
                program.url?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let { url ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    }) { Text(stringResource(R.string.radio_open_program_site)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
private fun RadioRegionDialog(
    selectedAreaId: String,
    amoledBlack: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.radio_select_area)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                PrefectureData.regions.forEach { region ->
                    item(key = "region-${region.id}") {
                        Text(
                            text = region.name,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(PrefectureData.prefecturesForRegion(region.id), key = { it.id }) { prefecture ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(prefecture.id) }
                                .background(
                                    when {
                                        !amoledBlack && prefecture.id == selectedAreaId -> MaterialTheme.colorScheme.secondaryContainer
                                        !amoledBlack -> MaterialTheme.colorScheme.surface
                                        prefecture.id == selectedAreaId -> Color(0xFF171717)
                                        else -> Color.Black
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(prefecture.name, modifier = Modifier.weight(1f))
                            if (prefecture.id == selectedAreaId) Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
