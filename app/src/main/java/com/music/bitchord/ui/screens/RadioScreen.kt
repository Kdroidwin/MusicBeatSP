package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Delete
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.radio.OnsenPlaybackPositionStore
import com.music.bitchord.radio.RadioPlaybackSession
import com.music.bitchord.radio.data.OnsenRepository
import com.music.bitchord.radio.data.RadioRepository
import com.music.bitchord.radio.model.OnsenEpisode
import com.music.bitchord.radio.model.OnsenProgram
import com.music.bitchord.radio.model.PrefectureData
import com.music.bitchord.radio.model.ProgramEntry
import com.music.bitchord.radio.model.RadioService
import com.music.bitchord.radio.model.Station
import com.music.bitchord.radio.model.StationRegistry
import com.music.bitchord.radio.model.logoUrl
import com.music.bitchord.radio.recording.RadioAutoRecordingScheduler
import com.music.bitchord.radio.recording.RadioAutoRecordingStore
import com.music.bitchord.radio.recording.RadioFavoriteKind
import com.music.bitchord.radio.recording.RadioFavoriteRepeat
import com.music.bitchord.radio.recording.RadioRecordingFavorite
import com.music.bitchord.radio.recording.RadioRecordingFavoriteScheduler
import com.music.bitchord.radio.recording.RadioRecordingFavoriteStore
import kotlinx.coroutines.delay
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
    val darkTheme = when (themeMode) {
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.ARTWORK -> isSystemInDarkTheme()
        ThemeMode.CUSTOM_IMAGE -> true
    }
    val selectedAreaId by AppSettings.radioAreaId.collectAsStateWithLifecycle()
    val selectedRadioService by AppSettings.radioService.collectAsStateWithLifecycle()
    val hideRadioTopLabels by AppSettings.hideRadioTopLabels.collectAsStateWithLifecycle()
    var stationDetail by remember { mutableStateOf<Station?>(null) }
    var showRegionPicker by remember { mutableStateOf(false) }
    var showServicePicker by remember { mutableStateOf(false) }
    var onsenPrograms by remember { mutableStateOf<List<OnsenProgram>>(emptyList()) }
    var searchedOnsenPrograms by remember { mutableStateOf<Pair<String, List<OnsenProgram>>?>(null) }
    var isLoadingOnsenPrograms by remember { mutableStateOf(false) }
    var isSearchingOnsenPrograms by remember { mutableStateOf(false) }
    var hasOnsenProgramsError by remember { mutableStateOf(false) }
    var refreshOnsenProgramsKey by remember { mutableStateOf(0) }
    var selectedOnsenProgram by remember { mutableStateOf<OnsenProgram?>(null) }
    var isLoadingOnsenProgram by remember { mutableStateOf(false) }
    var hasOnsenProgramError by remember { mutableStateOf(false) }
    var refreshOnsenProgramKey by remember { mutableStateOf(0) }
    var selectedProgram by remember { mutableStateOf<ProgramEntry?>(null) }
    var keywordFavoriteStation by remember { mutableStateOf<Station?>(null) }
    var showRecordingFavorites by remember { mutableStateOf(false) }
    var favoritesVersion by remember { mutableStateOf(0) }
    var pendingAutoRecordProgram by remember { mutableStateOf<ProgramEntry?>(null) }
    var pendingOnsenDownload by remember { mutableStateOf<Song?>(null) }
    var autoRecordingIds by remember { mutableStateOf(RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }) }
    var recordingFolderName by remember {
        mutableStateOf(
            RadioAutoRecordingStore.folderUri(context)?.let { uri -> DocumentFile.fromTreeUri(context, uri)?.name }
                ?: context.getString(R.string.radio_recording_choose_folder_first),
        )
    }
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
        ?.videoId?.takeIf { it.startsWith("radiko:") }?.removePrefix("radiko:")
    val currentOnsenEpisodeId = currentSong?.takeIf { it.isOnsenAudio }
        ?.videoId?.removePrefix("onsen:")

    LaunchedEffect(controller, currentOnsenEpisodeId) {
        val episodeId = currentOnsenEpisodeId ?: return@LaunchedEffect
        val activeController = controller ?: return@LaunchedEffect
        while (true) {
            val position = activeController.currentPosition
            if (position > 0L) OnsenPlaybackPositionStore.save(context, episodeId, position)
            delay(1_000L)
        }
    }

    val exactAlarmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val program = pendingAutoRecordProgram
        if (RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) {
            if (program != null) {
                when (scheduleRadioAutoRecording(context, program, selectedAreaId)) {
                    RadioScheduleResult.SCHEDULED -> {
                        autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }
                        Toast.makeText(context, context.getString(R.string.radio_recording_scheduled_toast, program.title), Toast.LENGTH_LONG).show()
                    }
                    RadioScheduleResult.ENDED -> Toast.makeText(context, R.string.radio_recording_time_passed, Toast.LENGTH_LONG).show()
                    RadioScheduleResult.OVERLAP -> Toast.makeText(context, R.string.radio_recording_overlap, Toast.LENGTH_LONG).show()
                    RadioScheduleResult.ERROR -> Toast.makeText(context, R.string.radio_recording_exact_alarm_denied, Toast.LENGTH_LONG).show()
                }
            } else {
                RadioRecordingFavoriteScheduler.scheduleAllTimeFavorites(context)
                RadioRecordingFavoriteScheduler.requestKeywordRefresh(context)
            }
        } else if (program != null) {
            Toast.makeText(context, R.string.radio_recording_exact_alarm_denied, Toast.LENGTH_LONG).show()
        }
        pendingAutoRecordProgram = null
    }

    val onsenDownloadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val song = pendingOnsenDownload
        pendingOnsenDownload = null
        if (uri != null && song != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) {
                Downloads.enqueueToFolder(context, song, uri)
                Toast.makeText(context, R.string.radio_onsen_download_started, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.radio_onsen_download_folder_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            pendingAutoRecordProgram = null
        } else {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (!persisted) {
                Toast.makeText(context, R.string.radio_recording_folder_unavailable, Toast.LENGTH_LONG).show()
                pendingAutoRecordProgram = null
            } else {
                RadioAutoRecordingStore.setFolderUri(context, uri)
                recordingFolderName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
                if (RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) {
                    RadioRecordingFavoriteScheduler.scheduleAllTimeFavorites(context)
                    RadioRecordingFavoriteScheduler.requestKeywordRefresh(context)
                }
                pendingAutoRecordProgram?.let { program ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) {
                        Toast.makeText(context, R.string.radio_recording_exact_alarm_needed, Toast.LENGTH_LONG).show()
                        exactAlarmLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    } else {
                        when (scheduleRadioAutoRecording(context, program, selectedAreaId)) {
                            RadioScheduleResult.SCHEDULED -> {
                                autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }
                                Toast.makeText(context, context.getString(R.string.radio_recording_scheduled_toast, program.title), Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.ENDED -> {
                                Toast.makeText(context, R.string.radio_recording_time_passed, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.OVERLAP -> {
                                Toast.makeText(context, R.string.radio_recording_overlap, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.ERROR -> {
                                Toast.makeText(context, R.string.radio_recording_folder_unavailable, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                        }
                    }
                } ?: run {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !RadioAutoRecordingScheduler.canScheduleExactAlarms(context) &&
                        RadioRecordingFavoriteStore.favorites(context).isNotEmpty()
                    ) {
                        Toast.makeText(context, R.string.radio_recording_exact_alarm_needed, Toast.LENGTH_LONG).show()
                        exactAlarmLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    } else {
                        Toast.makeText(context, context.getString(R.string.radio_recording_folder_set, recordingFolderName), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(currentStationId) {
        playingStationId = currentStationId
    }

    LaunchedEffect(selectedRadioService, networkUnavailable, refreshOnsenProgramsKey) {
        if (selectedRadioService != RadioService.ONSEN) return@LaunchedEffect
        if (networkUnavailable) {
            hasOnsenProgramsError = true
            isLoadingOnsenPrograms = false
            return@LaunchedEffect
        }
        isLoadingOnsenPrograms = true
        hasOnsenProgramsError = false
        runCatching { OnsenRepository.fetchPrograms(forceRefresh = refreshOnsenProgramsKey > 0) }
            .onSuccess { onsenPrograms = it }
            .onFailure { hasOnsenProgramsError = true }
        isLoadingOnsenPrograms = false
    }

    LaunchedEffect(selectedRadioService, query, networkUnavailable, refreshOnsenProgramsKey) {
        val word = query.trim()
        searchedOnsenPrograms = null
        isSearchingOnsenPrograms = false
        if (selectedRadioService != RadioService.ONSEN || word.isBlank() || networkUnavailable) return@LaunchedEffect
        delay(350)
        isSearchingOnsenPrograms = true
        runCatching { OnsenRepository.searchPrograms(word) }
            .onSuccess { searchedOnsenPrograms = word to it }
        isSearchingOnsenPrograms = false
    }

    LaunchedEffect(selectedOnsenProgram?.id, networkUnavailable, refreshOnsenProgramKey) {
        val program = selectedOnsenProgram ?: return@LaunchedEffect
        if (networkUnavailable) {
            hasOnsenProgramError = true
            isLoadingOnsenProgram = false
            return@LaunchedEffect
        }
        isLoadingOnsenProgram = true
        hasOnsenProgramError = false
        runCatching { OnsenRepository.fetchProgram(program.id) }
            .onSuccess { selectedOnsenProgram = it }
            .onFailure { hasOnsenProgramError = true }
        isLoadingOnsenProgram = false
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

    val playOnsenEpisode: (OnsenEpisode, OnsenProgram) -> Unit = { episode, program ->
        val streamUrl = episode.streamingUrl
        val activeEpisodeId = currentSong?.takeIf { it.isOnsenAudio }?.videoId?.removePrefix("onsen:")
        when {
            activeEpisodeId == episode.id && controller != null && isPlaying -> controller.pause()
            networkUnavailable -> Toast.makeText(context, R.string.radio_offline_unavailable, Toast.LENGTH_SHORT).show()
            !episode.isAudio || streamUrl.isNullOrBlank() -> Toast.makeText(context, R.string.radio_onsen_episode_unavailable, Toast.LENGTH_SHORT).show()
            controller == null -> Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_SHORT).show()
            else -> scope.launch {
                runCatching {
                    val activeController = controller
                    activeController.beginRadioQueue()
                    val song = Song(
                        videoId = "onsen:${episode.id}",
                        title = episode.title.ifBlank { program.title },
                        artist = program.performer ?: program.title,
                        thumbnailUrl = episode.imageUrl ?: program.imageUrl,
                        radioName = program.title,
                        isOnsenAudio = true,
                        radioStreamUrl = streamUrl,
                    )
                    activeController.playSongs(
                        listOf(song),
                        0,
                        startPositionMs = OnsenPlaybackPositionStore.get(context, episode.id),
                    )
                }.onFailure {
                    Toast.makeText(context, R.string.radio_onsen_play_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val downloadOnsenEpisode: (OnsenEpisode, OnsenProgram) -> Unit = { episode, program ->
        val streamUrl = episode.streamingUrl
        when {
            networkUnavailable -> Toast.makeText(context, R.string.radio_offline_unavailable, Toast.LENGTH_SHORT).show()
            !episode.isAudio || streamUrl.isNullOrBlank() ->
                Toast.makeText(context, R.string.radio_onsen_episode_unavailable, Toast.LENGTH_SHORT).show()
            else -> {
                val song = onsenSong(episode, program)
                pendingOnsenDownload = song
                onsenDownloadFolderPicker.launch(null)
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

    if (showServicePicker) {
        RadioServiceDialog(
            selectedService = selectedRadioService,
            amoledBlack = amoledBlack,
            onSelect = { service ->
                AppSettings.setRadioService(service)
                if (service == RadioService.ONSEN) stationDetail = null else selectedOnsenProgram = null
                showServicePicker = false
            },
            onDismiss = { showServicePicker = false },
        )
    }

    if (showRecordingFavorites) {
        RadioRecordingFavoritesDialog(
            favorites = remember(favoritesVersion) { RadioRecordingFavoriteStore.favorites(context) },
            amoledBlack = amoledBlack,
            onRemove = { favorite ->
                RadioRecordingFavoriteStore.remove(context, favorite.id)
                favoritesVersion++
                autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }
            },
            onDismiss = { showRecordingFavorites = false },
        )
    }

    keywordFavoriteStation?.let { favoriteStation ->
        RadioKeywordFavoriteDialog(
            stationName = favoriteStation.name,
            amoledBlack = amoledBlack,
            onChooseFolder = { folderPicker.launch(RadioAutoRecordingStore.folderUri(context)) },
            onDismiss = { keywordFavoriteStation = null },
            onSave = { keyword ->
                if (networkUnavailable) {
                    Toast.makeText(context, R.string.radio_offline_unavailable, Toast.LENGTH_SHORT).show()
                } else if (RadioAutoRecordingStore.folderUri(context) == null) {
                    Toast.makeText(context, R.string.radio_recording_choose_folder_first, Toast.LENGTH_LONG).show()
                    folderPicker.launch(null)
                } else {
                    runCatching {
                        RadioRecordingFavoriteStore.addKeywordFavorite(context, favoriteStation.id, selectedAreaId, keyword)
                    }.onSuccess {
                        favoritesVersion++
                        keywordFavoriteStation = null
                        Toast.makeText(context, R.string.radio_recording_favorite_added, Toast.LENGTH_SHORT).show()
                        if (RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) {
                            RadioRecordingFavoriteScheduler.requestKeywordRefresh(context)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            exactAlarmLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }.onFailure {
                        Toast.makeText(context, R.string.radio_recording_favorite_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    selectedProgram?.let { program ->
        val recordingId = RadioAutoRecordingStore.programId(program.stationId, program.startAt)
        RadioProgramDialog(
            program = program,
            amoledBlack = amoledBlack,
            autoRecordEnabled = !networkUnavailable,
            isRecordingScheduled = recordingId in autoRecordingIds,
            recordingFolderName = recordingFolderName,
            onAutoRecord = {
                if (recordingId in autoRecordingIds) {
                    RadioAutoRecordingScheduler.cancel(context, recordingId)
                    autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }
                    Toast.makeText(context, R.string.radio_recording_cancelled_toast, Toast.LENGTH_SHORT).show()
                } else if (networkUnavailable) {
                    Toast.makeText(context, R.string.radio_offline_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    pendingAutoRecordProgram = program
                    val folder = RadioAutoRecordingStore.folderUri(context)
                    if (folder == null) {
                        folderPicker.launch(null)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !RadioAutoRecordingScheduler.canScheduleExactAlarms(context)) {
                        Toast.makeText(context, R.string.radio_recording_exact_alarm_needed, Toast.LENGTH_LONG).show()
                        exactAlarmLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    } else {
                        when (scheduleRadioAutoRecording(context, program, selectedAreaId)) {
                            RadioScheduleResult.SCHEDULED -> {
                                autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { it.id }
                                Toast.makeText(context, context.getString(R.string.radio_recording_scheduled_toast, program.title), Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.ENDED -> {
                                Toast.makeText(context, R.string.radio_recording_time_passed, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.OVERLAP -> {
                                Toast.makeText(context, R.string.radio_recording_overlap, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                            RadioScheduleResult.ERROR -> {
                                Toast.makeText(context, R.string.radio_recording_folder_unavailable, Toast.LENGTH_LONG).show()
                                pendingAutoRecordProgram = null
                            }
                        }
                    }
                }
            },
            onAddTimeFavorite = { repeat ->
                val favorite = runCatching {
                    RadioRecordingFavoriteStore.addTimeFavorite(context, program, selectedAreaId, repeat)
                }.getOrNull()
                if (favorite == null) {
                    Toast.makeText(context, R.string.radio_recording_favorite_failed, Toast.LENGTH_SHORT).show()
                } else {
                    favoritesVersion++
                    Toast.makeText(context, R.string.radio_recording_favorite_added, Toast.LENGTH_SHORT).show()
                    if (RadioAutoRecordingStore.folderUri(context) == null) {
                        Toast.makeText(context, R.string.radio_recording_choose_folder_first, Toast.LENGTH_LONG).show()
                        folderPicker.launch(null)
                    } else if (!RadioAutoRecordingScheduler.canScheduleExactAlarms(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        exactAlarmLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    } else {
                        RadioRecordingFavoriteScheduler.scheduleNextTimeFavorite(context, favorite.id)
                    }
                }
            },
            onChooseFolder = {
                pendingAutoRecordProgram = null
                folderPicker.launch(RadioAutoRecordingStore.folderUri(context))
            },
            onDismiss = { selectedProgram = null },
        )
    }

    val station = stationDetail
    val onsenProgram = selectedOnsenProgram
    if (onsenProgram != null) {
        OnsenProgramDetail(
            program = onsenProgram,
            amoledBlack = amoledBlack,
            darkTheme = darkTheme,
            isLoading = isLoadingOnsenProgram,
            hasError = hasOnsenProgramError,
            networkUnavailable = networkUnavailable,
            currentEpisodeId = currentOnsenEpisodeId,
            isPlaybackPlaying = isPlaying,
            contentPadding = contentPadding,
            onBack = { selectedOnsenProgram = null },
            onRetry = { refreshOnsenProgramKey++ },
            onPlayEpisode = { episode -> playOnsenEpisode(episode, onsenProgram) },
            onDownloadEpisode = { episode -> downloadOnsenEpisode(episode, onsenProgram) },
        )
    } else if (station == null) {
        val areaName = PrefectureData.prefecture(selectedAreaId)?.name.orEmpty()
        val isRadikoSelected = selectedRadioService == RadioService.RADIKO
        val stations = remember(selectedAreaId, query) {
            StationRegistry.search(query, selectedAreaId)
        }
        val onsenSearch = query.trim()
        val filteredOnsenPrograms = remember(onsenPrograms, onsenSearch) {
            if (onsenSearch.isBlank()) onsenPrograms else onsenPrograms.filter { program ->
                program.title.contains(onsenSearch, ignoreCase = true) ||
                    program.performer.orEmpty().contains(onsenSearch, ignoreCase = true) ||
                    program.description.contains(onsenSearch, ignoreCase = true) ||
                    program.directoryName.contains(onsenSearch, ignoreCase = true)
            }
        }
        val visibleOnsenPrograms = searchedOnsenPrograms
            ?.takeIf { it.first == onsenSearch }
            ?.second
            ?: filteredOnsenPrograms
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
                                text = stringResource(
                                    if (isRadikoSelected) R.string.radio_station_list else R.string.radio_onsen_program_list,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (isRadikoSelected) {
                                    stringResource(R.string.radio_area_current, areaName)
                                } else {
                                    stringResource(R.string.radio_onsen_all_programs)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRadikoSelected) {
                                TextButton(onClick = { showRecordingFavorites = true }) {
                                    Icon(Icons.Rounded.Star, contentDescription = null)
                                    if (!hideRadioTopLabels) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.radio_recording_favorites))
                                    }
                                }
                            }
                            TextButton(onClick = { showServicePicker = true }) {
                                Icon(Icons.Rounded.Radio, contentDescription = null)
                                if (!hideRadioTopLabels) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.radio_change_service))
                                }
                            }
                            if (selectedRadioService == RadioService.RADIKO) {
                                TextButton(onClick = { showRegionPicker = true }) {
                                    Icon(Icons.Rounded.LocationOn, contentDescription = null)
                                if (!hideRadioTopLabels) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.radio_change_area))
                                }
                                }
                            } else {
                                IconButton(onClick = { refreshOnsenProgramsKey++ }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.radio_onsen_refresh))
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = {
                            Text(
                                stringResource(
                                    if (selectedRadioService == RadioService.RADIKO) R.string.radio_search_station
                                    else R.string.radio_onsen_search,
                                ),
                            )
                        },
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
                    currentSong?.takeIf { it.isLiveRadio || it.isOnsenAudio }?.let { song ->
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
            if (selectedRadioService == RadioService.RADIKO && stations.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.radio_no_stations),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (selectedRadioService == RadioService.RADIKO) {
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
            } else if (isLoadingOnsenPrograms) {
                item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (isSearchingOnsenPrograms && visibleOnsenPrograms.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (hasOnsenProgramsError) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.radio_onsen_loading_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { refreshOnsenProgramsKey++ }) { Text(stringResource(R.string.retry)) }
                    }
                }
            } else if (visibleOnsenPrograms.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.radio_onsen_no_programs),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(visibleOnsenPrograms, key = { "onsen-${it.id}" }) { program ->
                    OnsenProgramRow(
                        program = program,
                        amoledBlack = amoledBlack,
                        isPlaying = isPlaying && (
                            program.episodes.any { it.id == currentOnsenEpisodeId } ||
                                program.title == currentSong?.takeIf { it.isOnsenAudio }?.radioName
                            ),
                        onClick = {
                            selectedOnsenProgram = program
                            hasOnsenProgramError = false
                        },
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
            onSelectProgram = {
                autoRecordingIds = RadioAutoRecordingStore.recordings(context).mapTo(mutableSetOf()) { item -> item.id }
                selectedProgram = it
            },
            onAddKeywordFavorite = { keywordFavoriteStation = station },
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
private fun RadioServiceDialog(
    selectedService: RadioService,
    amoledBlack: Boolean,
    onSelect: (RadioService) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.radio_select_service)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedService == RadioService.RADIKO,
                    onClick = { onSelect(RadioService.RADIKO) },
                    label = { Text(stringResource(R.string.radio_service_radiko)) },
                )
                FilterChip(
                    selected = selectedService == RadioService.ONSEN,
                    onClick = { onSelect(RadioService.ONSEN) },
                    label = { Text(stringResource(R.string.radio_service_onsen)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun OnsenProgramRow(
    program: OnsenProgram,
    amoledBlack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (amoledBlack) Color(0xFF171717) else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (program.imageUrl != null) {
                    AsyncImage(
                        model = program.imageUrl,
                        contentDescription = program.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Rounded.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(program.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                program.performer?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isPlaying) Text(stringResource(R.string.radio_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun OnsenProgramDetail(
    program: OnsenProgram,
    amoledBlack: Boolean,
    darkTheme: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    networkUnavailable: Boolean,
    currentEpisodeId: String?,
    isPlaybackPlaying: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayEpisode: (OnsenEpisode) -> Unit,
    onDownloadEpisode: (OnsenEpisode) -> Unit,
) {
    val darkSurface = amoledBlack || darkTheme
    val episodes = remember(program.episodes) { program.episodes.filter(OnsenEpisode::isAudio) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "onsen-detail-heading") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (darkSurface) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        program.imageUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = program.title,
                                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            program.performer?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            program.description.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.radio_onsen_episodes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        when {
            networkUnavailable -> item { RadioScheduleMessage(stringResource(R.string.radio_offline_unavailable)) }
            isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            hasError -> item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.radio_onsen_loading_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            episodes.isEmpty() -> item { RadioScheduleMessage(stringResource(R.string.radio_onsen_episode_list_empty)) }
            else -> items(episodes, key = { "onsen-episode-${it.id}" }) { episode ->
                OnsenEpisodeRow(
                    episode = episode,
                    amoledBlack = amoledBlack,
                    isPlaying = episode.id == currentEpisodeId && isPlaybackPlaying,
                    onClick = { onPlayEpisode(episode) },
                    onDownload = { onDownloadEpisode(episode) },
                )
            }
        }
    }
}

@Composable
private fun OnsenEpisodeRow(
    episode: OnsenEpisode,
    amoledBlack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val canPlay = episode.isAudio && !episode.streamingUrl.isNullOrBlank()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = canPlay, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(episode.title.ifBlank { stringResource(R.string.radio_onsen_episode_play) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                episode.deliveryDate?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    isPlaying -> Text(stringResource(R.string.radio_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    !canPlay -> Text(
                        stringResource(if (episode.isPremium) R.string.radio_onsen_premium_required else R.string.radio_onsen_episode_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClick, enabled = canPlay) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (canPlay) R.string.play else R.string.radio_onsen_episode_unavailable),
                    tint = if (canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload, enabled = canPlay) {
                Icon(
                    Icons.Rounded.FileDownload,
                    contentDescription = stringResource(R.string.radio_onsen_download),
                    tint = if (canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun onsenSong(episode: OnsenEpisode, program: OnsenProgram) = Song(
    videoId = "onsen:${episode.id}",
    title = episode.title.ifBlank { program.title },
    artist = program.performer ?: program.title,
    thumbnailUrl = episode.imageUrl ?: program.imageUrl,
    radioName = program.title,
    isOnsenAudio = true,
    radioStreamUrl = episode.streamingUrl,
)

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
    onAddKeywordFavorite: () -> Unit,
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAddKeywordFavorite, enabled = !networkUnavailable) {
                        Icon(Icons.Rounded.Star, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.radio_recording_keyword_favorite))
                    }
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
private fun RadioProgramDialog(
    program: ProgramEntry,
    amoledBlack: Boolean,
    autoRecordEnabled: Boolean,
    isRecordingScheduled: Boolean,
    recordingFolderName: String,
    onAutoRecord: () -> Unit,
    onAddTimeFavorite: (RadioFavoriteRepeat) -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showTimeFavoriteDialog by remember(program.stationId, program.startAt) { mutableStateOf(false) }
    var selectedRepeat by remember(program.stationId, program.startAt) { mutableStateOf(RadioFavoriteRepeat.WEEKLY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(program.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onAutoRecord, enabled = autoRecordEnabled, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (isRecordingScheduled) R.string.radio_auto_record_scheduled else R.string.radio_auto_record,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onChooseFolder) {
                        Text(stringResource(R.string.radio_recording_choose_folder), maxLines = 1)
                    }
                }
                Text(
                    stringResource(R.string.radio_recording_folder_set, recordingFolderName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { showTimeFavoriteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Star, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.radio_recording_time_favorite))
                }
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

    if (showTimeFavoriteDialog) {
        AlertDialog(
            onDismissRequest = { showTimeFavoriteDialog = false },
            containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.radio_recording_repeat_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.radio_recording_repeat_help))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedRepeat == RadioFavoriteRepeat.WEEKLY,
                            onClick = { selectedRepeat = RadioFavoriteRepeat.WEEKLY },
                            label = { Text(stringResource(R.string.radio_recording_weekly)) },
                        )
                        FilterChip(
                            selected = selectedRepeat == RadioFavoriteRepeat.DAILY,
                            onClick = { selectedRepeat = RadioFavoriteRepeat.DAILY },
                            label = { Text(stringResource(R.string.radio_recording_daily)) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddTimeFavorite(selectedRepeat)
                    showTimeFavoriteDialog = false
                }) { Text(stringResource(R.string.radio_recording_favorite_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimeFavoriteDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RadioKeywordFavoriteDialog(
    stationName: String,
    amoledBlack: Boolean,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var keyword by remember(stationName) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.radio_recording_keyword_favorite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.radio_recording_keyword_help, stationName))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.radio_recording_keyword_label)) },
                )
                TextButton(onClick = onChooseFolder) {
                    Text(stringResource(R.string.radio_recording_choose_folder))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(keyword) }, enabled = keyword.isNotBlank()) {
                Text(stringResource(R.string.radio_recording_favorite_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RadioRecordingFavoritesDialog(
    favorites: List<RadioRecordingFavorite>,
    amoledBlack: Boolean,
    onRemove: (RadioRecordingFavorite) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.radio_recording_favorites)) },
        text = {
            if (favorites.isEmpty()) {
                Text(stringResource(R.string.radio_recording_favorites_empty))
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    favorites.forEach { favorite ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(
                                    text = if (favorite.kind == RadioFavoriteKind.TIME) {
                                        val repeatLabel = stringResource(
                                            if (favorite.repeat == RadioFavoriteRepeat.DAILY) R.string.radio_recording_daily
                                            else R.string.radio_recording_weekly,
                                        )
                                        stringResource(
                                            R.string.radio_recording_time_favorite_item,
                                            favorite.stationName,
                                            favorite.timeOfDay.orEmpty().let { if (it.length >= 4) "${it.take(2)}:${it.substring(2, 4)}" else it },
                                            repeatLabel,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.radio_recording_keyword_favorite_item,
                                            favorite.stationName,
                                            favorite.keyword.orEmpty(),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(favorite.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onRemove(favorite) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.radio_recording_favorite_remove))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private enum class RadioScheduleResult { SCHEDULED, ENDED, OVERLAP, ERROR }

private fun scheduleRadioAutoRecording(
    context: android.content.Context,
    program: ProgramEntry,
    areaId: String,
): RadioScheduleResult {
    return runCatching {
        val station = StationRegistry.getStation(program.stationId) ?: return RadioScheduleResult.ERROR
        val recording = RadioAutoRecordingStore.fromProgram(
            stationId = program.stationId,
            stationName = station.name,
            areaId = areaId,
            title = program.title,
            startAt = program.startAt,
            endAt = program.endAt,
            artworkUrl = program.imageUrl,
        )
        val now = System.currentTimeMillis()
        if (recording.startTimeMs <= now || recording.endTimeMs <= now) return RadioScheduleResult.ENDED
        val overlaps = RadioAutoRecordingStore.recordings(context).any { existing ->
            existing.id != recording.id && recording.startTimeMs < existing.endTimeMs && existing.startTimeMs < recording.endTimeMs
        }
        if (overlaps) return RadioScheduleResult.OVERLAP
        if (RadioAutoRecordingStore.folderUri(context) == null) return RadioScheduleResult.ERROR
        RadioAutoRecordingScheduler.schedule(context, recording)
        RadioScheduleResult.SCHEDULED
    }.getOrDefault(RadioScheduleResult.ERROR)
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
