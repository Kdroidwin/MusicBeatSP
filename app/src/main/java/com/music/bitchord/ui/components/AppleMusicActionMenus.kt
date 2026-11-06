package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.ui.player.ThinSlider
import com.music.bitchord.ui.components.BrowseTarget
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource

/** Apple Music's anchored action panel, backed by BitChord's menu shell and row interaction. */
@Composable
fun AppleMusicTrackActionsMenu(
    song: Song,
    open: Boolean,
    showPlayerActions: Boolean,
    onDismiss: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onChangeArtistImage: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onDeleteDownload: (() -> Unit)?,
    onEqualizer: () -> Unit,
    onPlaybackTuning: () -> Unit,
    onTagEditor: () -> Unit,
    onEditLyrics: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    val menu: @Composable () -> Unit = {
        IosMenuPopover(
            item = song.takeIf { open },
            anchor = null,
            onDismiss = onDismiss,
        ) { selectedSong ->
        var showingSleepTimer by remember(selectedSong.videoId) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            if (showingSleepTimer) {
                AppleSleepTimerMenu(
                    onBack = { showingSleepTimer = false },
                    onFinish = onDismiss,
                )
            } else {
                MenuHeading(
                    title = selectedSong.title,
                    subtitle = selectedSong.artist,
                    artwork = selectedSong.thumbnailUrl,
                )
                IosMenuSeparator()
                MenuRow(R.string.go_to_album, Icons.Rounded.Album, onCloseMenu, onOpenAlbum)
                MenuRow(R.string.go_to_artist, Icons.Rounded.Person, onCloseMenu, onOpenArtist)
                MenuRow(R.string.add_to_playlist, Icons.AutoMirrored.Rounded.PlaylistAdd, onCloseMenu, onAddToPlaylist)
                onChangeArtistImage?.let { MenuRow(R.string.change_artist_image, Icons.Rounded.Image, onCloseMenu, it) }
                if (onDownload != null) MenuRow(R.string.download, Icons.Rounded.FileDownload, onCloseMenu, onDownload)
                if (onDeleteDownload != null) MenuRow(R.string.delete_download, Icons.Rounded.Delete, onCloseMenu, onDeleteDownload)
                IosMenuSeparator()
                MenuRow(R.string.equalizer, Icons.Rounded.GraphicEq, onCloseMenu, onEqualizer)
                MenuRow(R.string.playback_tuning, Icons.Rounded.Speed, onCloseMenu, onPlaybackTuning)
                if (showPlayerActions) {
                    IosMenuRow(
                        label = stringResource(R.string.sleep_timer),
                        icon = Icons.Rounded.Bedtime,
                        onClick = { showingSleepTimer = true },
                    )
                }
                IosMenuSeparator()
                MenuRow(R.string.tag_editor, Icons.Rounded.Edit, onCloseMenu, onTagEditor)
                MenuRow(R.string.edit_lyrics, Icons.AutoMirrored.Rounded.Notes, onCloseMenu, onEditLyrics)
                MenuRow(R.string.details, Icons.Rounded.Info, onCloseMenu, onDetails)
                MenuRow(R.string.share, Icons.Rounded.Share, onCloseMenu, onShare)
                IosMenuSeparator()
                MenuRow(R.string.more, Icons.Rounded.MoreHoriz, onCloseMenu, onMore)
            }
            }
        }
    }
    if (showPlayerActions && open) {
        // MainActivity owns player actions above the page tree, while the
        // player itself is a ModalBottomSheet dialog window. Put this overlay
        // in its own window too, so it is always above the visible player.
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize()) { menu() }
        }
    } else if (!showPlayerActions) {
        menu()
    }
}

