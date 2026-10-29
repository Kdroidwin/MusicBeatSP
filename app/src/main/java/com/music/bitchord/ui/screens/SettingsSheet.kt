package com.music.bitchord.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Waves
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import com.music.bitchord.feature.localmusic.domain.model.LocalFolder
import com.music.bitchord.feature.localmusic.ui.components.BlacklistedFoldersSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.pm.PackageManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.FastScroller
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlayerArtworkMode
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.LocalLibraryTab
import com.music.bitchord.data.settings.LauncherIcon
import com.music.bitchord.data.settings.SeekButtonMode
import com.music.bitchord.data.settings.TransportSideAction
import com.music.bitchord.data.settings.MAX_PLAYER_ARTWORK_BLUR_DP
import com.music.bitchord.data.settings.MAX_PLAYER_BUTTON_SPACING_DP
import com.music.bitchord.data.settings.MainNavigationTab
import com.music.bitchord.data.settings.PlayerControl
import com.music.bitchord.data.settings.PlayerQuickAction
import com.music.bitchord.data.settings.PlayerDetailsAction
import com.music.bitchord.data.settings.MediaControlSecondaryAction
import com.music.bitchord.data.settings.ScreenOrientationMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.LyricsTextAlignment
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.R
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.settings.PillMotionStyle
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.data.M3uImportException
import com.music.bitchord.feature.localmusic.data.M3uImportFailure
import com.music.bitchord.feature.localmusic.data.M3uPlaylistImporter
import com.music.bitchord.feature.localmusic.data.CueSheetImporter
import com.music.bitchord.feature.localmusic.data.PreparedM3uImport
import com.music.bitchord.feature.localmusic.data.MusicoletBackupImporter
import com.music.bitchord.feature.localmusic.data.MusicoletImportException
import com.music.bitchord.feature.localmusic.data.MusicoletImportFailure
import com.music.bitchord.feature.localmusic.data.PreparedMusicoletBackup
import com.music.bitchord.ui.screens.WearOsSettingsRow
import com.music.bitchord.wear.WatchInstaller
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private data class SettingsSearchEntry(
    val title: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
    val checked: Boolean? = null,
    val valueLabel: String? = null,
    val sliderValue: Float? = null,
    val sliderValueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    val sliderSteps: Int = 0,
    val onSliderValueChange: ((Float) -> Unit)? = null,
    val enabled: Boolean = true,
    val activate: () -> Unit,
)

@Composable
private fun musicFolderRootSearchEntry(path: String, onPick: () -> Unit) = SettingsSearchEntry(
    title = stringResource(R.string.music_folder_root),
    subtitle = stringResource(R.string.music_folder_root_subtitle),
    keywords = listOf("folder", "root", "sd card", "storage", "フォルダー", "ルート", "SDカード", "ストレージ"),
    valueLabel = path.ifBlank { stringResource(R.string.music_folder_root_auto) },
    activate = onPick,
)

@Composable
private fun musicServerSearchEntry(onOpen: () -> Unit) = SettingsSearchEntry(
    title = stringResource(R.string.music_servers),
    subtitle = stringResource(R.string.music_servers_subtitle),
    keywords = listOf("Jellyfin", "Navidrome", "OpenSubsonic", "server", "音楽サーバー", "ストリーミング"),
    activate = onOpen,
)

@Composable
private fun autoShowLyricsSearchEntry(enabled: Boolean) = SettingsSearchEntry(
    title = stringResource(R.string.auto_show_lyrics_on_playback),
    subtitle = stringResource(R.string.auto_show_lyrics_on_playback_subtitle),
    keywords = listOf("lyrics", "歌詞", "automatic", "自動取得", "自動表示", "歌詞パネル"),
    checked = enabled,
    activate = { AppSettings.setAutoShowLyricsOnPlayback(!enabled) },
)

@Composable
private fun playerTransportSpacingSearchEntry(spacingDp: Float) = SettingsSearchEntry(
    title = stringResource(R.string.player_transport_button_spacing),
    subtitle = stringResource(R.string.player_transport_button_spacing_subtitle),
    keywords = listOf("player", "transport", "button", "spacing", "gap", "プレーヤー", "操作ボタン", "間隔", "余白"),
    valueLabel = if (spacingDp <= 0f) {
        stringResource(R.string.player_transport_spacing_auto)
    } else {
        stringResource(R.string.player_transport_spacing_value, spacingDp.roundToInt())
    },
    sliderValue = spacingDp,
    sliderValueRange = 0f..MAX_PLAYER_BUTTON_SPACING_DP,
    sliderSteps = MAX_PLAYER_BUTTON_SPACING_DP.toInt() - 1,
    onSliderValueChange = AppSettings::setPlayerTransportButtonSpacingDp,
    activate = {},
)

@Composable
private fun playerActionSpacingSearchEntry(spacingDp: Float) = SettingsSearchEntry(
    title = stringResource(R.string.player_action_button_spacing),
    subtitle = stringResource(R.string.player_action_button_spacing_subtitle),
    keywords = listOf("player", "action", "button", "spacing", "gap", "プレーヤー", "下部", "操作ボタン", "間隔", "余白"),
    valueLabel = if (spacingDp <= 0f) {
        stringResource(R.string.player_transport_spacing_auto)
    } else {
        stringResource(R.string.player_transport_spacing_value, spacingDp.roundToInt())
    },
    sliderValue = spacingDp,
    sliderValueRange = 0f..MAX_PLAYER_BUTTON_SPACING_DP,
    sliderSteps = MAX_PLAYER_BUTTON_SPACING_DP.toInt() - 1,
    onSliderValueChange = AppSettings::setPlayerActionButtonSpacingDp,
    activate = {},
)

@Composable
private fun playerPlayPauseCornerRoundnessSearchEntry(roundnessPercent: Int) = SettingsSearchEntry(
    title = stringResource(R.string.player_play_pause_corner_roundness),
    subtitle = stringResource(R.string.player_play_pause_corner_roundness_subtitle),
    keywords = listOf("player", "play", "pause", "button", "corner", "roundness", "shape", "角", "丸み", "丸角", "再生ボタン"),
    valueLabel = stringResource(R.string.player_corner_roundness_value, roundnessPercent),
    sliderValue = roundnessPercent.toFloat(),
    sliderValueRange = 0f..100f,
    sliderSteps = 99,
    onSliderValueChange = { AppSettings.setPlayerPlayPauseCornerRadiusPercent(it.roundToInt()) },
    activate = {},
)

@Composable
private fun PlayerArtworkMode.localizedLabel(): String = stringResource(
    when (this) {
        PlayerArtworkMode.FULL_SCREEN -> R.string.artwork_mode_full_screen
        PlayerArtworkMode.FULL_SCREEN_LARGER -> R.string.artwork_mode_full_screen_larger
        PlayerArtworkMode.AURIS_FULL_SCREEN -> R.string.artwork_mode_auris_full_screen
        PlayerArtworkMode.STANDARD_SQUARE -> R.string.artwork_mode_standard_square
        PlayerArtworkMode.STANDARD_ORIGINAL_ASPECT -> R.string.artwork_mode_standard_original_aspect
    },
)

@Composable
private fun playerArtworkModeSearchEntry(mode: PlayerArtworkMode, selectedMode: PlayerArtworkMode) =
    SettingsSearchEntry(
        title = mode.localizedLabel(),
        subtitle = stringResource(R.string.player_artwork_mode_subtitle),
        keywords = listOf("player", "artwork", "cover", "full screen", "Auris", "square", "aspect ratio", "プレイヤー", "アルバムアート", "全画面", "正方形", "縦横比"),
        checked = selectedMode == mode,
        activate = { AppSettings.setPlayerArtworkMode(mode) },
    )

@Composable
private fun fullBleedArtworkBottomBlurSearchEntry(strengthDp: Int) = SettingsSearchEntry(
    title = stringResource(R.string.full_screen_artwork_bottom_blur_strength),
    subtitle = stringResource(R.string.full_screen_artwork_bottom_blur_strength_subtitle),
    keywords = listOf("artwork", "cover", "blur", "gradient", "full screen", "アート", "カバー", "ぼかし", "グラデーション", "全画面", "強さ"),
    valueLabel = stringResource(R.string.artwork_blur_strength_value, strengthDp),
    sliderValue = strengthDp.toFloat(),
    sliderValueRange = 0f..64f,
    sliderSteps = 63,
    onSliderValueChange = { AppSettings.setFullBleedArtworkBottomBlurStrengthDp(it.roundToInt()) },
    activate = {},
)

@Composable
private fun FullBleedArtworkBottomBlurSettingsRow(strengthDp: Int) {
    SettingsRow(
        icon = Icons.Rounded.BlurOn,
        title = stringResource(R.string.full_screen_artwork_bottom_blur_strength),
        subtitle = stringResource(R.string.full_screen_artwork_bottom_blur_strength_subtitle),
        value = stringResource(R.string.artwork_blur_strength_value, strengthDp),
    )
    Slider(
        value = strengthDp.toFloat(),
        onValueChange = { AppSettings.setFullBleedArtworkBottomBlurStrengthDp(it.roundToInt()) },
        steps = 63,
        valueRange = 0f..64f,
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
    )
}

@Composable
private fun appleMusicLyricsButtonStyleSearchEntry(enabled: Boolean) = SettingsSearchEntry(
    title = stringResource(R.string.apple_music_lyrics_button_style),
    subtitle = stringResource(R.string.apple_music_button_style_subtitle),
    keywords = listOf("player", "lyrics", "Apple Music", "button", "style", "プレーヤー", "歌詞", "Apple Music風", "アイコン"),
    checked = enabled,
    activate = { AppSettings.setAppleMusicLyricsButtonStyle(!enabled) },
)

@Composable
private fun appleMusicChromecastButtonStyleSearchEntry(enabled: Boolean) = SettingsSearchEntry(
    title = stringResource(R.string.apple_music_chromecast_button_style),
    subtitle = stringResource(R.string.apple_music_button_style_subtitle),
    keywords = listOf("player", "chromecast", "cast", "Apple Music", "button", "style", "プレーヤー", "キャスト", "Apple Music風", "アイコン"),
    checked = enabled,
    activate = { AppSettings.setAppleMusicChromecastButtonStyle(!enabled) },
)

@Composable
private fun appleMusicQueueButtonStyleSearchEntry(enabled: Boolean) = SettingsSearchEntry(
    title = stringResource(R.string.apple_music_queue_button_style),
    subtitle = stringResource(R.string.apple_music_button_style_subtitle),
    keywords = listOf("player", "queue", "up next", "Apple Music", "button", "style", "プレーヤー", "キュー", "再生キュー", "Apple Music風", "アイコン"),
    checked = enabled,
    activate = { AppSettings.setAppleMusicQueueButtonStyle(!enabled) },
)