@Composable
private fun AppleSleepTimerMenu(onBack: () -> Unit, onFinish: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (true) {
            value = SleepTimer.remainingMs()
            delay(1000)
        }
    }
    val countdown = remaining?.let { ms ->
        val seconds = (ms + 999) / 1000
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
    var customPage by remember { mutableStateOf(false) }
    var customMinutes by remember(chosen) { mutableIntStateOf((chosen ?: 90).coerceIn(SleepTimer.STEP_MINUTES, SleepTimer.MAX_MINUTES)) }

    if (customPage) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TimerHeader(R.string.sleep_timer_custom, stringResource(R.string.pause_after_a_while), onBack)
            HorizontalDivider(thickness = 0.5.dp, color = iosMenuColors().separator)
            Text(
                text = sleepDurationLabel(customMinutes),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = iosMenuColors().label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { customMinutes -= SleepTimer.STEP_MINUTES },
                    enabled = customMinutes > SleepTimer.STEP_MINUTES,
                ) { Text("−", color = iosMenuColors().label, fontSize = 24.sp) }
                ThinSlider(
                    value = SleepTimer.fractionOf(customMinutes),
                    onValueChange = { customMinutes = SleepTimer.minutesAt(it) },
                    idleHeight = 6.dp,
                    activeHeight = 10.dp,
                    activeColor = iosMenuColors().label.copy(alpha = 0.92f),
                    inactiveColor = iosMenuColors().label.copy(alpha = 0.26f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { customMinutes += SleepTimer.STEP_MINUTES },
                    enabled = customMinutes < SleepTimer.MAX_MINUTES,
                ) { Text("+", color = iosMenuColors().label, fontSize = 24.sp) }
            }
            MenuRow(R.string.start_sleep_timer, Icons.Rounded.Bedtime, {}, {
                SleepTimer.start(customMinutes)
                onFinish()
            })
        }
        return
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TimerHeader(
            R.string.sleep_timer,
            when {
                countdown != null -> stringResource(R.string.pause_countdown, countdown)
                afterTrack -> stringResource(R.string.pause_after_song)
                else -> stringResource(R.string.pause_after_a_while)
            },
            onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = iosMenuColors().separator)
        IosMenuRow(
            label = stringResource(R.string.after_this_song),
            icon = if (afterTrack) Icons.Rounded.Check else null,
            onClick = { SleepTimer.startAfterTrack(); onFinish() },
            iconTint = MaterialTheme.colorScheme.primary,
        )
        SleepTimer.PRESETS.forEach { minutes ->
            IosMenuRow(
                label = if (minutes == 60) stringResource(R.string.one_hour)
                else pluralStringResource(R.plurals.minute_count, minutes, minutes),
                icon = if (minutes == chosen) Icons.Rounded.Check else null,
                onClick = { SleepTimer.start(minutes); onFinish() },
                iconTint = MaterialTheme.colorScheme.primary,
            )
        }
        IosMenuRow(
            label = chosen?.takeIf { it !in SleepTimer.PRESETS }?.let {
                stringResource(R.string.sleep_timer_custom) + " · " + sleepDurationLabel(it)
            } ?: stringResource(R.string.sleep_timer_custom),
            icon = Icons.Rounded.Timer,
            onClick = { customPage = true },
        )
        if (chosen != null || afterTrack) {
            IosMenuRow(
                label = stringResource(R.string.turn_off_timer),
                icon = Icons.Rounded.Close,
                iconTint = MaterialTheme.colorScheme.error,
                labelColor = MaterialTheme.colorScheme.error,
                onClick = { SleepTimer.cancel(); onFinish() },
            )
        }
    }
}

@Composable
fun AppleMusicSleepTimerPicker(onDismiss: () -> Unit) {
    AppleSleepTimerMenu(onBack = onDismiss, onFinish = onDismiss)
}

@Composable
private fun TimerHeader(titleRes: Int, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = iosMenuColors().label)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge, color = iosMenuColors().label)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = iosMenuColors().secondaryLabel)
        }
    }
}

@Composable
private fun sleepDurationLabel(minutes: Int): String = if (minutes >= 60) {
    stringResource(R.string.hours_minutes_short, minutes / 60, minutes % 60)
} else {
    stringResource(R.string.minutes_short, minutes)
}

/** The collection menu keeps the high-frequency actions in the popover; More opens the full legacy action set. */
@Composable
fun AppleMusicBrowseActionsMenu(
    target: BrowseTarget,
    open: Boolean,
    onDismiss: () -> Unit,
    onCloseMenu: () -> Unit,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onMore: () -> Unit,
) {
    IosMenuPopover(
        item = target.takeIf { open },
        anchor = null,
        onDismiss = onDismiss,
    ) { selectedTarget ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            MenuHeading(selectedTarget.title, selectedTarget.subtitle, selectedTarget.thumbnailUrl)
            IosMenuSeparator()
            onPlay?.let { MenuRow(R.string.play, Icons.Rounded.PlayArrow, onCloseMenu, it) }
            onShuffle?.let { MenuRow(R.string.shuffle, Icons.Rounded.Shuffle, onCloseMenu, it) }
            MenuRow(R.string.play_next, Icons.AutoMirrored.Rounded.PlaylistPlay, onCloseMenu, onPlayNext)
            MenuRow(R.string.add_to_queue, Icons.AutoMirrored.Rounded.QueueMusic, onCloseMenu, onAddToQueue)
            onAddToPlaylist?.let { MenuRow(R.string.add_to_playlist, Icons.AutoMirrored.Rounded.PlaylistAdd, onCloseMenu, it) }
            onDownloadAll?.let { MenuRow(R.string.download_all, Icons.Rounded.Download, onCloseMenu, it) }
            onOpen?.let { MenuRow(R.string.open, Icons.Rounded.OpenInNew, onCloseMenu, it) }
            IosMenuSeparator()
            MenuRow(R.string.more, Icons.Rounded.MoreHoriz, onCloseMenu, onMore)
        }
    }
}

@Composable
private fun MenuHeading(title: String, subtitle: String, artwork: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!artwork.isNullOrBlank()) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    IosMenuRow(
        label = stringResource(labelRes),
        icon = icon,
        onClick = {
            onDismiss()
            action()
        },
    )
}