@Composable
private fun PlayerActionButtonStyleRow(
    icon: ImageVector,
    titleRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = stringResource(titleRes),
        subtitle = stringResource(R.string.apple_music_button_style_subtitle),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onAppLanguage: () -> Unit,
    onPreloadArtworkNow: () -> Unit,
    onPlayCueSheet: (List<com.music.bitchord.data.model.Song>) -> Unit,
    onManageSources: () -> Unit,
    onWearOs: () -> Unit,
    onCdRipComplete: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val musicFolderRootPicker = rememberMusicFolderRootPicker(AppSettings::setLocalMusicFolderPath)
    val settingsScrollState = rememberScrollState()
    val popupOverlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = Settings.canDrawOverlays(context)
        AppSettings.setPopupExternalAudioPlayer(granted)
        if (!granted) {
            Toast.makeText(context, R.string.popup_overlay_access_not_granted, Toast.LENGTH_SHORT).show()
        }
    }
    val setPopupExternalAudioPlayerWithPermission: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> AppSettings.setPopupExternalAudioPlayer(false)
            Settings.canDrawOverlays(context) -> AppSettings.setPopupExternalAudioPlayer(true)
            else -> runCatching {
                popupOverlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }.onFailure {
                Toast.makeText(context, R.string.popup_overlay_access_not_granted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val audioOffloadEnabled by AppSettings.audioOffloadEnabled.collectAsStateWithLifecycle()
    val crossfadeSeconds by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    val diracAudioEnabled by AppSettings.diracAudioEnabled.collectAsStateWithLifecycle()
    val doubleTapToSeek by AppSettings.doubleTapToSeek.collectAsStateWithLifecycle()
    val seekIntervalSeconds by AppSettings.seekIntervalSeconds.collectAsStateWithLifecycle()
    val recentlyPlayedThresholdSeconds by AppSettings.recentlyPlayedThresholdSeconds.collectAsStateWithLifecycle()
    val seekButtonMode by AppSettings.seekButtonMode.collectAsStateWithLifecycle()
    val transportLeftAction by AppSettings.transportLeftAction.collectAsStateWithLifecycle()
    val transportRightAction by AppSettings.transportRightAction.collectAsStateWithLifecycle()
    val hideSeekButtonLabels by AppSettings.hideSeekButtonLabels.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val playerArtworkTransition by AppSettings.playerArtworkTransition.collectAsStateWithLifecycle()
    val walkmanPlayerStyle by AppSettings.walkmanPlayerStyle.collectAsStateWithLifecycle()
    val pillMotionStyle by AppSettings.pillMotionStyle.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val classicNavBar by AppSettings.classicNavBar.collectAsStateWithLifecycle()
    val mainNavigationTabsPopup by AppSettings.mainNavigationTabsPopup.collectAsStateWithLifecycle()
    val settingsTopBarUsesMoreIcon by AppSettings.settingsTopBarUsesMoreIcon.collectAsStateWithLifecycle()
    val hideNavBarLabels by AppSettings.hideNavigationBarLabels.collectAsStateWithLifecycle()
    val navigationBarMatchesMiniPlayerColor by AppSettings.navigationBarMatchesMiniPlayerColor.collectAsStateWithLifecycle()
    val includeRemoteSourceMediaInLibrary by AppSettings.includeRemoteSourceMediaInLibrary.collectAsStateWithLifecycle()
    val visibleMainTabs by AppSettings.visibleMainNavigationTabs.collectAsStateWithLifecycle()
    val mainTabOrder by AppSettings.mainNavigationTabOrder.collectAsStateWithLifecycle()
    val localTabs by AppSettings.visibleLocalLibraryTabs.collectAsStateWithLifecycle()
    val localTabOrder by AppSettings.localLibraryTabOrder.collectAsStateWithLifecycle()
    val localFolderHierarchy by AppSettings.localFolderHierarchy.collectAsStateWithLifecycle()
    val localMusicFolderRoot by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val fullScreenCollectionArtwork by AppSettings.fullScreenCollectionArtwork.collectAsStateWithLifecycle()
    val whiteCollectionActionButtons by AppSettings.whiteCollectionActionButtons.collectAsStateWithLifecycle()
    val tabletLyricsBesidePlayer by AppSettings.tabletLyricsBesidePlayer.collectAsStateWithLifecycle()
    val hideCollectionTopBar by AppSettings.hideCollectionTopBar.collectAsStateWithLifecycle()
    val hideFloatingCollectionControls by AppSettings.hideFloatingCollectionControls.collectAsStateWithLifecycle()
    val frostedQueueManager by AppSettings.frostedQueueManager.collectAsStateWithLifecycle()
    val redesignedSavedQueueManager by AppSettings.redesignedSavedQueueManager.collectAsStateWithLifecycle()
    val hideSavedQueueHeading by AppSettings.hideSavedQueueHeading.collectAsStateWithLifecycle()
    val frostedPlayerDetails by AppSettings.frostedPlayerDetails.collectAsStateWithLifecycle()
    val detailsSheetArtworkBackdrop by AppSettings.detailsSheetArtworkBackdrop.collectAsStateWithLifecycle()
    val showEmbeddedComment by AppSettings.showEmbeddedComment.collectAsStateWithLifecycle()
    val queueManagerEqualsIcon by AppSettings.queueManagerEqualsIcon.collectAsStateWithLifecycle()
    val hideQueueLabel by AppSettings.hideQueueLabel.collectAsStateWithLifecycle()
    val queueClearTrashIcon by AppSettings.queueClearTrashIcon.collectAsStateWithLifecycle()
    val hidePlaybackTuningTitle by AppSettings.hidePlaybackTuningTitle.collectAsStateWithLifecycle()
    val playbackTuningThinSliders by AppSettings.playbackTuningThinSliders.collectAsStateWithLifecycle()
    val launcherIcon by AppSettings.launcherIcon.collectAsStateWithLifecycle()
    val artistsOpenAlbums by AppSettings.artistsOpenAlbums.collectAsStateWithLifecycle()
    val shuffleArtistAlbums by AppSettings.shuffleArtistAlbums.collectAsStateWithLifecycle()
    val onlineAlbumArtistArtwork by AppSettings.onlineAlbumArtistArtwork.collectAsStateWithLifecycle()
    val startupTab by AppSettings.startupTab.collectAsStateWithLifecycle()
    val quickActions by AppSettings.playerQuickActions.collectAsStateWithLifecycle()
    val hidePlayerQuickActionCircles by AppSettings.hidePlayerQuickActionCircles.collectAsStateWithLifecycle()
    val hiddenPlayerDetailsActions by AppSettings.hiddenPlayerDetailsActions.collectAsStateWithLifecycle()
    val audioCutterEnabled by AppSettings.audioCutterEnabled.collectAsStateWithLifecycle()
    val artworkBackdropBlurDp by AppSettings.artworkBackdropBlurDp.collectAsStateWithLifecycle()
    val keepScreenOn by AppSettings.keepScreenOn.collectAsStateWithLifecycle()
    val keepScreenOnDuringLyrics by AppSettings.keepScreenOnDuringLyrics.collectAsStateWithLifecycle()
    val showStatusBar by AppSettings.showStatusBar.collectAsStateWithLifecycle()
    val showPlayerStatusBar by AppSettings.showPlayerStatusBar.collectAsStateWithLifecycle()
    val showNavigationBar by AppSettings.showNavigationBar.collectAsStateWithLifecycle()
    val musicOnlyDeviceMode by AppSettings.musicOnlyDeviceMode.collectAsStateWithLifecycle()
    val orientationMode by AppSettings.screenOrientationMode.collectAsStateWithLifecycle()
    val favoriteUsesStar by AppSettings.favoriteUsesStar.collectAsStateWithLifecycle()
    val mediaControlFavoriteUsesStar by AppSettings.mediaControlFavoriteUsesStar.collectAsStateWithLifecycle()
    val mediaControlSecondaryAction by AppSettings.mediaControlSecondaryAction.collectAsStateWithLifecycle()
    val hideMediaControlCustomActions by AppSettings.hideMediaControlCustomActions.collectAsStateWithLifecycle()
    val restartQueueWhenEnded by AppSettings.restartQueueWhenEnded.collectAsStateWithLifecycle()
    val advanceQueueWhenRepeatOff by AppSettings.advanceQueueWhenRepeatOff.collectAsStateWithLifecycle()
    val persistentLocalArtwork by AppSettings.persistentLocalArtwork.collectAsStateWithLifecycle()
    val resamplerCutoffHz by AppSettings.resamplerCutoffHz.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val volumeBoostEnabled by AppSettings.volumeBoostEnabled.collectAsStateWithLifecycle()
    val volumeBoostDb by AppSettings.volumeBoostDb.collectAsStateWithLifecycle()
    val pillWidgetOpacity by AppSettings.pillWidgetOpacity.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsFontScale by AppSettings.lyricsFontScale.collectAsStateWithLifecycle()
    val lyricsTextAlignment by AppSettings.lyricsTextAlignment.collectAsStateWithLifecycle()
    val showLyricsFurigana by AppSettings.showLyricsFurigana.collectAsStateWithLifecycle()
    val autoFitOneLineLyrics by AppSettings.autoFitOneLineLyrics.collectAsStateWithLifecycle()
    val preferEmbeddedLocalLyrics by AppSettings.preferEmbeddedLocalLyrics.collectAsStateWithLifecycle()
    val showLyricsCloseButton by AppSettings.showLyricsCloseButton.collectAsStateWithLifecycle()
    val hideLyricsStatusText by AppSettings.hideLyricsStatusText.collectAsStateWithLifecycle()
    val hideLyricsSavedMessage by AppSettings.hideLyricsSavedMessage.collectAsStateWithLifecycle()
    val hideLyricsUnavailableLabel by AppSettings.hideLyricsUnavailableLabel.collectAsStateWithLifecycle()
    val hideLyricsGapNote by AppSettings.hideLyricsGapNote.collectAsStateWithLifecycle()
    val showLyricsPreviousControl by AppSettings.showLyricsPreviousControl.collectAsStateWithLifecycle()
    val showLyricsPlayPauseControl by AppSettings.showLyricsPlayPauseControl.collectAsStateWithLifecycle()
    val showLyricsNextControl by AppSettings.showLyricsNextControl.collectAsStateWithLifecycle()
    val lyricsTransportControlSize by AppSettings.lyricsTransportControlSize.collectAsStateWithLifecycle()
    val lyricsTransportControlsCentered by AppSettings.lyricsTransportControlsCentered.collectAsStateWithLifecycle()
    val artworkTapOpensLyrics by AppSettings.artworkTapOpensLyrics.collectAsStateWithLifecycle()
    val lyricsReplaceArtworkOnly by AppSettings.lyricsReplaceArtworkOnly.collectAsStateWithLifecycle()
    val hidePlayerHeaderDuringLyrics by AppSettings.hidePlayerHeaderDuringLyrics.collectAsStateWithLifecycle()
    val showPlayerLyricsStrip by AppSettings.showPlayerLyricsStrip.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val youtubeMusicEnabled by AppSettings.youtubeMusicEnabled.collectAsStateWithLifecycle()
    val youtubeMusicSearchDefault by AppSettings.youtubeMusicSearchDefault.collectAsStateWithLifecycle()
    val includeYoutubeMusicVideosInSearch by AppSettings.includeYoutubeMusicVideosInSearch.collectAsStateWithLifecycle()
    val hideLosslessLabel by AppSettings.hideLosslessLabel.collectAsStateWithLifecycle()
    val showTrackDurationInsteadOfRemaining by AppSettings.showTrackDurationInsteadOfRemaining.collectAsStateWithLifecycle()
    val popupExternalAudioPlayer by AppSettings.popupExternalAudioPlayer.collectAsStateWithLifecycle()
    val preventPlayAtZeroVolume by AppSettings.preventPlayAtZeroVolume.collectAsStateWithLifecycle()
    val pausePlaybackAtZeroVolume by AppSettings.pausePlaybackAtZeroVolume.collectAsStateWithLifecycle()
    val useLibraryIconForPlaylistControl by AppSettings.useLibraryIconForPlaylistControl.collectAsStateWithLifecycle()
    val visiblePlayerControls by AppSettings.visiblePlayerControls.collectAsStateWithLifecycle()
    val playerControlOrder by AppSettings.playerControlOrder.collectAsStateWithLifecycle()
    val appleMusicLyricsButtonStyle by AppSettings.appleMusicLyricsButtonStyle.collectAsStateWithLifecycle()
    val appleMusicChromecastButtonStyle by AppSettings.appleMusicChromecastButtonStyle.collectAsStateWithLifecycle()
    val appleMusicQueueButtonStyle by AppSettings.appleMusicQueueButtonStyle.collectAsStateWithLifecycle()
    val hidePlayerArtist by AppSettings.hidePlayerArtist.collectAsStateWithLifecycle()
    val hideUnknownPlayerArtist by AppSettings.hideUnknownPlayerArtist.collectAsStateWithLifecycle()
    val centerPlayerTrackInfo by AppSettings.centerPlayerTrackInfo.collectAsStateWithLifecycle()
    val playerDetailsVerticalMenu by AppSettings.playerDetailsVerticalMenu.collectAsStateWithLifecycle()
    val preloadAlbumArtOnStartup by AppSettings.preloadAlbumArtOnStartup.collectAsStateWithLifecycle()
    val playerArtworkMode by AppSettings.playerArtworkMode.collectAsStateWithLifecycle()
    val fullBleedArtworkBottomBlurStrengthDp by AppSettings.fullBleedArtworkBottomBlurStrengthDp.collectAsStateWithLifecycle()
    val preserveArtworkShapeInCompactTransitions by AppSettings.preserveArtworkShapeInCompactTransitions.collectAsStateWithLifecycle()
    val keepArtworkFullSizeWhenPaused by AppSettings.keepArtworkFullSizeWhenPaused.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val autoEmbedLyrics by AppSettings.autoEmbedLyrics.collectAsStateWithLifecycle()
    val autoShowLyricsOnPlayback by AppSettings.autoShowLyricsOnPlayback.collectAsStateWithLifecycle()
    val lyricsExtensionsEnabled by AppSettings.lyricsExtensionsEnabled.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val showLyricsLogs by AppSettings.showLyricsLogs.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val customThemeImageUri by AppSettings.customThemeImageUri.collectAsStateWithLifecycle()
    val matchButtonAccentToArtwork by AppSettings.matchButtonAccentToArtwork.collectAsStateWithLifecycle()
    val miniPlayerArtworkColor by AppSettings.miniPlayerArtworkColor.collectAsStateWithLifecycle()
    val miniPlayerArtworkOutline by AppSettings.miniPlayerArtworkOutline.collectAsStateWithLifecycle()
    val miniPlayerPreviousButton by AppSettings.miniPlayerPreviousButton.collectAsStateWithLifecycle()
    val miniPlayerSwipeChangesTrack by AppSettings.miniPlayerSwipeChangesTrack.collectAsStateWithLifecycle()
    val miniPlayerSwipeUpExpands by AppSettings.miniPlayerSwipeUpExpands.collectAsStateWithLifecycle()
    val playlistTrackMenuUsesArtworkColor by AppSettings.playlistTrackMenuUsesArtworkColor.collectAsStateWithLifecycle()
    val playerTitleFontSizeSp by AppSettings.playerTitleFontSizeSp.collectAsStateWithLifecycle()
    val playerArtistFontSizeSp by AppSettings.playerArtistFontSizeSp.collectAsStateWithLifecycle()
    val playerPlayPauseIconSizeDp by AppSettings.playerPlayPauseIconSizeDp.collectAsStateWithLifecycle()
    val playerPlayPauseCornerRadiusPercent by AppSettings.playerPlayPauseCornerRadiusPercent.collectAsStateWithLifecycle()
    val playerTransportButtonSpacingDp by AppSettings.playerTransportButtonSpacingDp.collectAsStateWithLifecycle()
    val playerActionButtonSpacingDp by AppSettings.playerActionButtonSpacingDp.collectAsStateWithLifecycle()
    val showPlayerTopGrabber by AppSettings.showPlayerTopGrabber.collectAsStateWithLifecycle()
    val cleartextHttpAllowed by AppSettings.cleartextHttpAllowed.collectAsStateWithLifecycle()
    val parametricEqEnabled by AppSettings.parametricEqEnabled.collectAsStateWithLifecycle()
    val tpdfDitherEnabled by AppSettings.tpdfDitherEnabled.collectAsStateWithLifecycle()
    val channelSwapEnabled by AppSettings.channelSwapEnabled.collectAsStateWithLifecycle()
    val reverbEnabled by AppSettings.reverbEnabled.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val bitPerfectPlayback by AppSettings.bitPerfectPlayback.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val speakerPlaybackAllowed by AppSettings.speakerPlaybackAllowed.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val sourceAudioStats by NerdStats.current.collectAsStateWithLifecycle()
    val availableSystemAudioEffects = remember {
        runCatching {
            AudioEffect.queryEffects().orEmpty()
                .mapNotNull { descriptor ->
                    val name = descriptor.name?.trim().orEmpty()
                    val maker = descriptor.implementor?.trim().orEmpty()
                    when {
                        name.isBlank() -> null
                        maker.isBlank() -> name
                        else -> "$name ($maker)"
                    }
                }
                .distinct()
                .take(4)
        }.getOrDefault(emptyList())
    }
    var transportActionSlot by rememberSaveable { mutableStateOf("") }
    val bitPerfectOutputStatus = stringResource(
        if (outputStatus.bitPerfectMixerActive) R.string.bit_perfect_mixer_requested
        else R.string.bit_perfect_mixer_unavailable,
    )
    val audioFocusLevel by AppSettings.audioFocusLevel.collectAsStateWithLifecycle()
    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideRadioPlaybackBar by AppSettings.hideRadioPlaybackBar.collectAsStateWithLifecycle()
    val playerSeekBarThicknessDp by AppSettings.playerSeekBarThicknessDp.collectAsStateWithLifecycle()
    val playerVolumeBarThicknessDp by AppSettings.playerVolumeBarThicknessDp.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val showPlaylistSongArtwork by AppSettings.showPlaylistSongArtwork.collectAsStateWithLifecycle()
    val hidePlaylistPlayButtonLabel by AppSettings.hidePlaylistPlayButtonLabel.collectAsStateWithLifecycle()
    val showCollectionSongArtwork by AppSettings.showCollectionSongArtwork.collectAsStateWithLifecycle()
    val keepCollectionArtworkAspectRatio by AppSettings.keepCollectionArtworkAspectRatio.collectAsStateWithLifecycle()
    val localizeLibraryCollectionCount by AppSettings.localizeLibraryCollectionCount.collectAsStateWithLifecycle()
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var showLauncherIconPicker by rememberSaveable { mutableStateOf(false) }
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var showMediaControlActionPicker by rememberSaveable { mutableStateOf(false) }
    var showPlayerArtworkModePicker by rememberSaveable { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showAutomixPerformanceSheet by rememberSaveable { mutableStateOf(false) }
    var showCdRippingSheet by rememberSaveable { mutableStateOf(false) }
    val themeImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected == null) return@rememberLauncherForActivityResult
        val retained = runCatching {
            context.contentResolver.takePersistableUriPermission(
                selected,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (retained) {
            customThemeImageUri
                ?.takeIf { it != selected.toString() }
                ?.let { old ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(old),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            AppSettings.setCustomThemeImageUri(selected.toString())
            AppSettings.setThemeMode(ThemeMode.CUSTOM_IMAGE)
        } else {
            Toast.makeText(context, R.string.theme_image_permission_failed, Toast.LENGTH_LONG).show()
        }
    }
    val chooseThemeImage: () -> Unit = { themeImagePicker.launch(arrayOf("image/*")) }

    @Composable
    fun buildSettingsSearchEntries(): List<SettingsSearchEntry> {
        return listOf(
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_status_text), stringResource(R.string.hide_lyrics_status_text_subtitle), listOf("lyrics", "歌詞", "状態", "status"), hideLyricsStatusText) { AppSettings.setHideLyricsStatusText(!hideLyricsStatusText) },
        SettingsSearchEntry(stringResource(R.string.show_lyrics_close_button), stringResource(R.string.show_lyrics_close_button_subtitle), listOf("lyrics", "歌詞", "close", "閉じる", "×"), showLyricsCloseButton) { AppSettings.setShowLyricsCloseButton(!showLyricsCloseButton) },
        SettingsSearchEntry(stringResource(R.string.prefer_embedded_local_lyrics), stringResource(R.string.prefer_embedded_local_lyrics_subtitle), listOf("lyrics", "歌詞", "embedded", "埋め込み", "downloaded", "優先順位", "lrc"), preferEmbeddedLocalLyrics) { AppSettings.setPreferEmbeddedLocalLyrics(!preferEmbeddedLocalLyrics) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_saved_message), stringResource(R.string.hide_lyrics_saved_message_subtitle), listOf("lyrics", "歌詞", "保存済み", "downloaded"), hideLyricsSavedMessage) { AppSettings.setHideLyricsSavedMessage(!hideLyricsSavedMessage) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_unavailable_label), stringResource(R.string.hide_lyrics_unavailable_label_subtitle), listOf("lyrics", "歌詞", "ありません", "unavailable"), hideLyricsUnavailableLabel) { AppSettings.setHideLyricsUnavailableLabel(!hideLyricsUnavailableLabel) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_gap_note), stringResource(R.string.hide_lyrics_gap_note_subtitle), listOf("lyrics", "歌詞", "音符", "gap", "instrumental"), hideLyricsGapNote) { AppSettings.setHideLyricsGapNote(!hideLyricsGapNote) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_previous), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "previous", "前の曲"), showLyricsPreviousControl) { AppSettings.setShowLyricsPreviousControl(!showLyricsPreviousControl) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_play_pause), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "play", "pause", "再生", "一時停止"), showLyricsPlayPauseControl) { AppSettings.setShowLyricsPlayPauseControl(!showLyricsPlayPauseControl) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_next), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "next", "次の曲"), showLyricsNextControl) { AppSettings.setShowLyricsNextControl(!showLyricsNextControl) },
        SettingsSearchEntry(stringResource(R.string.lyrics_transport_controls_centered), stringResource(R.string.lyrics_transport_controls_centered_subtitle), listOf("lyrics", "歌詞", "center", "中央揃え", "ボタン"), lyricsTransportControlsCentered) { AppSettings.setLyricsTransportControlsCentered(!lyricsTransportControlsCentered) },
        SettingsSearchEntry(
            title = stringResource(R.string.lyrics_transport_control_size),
            subtitle = stringResource(R.string.lyrics_transport_control_size_subtitle),
            keywords = listOf("lyrics", "歌詞", "button", "ボタン", "size", "サイズ", "transport"),
            valueLabel = stringResource(R.string.lyrics_transport_control_size_value, lyricsTransportControlSize.roundToInt()),
            sliderValue = lyricsTransportControlSize,
            sliderValueRange = 14f..64f,
            sliderSteps = 49,
            onSliderValueChange = AppSettings::setLyricsTransportControlSize,
            activate = {},
        ),
        SettingsSearchEntry(stringResource(R.string.hide_lossless_label), stringResource(R.string.hide_lossless_label_subtitle), listOf("lossless", "quality", "ロスレス", "高音質"), hideLosslessLabel) { AppSettings.setHideLosslessLabel(!hideLosslessLabel) },
        SettingsSearchEntry(
            stringResource(R.string.frosted_queue_manager),
            stringResource(R.string.frosted_queue_manager_subtitle),
            listOf("queue", "glass", "frosted", "blur", "再生キュー", "すりガラス", "ぼかし"),
            frostedQueueManager,
        ) { AppSettings.setFrostedQueueManager(!frostedQueueManager) },
        SettingsSearchEntry(
            stringResource(R.string.redesigned_saved_queue_manager),
            stringResource(R.string.redesigned_saved_queue_manager_subtitle),
            listOf("queue", "saved queues", "manager", "再生キュー", "保存済みキュー", "管理画面"),
            redesignedSavedQueueManager,
        ) { AppSettings.setRedesignedSavedQueueManager(!redesignedSavedQueueManager) },
        SettingsSearchEntry(
            stringResource(R.string.hide_saved_queue_heading),
            stringResource(R.string.hide_saved_queue_heading_subtitle),
            listOf("queue", "save queue", "heading", "再生キュー", "現在のキュー", "見出し"),
            hideSavedQueueHeading,
        ) { AppSettings.setHideSavedQueueHeading(!hideSavedQueueHeading) },
        SettingsSearchEntry(
            stringResource(R.string.frosted_player_details),
            stringResource(R.string.frosted_player_details_subtitle),
            listOf("player", "details", "glass", "frosted", "プレーヤー", "詳細", "すりガラス"),
            frostedPlayerDetails,
        ) { AppSettings.setFrostedPlayerDetails(!frostedPlayerDetails) },
        SettingsSearchEntry(
            stringResource(R.string.details_sheet_artwork_backdrop),
            stringResource(R.string.details_sheet_artwork_backdrop_subtitle),
            listOf("metadata", "file information", "details", "artwork", "メタデータ", "ファイル情報", "詳細", "アートワーク"),
            detailsSheetArtworkBackdrop,
        ) { AppSettings.setDetailsSheetArtworkBackdrop(!detailsSheetArtworkBackdrop) },
        SettingsSearchEntry(
            stringResource(R.string.show_embedded_comment),
            stringResource(R.string.show_embedded_comment_subtitle),
            listOf("comment", "embedded tag", "metadata", "コメント", "埋め込み", "タグ", "詳細"),
            showEmbeddedComment,
        ) { AppSettings.setShowEmbeddedComment(!showEmbeddedComment) },
        SettingsSearchEntry(
            stringResource(R.string.queue_manage_equals_icon),
            stringResource(R.string.queue_manage_equals_icon_subtitle),
            listOf("queue", "manage", "equals", "icon", "再生キュー", "管理", "イコール", "アイコン"),
            queueManagerEqualsIcon,
        ) { AppSettings.setQueueManagerEqualsIcon(!queueManagerEqualsIcon) },
        SettingsSearchEntry(
            stringResource(R.string.hide_queue_label),
            stringResource(R.string.hide_queue_label_subtitle),
            listOf("queue", "label", "hide", "再生キュー", "ラベル", "非表示"),
            hideQueueLabel,
        ) { AppSettings.setHideQueueLabel(!hideQueueLabel) },
        SettingsSearchEntry(
            stringResource(R.string.queue_clear_trash_icon),
            stringResource(R.string.queue_clear_trash_icon_subtitle),
            listOf("queue", "clear", "trash", "delete", "再生キュー", "消去", "ゴミ箱", "アイコン"),
            queueClearTrashIcon,
        ) { AppSettings.setQueueClearTrashIcon(!queueClearTrashIcon) },
        SettingsSearchEntry(
            stringResource(R.string.hide_playback_tuning_title),
            stringResource(R.string.hide_playback_tuning_title_subtitle),
            listOf("speed", "pitch", "playback", "title", "再生速度", "ピッチ", "ラベル", "非表示"),
            hidePlaybackTuningTitle,
        ) { AppSettings.setHidePlaybackTuningTitle(!hidePlaybackTuningTitle) },
        SettingsSearchEntry(
            stringResource(R.string.playback_tuning_thin_sliders),
            stringResource(R.string.playback_tuning_thin_sliders_subtitle),
            listOf("playback", "speed", "pitch", "slider", "再生速度", "ピッチ", "バー", "スライダー"),
            playbackTuningThinSliders,
        ) { AppSettings.setPlaybackTuningThinSliders(!playbackTuningThinSliders) },
        SettingsSearchEntry(
            title = stringResource(R.string.app_icon),
            subtitle = stringResource(R.string.app_icon_subtitle),
            keywords = listOf("app icon", "launcher", "pink", "black", "green", "アプリアイコン", "ホーム画面", "ピンク", "黒", "緑"),
            valueLabel = stringResource(when (launcherIcon) {
                LauncherIcon.PINK -> R.string.app_icon_pink
                LauncherIcon.BLACK -> R.string.app_icon_black
                LauncherIcon.GREEN -> R.string.app_icon_green
            }),
            activate = { showLauncherIconPicker = true },
        ),
        SettingsSearchEntry(
            stringResource(R.string.popup_external_audio_player),
            stringResource(R.string.popup_external_audio_player_subtitle),
            listOf("popup", "floating", "overlay", "external audio", "ポップアップ", "小窓", "重ねて表示"),
            popupExternalAudioPlayer,
        ) { setPopupExternalAudioPlayerWithPermission(!popupExternalAudioPlayer) },
        SettingsSearchEntry(stringResource(R.string.audio_cutter_enabled), stringResource(R.string.audio_cutter_enabled_subtitle), listOf("audio cutter", "cut", "edit", "オーディオカッター", "曲編集"), audioCutterEnabled) { AppSettings.setAudioCutterEnabled(!audioCutterEnabled) },
        SettingsSearchEntry(
            title = stringResource(R.string.cd_ripping_settings),
            subtitle = stringResource(R.string.cd_ripping_settings_subtitle),
            keywords = listOf("CD", "CD-DA", "rip", "ripping", "optical drive", "USB", "CD取り込み", "CDリッピング", "光学ドライブ"),
            activate = { showCdRippingSheet = true },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.artwork_backdrop_blur),
            subtitle = stringResource(R.string.artwork_backdrop_blur_subtitle),
            keywords = listOf("artwork", "background", "blur", "ぼかし", "背景", "アルバムアート"),
            valueLabel = stringResource(R.string.artwork_backdrop_blur_value, artworkBackdropBlurDp.roundToInt()),
            sliderValue = artworkBackdropBlurDp,
            sliderValueRange = 0f..MAX_PLAYER_ARTWORK_BLUR_DP,
            sliderSteps = 31,
            onSliderValueChange = AppSettings::setArtworkBackdropBlurDp,
            activate = {},
        ),
        SettingsSearchEntry(stringResource(R.string.offline_mode), stringResource(R.string.offline_mode_subtitle), listOf("offline", "オフライン", "通信"), offlineMode) { AppSettings.setOfflineMode(!offlineMode) },
        SettingsSearchEntry(
            stringResource(R.string.youtube_music_enabled),
            stringResource(R.string.youtube_music_enabled_subtitle),
            listOf("youtube", "youtube music", "ytmusic", "ネット検索", "オンライン音楽", "通信"),
            youtubeMusicEnabled,
        ) { AppSettings.setYoutubeMusicEnabled(!youtubeMusicEnabled) },
        SettingsSearchEntry(
            stringResource(R.string.youtube_music_search_default),
            stringResource(R.string.youtube_music_search_default_subtitle),
            listOf("youtube", "youtube music", "ytmusic", "default search", "search target", "検索先", "検索対象", "初期値"),
            youtubeMusicSearchDefault,
        ) { AppSettings.setYoutubeMusicSearchDefault(!youtubeMusicSearchDefault) },
        SettingsSearchEntry(
            stringResource(R.string.youtube_music_include_videos),
            stringResource(R.string.youtube_music_include_videos_subtitle),
            listOf("youtube", "youtube music", "video", "videos", "動画", "検索結果", "MV"),
            includeYoutubeMusicVideosInSearch,
        ) { AppSettings.setIncludeYoutubeMusicVideosInSearch(!includeYoutubeMusicVideosInSearch) },
        SettingsSearchEntry(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_subtitle), listOf("screen", "display", "画面", "常時"), keepScreenOn) { AppSettings.setKeepScreenOn(!keepScreenOn) },
        SettingsSearchEntry(
            stringResource(R.string.keep_screen_on_during_lyrics),
            stringResource(R.string.keep_screen_on_during_lyrics_subtitle),
            listOf("lyrics", "screen", "stay awake", "歌詞", "画面", "消灯"),
            keepScreenOnDuringLyrics,
        ) { AppSettings.setKeepScreenOnDuringLyrics(!keepScreenOnDuringLyrics) },
        SettingsSearchEntry(
            stringResource(R.string.show_track_duration_instead_of_remaining),
            stringResource(R.string.show_track_duration_instead_of_remaining_subtitle),
            listOf("duration", "remaining", "time", "再生時間", "残り時間", "曲の長さ"),
            showTrackDurationInsteadOfRemaining,
        ) { AppSettings.setShowTrackDurationInsteadOfRemaining(!showTrackDurationInsteadOfRemaining) },
        SettingsSearchEntry(stringResource(R.string.show_status_bar), stringResource(R.string.show_status_bar_search_help), listOf("status bar", "library", "ステータスバー", "ライブラリ", "battery", "電池"), showStatusBar) { AppSettings.setShowStatusBar(!showStatusBar) },
        SettingsSearchEntry(stringResource(R.string.show_player_status_bar), stringResource(R.string.show_player_status_bar_subtitle), listOf("status bar", "player", "ステータスバー", "プレイヤー", "battery", "電池"), showPlayerStatusBar) { AppSettings.setShowPlayerStatusBar(!showPlayerStatusBar) },
        SettingsSearchEntry(stringResource(R.string.show_navigation_bar), stringResource(R.string.show_navigation_bar_search_help), listOf("navigation bar", "ナビゲーションバー", "戻る", "home"), showNavigationBar) { AppSettings.setShowNavigationBar(!showNavigationBar) },
        SettingsSearchEntry(stringResource(R.string.music_only_device_mode), stringResource(R.string.music_only_device_mode_subtitle), listOf("music only", "screen pinning", "lock screen", "home", "音楽専用", "画面固定", "ロック画面", "ホーム"), musicOnlyDeviceMode) { AppSettings.setMusicOnlyDeviceMode(!musicOnlyDeviceMode) },
        SettingsSearchEntry(stringResource(R.string.hide_player_artist), stringResource(R.string.hide_player_artist_subtitle), listOf("artist", "アーティスト", "player"), hidePlayerArtist) { AppSettings.setHidePlayerArtist(!hidePlayerArtist) },
        SettingsSearchEntry(stringResource(R.string.player_lyrics_strip), stringResource(R.string.player_lyrics_strip_subtitle), listOf("lyrics", "歌詞", "strip", "player"), showPlayerLyricsStrip) { AppSettings.setShowPlayerLyricsStrip(!showPlayerLyricsStrip) },
        SettingsSearchEntry(
            stringResource(R.string.lyrics_replace_artwork_only),
            stringResource(R.string.lyrics_replace_artwork_only_subtitle),
            listOf("lyrics", "歌詞", "artwork", "アルバムアート", "controls", "操作を残す"),
            lyricsReplaceArtworkOnly,
        ) { AppSettings.setLyricsReplaceArtworkOnly(!lyricsReplaceArtworkOnly) },
        SettingsSearchEntry(
            stringResource(R.string.hide_player_header_during_lyrics),
            stringResource(R.string.hide_player_header_during_lyrics_subtitle),
            listOf("lyrics", "歌詞", "header", "title", "artist", "favorite", "more", "上部", "曲名", "アーティスト", "お気に入り", "詳細"),
            hidePlayerHeaderDuringLyrics,
        ) { AppSettings.setHidePlayerHeaderDuringLyrics(!hidePlayerHeaderDuringLyrics) },
        SettingsSearchEntry(stringResource(R.string.skip_silence), stringResource(R.string.skip_silence_subtitle), listOf("silence", "無音", "再生"), skipSilence) { AppSettings.setSkipSilence(!skipSilence) },
        SettingsSearchEntry(
            stringResource(R.string.loudness_normalization),
            stringResource(R.string.loudness_normalization_subtitle),
            listOf("loudness", "normalization", "volume", "音量", "正規化", "ラウドネス"),
            loudnessNormalization,
        ) { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
        SettingsSearchEntry(
            stringResource(R.string.loudness_off_on_speaker),
            stringResource(R.string.loudness_off_on_speaker_subtitle),
            listOf("loudness", "normalization", "speaker", "phone", "音量", "正規化", "スマートフォン", "スピーカー"),
            loudnessOffOnSpeaker,
            enabled = loudnessNormalization,
        ) { if (loudnessNormalization) AppSettings.setLoudnessOffOnSpeaker(!loudnessOffOnSpeaker) },
        SettingsSearchEntry(
            title = stringResource(R.string.automix),
            subtitle = stringResource(if (smartFade) R.string.automix_enabled_subtitle else R.string.automix_disabled_subtitle),
            keywords = listOf("automix", "smart fade", "mix", "オートミックス", "自動ミックス", "クロスフェード"),
            checked = smartFade,
            activate = { AppSettings.setSmartFadeEnabled(!smartFade) },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.automix_performance),
            subtitle = stringResource(R.string.automix_performance_subtitle),
            keywords = listOf("automix", "performance", "cpu", "battery", "オートミックス", "パフォーマンス", "CPU", "電池"),
            valueLabel = automixPerformance.localizedLabel(),
            activate = { showAutomixPerformanceSheet = true },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.crossfade),
            subtitle = stringResource(if (smartFade) R.string.crossfade_disabled_by_automix else R.string.crossfade_subtitle),
            keywords = listOf("crossfade", "fade", "transition", "クロスフェード", "曲間"),
            valueLabel = if (crossfadeSeconds == 0) stringResource(R.string.off)
                else stringResource(R.string.crossfade_value, crossfadeSeconds),
            sliderValue = crossfadeSeconds.toFloat(),
            sliderValueRange = 0f..AppSettings.MAX_CROSSFADE_SECONDS.toFloat(),
            sliderSteps = AppSettings.MAX_CROSSFADE_SECONDS - 1,
            onSliderValueChange = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
            enabled = !smartFade,
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.audio_offload),
            subtitle = stringResource(if (crossfadeSeconds > 0 || smartFade) R.string.audio_offload_disabled_by_transition else R.string.audio_offload_subtitle),
            keywords = listOf("audio offload", "offload", "hardware playback", "省電力", "オフロード再生"),
            checked = audioOffloadEnabled && crossfadeSeconds == 0 && !smartFade,
            enabled = crossfadeSeconds == 0 && !smartFade,
            activate = { if (crossfadeSeconds == 0 && !smartFade) AppSettings.setAudioOffloadEnabled(!audioOffloadEnabled) },
        ),
        SettingsSearchEntry(
            stringResource(R.string.dolby_atmos),
            stringResource(R.string.dolby_atmos_subtitle),
            listOf("dolby", "atmos", "eac3", "ドルビー", "空間オーディオ"),
            dolbyAtmos,
        ) { AppSettings.setDolbyAtmos(!dolbyAtmos) },
        SettingsSearchEntry(
            title = stringResource(R.string.volume_boost),
            subtitle = stringResource(R.string.volume_boost_subtitle),
            keywords = listOf("volume", "boost", "gain", "音量", "ブースト", "増幅"),
            checked = volumeBoostEnabled,
            activate = { AppSettings.setVolumeBoostEnabled(!volumeBoostEnabled) },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.volume_boost),
            subtitle = stringResource(R.string.volume_boost_subtitle),
            keywords = listOf("volume", "boost", "gain", "dB", "音量", "ブースト", "増幅"),
            valueLabel = stringResource(R.string.volume_boost_value, volumeBoostDb),
            sliderValue = volumeBoostDb,
            sliderValueRange = 0f..12f,
            enabled = volumeBoostEnabled,
            onSliderValueChange = AppSettings::setVolumeBoostDb,
            activate = {},
        ),
        SettingsSearchEntry(
            stringResource(R.string.dirac_audio_eq),
            stringResource(R.string.dirac_audio_eq_subtitle),
            listOf("dirac", "eq", "equalizer", "ディラック", "イコライザー"),
            diracAudioEnabled,
        ) { AppSettings.setDiracAudioEnabled(!diracAudioEnabled) },
        SettingsSearchEntry(
            stringResource(R.string.autoeq_profiles),
            stringResource(R.string.autoeq_profiles_subtitle),
            listOf("autoeq", "headphones", "equalizer", "ヘッドホン", "自動EQ"),
            activate = { showEqualizerSheet = true },
        ),
        SettingsSearchEntry(stringResource(R.string.double_tap_to_seek), stringResource(R.string.double_tap_to_seek_subtitle), listOf("seek", "double tap", "シーク", "ダブルタップ"), doubleTapToSeek) { AppSettings.setDoubleTapToSeek(!doubleTapToSeek) },
        SettingsSearchEntry(
            title = stringResource(R.string.seek_interval),
            subtitle = stringResource(R.string.seek_interval_subtitle),
            keywords = listOf("seek", "seconds", "秒", "シーク", "ダブルタップ"),
            valueLabel = stringResource(R.string.seek_interval_value, seekIntervalSeconds),
            sliderValue = seekIntervalSeconds.toFloat(),
            sliderValueRange = 1f..60f,
            sliderSteps = 58,
            onSliderValueChange = { AppSettings.setSeekIntervalSeconds(it.roundToInt()) },
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.recent_play_threshold),
            subtitle = stringResource(R.string.recent_play_threshold_subtitle),
            keywords = listOf("recent", "recently played", "play count", "再生履歴", "最近再生", "再生回数", "秒"),
            valueLabel = stringResource(R.string.recent_play_threshold_value, recentlyPlayedThresholdSeconds),
            sliderValue = recentlyPlayedThresholdSeconds.toFloat(),
            sliderValueRange = 0f..AppSettings.MAX_RECENT_PLAY_THRESHOLD_SECONDS.toFloat(),
            onSliderValueChange = { AppSettings.setRecentlyPlayedThresholdSeconds(it.roundToInt()) },
            activate = {},
        ),
        SettingsSearchEntry(
            stringResource(R.string.hide_seek_button_labels),
            stringResource(R.string.hide_seek_button_labels_subtitle),
            listOf("seek", "seconds", "label", "シーク", "秒数", "ラベル"),
            hideSeekButtonLabels,
        ) { AppSettings.setHideSeekButtonLabels(!hideSeekButtonLabels) },
        SettingsSearchEntry(
            stringResource(R.string.seek_button_mode),
            stringResource(R.string.seek_button_mode_subtitle),
            listOf("seek", "button", "15 minutes", "シーク", "ボタン"),
            valueLabel = stringResource(when (seekButtonMode) {
                SeekButtonMode.OFF -> R.string.seek_buttons_off
                SeekButtonMode.ALWAYS -> R.string.seek_buttons_always
                SeekButtonMode.LONG_TRACKS_ONLY -> R.string.seek_buttons_long_tracks
            }),
            activate = {
                AppSettings.setSeekButtonMode(
                    when (seekButtonMode) {
                        SeekButtonMode.OFF -> SeekButtonMode.ALWAYS
                        SeekButtonMode.ALWAYS -> SeekButtonMode.LONG_TRACKS_ONLY
                        SeekButtonMode.LONG_TRACKS_ONLY -> SeekButtonMode.OFF
                    },
                )
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.transport_left_action),
            subtitle = stringResource(R.string.transport_side_action_subtitle),
            keywords = listOf("transport", "left", "shuffle", "repeat", "left button", "再生", "左", "シャッフル", "リピート", "ボタン"),
            valueLabel = transportLeftAction.localizedLabel(),
            activate = {
                val next = (TransportSideAction.entries.indexOf(transportLeftAction) + 1) % TransportSideAction.entries.size
                AppSettings.setTransportLeftAction(TransportSideAction.entries[next])
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.transport_right_action),
            subtitle = stringResource(R.string.transport_side_action_subtitle),
            keywords = listOf("transport", "right", "shuffle", "repeat", "right button", "再生", "右", "シャッフル", "リピート", "ボタン"),
            valueLabel = transportRightAction.localizedLabel(),
            activate = {
                val next = (TransportSideAction.entries.indexOf(transportRightAction) + 1) % TransportSideAction.entries.size
                AppSettings.setTransportRightAction(TransportSideAction.entries[next])
            },
        ),
        SettingsSearchEntry(
            stringResource(R.string.speaker_playback_allowed),
            stringResource(R.string.speaker_playback_allowed_subtitle),
            listOf("speaker", "headphones", "bluetooth", "USB", "スピーカー", "イヤホン", "出力先", "音声出力"),
            speakerPlaybackAllowed,
        ) { AppSettings.setSpeakerPlaybackAllowed(!speakerPlaybackAllowed) },
        SettingsSearchEntry(stringResource(R.string.lyrics_furigana), stringResource(R.string.lyrics_furigana_subtitle), listOf("lyrics", "furigana", "ruby", "ふりがな", "ルビ"), showLyricsFurigana) { AppSettings.setShowLyricsFurigana(!showLyricsFurigana) },
        SettingsSearchEntry(stringResource(R.string.lyrics_auto_fit), stringResource(R.string.lyrics_auto_fit_subtitle), listOf("lyrics", "one line", "fit", "歌詞", "一行", "文字サイズ"), autoFitOneLineLyrics) { AppSettings.setAutoFitOneLineLyrics(!autoFitOneLineLyrics) },
        SettingsSearchEntry(stringResource(R.string.spatial_audio), stringResource(R.string.spatial_audio_subtitle), listOf("spatial", "空間オーディオ"), spatialAudio) { AppSettings.setSpatialAudio(!spatialAudio) },
        SettingsSearchEntry(stringResource(R.string.parametric_eq), stringResource(R.string.parametric_eq_subtitle), listOf("parametric", "equalizer", "frequency", "Q", "gain", "パラメトリック", "イコライザー", "周波数"), parametricEqEnabled) { AppSettings.setParametricEqEnabled(!parametricEqEnabled) },
        SettingsSearchEntry(stringResource(R.string.tpdf_dither), stringResource(R.string.tpdf_dither_subtitle), listOf("dither", "TPDF", "quantization", "PCM", "ディザー", "量子化", "音質"), tpdfDitherEnabled) { AppSettings.setTpdfDitherEnabled(!tpdfDitherEnabled) },
        SettingsSearchEntry(stringResource(R.string.channel_swap), stringResource(R.string.channel_swap_subtitle), listOf("channel swap", "swap channels", "stereo", "left", "right", "左右", "チャンネル", "反転"), channelSwapEnabled) { AppSettings.setChannelSwapEnabled(!channelSwapEnabled) },
        SettingsSearchEntry(stringResource(R.string.reverb), stringResource(R.string.reverb_subtitle), listOf("reverb", "room", "残響", "リバーブ"), reverbEnabled) { AppSettings.setReverbEnabled(!reverbEnabled) },
        SettingsSearchEntry(stringResource(R.string.favorite_icon_star), stringResource(R.string.favorite_icon_star_subtitle), listOf("favorite", "star", "お気に入り", "星", "ハート"), favoriteUsesStar) { AppSettings.setFavoriteUsesStar(!favoriteUsesStar) },
        SettingsSearchEntry(stringResource(R.string.hide_unknown_player_artist), stringResource(R.string.hide_unknown_player_artist_subtitle), listOf("artist", "unknown", "アーティスト", "unknown artist"), hideUnknownPlayerArtist) { AppSettings.setHideUnknownPlayerArtist(!hideUnknownPlayerArtist) },
        SettingsSearchEntry(stringResource(R.string.center_player_track_info), stringResource(R.string.center_player_track_info_subtitle), listOf("center", "中央", "曲情報"), centerPlayerTrackInfo) { AppSettings.setCenterPlayerTrackInfo(!centerPlayerTrackInfo) },
        SettingsSearchEntry(stringResource(R.string.player_playlist_library_icon), stringResource(R.string.player_playlist_library_icon_subtitle), listOf("playlist", "library", "プレイリスト", "ライブラリ", "icon"), useLibraryIconForPlaylistControl) { AppSettings.setUseLibraryIconForPlaylistControl(!useLibraryIconForPlaylistControl) },
        appleMusicLyricsButtonStyleSearchEntry(appleMusicLyricsButtonStyle),
        appleMusicChromecastButtonStyleSearchEntry(appleMusicChromecastButtonStyle),
        appleMusicQueueButtonStyleSearchEntry(appleMusicQueueButtonStyle),
        SettingsSearchEntry(stringResource(R.string.prevent_play_at_zero_volume), stringResource(R.string.prevent_play_at_zero_volume_subtitle), listOf("volume", "音量", "zero", "0"), preventPlayAtZeroVolume) { AppSettings.setPreventPlayAtZeroVolume(!preventPlayAtZeroVolume) },
        SettingsSearchEntry(stringResource(R.string.pause_playback_at_zero_volume), stringResource(R.string.pause_playback_at_zero_volume_subtitle), listOf("volume", "音量", "zero", "0", "pause", "一時停止"), pausePlaybackAtZeroVolume) { AppSettings.setPausePlaybackAtZeroVolume(!pausePlaybackAtZeroVolume) },
        SettingsSearchEntry(stringResource(R.string.bit_perfect_playback), stringResource(R.string.bit_perfect_playback_subtitle), listOf("bitperfect", "bit-perfect", "bit perfect", "USB DAC", "PCM", "ビットパーフェクト", "音質"), bitPerfectPlayback) { AppSettings.setBitPerfectPlayback(!bitPerfectPlayback) },
        SettingsSearchEntry(stringResource(R.string.preload_album_art), stringResource(R.string.preload_album_art_subtitle), listOf("artwork", "album art", "preload", "先読み", "アルバムアート"), preloadAlbumArtOnStartup) { AppSettings.setPreloadAlbumArtOnStartup(!preloadAlbumArtOnStartup) },
        SettingsSearchEntry(stringResource(R.string.persistent_local_artwork), stringResource(R.string.persistent_local_artwork_subtitle), listOf("artwork", "album art", "storage", "画像", "ストレージ"), persistentLocalArtwork) { AppSettings.setPersistentLocalArtwork(!persistentLocalArtwork) },
        SettingsSearchEntry(stringResource(R.string.synced_lyrics), stringResource(R.string.synced_lyrics_subtitle), listOf("lyrics", "歌詞", "同期"), syncedLyrics) { AppSettings.setSyncedLyrics(!syncedLyrics) },
        autoShowLyricsSearchEntry(autoShowLyricsOnPlayback),
        SettingsSearchEntry(stringResource(R.string.blur_unfocused_lyrics), stringResource(R.string.blur_unfocused_lyrics_subtitle), listOf("lyrics", "歌詞", "blur", "ぼかし"), lyricsBlur) { AppSettings.setLyricsBlur(!lyricsBlur) },
        SettingsSearchEntry(stringResource(R.string.auto_embed_lyrics), stringResource(R.string.auto_embed_lyrics_subtitle), listOf("lyrics", "歌詞", "embed", "埋め込み"), autoEmbedLyrics) { AppSettings.setAutoEmbedLyrics(!autoEmbedLyrics) },
        SettingsSearchEntry(
            stringResource(R.string.lyrics_extensions_enabled),
            stringResource(R.string.lyrics_extensions_enabled_subtitle),
            listOf("lyrics", "歌詞", "extension", "extensions", "script", "拡張", "スクリプト", "安全", "privacy", "supply chain"),
            lyricsExtensionsEnabled,
        ) { AppSettings.setLyricsExtensionsEnabled(!lyricsExtensionsEnabled) },
        *PlayerArtworkMode.entries.map { playerArtworkModeSearchEntry(it, playerArtworkMode) }.toTypedArray(),
        fullBleedArtworkBottomBlurSearchEntry(fullBleedArtworkBottomBlurStrengthDp),
        SettingsSearchEntry(stringResource(R.string.preserve_artwork_shape_in_compact_transitions), stringResource(R.string.preserve_artwork_shape_in_compact_transitions_subtitle), listOf("artwork", "cover", "aspect ratio", "mini player", "dock", "lyrics", "アート", "カバー", "縦横比", "ミニプレーヤー", "歌詞", "遷移"), preserveArtworkShapeInCompactTransitions) { AppSettings.setPreserveArtworkShapeInCompactTransitions(!preserveArtworkShapeInCompactTransitions) },
        SettingsSearchEntry(stringResource(R.string.keep_artwork_full_size_paused), stringResource(R.string.keep_artwork_full_size_paused_subtitle), listOf("artwork", "cover", "paused", "停止中", "アート"), keepArtworkFullSizeWhenPaused) { AppSettings.setKeepArtworkFullSizeWhenPaused(!keepArtworkFullSizeWhenPaused) },
        SettingsSearchEntry(stringResource(R.string.reduce_animation), stringResource(R.string.reduce_animation_subtitle), listOf("animation", "reduce", "アニメーション"), reduceAnimation) { AppSettings.setReduceAnimation(!reduceAnimation) },
        SettingsSearchEntry(stringResource(R.string.player_artwork_transition), stringResource(R.string.player_artwork_transition_subtitle), listOf("player", "artwork", "cover", "lyrics", "animation", "transition", "プレイヤー", "アート", "歌詞", "遷移", "アニメーション"), playerArtworkTransition) { AppSettings.setPlayerArtworkTransition(!playerArtworkTransition) },
        SettingsSearchEntry(stringResource(R.string.walkman_player_style), stringResource(R.string.walkman_player_style_subtitle), listOf("player", "walkman", "sony", "controls", "ウォークマン", "ソニー", "プレイヤー", "再生ボタン"), walkmanPlayerStyle) { AppSettings.setWalkmanPlayerStyle(!walkmanPlayerStyle) },
        SettingsSearchEntry(
            title = stringResource(R.string.pill_motion_style),
            subtitle = stringResource(R.string.pill_motion_style_subtitle),
            keywords = listOf("tab", "pill", "motion", "speed", "animation", "タブ", "ピル", "モーション", "速度"),
            valueLabel = pillMotionStyle.localizedLabel(),
        ) {
            val next = (PillMotionStyle.entries.indexOf(pillMotionStyle) + 1) % PillMotionStyle.entries.size
            AppSettings.setPillMotionStyle(PillMotionStyle.entries[next])
        },
        SettingsSearchEntry(stringResource(R.string.reduce_dynamic_blur), stringResource(R.string.reduce_dynamic_blur_subtitle), listOf("blur", "ぼかし", "performance", "動的"), reduceDynamicBlur) { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
        SettingsSearchEntry(stringResource(R.string.classic_nav_bar), stringResource(R.string.classic_nav_bar_subtitle), listOf("navigation", "nav bar", "ナビゲーション", "classic"), classicNavBar) { AppSettings.setClassicNavBar(!classicNavBar) },
        SettingsSearchEntry(stringResource(R.string.navigation_bar_matches_mini_player_color), stringResource(R.string.navigation_bar_matches_mini_player_color_subtitle), listOf("navigation", "tab bar", "mini player", "artwork", "color", "タブバー", "ミニプレイヤー", "アルバムアート", "色"), navigationBarMatchesMiniPlayerColor) { AppSettings.setNavigationBarMatchesMiniPlayerColor(!navigationBarMatchesMiniPlayerColor) },
        SettingsSearchEntry(stringResource(R.string.main_navigation_popup), stringResource(R.string.main_navigation_popup_subtitle), listOf("navigation", "tabs", "popup", "menu", "bottom bar", "ナビゲーション", "タブ", "ポップアップ", "メニュー"), mainNavigationTabsPopup) { AppSettings.setMainNavigationTabsPopup(!mainNavigationTabsPopup) },
        SettingsSearchEntry(stringResource(R.string.settings_top_bar_more_icon), stringResource(R.string.settings_top_bar_more_icon_subtitle), listOf("settings", "gear", "ellipsis", "more", "歯車", "設定", "三点", "︙"), settingsTopBarUsesMoreIcon) { AppSettings.setSettingsTopBarUsesMoreIcon(!settingsTopBarUsesMoreIcon) },
        SettingsSearchEntry(stringResource(R.string.hide_nav_bar_labels), stringResource(R.string.hide_nav_bar_labels_subtitle), listOf("navigation", "nav bar", "label", "ラベル", "ナビゲーション"), hideNavBarLabels) { AppSettings.setHideNavigationBarLabels(!hideNavBarLabels) },
        SettingsSearchEntry(stringResource(R.string.legacy_mesh_gradient), stringResource(R.string.legacy_mesh_gradient_subtitle), listOf("theme", "gradient", "テーマ", "グラデーション"), legacyMeshGradient) { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
        SettingsSearchEntry(
            stringResource(R.string.match_button_accent_to_artwork),
            stringResource(R.string.match_button_accent_to_artwork_subtitle),
            listOf("artwork", "button", "accent", "color", "アートワーク", "ボタン", "アクセント色"),
            matchButtonAccentToArtwork,
        ) { AppSettings.setMatchButtonAccentToArtwork(!matchButtonAccentToArtwork) },
        SettingsSearchEntry(
            stringResource(R.string.mini_player_artwork_color),
            stringResource(R.string.mini_player_artwork_color_subtitle),
            listOf("mini player", "album art", "color", "ミニプレイヤー", "アルバムアート", "色"),
            miniPlayerArtworkColor,
        ) { AppSettings.setMiniPlayerArtworkColor(!miniPlayerArtworkColor) },
        SettingsSearchEntry(
            title = stringResource(R.string.pill_widget_opacity),
            subtitle = stringResource(R.string.pill_widget_opacity_subtitle),
            keywords = listOf("widget", "opacity", "transparency", "ウィジェット", "透明度", "ピル型"),
            valueLabel = stringResource(R.string.pill_widget_opacity_value, pillWidgetOpacity.roundToInt()),
            sliderValue = pillWidgetOpacity,
            sliderValueRange = 0f..100f,
            onSliderValueChange = AppSettings::setPillWidgetOpacity,
            activate = {},
        ),
        SettingsSearchEntry(
            stringResource(R.string.mini_player_artwork_outline),
            stringResource(R.string.mini_player_artwork_outline_subtitle),
            listOf("mini player", "outline", "border", "edge", "album art", "ミニプレイヤー", "枠線", "縁", "アートワーク"),
            miniPlayerArtworkOutline,
        ) { AppSettings.setMiniPlayerArtworkOutline(!miniPlayerArtworkOutline) },
        SettingsSearchEntry(
            stringResource(R.string.mini_player_previous_button),
            stringResource(R.string.mini_player_previous_button_subtitle),
            listOf("mini player", "previous", "back", "skip previous", "ミニプレイヤー", "前の曲", "前へ"),
            miniPlayerPreviousButton,
        ) { AppSettings.setMiniPlayerPreviousButton(!miniPlayerPreviousButton) },
        SettingsSearchEntry(
            stringResource(R.string.mini_player_swipe_changes_track),
            stringResource(R.string.mini_player_swipe_changes_track_subtitle),
            listOf("mini player", "swipe", "gesture", "previous", "next", "ミニプレイヤー", "スワイプ", "ジェスチャー", "前の曲", "次の曲"),
            miniPlayerSwipeChangesTrack,
        ) { AppSettings.setMiniPlayerSwipeChangesTrack(!miniPlayerSwipeChangesTrack) },
        SettingsSearchEntry(
            stringResource(R.string.mini_player_swipe_up_expands),
            stringResource(R.string.mini_player_swipe_up_expands_subtitle),
            listOf("mini player", "swipe up", "expand", "open player", "ミニプレイヤー", "上スワイプ", "プレイヤーを開く"),
            miniPlayerSwipeUpExpands,
        ) { AppSettings.setMiniPlayerSwipeUpExpands(!miniPlayerSwipeUpExpands) },
        SettingsSearchEntry(
            stringResource(R.string.playlist_track_menu_artwork_color),
            stringResource(R.string.playlist_track_menu_artwork_color_subtitle),
            listOf("playlist", "track menu", "tag editor", "bulk tag", "selected songs", "artwork", "color", "プレイリスト", "曲メニュー", "タグ編集", "一括タグ編集", "選択した曲", "アートワーク", "色"),
            playlistTrackMenuUsesArtworkColor,
        ) { AppSettings.setPlaylistTrackMenuUsesArtworkColor(!playlistTrackMenuUsesArtworkColor) },
        SettingsSearchEntry(
            title = stringResource(R.string.player_title_font_size),
            subtitle = stringResource(R.string.player_title_font_size_subtitle),
            keywords = listOf("player", "title", "font", "size", "プレーヤー", "曲名", "文字", "大きさ"),
            valueLabel = stringResource(R.string.player_font_size_value, playerTitleFontSizeSp.roundToInt()),
            sliderValue = playerTitleFontSizeSp,
            sliderValueRange = 12f..36f,
            sliderSteps = 23,
            onSliderValueChange = AppSettings::setPlayerTitleFontSizeSp,
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.player_artist_font_size),
            subtitle = stringResource(R.string.player_artist_font_size_subtitle),
            keywords = listOf("player", "artist", "font", "size", "プレーヤー", "アーティスト", "文字", "大きさ"),
            valueLabel = stringResource(R.string.player_font_size_value, playerArtistFontSizeSp.roundToInt()),
            sliderValue = playerArtistFontSizeSp,
            sliderValueRange = 12f..36f,
            sliderSteps = 23,
            onSliderValueChange = AppSettings::setPlayerArtistFontSizeSp,
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.player_play_pause_icon_size),
            subtitle = stringResource(R.string.player_play_pause_icon_size_subtitle),
            keywords = listOf("player", "play", "pause", "icon", "size", "再生", "一時停止", "アイコン", "大きさ"),
            valueLabel = stringResource(R.string.player_icon_size_value, playerPlayPauseIconSizeDp.roundToInt()),
            sliderValue = playerPlayPauseIconSizeDp,
            sliderValueRange = 40f..78f,
            sliderSteps = 37,
            onSliderValueChange = AppSettings::setPlayerPlayPauseIconSizeDp,
            activate = {},
        ),
        playerPlayPauseCornerRoundnessSearchEntry(playerPlayPauseCornerRadiusPercent),
        playerTransportSpacingSearchEntry(playerTransportButtonSpacingDp),
        playerActionSpacingSearchEntry(playerActionButtonSpacingDp),
        SettingsSearchEntry(
            stringResource(R.string.show_player_top_grabber),
            stringResource(R.string.show_player_top_grabber_subtitle),
            listOf("player", "grabber", "handle", "line", "プレーヤー", "上部", "線", "バー"),
            showPlayerTopGrabber,
        ) { AppSettings.setShowPlayerTopGrabber(!showPlayerTopGrabber) },
        SettingsSearchEntry(stringResource(R.string.filter_non_music_audio), stringResource(R.string.filter_non_music_audio_subtitle), listOf("filter", "audio", "music", "音楽", "フィルター"), filterNonMusicAudio) { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
        SettingsSearchEntry(stringResource(R.string.playlist_song_artwork), stringResource(R.string.playlist_song_artwork_subtitle), listOf("playlist", "artwork", "プレイリスト", "アルバムアート"), showPlaylistSongArtwork) { AppSettings.setShowPlaylistSongArtwork(!showPlaylistSongArtwork) },
        SettingsSearchEntry(
            stringResource(R.string.hide_playlist_play_button_label),
            stringResource(R.string.hide_playlist_play_button_label_subtitle),
            listOf("playlist", "play", "button", "label", "text", "プレイリスト", "再生", "ボタン", "文字", "ラベル"),
            hidePlaylistPlayButtonLabel,
        ) { AppSettings.setHidePlaylistPlayButtonLabel(!hidePlaylistPlayButtonLabel) },
        SettingsSearchEntry(
            stringResource(R.string.collection_song_artwork),
            stringResource(R.string.collection_song_artwork_subtitle),
            listOf("album", "artist", "album artist", "artwork", "アルバム", "アーティスト", "アルバムアーティスト", "アートワーク"),
            showCollectionSongArtwork,
        ) { AppSettings.setShowCollectionSongArtwork(!showCollectionSongArtwork) },
        SettingsSearchEntry(stringResource(R.string.keep_collection_artwork_aspect_ratio), stringResource(R.string.keep_collection_artwork_aspect_ratio_subtitle), listOf("playlist", "album", "artwork", "aspect ratio", "cover", "プレイリスト", "アルバム", "アート", "縦横比"), keepCollectionArtworkAspectRatio) { AppSettings.setKeepCollectionArtworkAspectRatio(!keepCollectionArtworkAspectRatio) },
        SettingsSearchEntry(stringResource(R.string.localize_library_collection_count), stringResource(R.string.localize_library_collection_count_subtitle), listOf("library", "collections", "count", "language", "ライブラリ", "コレクション", "件数", "日本語"), localizeLibraryCollectionCount) { AppSettings.setLocalizeLibraryCollectionCount(!localizeLibraryCollectionCount) },
    ) + listOf(
        SettingsSearchEntry(
            stringResource(R.string.artwork_tap_opens_lyrics),
            stringResource(R.string.artwork_tap_opens_lyrics_subtitle),
            listOf("artwork", "tap", "lyrics", "アルバムアート", "タップ", "歌詞"),
            artworkTapOpensLyrics,
        ) { AppSettings.setArtworkTapOpensLyrics(!artworkTapOpensLyrics) },
        SettingsSearchEntry(
            stringResource(R.string.liquid_glass),
            stringResource(if (liquidGlassSupported) R.string.liquid_glass_subtitle else R.string.liquid_glass_unavailable),
            listOf("liquid glass", "navigation", "bar", "液体ガラス", "ナビゲーション"),
            liquidGlass,
            enabled = liquidGlassSupported,
        ) { if (liquidGlassSupported) AppSettings.setLiquidGlass(!liquidGlass) },
        SettingsSearchEntry(
            stringResource(R.string.player_details_vertical_menu),
            stringResource(R.string.player_details_vertical_menu_subtitle),
            listOf("player", "details", "menu", "vertical", "プレーヤー", "詳細", "縦"),
            playerDetailsVerticalMenu,
        ) { AppSettings.setPlayerDetailsVerticalMenu(!playerDetailsVerticalMenu) },
        SettingsSearchEntry(
            stringResource(R.string.work_out_genres),
            stringResource(if (replayGenres) R.string.replay_genres_enabled_subtitle else R.string.replay_genres_disabled_subtitle),
            listOf("replay", "genre", "last.fm", "ジャンル", "再生履歴"),
            replayGenres,
        ) { AppSettings.setReplayGenres(!replayGenres) },
        SettingsSearchEntry(
            stringResource(R.string.play_next_on_swipe),
            stringResource(if (swipeToPlayNext) R.string.swipe_plays_next else R.string.swipe_adds_to_queue),
            listOf("swipe", "queue", "play next", "スワイプ", "キュー", "次に再生"),
            swipeToPlayNext,
        ) { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
        SettingsSearchEntry(
            stringResource(R.string.dont_repeat_songs),
            stringResource(R.string.dont_repeat_songs_subtitle),
            listOf("repeat", "suggestions", "同じ曲", "繰り返し", "おすすめ"),
            dontRepeatSuggestions,
        ) { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
        SettingsSearchEntry(
            stringResource(R.string.stop_music_on_close),
            stringResource(R.string.stop_music_on_close_subtitle),
            listOf("close", "recent apps", "stop playback", "終了", "停止", "最近のアプリ"),
            stopOnTaskRemoved,
        ) { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
        SettingsSearchEntry(
            stringResource(R.string.hide_volume_bar),
            stringResource(R.string.hide_volume_bar_subtitle),
            listOf("volume", "slider", "bar", "音量", "スライダー"),
            hideVolumeBar,
        ) { AppSettings.setHideVolumeBar(!hideVolumeBar) },
        SettingsSearchEntry(
            stringResource(R.string.hide_radio_playback_bar),
            stringResource(R.string.hide_radio_playback_bar_subtitle),
            listOf("radio", "radiko", "progress", "seek bar", "ラジオ", "ラジコ", "再生バー", "シークバー"),
            hideRadioPlaybackBar,
        ) { AppSettings.setHideRadioPlaybackBar(!hideRadioPlaybackBar) },
        SettingsSearchEntry(
            stringResource(R.string.player_seek_bar_thickness),
            stringResource(R.string.player_bar_thickness_subtitle),
            listOf("player", "seek", "progress", "bar", "thickness", "再生バー", "シークバー", "太さ"),
            valueLabel = stringResource(R.string.player_bar_thickness_value, playerSeekBarThicknessDp),
            sliderValue = playerSeekBarThicknessDp.toFloat(),
            sliderValueRange = 2f..14f,
            sliderSteps = 11,
            onSliderValueChange = { AppSettings.setPlayerSeekBarThicknessDp(it.roundToInt()) },
            activate = {},
        ),
        SettingsSearchEntry(
            stringResource(R.string.player_volume_bar_thickness),
            stringResource(R.string.player_bar_thickness_subtitle),
            listOf("player", "volume", "bar", "thickness", "音量バー", "太さ"),
            valueLabel = stringResource(R.string.player_bar_thickness_value, playerVolumeBarThicknessDp),
            sliderValue = playerVolumeBarThicknessDp.toFloat(),
            sliderValueRange = 2f..14f,
            sliderSteps = 11,
            onSliderValueChange = { AppSettings.setPlayerVolumeBarThicknessDp(it.roundToInt()) },
            activate = {},
        ),
        SettingsSearchEntry(
            stringResource(R.string.hide_player_quick_action_circles),
            stringResource(R.string.hide_player_quick_action_circles_subtitle),
            listOf("player", "quick action", "circle", "outline", "icon", "クイック操作", "丸", "円", "アイコン"),
            hidePlayerQuickActionCircles,
        ) { AppSettings.setHidePlayerQuickActionCircles(!hidePlayerQuickActionCircles) },
        SettingsSearchEntry(
            stringResource(R.string.show_nerd_stats),
            stringResource(R.string.show_nerd_stats_subtitle),
            listOf("stats", "codec", "bitrate", "sample rate", "情報", "ビットレート"),
            nerdStats,
        ) { AppSettings.setShowNerdStats(!nerdStats) },
        SettingsSearchEntry(
            stringResource(R.string.lyrics_debug_logs),
            stringResource(R.string.lyrics_debug_logs_subtitle),
            listOf("lyrics", "debug", "logs", "歌詞", "デバッグ", "ログ"),
            showLyricsLogs,
        ) { AppSettings.setShowLyricsLogs(!showLyricsLogs) },
        SettingsSearchEntry(
            stringResource(R.string.prefer_usb_dac),
            stringResource(R.string.output_precision),
            listOf("USB", "DAC", "audio output", "USB DAC", "出力"),
            preferUsbDac,
        ) { AppSettings.setPreferUsbDac(!preferUsbDac) },
        SettingsSearchEntry(
            title = stringResource(R.string.audio_focus_level),
            subtitle = stringResource(R.string.audio_focus_level_subtitle),
            keywords = listOf("audio focus", "focus", "duck", "オーディオフォーカス", "他の音"),
            valueLabel = audioFocusLevel.toString(),
            sliderValue = audioFocusLevel.toFloat(),
            sliderValueRange = 0f..4f,
            sliderSteps = 3,
            onSliderValueChange = { AppSettings.setAudioFocusLevel(it.roundToInt()) },
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.resampler_cutoff),
            subtitle = stringResource(R.string.resampler_cutoff_subtitle),
            keywords = listOf("PCM", "resampler", "cutoff", "filter", "カットオフ", "フィルター"),
            checked = resamplerCutoffHz > 0,
            valueLabel = if (resamplerCutoffHz > 0) stringResource(R.string.resampler_cutoff_value, resamplerCutoffHz) else null,
            sliderValue = resamplerCutoffHz.toFloat().takeIf { resamplerCutoffHz > 0 },
            sliderValueRange = 8_000f..22_000f,
            sliderSteps = 13,
            onSliderValueChange = { AppSettings.setResamplerCutoffHz(it.roundToInt()) },
            activate = { AppSettings.setResamplerCutoffHz(if (resamplerCutoffHz > 0) 0 else 20_000) },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.output_precision),
            subtitle = "",
            keywords = listOf("output", "PCM", "float", "16-bit", "32-bit", "出力", "精度"),
            valueLabel = outputPcmMode.label,
            activate = {
                val next = (OutputPcmMode.entries.indexOf(outputPcmMode) + 1) % OutputPcmMode.entries.size
                AppSettings.setOutputPcmMode(OutputPcmMode.entries[next])
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.theme),
            subtitle = theme.localizedLabel(),
            keywords = listOf("theme", "appearance", "system", "light", "dark", "AMOLED", "artwork", "image", "画像", "写真", "テーマ", "外観"),
            activate = { showThemePicker = true },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.choose_theme_image),
            subtitle = stringResource(R.string.choose_theme_image_subtitle),
            keywords = listOf("theme", "wallpaper", "image", "photo", "local image", "テーマ", "壁紙", "画像", "写真", "ローカル"),
            valueLabel = stringResource(if (customThemeImageUri.isNullOrBlank()) R.string.choose_image else R.string.theme_image_selected),
            activate = chooseThemeImage,
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.media_control_secondary_action),
            subtitle = stringResource(R.string.media_control_secondary_action_subtitle),
            keywords = listOf("media controls", "notification", "shuffle", "queue", "lyrics", "playlists", "通知", "メディアコントロール", "再生キュー", "歌詞", "プレイリスト"),
            valueLabel = mediaControlSecondaryAction.localizedLabel(),
            activate = { showMediaControlActionPicker = true },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.media_control_favorite_star),
            subtitle = stringResource(R.string.media_control_favorite_star_subtitle),
            keywords = listOf("notification", "media controls", "star", "favorite", "heart", "通知", "メディアコントロール", "星", "ハート", "お気に入り"),
            checked = mediaControlFavoriteUsesStar,
        ) { AppSettings.setMediaControlFavoriteUsesStar(!mediaControlFavoriteUsesStar) },
        SettingsSearchEntry(
            title = stringResource(R.string.hide_media_control_custom_actions),
            subtitle = stringResource(R.string.hide_media_control_custom_actions_subtitle),
            keywords = listOf("notification", "media controls", "hide", "custom buttons", "通知", "メディアコントロール", "非表示", "ボタン"),
            checked = hideMediaControlCustomActions,
        ) { AppSettings.setHideMediaControlCustomActions(!hideMediaControlCustomActions) },
        SettingsSearchEntry(
            title = stringResource(R.string.restart_queue_when_ended),
            subtitle = stringResource(R.string.restart_queue_when_ended_subtitle),
            keywords = listOf("queue", "repeat", "restart", "end", "再生キュー", "リピート", "先頭", "最初"),
            checked = restartQueueWhenEnded,
        ) { AppSettings.setRestartQueueWhenEnded(!restartQueueWhenEnded) },
        SettingsSearchEntry(
            title = stringResource(R.string.advance_queue_when_repeat_off),
            subtitle = stringResource(R.string.advance_queue_when_repeat_off_subtitle),
            keywords = listOf("queue", "repeat off", "auto advance", "next track", "再生キュー", "自動再生", "次の曲", "リピートオフ"),
            checked = advanceQueueWhenRepeatOff,
        ) { AppSettings.setAdvanceQueueWhenRepeatOff(!advanceQueueWhenRepeatOff) },
        SettingsSearchEntry(
            title = stringResource(R.string.screen_orientation),
            subtitle = stringResource(R.string.screen_orientation_subtitle),
            keywords = listOf("orientation", "rotation", "portrait", "landscape", "画面", "回転", "縦向き", "横向き"),
            valueLabel = stringResource(when (orientationMode) {
                ScreenOrientationMode.SYSTEM -> R.string.orientation_system
                ScreenOrientationMode.PORTRAIT -> R.string.orientation_portrait
                ScreenOrientationMode.LANDSCAPE -> R.string.orientation_landscape
            }),
            activate = {
                val next = (ScreenOrientationMode.entries.indexOf(orientationMode) + 1) % ScreenOrientationMode.entries.size
                AppSettings.setScreenOrientationMode(ScreenOrientationMode.entries[next])
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.lyrics_text_alignment),
            subtitle = stringResource(when (lyricsTextAlignment) {
                LyricsTextAlignment.LEFT -> R.string.lyrics_align_left
                LyricsTextAlignment.CENTER -> R.string.lyrics_align_center
                LyricsTextAlignment.RIGHT -> R.string.lyrics_align_right
            }),
            keywords = listOf("lyrics", "alignment", "left", "center", "right", "歌詞", "配置", "左揃え", "中央揃え", "右揃え"),
            valueLabel = stringResource(when (lyricsTextAlignment) {
                LyricsTextAlignment.LEFT -> R.string.lyrics_align_left
                LyricsTextAlignment.CENTER -> R.string.lyrics_align_center
                LyricsTextAlignment.RIGHT -> R.string.lyrics_align_right
            }),
            activate = {
                val next = (LyricsTextAlignment.entries.indexOf(lyricsTextAlignment) + 1) % LyricsTextAlignment.entries.size
                AppSettings.setLyricsTextAlignment(LyricsTextAlignment.entries[next])
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.lyrics_font_size),
            subtitle = "",
            keywords = listOf("lyrics", "font", "size", "歌詞", "文字", "サイズ"),
            valueLabel = stringResource(R.string.lyrics_font_size_value, (lyricsFontScale * 100f).roundToInt()),
            sliderValue = lyricsFontScale,
            sliderValueRange = 0.6f..1.6f,
            sliderSteps = 19,
            onSliderValueChange = AppSettings::setLyricsFontScale,
            activate = {},
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.high_performance_mode),
            subtitle = if (highPerformanceMode) stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                else stringResource(R.string.high_performance_subtitle),
            keywords = listOf("performance", "refresh rate", "high performance", "パフォーマンス", "リフレッシュレート"),
            checked = highPerformanceMode,
            activate = {
                if (highPerformanceMode) AppSettings.setHighPerformanceMode(false)
                else showPerformanceWarning = true
            },
        ),
    ) + PlayerControl.entries.map { control ->
        val title = stringResource(when (control) {
            PlayerControl.SHUFFLE -> R.string.player_control_shuffle
            PlayerControl.REPEAT -> R.string.player_control_repeat
            PlayerControl.LYRICS -> R.string.player_control_lyrics
            PlayerControl.LIKE -> R.string.player_control_like
            PlayerControl.QUEUE -> R.string.player_control_queue
            PlayerControl.CHROMECAST -> R.string.player_control_chromecast
            PlayerControl.PLAYLIST -> R.string.player_control_playlist
            PlayerControl.SEARCH -> R.string.player_control_search
        })
        SettingsSearchEntry(
            title = title,
            subtitle = stringResource(R.string.player_control_visibility_subtitle),
            keywords = listOf("player", "control", "button", "visibility", "order", "プレーヤー", "ボタン", "表示", "順序"),
            checked = control in visiblePlayerControls,
        ) { AppSettings.setPlayerControlVisible(control, control !in visiblePlayerControls) }
    } + PlayerQuickAction.entries.map { action ->
        val titleRes = when (action) {
            PlayerQuickAction.LYRICS -> R.string.quick_action_lyrics
            PlayerQuickAction.ADD_TO_PLAYLIST -> R.string.quick_action_add_to_playlist
            PlayerQuickAction.PLAYBACK_TUNING -> R.string.quick_action_playback_tuning
            PlayerQuickAction.QUEUE -> R.string.quick_action_queue
            PlayerQuickAction.PLAYLISTS -> R.string.quick_action_playlists
            PlayerQuickAction.SEARCH -> R.string.quick_action_search
            PlayerQuickAction.ALBUM -> R.string.go_to_album
            PlayerQuickAction.ARTIST -> R.string.go_to_artist
            PlayerQuickAction.EQUALIZER -> R.string.equalizer
            PlayerQuickAction.CHROMECAST -> R.string.chromecast
            PlayerQuickAction.SLEEP_TIMER -> R.string.sleep_timer
            PlayerQuickAction.TAG_EDITOR -> R.string.tag_editor
            PlayerQuickAction.EDIT_LYRICS -> R.string.edit_lyrics
            PlayerQuickAction.DETAILS -> R.string.details
            PlayerQuickAction.SHARE_FILE -> R.string.share_file
            PlayerQuickAction.FAVORITE -> R.string.quick_action_favorite
            PlayerQuickAction.SHUFFLE -> R.string.player_control_shuffle
            PlayerQuickAction.REPEAT -> R.string.player_control_repeat
        }
        val title = stringResource(titleRes)
        SettingsSearchEntry(
            title = title,
            subtitle = stringResource(R.string.player_quick_action_order_subtitle),
            keywords = listOf("player", "quick action", "shortcut", "order", "プレーヤー", "クイック", "ショートカット", "並び替え"),
            checked = action in quickActions,
        ) {
            AppSettings.setPlayerQuickActions(
                if (action in quickActions) quickActions - action else quickActions + action,
            )
        }
    } + PlayerDetailsAction.entries.map { action ->
        val actionTitle = stringResource(when (action) {
            PlayerDetailsAction.ALBUM -> R.string.go_to_album
            PlayerDetailsAction.ARTIST -> R.string.go_to_artist
            PlayerDetailsAction.ADD_TO_PLAYLIST -> R.string.add_to_playlist
            PlayerDetailsAction.EQUALIZER -> R.string.equalizer
            PlayerDetailsAction.CHROMECAST -> R.string.chromecast
            PlayerDetailsAction.PLAYBACK_TUNING -> R.string.playback_tuning
            PlayerDetailsAction.SLEEP_TIMER -> R.string.sleep_timer
            PlayerDetailsAction.TAG_EDITOR -> R.string.tag_editor
            PlayerDetailsAction.EDIT_LYRICS -> R.string.edit_lyrics
            PlayerDetailsAction.DETAILS -> R.string.details
            PlayerDetailsAction.SHARE_FILE -> R.string.share_file
        })
        SettingsSearchEntry(
            title = stringResource(R.string.player_menu_action_visibility, actionTitle),
            subtitle = stringResource(R.string.player_details_actions_subtitle),
            keywords = listOf("player", "menu", "details", actionTitle, "表示", "非表示"),
            checked = action !in hiddenPlayerDetailsActions,
        ) {
            AppSettings.setPlayerDetailsActionVisible(action, action in hiddenPlayerDetailsActions)
        }
    } + MainNavigationTab.entries.map { tab ->
        val tabTitle = stringResource(when (tab) {
            MainNavigationTab.SONGS -> R.string.songs
            MainNavigationTab.ALBUMS -> R.string.albums
            MainNavigationTab.ARTISTS -> R.string.artists
            MainNavigationTab.LIBRARY -> R.string.library
            MainNavigationTab.SEARCH -> R.string.search
            MainNavigationTab.ALBUM_ARTISTS -> R.string.album_artists
            MainNavigationTab.FOLDERS -> R.string.folders
            MainNavigationTab.RADIO -> R.string.radio
        })
        SettingsSearchEntry(
            title = stringResource(R.string.show_tab_setting, tabTitle),
            subtitle = stringResource(R.string.show_tab_setting_subtitle, tabTitle),
            keywords = listOf("tab", "タブ", tabTitle),
            checked = if (tab == MainNavigationTab.ALBUM_ARTISTS) {
                com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in localTabs
            } else tab in visibleMainTabs,
        ) {
            val visible = if (tab == MainNavigationTab.ALBUM_ARTISTS) {
                com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in localTabs
            } else tab in visibleMainTabs
            AppSettings.setMainNavigationTabVisible(tab, !visible)
        }
    } + LocalLibraryTab.entries.map { tab ->
        val tabTitle = stringResource(when (tab) {
            LocalLibraryTab.SONGS -> R.string.songs
            LocalLibraryTab.ALBUMS -> R.string.albums
            LocalLibraryTab.ARTISTS -> R.string.artists
            LocalLibraryTab.FOLDERS -> R.string.folders
            LocalLibraryTab.PLAYLISTS -> R.string.playlists
            LocalLibraryTab.ALBUM_ARTISTS -> R.string.album_artists
        })
        SettingsSearchEntry(
            title = stringResource(R.string.show_tab_setting, tabTitle),
            subtitle = stringResource(R.string.local_library_tabs_subtitle),
            keywords = listOf("local", "library", "tab", "ローカル", "ライブラリ", "タブ", tabTitle),
            checked = tab in localTabs,
        ) {
            AppSettings.setLocalLibraryTabVisible(tab, tab !in localTabs)
        }
    } + listOf(
        SettingsSearchEntry(
            stringResource(R.string.local_folder_hierarchy),
            stringResource(R.string.local_folder_hierarchy_subtitle),
            listOf("folder", "hierarchy", "search", "フォルダ", "階層", "検索"),
            localFolderHierarchy,
        ) { AppSettings.setLocalFolderHierarchy(!localFolderHierarchy) },
        SettingsSearchEntry(
            stringResource(R.string.include_remote_source_media_in_library),
            stringResource(R.string.include_remote_source_media_in_library_subtitle),
            listOf("library", "remote", "server", "webdav", "jellyfin", "navidrome", "subsonic", "nextcloud", "ライブラリ", "リモート", "サーバー", "音源"),
            includeRemoteSourceMediaInLibrary,
        ) { AppSettings.setIncludeRemoteSourceMediaInLibrary(!includeRemoteSourceMediaInLibrary) },
        SettingsSearchEntry(
            stringResource(R.string.allow_cleartext_http),
            stringResource(R.string.allow_cleartext_http_subtitle),
            listOf("http", "cleartext", "insecure", "network", "HTTP", "平文", "通信", "セキュリティ", "安全でない"),
            cleartextHttpAllowed,
        ) { AppSettings.setCleartextHttpAllowed(!cleartextHttpAllowed) },
        musicFolderRootSearchEntry(localMusicFolderRoot) { musicFolderRootPicker.launch(null) },
        SettingsSearchEntry(
            stringResource(R.string.full_screen_collection_artwork),
            stringResource(R.string.full_screen_collection_artwork_subtitle),
            listOf("artwork", "collection", "library", "album", "全画面", "アート", "背景"),
            fullScreenCollectionArtwork,
        ) { AppSettings.setFullScreenCollectionArtwork(!fullScreenCollectionArtwork) },
        SettingsSearchEntry(
            stringResource(R.string.white_collection_action_buttons),
            stringResource(R.string.white_collection_action_buttons_subtitle),
            listOf("album", "playlist", "play", "shuffle", "white", "アルバム", "プレイリスト", "再生", "シャッフル", "白背景"),
            whiteCollectionActionButtons,
        ) { AppSettings.setWhiteCollectionActionButtons(!whiteCollectionActionButtons) },
        SettingsSearchEntry(
            stringResource(R.string.tablet_lyrics_beside_player),
            stringResource(R.string.tablet_lyrics_beside_player_subtitle),
            listOf("tablet", "lyrics", "split", "player", "タブレット", "歌詞", "分割", "左右"),
            tabletLyricsBesidePlayer,
        ) { AppSettings.setTabletLyricsBesidePlayer(!tabletLyricsBesidePlayer) },
        SettingsSearchEntry(
            stringResource(R.string.hide_collection_top_bar),
            stringResource(R.string.hide_collection_top_bar_subtitle),
            listOf("library", "album", "artist", "playlist", "header", "ライブラリ", "アルバム", "アーティスト", "上部バー"),
            hideCollectionTopBar,
        ) { AppSettings.setHideCollectionTopBar(!hideCollectionTopBar) },
        SettingsSearchEntry(
            title = stringResource(R.string.source),
            subtitle = stringResource(R.string.sources_subtitle),
            keywords = listOf("source", "addon", "webdav", "smb", "server", "音源", "ソース", "アドオン", "WebDAV", "共有フォルダ"),
            activate = onManageSources,
        ),
        SettingsSearchEntry(
            stringResource(R.string.hide_floating_collection_controls),
            stringResource(R.string.hide_floating_collection_controls_subtitle),
            listOf("album", "playlist", "artwork", "back", "settings", "アルバム", "プレイリスト", "アート", "戻る", "設定", "ボタン"),
            hideFloatingCollectionControls,
        ) { AppSettings.setHideFloatingCollectionControls(!hideFloatingCollectionControls) },
        SettingsSearchEntry(
            stringResource(R.string.artists_open_albums),
            stringResource(R.string.artists_open_albums_subtitle),
            listOf("artist", "album", "アーティスト", "アルバム", "曲一覧"),
            artistsOpenAlbums,
        ) { AppSettings.setArtistsOpenAlbums(!artistsOpenAlbums) },
        SettingsSearchEntry(
            stringResource(R.string.shuffle_artist_albums),
            stringResource(R.string.shuffle_artist_albums_subtitle),
            listOf("artist", "album", "shuffle", "アーティスト", "アルバム", "シャッフル"),
            shuffleArtistAlbums,
        ) { AppSettings.setShuffleArtistAlbums(!shuffleArtistAlbums) },
        SettingsSearchEntry(
            stringResource(R.string.online_album_artist_artwork),
            stringResource(R.string.online_album_artist_artwork_subtitle),
            listOf("album artist", "artist image", "cover", "deezer", "アルバムアーティスト", "カバー", "画像", "オンライン", "通信"),
            onlineAlbumArtistArtwork,
        ) { AppSettings.setOnlineAlbumArtistArtwork(!onlineAlbumArtistArtwork) },
    )
    }
    val settingsSearchEntries = buildSettingsSearchEntries()


    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    // Scrobbling states
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    val localPlaylists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()

    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    var showManageFoldersSheet by remember { mutableStateOf(false) }
    var showMusicServersSheet by rememberSaveable { mutableStateOf(false) }
    var showPlaylistOrderDialog by remember { mutableStateOf(false) }
    var preparedM3uImport by remember { mutableStateOf<PreparedM3uImport?>(null) }
    var m3uPlaylistName by remember { mutableStateOf("") }
    var preparedMusicoletBackup by remember { mutableStateOf<PreparedMusicoletBackup?>(null) }
    var selectedMusicoletPlaylistIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingMusicoletBackupUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showStartupTabPicker by remember { mutableStateOf(false) }
    val manageFoldersSheetState = rememberModalBottomSheetState()
    var discoveredFolders by remember { mutableStateOf<List<LocalFolder>>(emptyList()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // SAF result callbacks can arrive after this composable's remembered scope has
    // left composition (for example when the system picker recreates the activity).
    // Use the screen owner's lifecycle scope for the work they start.
    val backupScope = lifecycleOwner.lifecycleScope

    LaunchedEffect(showManageFoldersSheet) {
        if (showManageFoldersSheet) {
            discoveredFolders = LocalMediaRepository.getAllDiscoveredFolders(context)
        }
    }

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }
    var hasAllFiles by remember { mutableStateOf(LocalMediaRepository.hasAllFilesPermission(context)) }
    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hasAllFiles = LocalMediaRepository.hasAllFilesPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFiles = LocalMediaRepository.hasAllFilesPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { summary ->
                    buildString {
                        append("Exported ")
                        val parts = mutableListOf<String>()
                        if (summary.playlists > 0) parts += "${summary.playlists} ${if (summary.playlists == 1) "playlist" else "playlists"}"
                        if (summary.favorites > 0) parts += "${summary.favorites} ${if (summary.favorites == 1) "favorite" else "favorites"}"
                        if (summary.hasEqualizer) parts += "equalizer"
                        if (summary.queues > 0) parts += context.getString(R.string.saved_queue_count, summary.queues)
                        parts += "settings"
                        if (summary.months > 0) parts += context.countOfMonths(summary.months)
                        append(parts.joinToString(", "))
                    }
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = { summary ->
                    buildString {
                        append("Restored ")
                        val parts = mutableListOf<String>()
                        if (summary.playlists > 0) parts += "${summary.playlists} ${if (summary.playlists == 1) "playlist" else "playlists"}"
                        if (summary.favorites > 0) parts += "${summary.favorites} ${if (summary.favorites == 1) "favorite" else "favorites"}"
                        if (summary.hasEqualizer) parts += "equalizer"
                        if (summary.queues > 0) parts += context.getString(R.string.saved_queue_count, summary.queues)
                        parts += "settings"
                        if (summary.months > 0) parts += context.countOfMonths(summary.months)
                        append(parts.joinToString(", "))
                        if (summary.from.isNotBlank()) append(" from ${summary.from}")
                    }
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val m3uPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                source,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        backupScope.launch {
            runCatching {
                val songs = LocalMediaRepository.getLocalMusic(context)
                M3uPlaylistImporter.prepare(context, source, songs)
            }.onSuccess { prepared ->
                if (prepared.tracks.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.m3u_no_tracks_found, prepared.skippedCount),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    preparedM3uImport = prepared
                    m3uPlaylistName = prepared.suggestedName
                }
            }.onFailure { error ->
                val message = if (error is M3uImportException) {
                    context.getString(
                        when (error.reason) {
                            M3uImportFailure.CANNOT_OPEN -> R.string.m3u_cannot_open
                            M3uImportFailure.EMPTY_FILE -> R.string.m3u_empty_file
                            M3uImportFailure.INVALID_FILE -> R.string.m3u_invalid_file
                            M3uImportFailure.NO_ENTRIES -> R.string.m3u_no_entries
                        },
                    )
                } else {
                    context.getString(
                        R.string.import_failed,
                        error.message ?: context.getString(R.string.unknown_error),
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val cuePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            runCatching {
                val availableSongs = LocalMediaRepository.getLocalMusic(context)
                CueSheetImporter.prepare(context, source, availableSongs)
            }.onSuccess { prepared ->
                onPlayCueSheet(prepared.songs)
                Toast.makeText(
                    context,
                    context.getString(R.string.cue_play_result, prepared.songs.size, prepared.skippedCount),
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(context, R.string.cue_play_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun importMusicoletBackup(source: Uri) {
        backupScope.launch {
            runCatching {
                val songs = LocalMediaRepository.getLocalMusic(context)
                MusicoletBackupImporter.prepare(context, source, songs)
            }.onSuccess { prepared ->
                preparedMusicoletBackup = prepared
                selectedMusicoletPlaylistIds = prepared.playlists.map { it.id }.toSet()
            }.onFailure { error ->
                val message = if (error is MusicoletImportException) {
                    context.getString(
                        when (error.reason) {
                            MusicoletImportFailure.CANNOT_OPEN -> R.string.musicolet_cannot_open
                            MusicoletImportFailure.EMPTY_FILE -> R.string.musicolet_empty_file
                            MusicoletImportFailure.INVALID_BACKUP -> R.string.musicolet_invalid_backup
                            MusicoletImportFailure.NO_PLAYLISTS -> R.string.musicolet_no_playlists
                        },
                    )
                } else {
                    context.getString(
                        R.string.import_failed,
                        error.message ?: context.getString(R.string.unknown_error),
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val source = pendingMusicoletBackupUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        pendingMusicoletBackupUri = null
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.musicolet_audio_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
        source?.let(::importMusicoletBackup)
    }
    val musicoletBackupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                source,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            importMusicoletBackup(source)
        } else {
            pendingMusicoletBackupUri = source.toString()
            audioPermissionLauncher.launch(audioPermission)
        }
    }
    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }
    val scrobbleScope = rememberCoroutineScope()

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    val settingsActionSearchEntries = listOf(
        SettingsSearchEntry(
            title = stringResource(R.string.play_cue_sheet),
            subtitle = stringResource(R.string.play_cue_sheet_subtitle),
            keywords = listOf("CUE", "sheet", "cue sheet", "CUEシート", "再生"),
            activate = {
                cuePicker.launch(arrayOf("application/x-cue", "text/plain", "application/octet-stream", "*/*"))
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.import_m3u_playlist),
            subtitle = stringResource(R.string.import_m3u_subtitle),
            keywords = listOf("M3U", "M3U8", "playlist", "プレイリスト", "取り込み"),
            activate = {
                m3uPicker.launch(
                    arrayOf(
                        "audio/x-mpegurl",
                        "application/vnd.apple.mpegurl",
                        "application/x-mpegurl",
                        "audio/mpegurl",
                        "text/plain",
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.import_musicolet_backup),
            subtitle = stringResource(R.string.import_musicolet_backup_subtitle),
            keywords = listOf("Musicolet", "backup", "バックアップ", "プレイリスト", "ZIP"),
            activate = {
                musicoletBackupPicker.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"),
                )
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.manage_playlist_order),
            subtitle = stringResource(R.string.manage_playlist_order_subtitle),
            keywords = listOf("playlist", "order", "並び替え", "プレイリスト"),
            activate = { showPlaylistOrderDialog = true },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.lyrics_sources),
            subtitle = lyricsSourceOrder
                .filter { it in lyricsSources }
                .joinToString(", ") { it.label }
                .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
            keywords = listOf("lyrics", "source", "歌詞", "検索元"),
            activate = { onLyricsSources() },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.preload_album_art_now),
            subtitle = stringResource(R.string.preload_album_art_now_subtitle),
            keywords = listOf("artwork", "preload", "先読み", "アルバムアート"),
            activate = { onPreloadArtworkNow() },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.startup_tab),
            subtitle = stringResource(R.string.startup_tab_subtitle),
            keywords = listOf("startup", "起動", "tab", "タブ"),
            activate = {
                settingsSearchQuery = ""
                showStartupTabPicker = true
            },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.export_data),
            subtitle = stringResource(R.string.export_data_subtitle),
            keywords = listOf("backup", "export", "バックアップ", "書き出し"),
            activate = { exportPicker.launch(Backup.suggestedName()) },
        ),
        SettingsSearchEntry(
            title = stringResource(R.string.import_data),
            subtitle = stringResource(R.string.import_data_subtitle),
            keywords = listOf("backup", "restore", "import", "バックアップ", "復元"),
            activate = { confirmImport = true },
        ),
    ).let { entries ->
        if (context.packageName.endsWith(".nointernet")) entries else entries +
            musicServerSearchEntry { showMusicServersSheet = true }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(settingsScrollState)
            .padding(contentPadding),
    ) {
        val originalDeveloperLabel = stringResource(R.string.original_developer)
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        OutlinedTextField(
            value = settingsSearchQuery,
            onValueChange = { settingsSearchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.search_settings)) },
            leadingIcon = {
                Icon(
                    imageVector = BitChordIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
        )

        if (settingsSearchQuery.isNotBlank()) {
            val wearSearchEntry = if (WatchInstaller.available(context)) {
                listOf(
                    SettingsSearchEntry(
                        title = stringResource(R.string.wear_os_section),
                        subtitle = stringResource(R.string.wear_os_page_title),
                        keywords = listOf("wear", "watch", "wear os", "galaxy watch", "pixel watch", "ウォッチ", "時計"),
                        activate = onWearOs,
                    ),
                )
            } else {
                emptyList()
            }
            val matches = (settingsSearchEntries + settingsActionSearchEntries + wearSearchEntry).filter { entry ->
                (entry.title + " " + entry.subtitle + " " + entry.keywords.joinToString(" "))
                    .contains(settingsSearchQuery.trim(), ignoreCase = true)
            }
            SettingsGroup(header = stringResource(R.string.settings_search_results)) {
                if (matches.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                } else {
                    matches.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Tune,
                            title = entry.title,
                            subtitle = entry.subtitle,
                            trailing = entry.checked?.let { checked ->
                                {
                                    Switch(
                                        checked = checked,
                                        enabled = entry.enabled,
                                        onCheckedChange = { entry.activate() },
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                            } ?: entry.valueLabel?.let { valueLabel ->
                                {
                                    Text(
                                        text = valueLabel,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            },
                            onClick = if (entry.sliderValue == null) entry.activate else null,
                            enabled = entry.enabled,
                        )
                        val sliderValue = entry.sliderValue
                        val onSliderValueChange = entry.onSliderValueChange
                        if (sliderValue != null && onSliderValueChange != null) {
                            Slider(
                                value = sliderValue,
                                onValueChange = onSliderValueChange,
                                steps = entry.sliderSteps,
                                valueRange = entry.sliderValueRange,
                                enabled = entry.enabled,
                                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                            )
                        }
                    }
                }
            }
        } else {
        val originalDeveloperLabel = stringResource(R.string.original_developer)

        SettingsGroup(header = stringResource(R.string.playback)) {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.output_precision),
                subtitleContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = buildString {
                                append(outputStatus.sink)
                                append(" · ")
                                append(outputStatus.deviceName)
                                append(" · ")
                                append(AudioOutputStatus.encodingLabel(outputStatus))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sourceAudioStats?.sampleRateHz?.let { sourceRate ->
                            val appRate = outputStatus.actualSampleRateHz
                            Text(
                                text = if (appRate == null) {
                                    stringResource(R.string.audio_source_rate_only, sourceRate / 1000f)
                                } else if (sourceRate != appRate) {
                                    stringResource(R.string.audio_rate_conversion, sourceRate / 1000f, appRate / 1000f)
                                } else {
                                    stringResource(R.string.audio_rate_no_app_conversion, sourceRate / 1000f)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        outputStatus.audioTrackBufferSizeBytes?.let { bufferBytes ->
                            Text(
                                text = stringResource(R.string.audio_track_buffer_size, bufferBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AudioOutputStatus.estimatedTrackBufferDurationMs(outputStatus)?.let { durationMs ->
                            Text(
                                text = stringResource(R.string.audio_track_buffer_estimated_duration, durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (availableSystemAudioEffects.isEmpty()) R.string.audio_system_effects_unknown
                                else R.string.audio_system_effects_available,
                                availableSystemAudioEffects.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")
                                    ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bitPerfectPlayback) {
                            Text(
                                text = bitPerfectOutputStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.audio_output_rate_caveat),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.bit_perfect_playback),
                subtitle = stringResource(R.string.bit_perfect_playback_subtitle),
                trailing = {
                    Switch(
                        checked = bitPerfectPlayback,
                        onCheckedChange = AppSettings::setBitPerfectPlayback,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setBitPerfectPlayback(!bitPerfectPlayback) },
            )
            RowDivider()
            SettingsSubRow(
                title = stringResource(R.string.prefer_usb_dac),
                checked = preferUsbDac,
                onCheckedChange = AppSettings::setPreferUsbDac,
                badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.speaker_playback_allowed),
                subtitle = stringResource(R.string.speaker_playback_allowed_subtitle),
                trailing = {
                    Switch(
                        checked = speakerPlaybackAllowed,
                        onCheckedChange = AppSettings::setSpeakerPlaybackAllowed,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSpeakerPlaybackAllowed(!speakerPlaybackAllowed) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                title = stringResource(R.string.skip_silence),
                subtitle = stringResource(R.string.skip_silence_subtitle),
                trailing = {
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = AppSettings::setSkipSilence,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSkipSilence(!skipSilence) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.loudness_normalization),
                subtitle = stringResource(R.string.loudness_normalization_subtitle),
                trailing = {
                    Switch(
                        checked = loudnessNormalization,
                        onCheckedChange = AppSettings::setLoudnessNormalization,
                    )
                },
                onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
            )
            if (loudnessNormalization) {
                SettingsSubRow(
                    title = stringResource(R.string.loudness_off_on_speaker),
                    subtitle = stringResource(R.string.loudness_off_on_speaker_subtitle),
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.volume_boost),
                subtitle = stringResource(R.string.volume_boost_subtitle),
                trailing = {
                    Switch(
                        checked = volumeBoostEnabled,
                        onCheckedChange = AppSettings::setVolumeBoostEnabled,
                    )
                },
                onClick = { AppSettings.setVolumeBoostEnabled(!volumeBoostEnabled) },
            )
            if (volumeBoostEnabled) {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.volume_boost),
                    trailing = {
                        Text(
                            text = stringResource(R.string.volume_boost_value, volumeBoostDb),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
                Slider(
                    value = volumeBoostDb,
                    onValueChange = AppSettings::setVolumeBoostDb,
                    valueRange = 0f..12f,
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.automix),
                subtitle = stringResource(if (smartFade) R.string.automix_enabled_subtitle else R.string.automix_disabled_subtitle),
                trailing = {
                    Switch(
                        checked = smartFade,
                        onCheckedChange = AppSettings::setSmartFadeEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.automix_performance),
                subtitle = stringResource(R.string.automix_performance_subtitle),
                value = automixPerformance.localizedLabel(),
                onClick = { showAutomixPerformanceSheet = true },
            )
            if (!smartFade) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FastForward,
                    title = stringResource(R.string.crossfade),
                    subtitle = stringResource(R.string.crossfade_subtitle),
                    value = if (crossfadeSeconds == 0) stringResource(R.string.off)
                        else stringResource(R.string.crossfade_value, crossfadeSeconds),
                )
                Slider(
                    value = crossfadeSeconds.toFloat(),
                    onValueChange = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                    steps = AppSettings.MAX_CROSSFADE_SECONDS - 1,
                    valueRange = 0f..AppSettings.MAX_CROSSFADE_SECONDS.toFloat(),
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.audio_offload),
                subtitle = stringResource(
                    if (crossfadeSeconds > 0 || smartFade) R.string.audio_offload_disabled_by_transition
                    else R.string.audio_offload_subtitle,
                ),
                enabled = crossfadeSeconds == 0 && !smartFade,
                trailing = {
                    Switch(
                        checked = audioOffloadEnabled && crossfadeSeconds == 0 && !smartFade,
                        onCheckedChange = { enabled ->
                            if (crossfadeSeconds == 0 && !smartFade) AppSettings.setAudioOffloadEnabled(enabled)
                        },
                        enabled = crossfadeSeconds == 0 && !smartFade,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (crossfadeSeconds == 0 && !smartFade) AppSettings.setAudioOffloadEnabled(!audioOffloadEnabled)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = stringResource(R.string.dolby_atmos),
                subtitle = stringResource(R.string.dolby_atmos_subtitle),
                trailing = {
                    Switch(
                        checked = dolbyAtmos,
                        onCheckedChange = AppSettings::setDolbyAtmos,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.resampler_cutoff),
                subtitle = stringResource(R.string.resampler_cutoff_subtitle),
                trailing = {
                    Switch(
                        checked = resamplerCutoffHz > 0,
                        onCheckedChange = { enabled ->
                            AppSettings.setResamplerCutoffHz(if (enabled) 20_000 else 0)
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setResamplerCutoffHz(if (resamplerCutoffHz > 0) 0 else 20_000)
                },
            )
            if (resamplerCutoffHz > 0) {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.resampler_cutoff),
                    trailing = {
                        Text(
                            text = stringResource(R.string.resampler_cutoff_value, resamplerCutoffHz),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
                Slider(
                    value = resamplerCutoffHz.toFloat(),
                    onValueChange = { AppSettings.setResamplerCutoffHz(it.roundToInt()) },
                    steps = 13,
                    valueRange = 8_000f..22_000f,
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.audio_focus_level),
                subtitle = stringResource(R.string.audio_focus_level_subtitle),
                trailing = {
                    Text(
                        text = audioFocusLevel.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Slider(
                value = audioFocusLevel.toFloat(),
                onValueChange = { AppSettings.setAudioFocusLevel(it.roundToInt()) },
                steps = 3,
                valueRange = 0f..4f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.double_tap_to_seek),
                subtitle = stringResource(R.string.double_tap_to_seek_subtitle),
                trailing = {
                    Switch(
                        checked = doubleTapToSeek,
                        onCheckedChange = AppSettings::setDoubleTapToSeek,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setDoubleTapToSeek(!doubleTapToSeek) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.seek_interval),
                subtitle = stringResource(R.string.seek_interval_subtitle),
                trailing = {
                    Text(
                        text = stringResource(R.string.seek_interval_value, seekIntervalSeconds),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Slider(
                value = seekIntervalSeconds.toFloat(),
                onValueChange = { AppSettings.setSeekIntervalSeconds(it.roundToInt()) },
                steps = 58,
                valueRange = 1f..60f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.recent_play_threshold),
                subtitle = stringResource(R.string.recent_play_threshold_subtitle),
                trailing = {
                    Text(
                        text = stringResource(R.string.recent_play_threshold_value, recentlyPlayedThresholdSeconds),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Slider(
                value = recentlyPlayedThresholdSeconds.toFloat(),
                onValueChange = { AppSettings.setRecentlyPlayedThresholdSeconds(it.roundToInt()) },
                valueRange = 0f..AppSettings.MAX_RECENT_PLAY_THRESHOLD_SECONDS.toFloat(),
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FastForward,
                title = stringResource(R.string.seek_button_mode),
                subtitle = stringResource(R.string.seek_button_mode_subtitle),
            )
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.seek_buttons_off),
                    stringResource(R.string.seek_buttons_always),
                    stringResource(R.string.seek_buttons_long_tracks),
                ),
                selectedIndex = SeekButtonMode.entries.indexOf(seekButtonMode),
                onSelect = { AppSettings.setSeekButtonMode(SeekButtonMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.transport_left_action),
                subtitle = stringResource(R.string.transport_side_action_subtitle),
                value = transportLeftAction.localizedLabel(),
                onClick = { transportActionSlot = "left" },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.transport_right_action),
                subtitle = stringResource(R.string.transport_side_action_subtitle),
                value = transportRightAction.localizedLabel(),
                onClick = { transportActionSlot = "right" },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.show_track_duration_instead_of_remaining),
                subtitle = stringResource(R.string.show_track_duration_instead_of_remaining_subtitle),
                trailing = {
                    Switch(
                        checked = showTrackDurationInsteadOfRemaining,
                        onCheckedChange = AppSettings::setShowTrackDurationInsteadOfRemaining,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setShowTrackDurationInsteadOfRemaining(!showTrackDurationInsteadOfRemaining)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.hide_seek_button_labels),
                subtitle = stringResource(R.string.hide_seek_button_labels_subtitle),
                trailing = {
                    Switch(
                        checked = hideSeekButtonLabels,
                        onCheckedChange = AppSettings::setHideSeekButtonLabels,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideSeekButtonLabels(!hideSeekButtonLabels) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = stringResource(R.string.spatial_audio),
                subtitle = stringResource(R.string.spatial_audio_subtitle),
                trailing = {
                    Switch(
                        checked = spatialAudio,
                        onCheckedChange = AppSettings::setSpatialAudio,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.parametric_eq),
                subtitle = stringResource(R.string.parametric_eq_subtitle),
                trailing = {
                    Switch(
                        checked = parametricEqEnabled,
                        onCheckedChange = AppSettings::setParametricEqEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setParametricEqEnabled(!parametricEqEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Waves,
                title = stringResource(R.string.tpdf_dither),
                subtitle = stringResource(R.string.tpdf_dither_subtitle),
                trailing = {
                    Switch(
                        checked = tpdfDitherEnabled,
                        onCheckedChange = AppSettings::setTpdfDitherEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setTpdfDitherEnabled(!tpdfDitherEnabled) },
            )
            RowDivider()
            ChannelSwapSettingsRow(
                checked = channelSwapEnabled,
                onCheckedChange = AppSettings::setChannelSwapEnabled,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = stringResource(R.string.reverb),
                subtitle = stringResource(R.string.reverb_subtitle),
                trailing = {
                    Switch(
                        checked = reverbEnabled,
                        onCheckedChange = AppSettings::setReverbEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReverbEnabled(!reverbEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.equalizer),
                subtitle = stringResource(R.string.equalizer_subtitle),
                onClick = { showEqualizerSheet = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = stringResource(R.string.dirac_audio_eq),
                subtitle = stringResource(R.string.dirac_audio_eq_subtitle),
                trailing = {
                    Switch(
                        checked = diracAudioEnabled,
                        onCheckedChange = AppSettings::setDiracAudioEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setDiracAudioEnabled(!diracAudioEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.hide_lossless_label),
                subtitle = stringResource(R.string.hide_lossless_label_subtitle),
                trailing = {
                    Switch(
                        checked = hideLosslessLabel,
                        onCheckedChange = AppSettings::setHideLosslessLabel,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideLosslessLabel(!hideLosslessLabel) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.popup_external_audio_player),
                subtitle = stringResource(R.string.popup_external_audio_player_subtitle),
                trailing = {
                    Switch(
                        checked = popupExternalAudioPlayer,
                        onCheckedChange = setPopupExternalAudioPlayerWithPermission,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    setPopupExternalAudioPlayerWithPermission(!popupExternalAudioPlayer)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.restart_queue_when_ended),
                subtitle = stringResource(R.string.restart_queue_when_ended_subtitle),
                trailing = {
                    Switch(
                        checked = restartQueueWhenEnded,
                        onCheckedChange = AppSettings::setRestartQueueWhenEnded,
                    )
                },
                onClick = { AppSettings.setRestartQueueWhenEnded(!restartQueueWhenEnded) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.advance_queue_when_repeat_off),
                subtitle = stringResource(R.string.advance_queue_when_repeat_off_subtitle),
                trailing = {
                    Switch(
                        checked = advanceQueueWhenRepeatOff,
                        onCheckedChange = AppSettings::setAdvanceQueueWhenRepeatOff,
                    )
                },
                onClick = {
                    AppSettings.setAdvanceQueueWhenRepeatOff(!advanceQueueWhenRepeatOff)
                },
            )
        }

        SettingsGroup(header = stringResource(R.string.media_controls)) {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.media_control_secondary_action),
                subtitle = stringResource(R.string.media_control_secondary_action_subtitle),
                value = mediaControlSecondaryAction.localizedLabel(),
                onClick = { showMediaControlActionPicker = true },
            )
            RowDivider()
            SettingsRow(
                icon = BitChordIcons.Star,
                title = stringResource(R.string.media_control_favorite_star),
                subtitle = stringResource(R.string.media_control_favorite_star_subtitle),
                trailing = {
                    Switch(
                        checked = mediaControlFavoriteUsesStar,
                        onCheckedChange = AppSettings::setMediaControlFavoriteUsesStar,
                    )
                },
                onClick = { AppSettings.setMediaControlFavoriteUsesStar(!mediaControlFavoriteUsesStar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.hide_media_control_custom_actions),
                subtitle = stringResource(R.string.hide_media_control_custom_actions_subtitle),
                trailing = {
                    Switch(
                        checked = hideMediaControlCustomActions,
                        onCheckedChange = AppSettings::setHideMediaControlCustomActions,
                    )
                },
                onClick = { AppSettings.setHideMediaControlCustomActions(!hideMediaControlCustomActions) },
            )
        }

        SettingsGroup(header = stringResource(R.string.player_controls)) {
            playerControlOrder.forEachIndexed { index, control ->
                if (index > 0) RowDivider()
                val title = when (control) {
                    PlayerControl.SHUFFLE -> stringResource(R.string.player_control_shuffle)
                    PlayerControl.REPEAT -> stringResource(R.string.player_control_repeat)
                    PlayerControl.LYRICS -> stringResource(R.string.player_control_lyrics)
                    PlayerControl.LIKE -> stringResource(R.string.player_control_like)
                    PlayerControl.QUEUE -> stringResource(R.string.player_control_queue)
                    PlayerControl.CHROMECAST -> stringResource(R.string.player_control_chromecast)
                    PlayerControl.PLAYLIST -> stringResource(R.string.player_control_playlist)
                    PlayerControl.SEARCH -> stringResource(R.string.player_control_search)
                }
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = title,
                    subtitle = stringResource(R.string.player_control_order_subtitle),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = index > 0,
                                onClick = { AppSettings.movePlayerControl(control, -1) },
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    stringResource(R.string.move_up),
                                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            IconButton(
                                enabled = index < playerControlOrder.lastIndex,
                                onClick = { AppSettings.movePlayerControl(control, 1) },
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowDownward,
                                    stringResource(R.string.move_down),
                                    tint = if (index < playerControlOrder.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            Switch(
                                checked = control in visiblePlayerControls,
                                onCheckedChange = { AppSettings.setPlayerControlVisible(control, it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    },
                )
            }
            RowDivider()
            PlayerActionButtonStyleRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                titleRes = R.string.apple_music_lyrics_button_style,
                checked = appleMusicLyricsButtonStyle,
                onCheckedChange = AppSettings::setAppleMusicLyricsButtonStyle,
            )
            RowDivider()
            PlayerActionButtonStyleRow(
                icon = Icons.Rounded.Cast,
                titleRes = R.string.apple_music_chromecast_button_style,
                checked = appleMusicChromecastButtonStyle,
                onCheckedChange = AppSettings::setAppleMusicChromecastButtonStyle,
            )
            RowDivider()
            PlayerActionButtonStyleRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                titleRes = R.string.apple_music_queue_button_style,
                checked = appleMusicQueueButtonStyle,
                onCheckedChange = AppSettings::setAppleMusicQueueButtonStyle,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.player_playlist_library_icon),
                subtitle = stringResource(R.string.player_playlist_library_icon_subtitle),
                trailing = {
                    Switch(
                        checked = useLibraryIconForPlaylistControl,
                        onCheckedChange = AppSettings::setUseLibraryIconForPlaylistControl,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setUseLibraryIconForPlaylistControl(!useLibraryIconForPlaylistControl)
                },
            )
            RowDivider()
            SettingsRow(
                icon = BitChordIcons.Star,
                title = stringResource(R.string.favorite_icon_star),
                subtitle = stringResource(R.string.favorite_icon_star_subtitle),
                trailing = {
                    Switch(
                        checked = favoriteUsesStar,
                        onCheckedChange = AppSettings::setFavoriteUsesStar,
                    )
                },
                onClick = { AppSettings.setFavoriteUsesStar(!favoriteUsesStar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VolumeOff,
                title = stringResource(R.string.prevent_play_at_zero_volume),
                subtitle = stringResource(R.string.prevent_play_at_zero_volume_subtitle),
                trailing = {
                    Switch(
                        checked = preventPlayAtZeroVolume,
                        onCheckedChange = AppSettings::setPreventPlayAtZeroVolume,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPreventPlayAtZeroVolume(!preventPlayAtZeroVolume) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VolumeOff,
                title = stringResource(R.string.pause_playback_at_zero_volume),
                subtitle = stringResource(R.string.pause_playback_at_zero_volume_subtitle),
                trailing = {
                    Switch(
                        checked = pausePlaybackAtZeroVolume,
                        onCheckedChange = AppSettings::setPausePlaybackAtZeroVolume,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPausePlaybackAtZeroVolume(!pausePlaybackAtZeroVolume) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.hide_player_artist),
                subtitle = stringResource(R.string.hide_player_artist_subtitle),
                trailing = {
                    Switch(
                        checked = hidePlayerArtist,
                        onCheckedChange = AppSettings::setHidePlayerArtist,
                    )
                },
                onClick = { AppSettings.setHidePlayerArtist(!hidePlayerArtist) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.hide_unknown_player_artist),
                subtitle = stringResource(R.string.hide_unknown_player_artist_subtitle),
                trailing = {
                    Switch(
                        checked = hideUnknownPlayerArtist,
                        onCheckedChange = AppSettings::setHideUnknownPlayerArtist,
                    )
                },
                onClick = { AppSettings.setHideUnknownPlayerArtist(!hideUnknownPlayerArtist) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.center_player_track_info),
                subtitle = stringResource(R.string.center_player_track_info_subtitle),
                trailing = {
                    Switch(
                        checked = centerPlayerTrackInfo,
                        onCheckedChange = AppSettings::setCenterPlayerTrackInfo,
                    )
                },
                onClick = { AppSettings.setCenterPlayerTrackInfo(!centerPlayerTrackInfo) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.player_title_font_size),
                subtitle = stringResource(R.string.player_title_font_size_subtitle),
                value = stringResource(R.string.player_font_size_value, playerTitleFontSizeSp.roundToInt()),
            )
            Slider(
                value = playerTitleFontSizeSp,
                onValueChange = AppSettings::setPlayerTitleFontSizeSp,
                steps = 23,
                valueRange = 12f..36f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.player_artist_font_size),
                subtitle = stringResource(R.string.player_artist_font_size_subtitle),
                value = stringResource(R.string.player_font_size_value, playerArtistFontSizeSp.roundToInt()),
            )
            Slider(
                value = playerArtistFontSizeSp,
                onValueChange = AppSettings::setPlayerArtistFontSizeSp,
                steps = 23,
                valueRange = 12f..36f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlayArrow,
                title = stringResource(R.string.player_play_pause_icon_size),
                subtitle = stringResource(R.string.player_play_pause_icon_size_subtitle),
                value = stringResource(R.string.player_icon_size_value, playerPlayPauseIconSizeDp.roundToInt()),
            )
            Slider(
                value = playerPlayPauseIconSizeDp,
                onValueChange = AppSettings::setPlayerPlayPauseIconSizeDp,
                steps = 37,
                valueRange = 40f..78f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlayArrow,
                title = stringResource(R.string.player_play_pause_corner_roundness),
                subtitle = stringResource(R.string.player_play_pause_corner_roundness_subtitle),
                value = stringResource(R.string.player_corner_roundness_value, playerPlayPauseCornerRadiusPercent),
            )
            Slider(
                value = playerPlayPauseCornerRadiusPercent.toFloat(),
                onValueChange = { AppSettings.setPlayerPlayPauseCornerRadiusPercent(it.roundToInt()) },
                steps = 99,
                valueRange = 0f..100f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.player_transport_button_spacing),
                subtitle = stringResource(R.string.player_transport_button_spacing_subtitle),
                value = if (playerTransportButtonSpacingDp <= 0f) {
                    stringResource(R.string.player_transport_spacing_auto)
                } else {
                    stringResource(R.string.player_transport_spacing_value, playerTransportButtonSpacingDp.roundToInt())
                },
            )
            Slider(
                value = playerTransportButtonSpacingDp,
                onValueChange = AppSettings::setPlayerTransportButtonSpacingDp,
                steps = MAX_PLAYER_BUTTON_SPACING_DP.toInt() - 1,
                valueRange = 0f..MAX_PLAYER_BUTTON_SPACING_DP,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.player_action_button_spacing),
                subtitle = stringResource(R.string.player_action_button_spacing_subtitle),
                value = if (playerActionButtonSpacingDp <= 0f) {
                    stringResource(R.string.player_transport_spacing_auto)
                } else {
                    stringResource(R.string.player_transport_spacing_value, playerActionButtonSpacingDp.roundToInt())
                },
            )
            Slider(
                value = playerActionButtonSpacingDp,
                onValueChange = AppSettings::setPlayerActionButtonSpacingDp,
                steps = MAX_PLAYER_BUTTON_SPACING_DP.toInt() - 1,
                valueRange = 0f..MAX_PLAYER_BUTTON_SPACING_DP,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.show_player_top_grabber),
                subtitle = stringResource(R.string.show_player_top_grabber_subtitle),
                trailing = {
                    Switch(
                        checked = showPlayerTopGrabber,
                        onCheckedChange = AppSettings::setShowPlayerTopGrabber,
                    )
                },
                onClick = { AppSettings.setShowPlayerTopGrabber(!showPlayerTopGrabber) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MoreVert,
                title = stringResource(R.string.player_details_vertical_menu),
                subtitle = stringResource(R.string.player_details_vertical_menu_subtitle),
                trailing = {
                    Switch(
                        checked = playerDetailsVerticalMenu,
                        onCheckedChange = AppSettings::setPlayerDetailsVerticalMenu,
                    )
                },
                onClick = {
                    AppSettings.setPlayerDetailsVerticalMenu(!playerDetailsVerticalMenu)
                },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.player_quick_actions),
            footer = stringResource(R.string.player_quick_actions_subtitle),
        ) {
            SettingsRow(
                icon = Icons.Rounded.Circle,
                title = stringResource(R.string.hide_player_quick_action_circles),
                subtitle = stringResource(R.string.hide_player_quick_action_circles_subtitle),
                trailing = {
                    Switch(
                        checked = hidePlayerQuickActionCircles,
                        onCheckedChange = AppSettings::setHidePlayerQuickActionCircles,
                    )
                },
                onClick = { AppSettings.setHidePlayerQuickActionCircles(!hidePlayerQuickActionCircles) },
            )
            RowDivider()
            PlayerQuickAction.entries.forEachIndexed { actionIndex, action ->
                if (actionIndex > 0) RowDivider()
                val titleRes = when (action) {
                    PlayerQuickAction.LYRICS -> R.string.quick_action_lyrics
                    PlayerQuickAction.ADD_TO_PLAYLIST -> R.string.quick_action_add_to_playlist
                    PlayerQuickAction.PLAYBACK_TUNING -> R.string.quick_action_playback_tuning
                    PlayerQuickAction.QUEUE -> R.string.quick_action_queue
                    PlayerQuickAction.PLAYLISTS -> R.string.quick_action_playlists
                    PlayerQuickAction.SEARCH -> R.string.quick_action_search
                    PlayerQuickAction.ALBUM -> R.string.go_to_album
                    PlayerQuickAction.ARTIST -> R.string.go_to_artist
                    PlayerQuickAction.EQUALIZER -> R.string.equalizer
                    PlayerQuickAction.CHROMECAST -> R.string.chromecast
                    PlayerQuickAction.SLEEP_TIMER -> R.string.sleep_timer
                    PlayerQuickAction.TAG_EDITOR -> R.string.tag_editor
                    PlayerQuickAction.EDIT_LYRICS -> R.string.edit_lyrics
                    PlayerQuickAction.DETAILS -> R.string.details
                    PlayerQuickAction.SHARE_FILE -> R.string.share_file
                    PlayerQuickAction.FAVORITE -> R.string.quick_action_favorite
            PlayerQuickAction.SHUFFLE -> R.string.player_control_shuffle
            PlayerQuickAction.REPEAT -> R.string.player_control_repeat
                }
                val selectedIndex = quickActions.indexOf(action)
                SettingsRow(
                    icon = when (action) {
                        PlayerQuickAction.LYRICS, PlayerQuickAction.EDIT_LYRICS -> Icons.AutoMirrored.Rounded.Notes
                        PlayerQuickAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
                        PlayerQuickAction.PLAYBACK_TUNING -> Icons.Rounded.Speed
                        PlayerQuickAction.QUEUE -> Icons.Rounded.PlaylistPlay
                        PlayerQuickAction.PLAYLISTS -> Icons.Rounded.LibraryMusic
                        PlayerQuickAction.SEARCH -> Icons.Rounded.FilterAlt
                        PlayerQuickAction.ALBUM -> Icons.Rounded.Album
                        PlayerQuickAction.ARTIST -> Icons.Rounded.Person
                        PlayerQuickAction.EQUALIZER -> Icons.Rounded.GraphicEq
                        PlayerQuickAction.CHROMECAST -> Icons.Rounded.Cast
                        PlayerQuickAction.SLEEP_TIMER -> Icons.Rounded.Bedtime
                        PlayerQuickAction.TAG_EDITOR -> Icons.Rounded.Edit
                        PlayerQuickAction.DETAILS -> Icons.Rounded.Info
                        PlayerQuickAction.SHARE_FILE -> Icons.Rounded.Share
                        PlayerQuickAction.FAVORITE -> BitChordIcons.Heart
                        PlayerQuickAction.SHUFFLE -> BitChordIcons.Shuffle
                        PlayerQuickAction.REPEAT -> BitChordIcons.Repeat
                    },
                    title = stringResource(titleRes),
                    subtitle = if (selectedIndex >= 0) stringResource(R.string.player_quick_action_order_subtitle) else null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectedIndex >= 0) {
                                IconButton(
                                    enabled = selectedIndex > 0,
                                    onClick = { AppSettings.movePlayerQuickAction(action, -1) },
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        stringResource(R.string.move_up),
                                        tint = if (selectedIndex > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                                IconButton(
                                    enabled = selectedIndex < quickActions.lastIndex,
                                    onClick = { AppSettings.movePlayerQuickAction(action, 1) },
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowDownward,
                                        stringResource(R.string.move_down),
                                        tint = if (selectedIndex < quickActions.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = selectedIndex >= 0,
                                onCheckedChange = { checked ->
                                    AppSettings.setPlayerQuickActions(
                                        if (checked) quickActions + action else quickActions - action,
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.player_details_actions),
            footer = stringResource(R.string.player_details_actions_subtitle),
        ) {
            SettingsRow(
                icon = Icons.Rounded.AudioFile,
                title = stringResource(R.string.audio_cutter_enabled),
                subtitle = stringResource(R.string.audio_cutter_enabled_subtitle),
                trailing = {
                    Switch(
                        checked = audioCutterEnabled,
                        onCheckedChange = AppSettings::setAudioCutterEnabled,
                    )
                },
                onClick = { AppSettings.setAudioCutterEnabled(!audioCutterEnabled) },
            )
            PlayerDetailsAction.entries.forEach { action ->
                RowDivider()
                val title = stringResource(when (action) {
                    PlayerDetailsAction.ALBUM -> R.string.go_to_album
                    PlayerDetailsAction.ARTIST -> R.string.go_to_artist
                    PlayerDetailsAction.ADD_TO_PLAYLIST -> R.string.add_to_playlist
                    PlayerDetailsAction.EQUALIZER -> R.string.equalizer
                    PlayerDetailsAction.CHROMECAST -> R.string.chromecast
                    PlayerDetailsAction.PLAYBACK_TUNING -> R.string.playback_tuning
                    PlayerDetailsAction.SLEEP_TIMER -> R.string.sleep_timer
                    PlayerDetailsAction.TAG_EDITOR -> R.string.tag_editor
                    PlayerDetailsAction.EDIT_LYRICS -> R.string.edit_lyrics
                    PlayerDetailsAction.DETAILS -> R.string.details
                    PlayerDetailsAction.SHARE_FILE -> R.string.share_file
                })
                val visible = action !in hiddenPlayerDetailsActions
                SettingsRow(
                    icon = Icons.Rounded.MoreVert,
                    title = title,
                    trailing = {
                        Switch(
                            checked = visible,
                            onCheckedChange = { AppSettings.setPlayerDetailsActionVisible(action, it) },
                        )
                    },
                    onClick = { AppSettings.setPlayerDetailsActionVisible(action, !visible) },
                )
            }
        }

        SettingsGroup(header = stringResource(R.string.lyrics_display)) {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_font_size),
                trailing = {
                    Text(
                        text = stringResource(R.string.lyrics_font_size_value, (lyricsFontScale * 100f).roundToInt()),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Slider(
                value = lyricsFontScale,
                onValueChange = AppSettings::setLyricsFontScale,
                steps = 19,
                valueRange = 0.6f..1.6f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_text_alignment),
            )
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.lyrics_align_left),
                    stringResource(R.string.lyrics_align_center),
                    stringResource(R.string.lyrics_align_right),
                ),
                selectedIndex = LyricsTextAlignment.entries.indexOf(lyricsTextAlignment),
                onSelect = { AppSettings.setLyricsTextAlignment(LyricsTextAlignment.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_furigana),
                subtitle = stringResource(R.string.lyrics_furigana_subtitle),
                trailing = { Switch(checked = showLyricsFurigana, onCheckedChange = AppSettings::setShowLyricsFurigana) },
                onClick = { AppSettings.setShowLyricsFurigana(!showLyricsFurigana) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_auto_fit),
                subtitle = stringResource(R.string.lyrics_auto_fit_subtitle),
                trailing = { Switch(checked = autoFitOneLineLyrics, onCheckedChange = AppSettings::setAutoFitOneLineLyrics) },
                onClick = { AppSettings.setAutoFitOneLineLyrics(!autoFitOneLineLyrics) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.tablet_lyrics_beside_player),
                subtitle = stringResource(R.string.tablet_lyrics_beside_player_subtitle),
                trailing = {
                    Switch(
                        checked = tabletLyricsBesidePlayer,
                        onCheckedChange = AppSettings::setTabletLyricsBesidePlayer,
                    )
                },
                onClick = { AppSettings.setTabletLyricsBesidePlayer(!tabletLyricsBesidePlayer) },
            )
        }

        SettingsGroup(header = stringResource(R.string.appearance)) {
            SettingsRow(
                icon = Icons.Rounded.Brightness4,
                title = stringResource(R.string.theme),
                value = theme.localizedLabel(),
                onClick = { showThemePicker = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.choose_theme_image),
                subtitle = stringResource(R.string.choose_theme_image_subtitle),
                value = stringResource(if (customThemeImageUri.isNullOrBlank()) R.string.choose_image else R.string.theme_image_selected),
                onClick = chooseThemeImage,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ViewStream,
                title = stringResource(R.string.pill_widget_opacity),
                subtitle = stringResource(R.string.pill_widget_opacity_subtitle),
                trailing = {
                    Text(
                        text = stringResource(R.string.pill_widget_opacity_value, pillWidgetOpacity.roundToInt()),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Slider(
                value = pillWidgetOpacity,
                onValueChange = AppSettings::setPillWidgetOpacity,
                valueRange = 0f..100f,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            if (!customThemeImageUri.isNullOrBlank()) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.clear_theme_image),
                    onClick = {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(customThemeImageUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        AppSettings.setCustomThemeImageUri(null)
                        if (theme == ThemeMode.CUSTOM_IMAGE) AppSettings.setThemeMode(ThemeMode.DARK)
                    },
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.details_sheet_artwork_backdrop),
                subtitle = stringResource(R.string.details_sheet_artwork_backdrop_subtitle),
                trailing = {
                    Switch(
                        checked = detailsSheetArtworkBackdrop,
                        onCheckedChange = AppSettings::setDetailsSheetArtworkBackdrop,
                    )
                },
                onClick = { AppSettings.setDetailsSheetArtworkBackdrop(!detailsSheetArtworkBackdrop) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.show_embedded_comment),
                subtitle = stringResource(R.string.show_embedded_comment_subtitle),
                trailing = {
                    Switch(
                        checked = showEmbeddedComment,
                        onCheckedChange = AppSettings::setShowEmbeddedComment,
                    )
                },
                onClick = { AppSettings.setShowEmbeddedComment(!showEmbeddedComment) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.app_icon),
                subtitle = stringResource(R.string.app_icon_subtitle),
                value = stringResource(when (launcherIcon) {
                    LauncherIcon.PINK -> R.string.app_icon_pink
                    LauncherIcon.BLACK -> R.string.app_icon_black
                    LauncherIcon.GREEN -> R.string.app_icon_green
                }),
                onClick = { showLauncherIconPicker = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.match_button_accent_to_artwork),
                subtitle = stringResource(R.string.match_button_accent_to_artwork_subtitle),
                trailing = {
                    Switch(
                        checked = matchButtonAccentToArtwork,
                        onCheckedChange = AppSettings::setMatchButtonAccentToArtwork,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMatchButtonAccentToArtwork(!matchButtonAccentToArtwork) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MoreVert,
                title = stringResource(R.string.settings_top_bar_more_icon),
                subtitle = stringResource(R.string.settings_top_bar_more_icon_subtitle),
                trailing = {
                    Switch(
                        checked = settingsTopBarUsesMoreIcon,
                        onCheckedChange = AppSettings::setSettingsTopBarUsesMoreIcon,
                    )
                },
                onClick = { AppSettings.setSettingsTopBarUsesMoreIcon(!settingsTopBarUsesMoreIcon) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.mini_player_artwork_color),
                subtitle = stringResource(R.string.mini_player_artwork_color_subtitle),
                trailing = {
                    Switch(
                        checked = miniPlayerArtworkColor,
                        onCheckedChange = AppSettings::setMiniPlayerArtworkColor,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMiniPlayerArtworkColor(!miniPlayerArtworkColor) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ViewStream,
                title = stringResource(R.string.mini_player_artwork_outline),
                subtitle = stringResource(R.string.mini_player_artwork_outline_subtitle),
                trailing = {
                    Switch(
                        checked = miniPlayerArtworkOutline,
                        onCheckedChange = AppSettings::setMiniPlayerArtworkOutline,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMiniPlayerArtworkOutline(!miniPlayerArtworkOutline) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SkipPrevious,
                title = stringResource(R.string.mini_player_previous_button),
                subtitle = stringResource(R.string.mini_player_previous_button_subtitle),
                trailing = {
                    Switch(
                        checked = miniPlayerPreviousButton,
                        onCheckedChange = AppSettings::setMiniPlayerPreviousButton,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMiniPlayerPreviousButton(!miniPlayerPreviousButton) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SwapHoriz,
                title = stringResource(R.string.mini_player_swipe_changes_track),
                subtitle = stringResource(R.string.mini_player_swipe_changes_track_subtitle),
                trailing = {
                    Switch(
                        checked = miniPlayerSwipeChangesTrack,
                        onCheckedChange = AppSettings::setMiniPlayerSwipeChangesTrack,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMiniPlayerSwipeChangesTrack(!miniPlayerSwipeChangesTrack) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ArrowUpward,
                title = stringResource(R.string.mini_player_swipe_up_expands),
                subtitle = stringResource(R.string.mini_player_swipe_up_expands_subtitle),
                trailing = {
                    Switch(
                        checked = miniPlayerSwipeUpExpands,
                        onCheckedChange = AppSettings::setMiniPlayerSwipeUpExpands,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMiniPlayerSwipeUpExpands(!miniPlayerSwipeUpExpands) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.playlist_track_menu_artwork_color),
                subtitle = stringResource(R.string.playlist_track_menu_artwork_color_subtitle),
                trailing = {
                    Switch(
                        checked = playlistTrackMenuUsesArtworkColor,
                        onCheckedChange = AppSettings::setPlaylistTrackMenuUsesArtworkColor,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPlaylistTrackMenuUsesArtworkColor(!playlistTrackMenuUsesArtworkColor) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOn,
                title = stringResource(R.string.frosted_queue_manager),
                subtitle = stringResource(R.string.frosted_queue_manager_subtitle),
                trailing = {
                    Switch(
                        checked = frostedQueueManager,
                        onCheckedChange = AppSettings::setFrostedQueueManager,
                    )
                },
                onClick = { AppSettings.setFrostedQueueManager(!frostedQueueManager) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.redesigned_saved_queue_manager),
                subtitle = stringResource(R.string.redesigned_saved_queue_manager_subtitle),
                trailing = {
                    Switch(
                        checked = redesignedSavedQueueManager,
                        onCheckedChange = AppSettings::setRedesignedSavedQueueManager,
                    )
                },
                onClick = { AppSettings.setRedesignedSavedQueueManager(!redesignedSavedQueueManager) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.hide_saved_queue_heading),
                subtitle = stringResource(R.string.hide_saved_queue_heading_subtitle),
                trailing = {
                    Switch(
                        checked = hideSavedQueueHeading,
                        onCheckedChange = AppSettings::setHideSavedQueueHeading,
                    )
                },
                onClick = { AppSettings.setHideSavedQueueHeading(!hideSavedQueueHeading) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOn,
                title = stringResource(R.string.frosted_player_details),
                subtitle = stringResource(R.string.frosted_player_details_subtitle),
                trailing = {
                    Switch(
                        checked = frostedPlayerDetails,
                        onCheckedChange = AppSettings::setFrostedPlayerDetails,
                    )
                },
                onClick = { AppSettings.setFrostedPlayerDetails(!frostedPlayerDetails) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.queue_manage_equals_icon),
                subtitle = stringResource(R.string.queue_manage_equals_icon_subtitle),
                trailing = {
                    Switch(
                        checked = queueManagerEqualsIcon,
                        onCheckedChange = AppSettings::setQueueManagerEqualsIcon,
                    )
                },
                onClick = { AppSettings.setQueueManagerEqualsIcon(!queueManagerEqualsIcon) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.hide_queue_label),
                subtitle = stringResource(R.string.hide_queue_label_subtitle),
                trailing = {
                    Switch(
                        checked = hideQueueLabel,
                        onCheckedChange = AppSettings::setHideQueueLabel,
                    )
                },
                onClick = { AppSettings.setHideQueueLabel(!hideQueueLabel) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.queue_clear_trash_icon),
                subtitle = stringResource(R.string.queue_clear_trash_icon_subtitle),
                trailing = {
                    Switch(
                        checked = queueClearTrashIcon,
                        onCheckedChange = AppSettings::setQueueClearTrashIcon,
                    )
                },
                onClick = { AppSettings.setQueueClearTrashIcon(!queueClearTrashIcon) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.hide_playback_tuning_title),
                subtitle = stringResource(R.string.hide_playback_tuning_title_subtitle),
                trailing = {
                    Switch(
                        checked = hidePlaybackTuningTitle,
                        onCheckedChange = AppSettings::setHidePlaybackTuningTitle,
                    )
                },
                onClick = { AppSettings.setHidePlaybackTuningTitle(!hidePlaybackTuningTitle) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.playback_tuning_thin_sliders),
                subtitle = stringResource(R.string.playback_tuning_thin_sliders_subtitle),
                trailing = {
                    Switch(
                        checked = playbackTuningThinSliders,
                        onCheckedChange = AppSettings::setPlaybackTuningThinSliders,
                    )
                },
                onClick = { AppSettings.setPlaybackTuningThinSliders(!playbackTuningThinSliders) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOn,
                title = stringResource(R.string.artwork_backdrop_blur),
                subtitle = stringResource(R.string.artwork_backdrop_blur_subtitle),
                value = stringResource(R.string.artwork_backdrop_blur_value, artworkBackdropBlurDp.roundToInt()),
            )
            Slider(
                value = artworkBackdropBlurDp,
                onValueChange = AppSettings::setArtworkBackdropBlurDp,
                valueRange = 0f..MAX_PLAYER_ARTWORK_BLUR_DP,
                steps = 31,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.keep_screen_on),
                subtitle = stringResource(R.string.keep_screen_on_subtitle),
                trailing = {
                    Switch(checked = keepScreenOn, onCheckedChange = AppSettings::setKeepScreenOn)
                },
                onClick = { AppSettings.setKeepScreenOn(!keepScreenOn) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.keep_screen_on_during_lyrics),
                subtitle = stringResource(R.string.keep_screen_on_during_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = keepScreenOnDuringLyrics,
                        onCheckedChange = AppSettings::setKeepScreenOnDuringLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setKeepScreenOnDuringLyrics(!keepScreenOnDuringLyrics)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.show_status_bar),
                subtitle = stringResource(R.string.show_status_bar_search_help),
                trailing = {
                    Switch(checked = showStatusBar, onCheckedChange = AppSettings::setShowStatusBar)
                },
                onClick = { AppSettings.setShowStatusBar(!showStatusBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.show_player_status_bar),
                subtitle = stringResource(R.string.show_player_status_bar_subtitle),
                trailing = {
                    Switch(checked = showPlayerStatusBar, onCheckedChange = AppSettings::setShowPlayerStatusBar)
                },
                onClick = { AppSettings.setShowPlayerStatusBar(!showPlayerStatusBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.show_navigation_bar),
                subtitle = stringResource(R.string.system_bar_visibility_subtitle),
                trailing = {
                    Switch(checked = showNavigationBar, onCheckedChange = AppSettings::setShowNavigationBar)
                },
                onClick = { AppSettings.setShowNavigationBar(!showNavigationBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.music_only_device_mode),
                subtitle = stringResource(R.string.music_only_device_mode_subtitle),
                trailing = {
                    Switch(
                        checked = musicOnlyDeviceMode,
                        onCheckedChange = AppSettings::setMusicOnlyDeviceMode,
                    )
                },
                onClick = { AppSettings.setMusicOnlyDeviceMode(!musicOnlyDeviceMode) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ScreenRotation,
                title = stringResource(R.string.screen_orientation),
                subtitle = stringResource(R.string.screen_orientation_subtitle),
            )
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.orientation_system),
                    stringResource(R.string.orientation_portrait),
                    stringResource(R.string.orientation_landscape),
                ),
                selectedIndex = ScreenOrientationMode.entries.indexOf(orientationMode),
                onSelect = { AppSettings.setScreenOrientationMode(ScreenOrientationMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.preload_album_art),
                subtitle = stringResource(R.string.preload_album_art_subtitle),
                trailing = {
                    Switch(
                        checked = preloadAlbumArtOnStartup,
                        onCheckedChange = AppSettings::setPreloadAlbumArtOnStartup,
                    )
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.persistent_local_artwork),
                subtitle = stringResource(R.string.persistent_local_artwork_subtitle),
                trailing = {
                    Switch(
                        checked = persistentLocalArtwork,
                        onCheckedChange = AppSettings::setPersistentLocalArtwork,
                    )
                },
                onClick = { AppSettings.setPersistentLocalArtwork(!persistentLocalArtwork) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.preload_album_art_now),
                subtitle = stringResource(R.string.preload_album_art_now_subtitle),
                onClick = onPreloadArtworkNow,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MotionPhotosOff,
                title = stringResource(R.string.reduce_animation),
                subtitle = stringResource(R.string.reduce_animation_subtitle),
                trailing = {
                    Switch(
                        checked = reduceAnimation,
                        onCheckedChange = AppSettings::setReduceAnimation,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.player_artwork_transition),
                subtitle = stringResource(R.string.player_artwork_transition_subtitle),
                trailing = {
                    Switch(
                        checked = playerArtworkTransition,
                        onCheckedChange = AppSettings::setPlayerArtworkTransition,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPlayerArtworkTransition(!playerArtworkTransition) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.pill_motion_style),
                subtitle = stringResource(R.string.pill_motion_style_subtitle),
            )
            SegmentedControl(
                options = PillMotionStyle.entries.map { it.localizedLabel() },
                selectedIndex = PillMotionStyle.entries.indexOf(pillMotionStyle),
                onSelect = { AppSettings.setPillMotionStyle(PillMotionStyle.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOff,
                title = stringResource(R.string.reduce_dynamic_blur),
                subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                trailing = {
                    Switch(
                        checked = reduceDynamicBlur,
                        onCheckedChange = AppSettings::setReduceDynamicBlur,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.liquid_glass),
                subtitle = stringResource(
                    if (liquidGlassSupported) {
                        R.string.liquid_glass_subtitle
                    } else {
                        R.string.liquid_glass_unavailable
                    },
                ),
                enabled = liquidGlassSupported,
                trailing = {
                    Switch(
                        checked = liquidGlass,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ViewStream,
                title = stringResource(R.string.classic_nav_bar),
                subtitle = stringResource(R.string.classic_nav_bar_subtitle),
                trailing = {
                    Switch(
                        checked = classicNavBar,
                        onCheckedChange = AppSettings::setClassicNavBar,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setClassicNavBar(!classicNavBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.navigation_bar_matches_mini_player_color),
                subtitle = stringResource(R.string.navigation_bar_matches_mini_player_color_subtitle),
                trailing = {
                    Switch(
                        checked = navigationBarMatchesMiniPlayerColor,
                        onCheckedChange = AppSettings::setNavigationBarMatchesMiniPlayerColor,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setNavigationBarMatchesMiniPlayerColor(!navigationBarMatchesMiniPlayerColor) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MoreVert,
                title = stringResource(R.string.main_navigation_popup),
                subtitle = stringResource(R.string.main_navigation_popup_subtitle),
                trailing = {
                    Switch(
                        checked = mainNavigationTabsPopup,
                        onCheckedChange = AppSettings::setMainNavigationTabsPopup,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMainNavigationTabsPopup(!mainNavigationTabsPopup) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.LocalOffer,
                title = stringResource(R.string.hide_nav_bar_labels),
                subtitle = stringResource(R.string.hide_nav_bar_labels_subtitle),
                trailing = {
                    Switch(
                        checked = hideNavBarLabels,
                        onCheckedChange = AppSettings::setHideNavigationBarLabels,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideNavigationBarLabels(!hideNavBarLabels) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = stringResource(R.string.player_artwork_mode),
                subtitle = stringResource(R.string.player_artwork_mode_subtitle),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            playerArtworkMode.localizedLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { showPlayerArtworkModePicker = true },
            )
            RowDivider()
            if (playerArtworkMode.usesFullScreenArtwork) {
                FullBleedArtworkBottomBlurSettingsRow(fullBleedArtworkBottomBlurStrengthDp)
                RowDivider()
            }
            SettingsRow(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.walkman_player_style),
                subtitle = stringResource(R.string.walkman_player_style_subtitle),
                trailing = {
                    Switch(
                        checked = walkmanPlayerStyle,
                        onCheckedChange = AppSettings::setWalkmanPlayerStyle,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setWalkmanPlayerStyle(!walkmanPlayerStyle) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.ViewStream,
                title = stringResource(R.string.preserve_artwork_shape_in_compact_transitions),
                subtitle = stringResource(R.string.preserve_artwork_shape_in_compact_transitions_subtitle),
                trailing = {
                    Switch(
                        checked = preserveArtworkShapeInCompactTransitions,
                        onCheckedChange = AppSettings::setPreserveArtworkShapeInCompactTransitions,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setPreserveArtworkShapeInCompactTransitions(!preserveArtworkShapeInCompactTransitions)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.keep_artwork_full_size_paused),
                subtitle = stringResource(R.string.keep_artwork_full_size_paused_subtitle),
                trailing = {
                    Switch(
                        checked = keepArtworkFullSizeWhenPaused,
                        onCheckedChange = AppSettings::setKeepArtworkFullSizeWhenPaused,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setKeepArtworkFullSizeWhenPaused(!keepArtworkFullSizeWhenPaused)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Gradient,
                title = stringResource(R.string.legacy_mesh_gradient),
                subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                trailing = {
                    Switch(
                        checked = legacyMeshGradient,
                        onCheckedChange = AppSettings::setLegacyMeshGradient,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
            )

            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.synced_lyrics),
                subtitle = stringResource(R.string.synced_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.auto_show_lyrics_on_playback),
                subtitle = stringResource(R.string.auto_show_lyrics_on_playback_subtitle),
                trailing = {
                    Switch(
                        checked = autoShowLyricsOnPlayback,
                        onCheckedChange = AppSettings::setAutoShowLyricsOnPlayback,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setAutoShowLyricsOnPlayback(!autoShowLyricsOnPlayback) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.player_lyrics_strip),
                subtitle = stringResource(R.string.player_lyrics_strip_subtitle),
                trailing = {
                    Switch(
                        checked = showPlayerLyricsStrip,
                        onCheckedChange = AppSettings::setShowPlayerLyricsStrip,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setShowPlayerLyricsStrip(!showPlayerLyricsStrip) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.artwork_tap_opens_lyrics),
                subtitle = stringResource(R.string.artwork_tap_opens_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = artworkTapOpensLyrics,
                        onCheckedChange = AppSettings::setArtworkTapOpensLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setArtworkTapOpensLyrics(!artworkTapOpensLyrics) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_replace_artwork_only),
                subtitle = stringResource(R.string.lyrics_replace_artwork_only_subtitle),
                trailing = {
                    Switch(
                        checked = lyricsReplaceArtworkOnly,
                        onCheckedChange = AppSettings::setLyricsReplaceArtworkOnly,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setLyricsReplaceArtworkOnly(!lyricsReplaceArtworkOnly) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.hide_player_header_during_lyrics),
                subtitle = stringResource(R.string.hide_player_header_during_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = hidePlayerHeaderDuringLyrics,
                        onCheckedChange = AppSettings::setHidePlayerHeaderDuringLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHidePlayerHeaderDuringLyrics(!hidePlayerHeaderDuringLyrics) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Close,
                title = stringResource(R.string.show_lyrics_close_button),
                subtitle = stringResource(R.string.show_lyrics_close_button_subtitle),
                trailing = {
                    Switch(
                        checked = showLyricsCloseButton,
                        onCheckedChange = AppSettings::setShowLyricsCloseButton,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setShowLyricsCloseButton(!showLyricsCloseButton) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.prefer_embedded_local_lyrics),
                subtitle = stringResource(R.string.prefer_embedded_local_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = preferEmbeddedLocalLyrics,
                        onCheckedChange = AppSettings::setPreferEmbeddedLocalLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPreferEmbeddedLocalLyrics(!preferEmbeddedLocalLyrics) },
            )
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                RowDivider()
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = stringResource(R.string.hide_lyrics_status_text),
                    subtitle = stringResource(R.string.hide_lyrics_status_text_subtitle),
                    trailing = {
                        Switch(
                            checked = hideLyricsStatusText,
                            onCheckedChange = AppSettings::setHideLyricsStatusText,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideLyricsStatusText(!hideLyricsStatusText) },
                )
                RowDivider()
            }
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.hide_lyrics_saved_message),
                subtitle = stringResource(R.string.hide_lyrics_saved_message_subtitle),
                trailing = {
                    Switch(
                        checked = hideLyricsSavedMessage,
                        onCheckedChange = AppSettings::setHideLyricsSavedMessage,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideLyricsSavedMessage(!hideLyricsSavedMessage) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.hide_lyrics_unavailable_label),
                subtitle = stringResource(R.string.hide_lyrics_unavailable_label_subtitle),
                trailing = {
                    Switch(
                        checked = hideLyricsUnavailableLabel,
                        onCheckedChange = AppSettings::setHideLyricsUnavailableLabel,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideLyricsUnavailableLabel(!hideLyricsUnavailableLabel) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(R.string.hide_lyrics_gap_note),
                subtitle = stringResource(R.string.hide_lyrics_gap_note_subtitle),
                trailing = {
                    Switch(
                        checked = hideLyricsGapNote,
                        onCheckedChange = AppSettings::setHideLyricsGapNote,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideLyricsGapNote(!hideLyricsGapNote) },
            )
            if (hideLyricsSavedMessage || hideLyricsUnavailableLabel) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FastRewind,
                    title = stringResource(R.string.lyrics_show_previous),
                    subtitle = stringResource(R.string.lyrics_transport_visibility_subtitle),
                    trailing = {
                        Switch(
                            checked = showLyricsPreviousControl,
                            onCheckedChange = AppSettings::setShowLyricsPreviousControl,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowLyricsPreviousControl(!showLyricsPreviousControl) },
                )
                RowDivider()
                SettingsRow(
                    icon = if (showLyricsPlayPauseControl) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.lyrics_show_play_pause),
                    subtitle = stringResource(R.string.lyrics_transport_visibility_subtitle),
                    trailing = {
                        Switch(
                            checked = showLyricsPlayPauseControl,
                            onCheckedChange = AppSettings::setShowLyricsPlayPauseControl,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowLyricsPlayPauseControl(!showLyricsPlayPauseControl) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FastForward,
                    title = stringResource(R.string.lyrics_show_next),
                    subtitle = stringResource(R.string.lyrics_transport_visibility_subtitle),
                    trailing = {
                        Switch(
                            checked = showLyricsNextControl,
                            onCheckedChange = AppSettings::setShowLyricsNextControl,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowLyricsNextControl(!showLyricsNextControl) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.lyrics_transport_controls_centered),
                    subtitle = stringResource(R.string.lyrics_transport_controls_centered_subtitle),
                    trailing = {
                        Switch(
                            checked = lyricsTransportControlsCentered,
                            onCheckedChange = AppSettings::setLyricsTransportControlsCentered,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        AppSettings.setLyricsTransportControlsCentered(!lyricsTransportControlsCentered)
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.lyrics_transport_control_size),
                    subtitle = stringResource(R.string.lyrics_transport_control_size_subtitle),
                    trailing = {
                        Text(
                            text = stringResource(
                                R.string.lyrics_transport_control_size_value,
                                lyricsTransportControlSize.roundToInt(),
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
                Slider(
                    value = lyricsTransportControlSize,
                    onValueChange = AppSettings::setLyricsTransportControlSize,
                    steps = 49,
                    valueRange = 14f..64f,
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            // Source ordering and lyric lookup only matter while lyrics are on.
            if (syncedLyrics) {
                RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOn,
                title = stringResource(R.string.blur_unfocused_lyrics),
                subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = lyricsBlur,
                            onCheckedChange = AppSettings::setLyricsBlur,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(R.string.auto_embed_lyrics),
                    subtitle = stringResource(R.string.auto_embed_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = autoEmbedLyrics,
                            onCheckedChange = AppSettings::setAutoEmbedLyrics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setAutoEmbedLyrics(!autoEmbedLyrics) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.lyrics_extensions_enabled),
                    subtitle = stringResource(R.string.lyrics_extensions_enabled_subtitle),
                    trailing = {
                        Switch(
                            checked = lyricsExtensionsEnabled,
                            onCheckedChange = AppSettings::setLyricsExtensionsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLyricsExtensionsEnabled(!lyricsExtensionsEnabled) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.lyrics_sources),
                    subtitle = lyricsSourceOrder
                        .filter { it in lyricsSources }
                        .joinToString(", ") { it.label }
                        .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                    trailing = { Chevron() },
                    onClick = onLyricsSources,
                )
            }
        }

        SettingsGroup(header = stringResource(R.string.main_navigation_tabs)) {
            val startupTitle = stringResource(when (startupTab) {
                MainNavigationTab.SONGS -> R.string.songs
                MainNavigationTab.ALBUMS -> R.string.albums
                MainNavigationTab.ARTISTS -> R.string.artists
                MainNavigationTab.LIBRARY -> R.string.library
                MainNavigationTab.SEARCH -> R.string.search
                MainNavigationTab.ALBUM_ARTISTS -> R.string.album_artists
                MainNavigationTab.FOLDERS -> R.string.folders
                MainNavigationTab.RADIO -> R.string.radio
            })
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(R.string.startup_tab),
                subtitle = stringResource(R.string.startup_tab_subtitle),
                value = startupTitle,
                onClick = { showStartupTabPicker = true },
            )
            RowDivider()
            mainTabOrder.forEachIndexed { index, tab ->
                if (index > 0) RowDivider()
                val titleRes = when (tab) {
                    MainNavigationTab.SONGS -> R.string.songs
                    MainNavigationTab.ALBUMS -> R.string.albums
                    MainNavigationTab.ARTISTS -> R.string.artists
                    MainNavigationTab.LIBRARY -> R.string.library
                    MainNavigationTab.SEARCH -> R.string.search
                    MainNavigationTab.ALBUM_ARTISTS -> R.string.album_artists
                    MainNavigationTab.FOLDERS -> R.string.folders
                    MainNavigationTab.RADIO -> R.string.radio
                }
                val icon = when (tab) {
                    MainNavigationTab.SONGS -> Icons.Rounded.MusicNote
                    MainNavigationTab.ALBUMS -> Icons.Rounded.Album
                    MainNavigationTab.ARTISTS -> Icons.Rounded.Person
                    MainNavigationTab.LIBRARY -> Icons.Rounded.LibraryMusic
                    MainNavigationTab.SEARCH -> BitChordIcons.Search
                    MainNavigationTab.ALBUM_ARTISTS -> Icons.Rounded.Person
                    MainNavigationTab.FOLDERS -> Icons.Rounded.Folder
                    MainNavigationTab.RADIO -> Icons.Rounded.Radio
                }
                val checked = if (tab == MainNavigationTab.ALBUM_ARTISTS) {
                    com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in localTabs
                } else tab in visibleMainTabs
                val visibleMainCount = visibleMainTabs.size + if (
                    com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in localTabs
                ) 1 else 0
                SettingsRow(
                    icon = icon,
                    title = stringResource(titleRes),
                    subtitle = stringResource(R.string.navigation_tab_order_subtitle),
                    enabled = checked || visibleMainCount > 1,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = index > 0,
                                onClick = { AppSettings.moveMainNavigationTab(tab, -1) },
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    stringResource(R.string.move_up),
                                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            IconButton(
                                enabled = index < mainTabOrder.lastIndex,
                                onClick = { AppSettings.moveMainNavigationTab(tab, 1) },
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowDownward,
                                    stringResource(R.string.move_down),
                                    tint = if (index < mainTabOrder.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            Switch(
                                checked = checked,
                                enabled = checked || visibleMainTabs.size > 1,
                                onCheckedChange = { AppSettings.setMainNavigationTabVisible(tab, it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    },
                )
            }
        }

        SettingsGroup(header = stringResource(R.string.performance)) {
            SettingsRow(
                icon = BitChordIcons.Performance,
                title = stringResource(R.string.high_performance_mode),
                subtitle = if (highPerformanceMode) {
                    stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                } else {
                    stringResource(R.string.high_performance_subtitle)
                },
                badge = stringResource(R.string.beta),
                trailing = {
                    Switch(
                        checked = highPerformanceMode,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showPerformanceWarning = true
                            } else {
                                AppSettings.setHighPerformanceMode(false)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (highPerformanceMode) {
                        AppSettings.setHighPerformanceMode(false)
                    } else {
                        showPerformanceWarning = true
                    }
                },
            )
            if (highPerformanceMode) {
                RowDivider()
                SettingsRow(
                    icon = BitChordIcons.FrameRate,
                    title = stringResource(R.string.refresh_rate),
                )
                SegmentedControl(
                    options = supportedRefreshRates.map { "$it Hz" },
                    selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                    onSelect = { index ->
                        AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                    },
                    modifier = Modifier.padding(
                        start = TEXT_INSET,
                        end = ROW_INSET,
                        bottom = 14.dp,
                    ),
                )
            }
        }

        SourcesSettingsGroup(onClick = onManageSources)

        if (WatchInstaller.available(context)) {
            WearOsSettingsRow(onClick = onWearOs)
        }

        if (!context.packageName.endsWith(".nointernet")) {
            MusicServersSettingsGroup(
                onClick = { showMusicServersSheet = true },
                cleartextHttpAllowed = cleartextHttpAllowed,
            )
        }

        LocalMusicSettingsGroup(
            localTabs = localTabs,
            localTabOrder = localTabOrder,
            localFolderHierarchy = localFolderHierarchy,
            includeRemoteSourceMediaInLibrary = includeRemoteSourceMediaInLibrary,
            localMusicFolderRoot = localMusicFolderRoot,
            fullScreenCollectionArtwork = fullScreenCollectionArtwork,
            hideCollectionTopBar = hideCollectionTopBar,
            hideFloatingCollectionControls = hideFloatingCollectionControls,
            artistsOpenAlbums = artistsOpenAlbums,
            shuffleArtistAlbums = shuffleArtistAlbums,
            onlineAlbumArtistArtwork = onlineAlbumArtistArtwork,
            filterNonMusicAudio = filterNonMusicAudio,
            showPlaylistSongArtwork = showPlaylistSongArtwork,
            showCollectionSongArtwork = showCollectionSongArtwork,
            keepCollectionArtworkAspectRatio = keepCollectionArtworkAspectRatio,
            localizeLibraryCollectionCount = localizeLibraryCollectionCount,
            whiteCollectionActionButtons = whiteCollectionActionButtons,
            hasAllFiles = hasAllFiles,
            onManageFolders = { showManageFoldersSheet = true },
            onChooseMusicFolder = { musicFolderRootPicker.launch(null) },
            onClearMusicFolder = { AppSettings.setLocalMusicFolderPath("") },
            onOpenAllFiles = {
                runCatching {
                    allFilesSettingsLauncher.launch(LocalMediaRepository.createAllFilesAccessIntent(context))
                }.onFailure {
                    LocalMediaRepository.requestAllFilesAccess(context)
                }
            },
        )

        SettingsGroup(header = stringResource(R.string.storage)) {
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.cd_ripping_settings),
                subtitle = stringResource(R.string.cd_ripping_settings_subtitle),
                onClick = { showCdRippingSheet = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.clear_image_cache),
                subtitle = stringResource(R.string.clear_image_cache_subtitle),
                onClick = {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                },
            )
        }

        SettingsGroup(header = stringResource(R.string.playlist_tools)) {
            SettingsRow(
                icon = Icons.Rounded.PlayArrow,
                title = stringResource(R.string.hide_playlist_play_button_label),
                subtitle = stringResource(R.string.hide_playlist_play_button_label_subtitle),
                trailing = {
                    Switch(
                        checked = hidePlaylistPlayButtonLabel,
                        onCheckedChange = AppSettings::setHidePlaylistPlayButtonLabel,
                    )
                },
                onClick = { AppSettings.setHidePlaylistPlayButtonLabel(!hidePlaylistPlayButtonLabel) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.import_m3u_playlist),
                subtitle = stringResource(R.string.import_m3u_subtitle),
                onClick = {
                    m3uPicker.launch(
                        arrayOf(
                            "audio/x-mpegurl",
                            "application/vnd.apple.mpegurl",
                            "application/x-mpegurl",
                            "audio/mpegurl",
                            "text/plain",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlayArrow,
                title = stringResource(R.string.play_cue_sheet),
                subtitle = stringResource(R.string.play_cue_sheet_subtitle),
                onClick = {
                    cuePicker.launch(arrayOf("application/x-cue", "text/plain", "application/octet-stream", "*/*"))
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.import_musicolet_backup),
                subtitle = stringResource(R.string.import_musicolet_backup_subtitle),
                onClick = {
                    musicoletBackupPicker.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"),
                    )
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.manage_playlist_order),
                subtitle = stringResource(R.string.manage_playlist_order_subtitle),
                onClick = { showPlaylistOrderDialog = true },
            )
        }

        SettingsGroup(header = stringResource(R.string.your_data)) {
            SettingsRow(
                icon = Icons.Rounded.BarChart,
                title = stringResource(R.string.replay),
                subtitle = stringResource(R.string.replay_subtitle),
                onClick = onOpenReplay,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.LocalOffer,
                title = stringResource(R.string.work_out_genres),
                subtitle = if (replayGenres) {
                    stringResource(R.string.replay_genres_enabled_subtitle)
                } else {
                    stringResource(R.string.replay_genres_disabled_subtitle)
                },
                trailing = {
                    Switch(
                        checked = replayGenres,
                        onCheckedChange = AppSettings::setReplayGenres,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReplayGenres(!replayGenres) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.export_data),
                subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                onClick = { exportPicker.launch(Backup.suggestedName()) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.import_data),
                subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                onClick = { confirmImport = true },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.miscellaneous),
            footer = stringResource(R.string.miscellaneous_footer),
        ) {
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.play_next_on_swipe),
                subtitle = if (swipeToPlayNext) {
                    stringResource(R.string.swipe_plays_next)
                } else {
                    stringResource(R.string.swipe_adds_to_queue)
                },
                trailing = {
                    Switch(
                        checked = swipeToPlayNext,
                        onCheckedChange = AppSettings::setSwipeToPlayNext,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.dont_repeat_songs),
                subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                trailing = {
                    Switch(
                        checked = dontRepeatSuggestions,
                        onCheckedChange = AppSettings::setDontRepeatSuggestions,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MusicOff,
                title = stringResource(R.string.stop_music_on_close),
                subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                trailing = {
                    Switch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VolumeOff,
                title = stringResource(R.string.hide_volume_bar),
                subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                trailing = {
                    Switch(
                        checked = hideVolumeBar,
                        onCheckedChange = AppSettings::setHideVolumeBar,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Radio,
                title = stringResource(R.string.hide_radio_playback_bar),
                subtitle = stringResource(R.string.hide_radio_playback_bar_subtitle),
                trailing = {
                    Switch(
                        checked = hideRadioPlaybackBar,
                        onCheckedChange = AppSettings::setHideRadioPlaybackBar,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideRadioPlaybackBar(!hideRadioPlaybackBar) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.player_seek_bar_thickness),
                subtitle = stringResource(R.string.player_bar_thickness_subtitle),
                trailing = {
                    Text(
                        stringResource(R.string.player_bar_thickness_value, playerSeekBarThicknessDp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Slider(
                value = playerSeekBarThicknessDp.toFloat(),
                onValueChange = { AppSettings.setPlayerSeekBarThicknessDp(it.roundToInt()) },
                valueRange = 2f..14f,
                steps = 11,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 12.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.player_volume_bar_thickness),
                subtitle = stringResource(R.string.player_bar_thickness_subtitle),
                trailing = {
                    Text(
                        stringResource(R.string.player_bar_thickness_value, playerVolumeBarThicknessDp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Slider(
                value = playerVolumeBarThicknessDp.toFloat(),
                onValueChange = { AppSettings.setPlayerVolumeBarThicknessDp(it.roundToInt()) },
                valueRange = 2f..14f,
                steps = 11,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 12.dp),
            )
        }

        SettingsGroup(header = stringResource(R.string.language)) {
            val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language
                ?: Locale.getDefault().language
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.app_language),
                subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                onClick = onAppLanguage,
            )
        }

        SettingsGroup(header = stringResource(R.string.advanced_options)) {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(R.string.youtube_music_enabled),
                subtitle = stringResource(R.string.youtube_music_enabled_subtitle),
                trailing = {
                    Switch(
                        checked = youtubeMusicEnabled,
                        onCheckedChange = AppSettings::setYoutubeMusicEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setYoutubeMusicEnabled(!youtubeMusicEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Search,
                title = stringResource(R.string.youtube_music_search_default),
                subtitle = stringResource(R.string.youtube_music_search_default_subtitle),
                trailing = {
                    Switch(
                        checked = youtubeMusicSearchDefault,
                        onCheckedChange = AppSettings::setYoutubeMusicSearchDefault,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setYoutubeMusicSearchDefault(!youtubeMusicSearchDefault) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(R.string.youtube_music_include_videos),
                subtitle = stringResource(R.string.youtube_music_include_videos_subtitle),
                trailing = {
                    Switch(
                        checked = includeYoutubeMusicVideosInSearch,
                        onCheckedChange = AppSettings::setIncludeYoutubeMusicVideosInSearch,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    AppSettings.setIncludeYoutubeMusicVideosInSearch(!includeYoutubeMusicVideosInSearch)
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Cloud,
                title = stringResource(R.string.offline_mode),
                subtitle = stringResource(R.string.offline_mode_subtitle),
                trailing = {
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = AppSettings::setOfflineMode,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setOfflineMode(!offlineMode) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.show_nerd_stats),
                subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                trailing = {
                    Switch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.lyrics_debug_logs),
                subtitle = stringResource(R.string.lyrics_debug_logs_subtitle),
                trailing = {
                    Switch(
                        checked = showLyricsLogs,
                        onCheckedChange = AppSettings::setShowLyricsLogs,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setShowLyricsLogs(!showLyricsLogs) },
            )
        }

        if (showThemePicker) {
            AlertDialog(
                onDismissRequest = { showThemePicker = false },
                title = { Text(stringResource(R.string.theme)) },
                text = {
                    Column {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showThemePicker = false
                                        if (mode == ThemeMode.CUSTOM_IMAGE && customThemeImageUri.isNullOrBlank()) {
                                            chooseThemeImage()
                                        } else {
                                            AppSettings.setThemeMode(mode)
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = theme == mode,
                                    onClick = {
                                        showThemePicker = false
                                        if (mode == ThemeMode.CUSTOM_IMAGE && customThemeImageUri.isNullOrBlank()) {
                                            chooseThemeImage()
                                        } else {
                                            AppSettings.setThemeMode(mode)
                                        }
                                    },
                                )
                                Text(mode.localizedLabel(), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemePicker = false }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        }

        if (showPlayerArtworkModePicker) {
            AlertDialog(
                onDismissRequest = { showPlayerArtworkModePicker = false },
                title = { Text(stringResource(R.string.player_artwork_mode)) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PlayerArtworkMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettings.setPlayerArtworkMode(mode)
                                        showPlayerArtworkModePicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = playerArtworkMode == mode,
                                    onClick = {
                                        AppSettings.setPlayerArtworkMode(mode)
                                        showPlayerArtworkModePicker = false
                                    },
                                )
                                Text(mode.localizedLabel(), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPlayerArtworkModePicker = false }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        }

        if (showMediaControlActionPicker) {
            AlertDialog(
                onDismissRequest = { showMediaControlActionPicker = false },
                title = { Text(stringResource(R.string.media_control_secondary_action)) },
                text = {
                    Column {
                        MediaControlSecondaryAction.entries.forEach { action ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettings.setMediaControlSecondaryAction(action)
                                        showMediaControlActionPicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = mediaControlSecondaryAction == action,
                                    onClick = {
                                        AppSettings.setMediaControlSecondaryAction(action)
                                        showMediaControlActionPicker = false
                                    },
                                )
                                Text(action.localizedLabel(), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMediaControlActionPicker = false }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        }

        if (showLauncherIconPicker) {
            AlertDialog(
                onDismissRequest = { showLauncherIconPicker = false },
                title = { Text(stringResource(R.string.app_icon)) },
                text = {
                    Column {
                        LauncherIcon.entries.forEach { icon ->
                            val label = stringResource(when (icon) {
                                LauncherIcon.PINK -> R.string.app_icon_pink
                                LauncherIcon.BLACK -> R.string.app_icon_black
                                LauncherIcon.GREEN -> R.string.app_icon_green
                            })
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettings.setLauncherIcon(icon)
                                        showLauncherIconPicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = launcherIcon == icon,
                                    onClick = {
                                        AppSettings.setLauncherIcon(icon)
                                        showLauncherIconPicker = false
                                    },
                                )
                                Text(label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLauncherIconPicker = false }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        }

        if (showStartupTabPicker) {
            AlertDialog(
                onDismissRequest = { showStartupTabPicker = false },
                title = { Text(stringResource(R.string.startup_tab)) },
                text = {
                    Column {
                        mainTabOrder.filter {
                            it in visibleMainTabs || (
                                it == MainNavigationTab.ALBUM_ARTISTS &&
                                    com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in localTabs
                            )
                        }.forEach { tab ->
                            val label = when (tab) {
                                MainNavigationTab.SONGS -> stringResource(R.string.songs)
                                MainNavigationTab.ALBUMS -> stringResource(R.string.albums)
                                MainNavigationTab.ARTISTS -> stringResource(R.string.artists)
                                MainNavigationTab.LIBRARY -> stringResource(R.string.library)
                                MainNavigationTab.SEARCH -> stringResource(R.string.search)
                                MainNavigationTab.ALBUM_ARTISTS -> stringResource(R.string.album_artists)
                                MainNavigationTab.FOLDERS -> stringResource(R.string.folders)
                                MainNavigationTab.RADIO -> stringResource(R.string.radio)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettings.setStartupTab(tab)
                                        showStartupTabPicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = startupTab == tab,
                                    onClick = {
                                        AppSettings.setStartupTab(tab)
                                        showStartupTabPicker = false
                                    },
                                )
                                Text(label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStartupTabPicker = false }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        }

        Text(
            text = buildAnnotatedString {
                append("MusicBeatSP $version  ")
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                withLink(LinkAnnotation.Url("https://github.com/Kdroidwin/MusicBeatSP", linkStyles)) {
                    append("GitHub")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                    append(originalDeveloperLabel)
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                    append("Discord")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        }
    }
    FastScroller(
        scrollState = settingsScrollState,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
    )
    }




    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = {
                Text(stringResource(R.string.import_backup_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    preparedM3uImport?.let { prepared ->
        AlertDialog(
            onDismissRequest = { preparedM3uImport = null },
            title = { Text(stringResource(R.string.import_m3u_playlist)) },
            text = {
                Column {
                    Text(stringResource(R.string.m3u_import_ready, prepared.tracks.size, prepared.skippedCount))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = m3uPlaylistName,
                        onValueChange = { m3uPlaylistName = it },
                        label = { Text(stringResource(R.string.m3u_playlist_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = m3uPlaylistName.isNotBlank(),
                    onClick = {
                        val playlist = LocalPlaylistStore.createPlaylist(
                            name = m3uPlaylistName,
                            songIds = prepared.tracks.map { it.id },
                            songMetadata = prepared.metadata,
                            coverUrl = prepared.artworkUrl,
                        )
                        preparedM3uImport = null
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.m3u_import_summary,
                                playlist.songIds.size,
                                prepared.skippedCount,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.import_m3u_playlist))
                }
            },
            dismissButton = {
                TextButton(onClick = { preparedM3uImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    preparedMusicoletBackup?.let { prepared ->
        val allSelected = prepared.playlists.isNotEmpty() &&
            selectedMusicoletPlaylistIds.size == prepared.playlists.size
        AlertDialog(
            onDismissRequest = { preparedMusicoletBackup = null },
            title = { Text(stringResource(R.string.import_musicolet_backup)) },
            text = {
                Column {
                    Text(stringResource(R.string.musicolet_playlists_found, prepared.playlists.size))
                    if (prepared.playlists.sumOf { it.tracks.size } == 0 &&
                        prepared.playlists.any { it.skippedCount > 0 }
                    ) {
                        Text(
                            text = stringResource(R.string.musicolet_no_tracks_match_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedMusicoletPlaylistIds = if (allSelected) emptySet()
                                else prepared.playlists.map { it.id }.toSet()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                selectedMusicoletPlaylistIds = if (checked) prepared.playlists.map { it.id }.toSet()
                                else emptySet()
                            },
                        )
                        Text(stringResource(R.string.select_all_playlists))
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        prepared.playlists.forEachIndexed { index, playlist ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMusicoletPlaylistIds = if (playlist.id in selectedMusicoletPlaylistIds) {
                                            selectedMusicoletPlaylistIds - playlist.id
                                        } else {
                                            selectedMusicoletPlaylistIds + playlist.id
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = playlist.id in selectedMusicoletPlaylistIds,
                                    onCheckedChange = { checked ->
                                        selectedMusicoletPlaylistIds = if (checked) {
                                            selectedMusicoletPlaylistIds + playlist.id
                                        } else {
                                            selectedMusicoletPlaylistIds - playlist.id
                                        }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(
                                            R.string.musicolet_playlist_track_counts,
                                            playlist.tracks.size,
                                            playlist.skippedCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedMusicoletPlaylistIds.isNotEmpty(),
                    onClick = {
                        val selected = prepared.playlists.filter { it.id in selectedMusicoletPlaylistIds }
                        var addedTracks = 0
                        var skippedEntries = 0
                        selected.forEach { playlist ->
                            val imported = LocalPlaylistStore.createPlaylist(
                                name = playlist.name,
                                songIds = playlist.tracks.map { it.id },
                                songMetadata = playlist.metadata,
                                coverUrl = playlist.artworkUrl,
                            )
                            addedTracks += imported.songIds.size
                            skippedEntries += playlist.skippedCount
                        }
                        preparedMusicoletBackup = null
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.musicolet_import_summary,
                                selected.size,
                                addedTracks,
                                skippedEntries,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                ) { Text(stringResource(R.string.import_selected_playlists)) }
            },
            dismissButton = {
                TextButton(onClick = { preparedMusicoletBackup = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPlaylistOrderDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistOrderDialog = false },
            title = { Text(stringResource(R.string.playlist_order_title)) },
            text = {
                if (localPlaylists.isEmpty()) {
                    Text(stringResource(R.string.no_playlists_to_reorder))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        localPlaylists.forEachIndexed { index, playlist ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = playlist.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = { LocalPlaylistStore.movePlaylist(playlist.id, -1) },
                                    ) { Text(stringResource(R.string.move_playlist_up)) }
                                    TextButton(
                                        enabled = index < localPlaylists.lastIndex,
                                        onClick = { LocalPlaylistStore.movePlaylist(playlist.id, 1) },
                                    ) { Text(stringResource(R.string.move_playlist_down)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistOrderDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showPerformanceWarning) {
        AlertDialog(
            onDismissRequest = { showPerformanceWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPerformanceConfirmation) {
        AlertDialog(
            onDismissRequest = { showPerformanceConfirmation = false },
            title = { Text(stringResource(R.string.enable_high_performance_title)) },
            text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }

    if (showListenBrainzTokenDialog) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        AlertDialog(
            onDismissRequest = { showListenBrainzTokenDialog = false },
            title = { Text(stringResource(R.string.listenbrainz_token)) },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.api_token)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzTokenDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showListenBrainzTokenDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLastfmLoginDialog) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!lastfmLoading) showLastfmLoginDialog = false },
            title = { Text(stringResource(R.string.lastfm_login)) },
            text = {
                Column {
                    if (lastfmError != null) {
                        Text(
                            text = lastfmError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lastfmLoading = true
                        lastfmError = null
                        scrobbleScope.launch {
                            try {
                                // Use the credentials supplied for this build.
                                LastFM.initialize(
                                    apiKey = AppSettings.lastfmApiKey.value,
                                    secret = AppSettings.lastfmSecret.value,
                                )
                                LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                    .onSuccess { auth ->
                                        AppSettings.setLastfmSessionKey(auth.session.key)
                                        AppSettings.setLastfmUsername(auth.session.name)
                                        AppSettings.setLastfmEnabled(true)
                                        showLastfmLoginDialog = false
                                    }
                                    .onFailure { e ->
                                        lastfmError = e.message ?: context.getString(R.string.login_failed)
                                    }
                            } catch (e: Exception) {
                                lastfmError = e.message ?: context.getString(R.string.login_failed)
                            } finally {
                                lastfmLoading = false
                            }
                        }
                    },
                    enabled = !lastfmLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                ) {
                    Text(stringResource(if (lastfmLoading) R.string.signing_in else R.string.sign_in))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLastfmLoginDialog = false }, enabled = !lastfmLoading) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showManageFoldersSheet) {
        BlacklistedFoldersSheet(
            folders = discoveredFolders,
            onToggleBlacklist = { path, isBlacklisted ->
                AppSettings.setFolderBlacklisted(path, isBlacklisted)
                backupScope.launch {
                    discoveredFolders = LocalMediaRepository.getAllDiscoveredFolders(context)
                }
            },
            sheetState = manageFoldersSheetState,
            onDismiss = { showManageFoldersSheet = false },
        )
    }

    MusicServerManagerSheetHost(
        visible = showMusicServersSheet,
        onDismiss = { showMusicServersSheet = false },
    )

    if (showEqualizerSheet) {
        com.music.bitchord.ui.screens.equalizer.EqualizerSheet(
            onDismiss = { showEqualizerSheet = false },
        )
    }

    if (showAutomixPerformanceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAutomixPerformanceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AutomixPerformanceSheet(
                selected = automixPerformance,
                onSelect = {
                    AppSettings.setAutomixPerformanceMode(it)
                    showAutomixPerformanceSheet = false
                },
            )
        }
    }

    if (showCdRippingSheet) {
        CdRippingDialog(
            onDismiss = { showCdRippingSheet = false },
            onRipComplete = onCdRipComplete,
        )
    }

    if (transportActionSlot.isNotBlank()) {
        val isLeft = transportActionSlot == "left"
        val selectedAction = if (isLeft) transportLeftAction else transportRightAction
        AlertDialog(
            onDismissRequest = { transportActionSlot = "" },
            title = {
                Text(
                    stringResource(
                        if (isLeft) R.string.transport_left_action else R.string.transport_right_action,
                    ),
                )
            },
            text = {
                Column {
                    TransportSideAction.entries.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isLeft) AppSettings.setTransportLeftAction(action)
                                    else AppSettings.setTransportRightAction(action)
                                    transportActionSlot = ""
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedAction == action,
                                onClick = {
                                    if (isLeft) AppSettings.setTransportLeftAction(action)
                                    else AppSettings.setTransportRightAction(action)
                                    transportActionSlot = ""
                                },
                            )
                            Text(action.localizedLabel(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { transportActionSlot = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/** The original BitChord selector for background Automix analysis load. */
@Composable
private fun AutomixPerformanceSheet(
    selected: AutomixPerformanceMode,
    onSelect: (AutomixPerformanceMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.automix_performance),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.automix_performance_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        AutomixPerformanceMode.entries.forEach { mode ->
            val chosen = mode == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(mode)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            when (mode) {
                                AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                                AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                                AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}


@Composable
private fun LocalMusicSettingsGroup(
    localTabs: Set<LocalLibraryTab>,
    localTabOrder: List<LocalLibraryTab>,
    localFolderHierarchy: Boolean,
    includeRemoteSourceMediaInLibrary: Boolean,
    localMusicFolderRoot: String,
    fullScreenCollectionArtwork: Boolean,
    hideCollectionTopBar: Boolean,
    hideFloatingCollectionControls: Boolean,
    artistsOpenAlbums: Boolean,
    shuffleArtistAlbums: Boolean,
    onlineAlbumArtistArtwork: Boolean,
    filterNonMusicAudio: Boolean,
    showPlaylistSongArtwork: Boolean,
    showCollectionSongArtwork: Boolean,
    keepCollectionArtworkAspectRatio: Boolean,
    localizeLibraryCollectionCount: Boolean,
    whiteCollectionActionButtons: Boolean,
    hasAllFiles: Boolean,
    onManageFolders: () -> Unit,
    onChooseMusicFolder: () -> Unit,
    onClearMusicFolder: () -> Unit,
    onOpenAllFiles: () -> Unit,
) {
    val context = LocalContext.current
SettingsGroup(header = stringResource(R.string.local_music)) {
    SettingsRow(
        icon = Icons.Rounded.Folder,
        title = stringResource(R.string.music_folders),
        subtitle = stringResource(R.string.music_folders_subtitle),
        trailing = { Chevron() },
        onClick = onManageFolders,
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Tune,
        title = stringResource(R.string.local_library_tabs),
        subtitle = stringResource(R.string.local_library_tabs_subtitle),
    )
    localTabOrder.forEachIndexed { index, tab ->
        if (index > 0) RowDivider()
        val titleRes = when (tab) {
            LocalLibraryTab.SONGS -> R.string.songs
            LocalLibraryTab.ALBUMS -> R.string.albums
            LocalLibraryTab.ARTISTS -> R.string.artists
            LocalLibraryTab.FOLDERS -> R.string.folders
            LocalLibraryTab.PLAYLISTS -> R.string.playlists
            LocalLibraryTab.ALBUM_ARTISTS -> R.string.album_artists
        }
        val tabIcon = when (tab) {
            LocalLibraryTab.SONGS -> Icons.Rounded.MusicNote
            LocalLibraryTab.ALBUMS -> Icons.Rounded.Album
            LocalLibraryTab.ARTISTS, LocalLibraryTab.ALBUM_ARTISTS -> Icons.Rounded.Person
            LocalLibraryTab.FOLDERS -> Icons.Rounded.Folder
            LocalLibraryTab.PLAYLISTS -> Icons.Rounded.PlaylistPlay
        }
        val visible = tab in localTabs
        SettingsRow(
            icon = tabIcon,
            title = stringResource(titleRes),
            subtitle = stringResource(R.string.navigation_tab_order_subtitle),
            enabled = visible || localTabs.size > 1,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = index > 0,
                        onClick = { AppSettings.moveLocalLibraryTab(tab, -1) },
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.move_up),
                            tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
                    }
                    IconButton(
                        enabled = index < localTabOrder.lastIndex,
                        onClick = { AppSettings.moveLocalLibraryTab(tab, 1) },
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.move_down),
                            tint = if (index < localTabOrder.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
                    }
                    Switch(
                        checked = visible,
                        enabled = visible || localTabs.size > 1,
                        onCheckedChange = { AppSettings.setLocalLibraryTabVisible(tab, it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            },
        )
    }
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Folder,
        title = stringResource(R.string.local_folder_hierarchy),
        subtitle = stringResource(R.string.local_folder_hierarchy_subtitle),
        trailing = {
            Switch(checked = localFolderHierarchy, onCheckedChange = AppSettings::setLocalFolderHierarchy)
        },
        onClick = { AppSettings.setLocalFolderHierarchy(!localFolderHierarchy) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Cloud,
        title = stringResource(R.string.include_remote_source_media_in_library),
        subtitle = stringResource(R.string.include_remote_source_media_in_library_subtitle),
        trailing = {
            Switch(
                checked = includeRemoteSourceMediaInLibrary,
                onCheckedChange = AppSettings::setIncludeRemoteSourceMediaInLibrary,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setIncludeRemoteSourceMediaInLibrary(!includeRemoteSourceMediaInLibrary) },
    )
    RowDivider()
    LocalMusicFolderRootSettingsRow(
        path = localMusicFolderRoot,
        onChoose = onChooseMusicFolder,
        onClear = onClearMusicFolder,
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.full_screen_collection_artwork),
        subtitle = stringResource(R.string.full_screen_collection_artwork_subtitle),
        trailing = {
            Switch(checked = fullScreenCollectionArtwork, onCheckedChange = AppSettings::setFullScreenCollectionArtwork)
        },
        onClick = { AppSettings.setFullScreenCollectionArtwork(!fullScreenCollectionArtwork) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.PlayArrow,
        title = stringResource(R.string.white_collection_action_buttons),
        subtitle = stringResource(R.string.white_collection_action_buttons_subtitle),
        trailing = {
            Switch(
                checked = whiteCollectionActionButtons,
                onCheckedChange = AppSettings::setWhiteCollectionActionButtons,
            )
        },
        onClick = { AppSettings.setWhiteCollectionActionButtons(!whiteCollectionActionButtons) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.VisibilityOff,
        title = stringResource(R.string.hide_collection_top_bar),
        subtitle = stringResource(R.string.hide_collection_top_bar_subtitle),
        trailing = {
            Switch(
                checked = hideCollectionTopBar,
                onCheckedChange = AppSettings::setHideCollectionTopBar,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setHideCollectionTopBar(!hideCollectionTopBar) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.VisibilityOff,
        title = stringResource(R.string.hide_floating_collection_controls),
        subtitle = stringResource(R.string.hide_floating_collection_controls_subtitle),
        trailing = {
            Switch(
                checked = hideFloatingCollectionControls,
                onCheckedChange = AppSettings::setHideFloatingCollectionControls,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setHideFloatingCollectionControls(!hideFloatingCollectionControls) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Person,
        title = stringResource(R.string.artists_open_albums),
        subtitle = stringResource(R.string.artists_open_albums_subtitle),
        trailing = {
            Switch(checked = artistsOpenAlbums, onCheckedChange = AppSettings::setArtistsOpenAlbums)
        },
        onClick = { AppSettings.setArtistsOpenAlbums(!artistsOpenAlbums) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.shuffle_artist_albums),
        subtitle = stringResource(R.string.shuffle_artist_albums_subtitle),
        trailing = {
            Switch(checked = shuffleArtistAlbums, onCheckedChange = AppSettings::setShuffleArtistAlbums)
        },
        onClick = { AppSettings.setShuffleArtistAlbums(!shuffleArtistAlbums) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Cloud,
        title = stringResource(R.string.online_album_artist_artwork),
        subtitle = stringResource(R.string.online_album_artist_artwork_subtitle),
        trailing = {
            Switch(
                checked = onlineAlbumArtistArtwork,
                onCheckedChange = AppSettings::setOnlineAlbumArtistArtwork,
            )
        },
        onClick = { AppSettings.setOnlineAlbumArtistArtwork(!onlineAlbumArtistArtwork) },
    )
    RowDivider()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        SettingsRow(
            icon = Icons.Rounded.FolderSpecial,
            title = stringResource(R.string.all_files_access),
            subtitle = if (hasAllFiles) {
                "Granted — tags and lyrics are edited directly without prompts"
            } else {
                "Grant access to edit tags and embed lyrics without system prompts"
            },
            trailing = {
                if (hasAllFiles) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Chevron()
                }
            },
            onClick = onOpenAllFiles,
        )
        RowDivider()
    }
    SettingsRow(
        icon = Icons.Rounded.FilterAlt,
        title = stringResource(R.string.filter_non_music_audio),
        subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
        trailing = {
            Switch(
                checked = filterNonMusicAudio,
                onCheckedChange = AppSettings::setFilterNonMusicAudio,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.playlist_song_artwork),
        subtitle = stringResource(R.string.playlist_song_artwork_subtitle),
        trailing = {
            Switch(
                checked = showPlaylistSongArtwork,
                onCheckedChange = AppSettings::setShowPlaylistSongArtwork,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setShowPlaylistSongArtwork(!showPlaylistSongArtwork) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.collection_song_artwork),
        subtitle = stringResource(R.string.collection_song_artwork_subtitle),
        trailing = {
            Switch(
                checked = showCollectionSongArtwork,
                onCheckedChange = AppSettings::setShowCollectionSongArtwork,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setShowCollectionSongArtwork(!showCollectionSongArtwork) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.localize_library_collection_count),
        subtitle = stringResource(R.string.localize_library_collection_count_subtitle),
        trailing = {
            Switch(
                checked = localizeLibraryCollectionCount,
                onCheckedChange = AppSettings::setLocalizeLibraryCollectionCount,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setLocalizeLibraryCollectionCount(!localizeLibraryCollectionCount) },
    )
    RowDivider()
    SettingsRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.keep_collection_artwork_aspect_ratio),
        subtitle = stringResource(R.string.keep_collection_artwork_aspect_ratio_subtitle),
        trailing = {
            Switch(
                checked = keepCollectionArtworkAspectRatio,
                onCheckedChange = AppSettings::setKeepCollectionArtworkAspectRatio,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { AppSettings.setKeepCollectionArtworkAspectRatio(!keepCollectionArtworkAspectRatio) },
    )
}


}

@Composable
private fun MusicServersSettingsGroup(
    onClick: () -> Unit,
    cleartextHttpAllowed: Boolean,
) {
    SettingsGroup(header = stringResource(R.string.music_servers)) {
        SettingsRow(
            icon = Icons.Rounded.Cloud,
            title = stringResource(R.string.music_servers),
            subtitle = stringResource(R.string.music_servers_subtitle),
            trailing = { Chevron() },
            onClick = onClick,
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Dns,
            title = stringResource(R.string.allow_cleartext_http),
            subtitle = stringResource(R.string.allow_cleartext_http_subtitle),
            trailing = {
                Switch(
                    checked = cleartextHttpAllowed,
                    onCheckedChange = AppSettings::setCleartextHttpAllowed,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            onClick = { AppSettings.setCleartextHttpAllowed(!cleartextHttpAllowed) },
        )
    }
}

@Composable
private fun SourcesSettingsGroup(onClick: () -> Unit) {
    SettingsGroup(header = stringResource(R.string.audio_quality)) {
        SettingsRow(
            icon = Icons.Rounded.Cloud,
            title = stringResource(R.string.source),
            subtitle = stringResource(R.string.sources_subtitle),
            trailing = { Chevron() },
            onClick = onClick,
        )
    }
}

@Composable
private fun MusicServerManagerSheetHost(visible: Boolean, onDismiss: () -> Unit) {
    if (visible) MusicServerManagerSheet(onDismiss = onDismiss)
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}



@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
        ThemeMode.AMOLED -> R.string.theme_amoled
        ThemeMode.ARTWORK -> R.string.theme_match_artwork
        ThemeMode.CUSTOM_IMAGE -> R.string.theme_custom_image
    },
)

@Composable
private fun MediaControlSecondaryAction.localizedLabel(): String = stringResource(
    when (this) {
        MediaControlSecondaryAction.SHUFFLE -> R.string.media_control_shuffle
        MediaControlSecondaryAction.PLAYLISTS -> R.string.media_control_playlists
        MediaControlSecondaryAction.LYRICS -> R.string.media_control_lyrics
        MediaControlSecondaryAction.QUEUE -> R.string.media_control_queue
    },
)

@Composable
private fun TransportSideAction.localizedLabel(): String = stringResource(
    when (this) {
        TransportSideAction.NONE -> R.string.transport_action_none
        TransportSideAction.SHUFFLE -> R.string.shuffle
        TransportSideAction.REPEAT -> R.string.repeat_all
        TransportSideAction.FAVORITE -> R.string.quick_action_favorite
        TransportSideAction.LYRICS -> R.string.quick_action_lyrics
        TransportSideAction.QUEUE -> R.string.quick_action_queue
        TransportSideAction.PLAYLISTS -> R.string.quick_action_playlists
        TransportSideAction.SEARCH -> R.string.quick_action_search
    },
)

@Composable
private fun PillMotionStyle.localizedLabel(): String = stringResource(
    when (this) {
        PillMotionStyle.FAST -> R.string.pill_motion_fast
        PillMotionStyle.FLUID -> R.string.pill_motion_fluid
        PillMotionStyle.RELAXED -> R.string.pill_motion_relaxed
    },
)

private fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}



// ---- Building blocks --------------------------------------------------------

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(26.dp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // A plain background does not establish LocalContentColor. Explicitly
        // provide the theme's foreground so default click ripples and controls
        // stay visible in both dark surfaces and AMOLED black.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            // Bare track: the step ticks and the end-stop dot are noise when the
            // value is already spelled out on the line above.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Sliding pill selector, for the handful of settings with two or three states. */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.outline)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}
