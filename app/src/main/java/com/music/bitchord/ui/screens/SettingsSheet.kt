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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Cloud
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
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewStream
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
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MAX_PLAYER_ARTWORK_BLUR_DP
import com.music.bitchord.data.settings.MainNavigationTab
import com.music.bitchord.data.settings.PlayerControl
import com.music.bitchord.data.settings.PlayerQuickAction
import com.music.bitchord.data.settings.PlayerDetailsAction
import com.music.bitchord.data.settings.ScreenOrientationMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.LyricsTextAlignment
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.R
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.data.M3uImportException
import com.music.bitchord.feature.localmusic.data.M3uImportFailure
import com.music.bitchord.feature.localmusic.data.M3uPlaylistImporter
import com.music.bitchord.feature.localmusic.data.PreparedM3uImport
import com.music.bitchord.feature.localmusic.data.MusicoletBackupImporter
import com.music.bitchord.feature.localmusic.data.MusicoletImportException
import com.music.bitchord.feature.localmusic.data.MusicoletImportFailure
import com.music.bitchord.feature.localmusic.data.PreparedMusicoletBackup
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
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
    val activate: () -> Unit,
)

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
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val doubleTapToSeek by AppSettings.doubleTapToSeek.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val classicNavBar by AppSettings.classicNavBar.collectAsStateWithLifecycle()
    val hideNavBarLabels by AppSettings.hideNavigationBarLabels.collectAsStateWithLifecycle()
    val visibleMainTabs by AppSettings.visibleMainNavigationTabs.collectAsStateWithLifecycle()
    val mainTabOrder by AppSettings.mainNavigationTabOrder.collectAsStateWithLifecycle()
    val startupTab by AppSettings.startupTab.collectAsStateWithLifecycle()
    val quickActions by AppSettings.playerQuickActions.collectAsStateWithLifecycle()
    val hiddenPlayerDetailsActions by AppSettings.hiddenPlayerDetailsActions.collectAsStateWithLifecycle()
    val audioCutterEnabled by AppSettings.audioCutterEnabled.collectAsStateWithLifecycle()
    val artworkBackdropBlurDp by AppSettings.artworkBackdropBlurDp.collectAsStateWithLifecycle()
    val keepScreenOn by AppSettings.keepScreenOn.collectAsStateWithLifecycle()
    val showStatusBar by AppSettings.showStatusBar.collectAsStateWithLifecycle()
    val showNavigationBar by AppSettings.showNavigationBar.collectAsStateWithLifecycle()
    val orientationMode by AppSettings.screenOrientationMode.collectAsStateWithLifecycle()
    val favoriteUsesStar by AppSettings.favoriteUsesStar.collectAsStateWithLifecycle()
    val persistentLocalArtwork by AppSettings.persistentLocalArtwork.collectAsStateWithLifecycle()
    val resamplerCutoffHz by AppSettings.resamplerCutoffHz.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsFontScale by AppSettings.lyricsFontScale.collectAsStateWithLifecycle()
    val lyricsTextAlignment by AppSettings.lyricsTextAlignment.collectAsStateWithLifecycle()
    val hideLyricsStatusText by AppSettings.hideLyricsStatusText.collectAsStateWithLifecycle()
    val hideLyricsSavedMessage by AppSettings.hideLyricsSavedMessage.collectAsStateWithLifecycle()
    val hideLyricsUnavailableLabel by AppSettings.hideLyricsUnavailableLabel.collectAsStateWithLifecycle()
    val hideLyricsGapNote by AppSettings.hideLyricsGapNote.collectAsStateWithLifecycle()
    val showLyricsPreviousControl by AppSettings.showLyricsPreviousControl.collectAsStateWithLifecycle()
    val showLyricsPlayPauseControl by AppSettings.showLyricsPlayPauseControl.collectAsStateWithLifecycle()
    val showLyricsNextControl by AppSettings.showLyricsNextControl.collectAsStateWithLifecycle()
    val lyricsTransportControlSize by AppSettings.lyricsTransportControlSize.collectAsStateWithLifecycle()
    val artworkTapOpensLyrics by AppSettings.artworkTapOpensLyrics.collectAsStateWithLifecycle()
    val showPlayerLyricsStrip by AppSettings.showPlayerLyricsStrip.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val hideLosslessLabel by AppSettings.hideLosslessLabel.collectAsStateWithLifecycle()
    val preventPlayAtZeroVolume by AppSettings.preventPlayAtZeroVolume.collectAsStateWithLifecycle()
    val useLibraryIconForPlaylistControl by AppSettings.useLibraryIconForPlaylistControl.collectAsStateWithLifecycle()
    val visiblePlayerControls by AppSettings.visiblePlayerControls.collectAsStateWithLifecycle()
    val playerControlOrder by AppSettings.playerControlOrder.collectAsStateWithLifecycle()
    val hidePlayerArtist by AppSettings.hidePlayerArtist.collectAsStateWithLifecycle()
    val hideUnknownPlayerArtist by AppSettings.hideUnknownPlayerArtist.collectAsStateWithLifecycle()
    val centerPlayerTrackInfo by AppSettings.centerPlayerTrackInfo.collectAsStateWithLifecycle()
    val playerDetailsVerticalMenu by AppSettings.playerDetailsVerticalMenu.collectAsStateWithLifecycle()
    val preloadAlbumArtOnStartup by AppSettings.preloadAlbumArtOnStartup.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val keepArtworkFullSizeWhenPaused by AppSettings.keepArtworkFullSizeWhenPaused.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val autoEmbedLyrics by AppSettings.autoEmbedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val showLyricsLogs by AppSettings.showLyricsLogs.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val audioFocusLevel by AppSettings.audioFocusLevel.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val showPlaylistSongArtwork by AppSettings.showPlaylistSongArtwork.collectAsStateWithLifecycle()
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

    val settingsSearchEntries = listOf(
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_status_text), stringResource(R.string.hide_lyrics_status_text_subtitle), listOf("lyrics", "歌詞", "状態", "status"), hideLyricsStatusText) { AppSettings.setHideLyricsStatusText(!hideLyricsStatusText) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_saved_message), stringResource(R.string.hide_lyrics_saved_message_subtitle), listOf("lyrics", "歌詞", "保存済み", "downloaded"), hideLyricsSavedMessage) { AppSettings.setHideLyricsSavedMessage(!hideLyricsSavedMessage) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_unavailable_label), stringResource(R.string.hide_lyrics_unavailable_label_subtitle), listOf("lyrics", "歌詞", "ありません", "unavailable"), hideLyricsUnavailableLabel) { AppSettings.setHideLyricsUnavailableLabel(!hideLyricsUnavailableLabel) },
        SettingsSearchEntry(stringResource(R.string.hide_lyrics_gap_note), stringResource(R.string.hide_lyrics_gap_note_subtitle), listOf("lyrics", "歌詞", "音符", "gap", "instrumental"), hideLyricsGapNote) { AppSettings.setHideLyricsGapNote(!hideLyricsGapNote) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_previous), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "previous", "前の曲"), showLyricsPreviousControl) { AppSettings.setShowLyricsPreviousControl(!showLyricsPreviousControl) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_play_pause), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "play", "pause", "再生", "一時停止"), showLyricsPlayPauseControl) { AppSettings.setShowLyricsPlayPauseControl(!showLyricsPlayPauseControl) },
        SettingsSearchEntry(stringResource(R.string.lyrics_show_next), stringResource(R.string.lyrics_transport_visibility_subtitle), listOf("lyrics", "歌詞", "next", "次の曲"), showLyricsNextControl) { AppSettings.setShowLyricsNextControl(!showLyricsNextControl) },
        SettingsSearchEntry(
            title = stringResource(R.string.lyrics_transport_control_size),
            subtitle = stringResource(R.string.lyrics_transport_control_size_subtitle),
            keywords = listOf("lyrics", "歌詞", "button", "ボタン", "size", "サイズ", "transport"),
            valueLabel = stringResource(R.string.lyrics_transport_control_size_value, lyricsTransportControlSize.roundToInt()),
            sliderValue = lyricsTransportControlSize,
            sliderValueRange = 14f..30f,
            sliderSteps = 15,
            onSliderValueChange = AppSettings::setLyricsTransportControlSize,
            activate = {},
        ),
        SettingsSearchEntry(stringResource(R.string.hide_lossless_label), stringResource(R.string.hide_lossless_label_subtitle), listOf("lossless", "quality", "ロスレス", "高音質"), hideLosslessLabel) { AppSettings.setHideLosslessLabel(!hideLosslessLabel) },
        SettingsSearchEntry(stringResource(R.string.audio_cutter_enabled), stringResource(R.string.audio_cutter_enabled_subtitle), listOf("audio cutter", "cut", "edit", "オーディオカッター", "曲編集"), audioCutterEnabled) { AppSettings.setAudioCutterEnabled(!audioCutterEnabled) },
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
        SettingsSearchEntry(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_subtitle), listOf("screen", "display", "画面", "常時"), keepScreenOn) { AppSettings.setKeepScreenOn(!keepScreenOn) },
        SettingsSearchEntry(stringResource(R.string.show_status_bar), stringResource(R.string.show_status_bar_search_help), listOf("status bar", "ステータスバー", "battery", "電池"), showStatusBar) { AppSettings.setShowStatusBar(!showStatusBar) },
        SettingsSearchEntry(stringResource(R.string.show_navigation_bar), stringResource(R.string.show_navigation_bar_search_help), listOf("navigation bar", "ナビゲーションバー", "戻る", "home"), showNavigationBar) { AppSettings.setShowNavigationBar(!showNavigationBar) },
        SettingsSearchEntry(stringResource(R.string.hide_player_artist), stringResource(R.string.hide_player_artist_subtitle), listOf("artist", "アーティスト", "player"), hidePlayerArtist) { AppSettings.setHidePlayerArtist(!hidePlayerArtist) },
        SettingsSearchEntry(stringResource(R.string.player_lyrics_strip), stringResource(R.string.player_lyrics_strip_subtitle), listOf("lyrics", "歌詞", "strip", "player"), showPlayerLyricsStrip) { AppSettings.setShowPlayerLyricsStrip(!showPlayerLyricsStrip) },
        SettingsSearchEntry(stringResource(R.string.skip_silence), stringResource(R.string.skip_silence_subtitle), listOf("silence", "無音", "再生"), skipSilence) { AppSettings.setSkipSilence(!skipSilence) },
        SettingsSearchEntry(stringResource(R.string.double_tap_to_seek), stringResource(R.string.double_tap_to_seek_subtitle), listOf("seek", "double tap", "シーク", "ダブルタップ"), doubleTapToSeek) { AppSettings.setDoubleTapToSeek(!doubleTapToSeek) },
        SettingsSearchEntry(stringResource(R.string.spatial_audio), stringResource(R.string.spatial_audio_subtitle), listOf("spatial", "空間オーディオ"), spatialAudio) { AppSettings.setSpatialAudio(!spatialAudio) },
        SettingsSearchEntry(stringResource(R.string.favorite_icon_star), stringResource(R.string.favorite_icon_star_subtitle), listOf("favorite", "star", "お気に入り", "星", "ハート"), favoriteUsesStar) { AppSettings.setFavoriteUsesStar(!favoriteUsesStar) },
        SettingsSearchEntry(stringResource(R.string.hide_unknown_player_artist), stringResource(R.string.hide_unknown_player_artist_subtitle), listOf("artist", "unknown", "アーティスト", "unknown artist"), hideUnknownPlayerArtist) { AppSettings.setHideUnknownPlayerArtist(!hideUnknownPlayerArtist) },
        SettingsSearchEntry(stringResource(R.string.center_player_track_info), stringResource(R.string.center_player_track_info_subtitle), listOf("center", "中央", "曲情報"), centerPlayerTrackInfo) { AppSettings.setCenterPlayerTrackInfo(!centerPlayerTrackInfo) },
        SettingsSearchEntry(stringResource(R.string.player_playlist_library_icon), stringResource(R.string.player_playlist_library_icon_subtitle), listOf("playlist", "library", "プレイリスト", "ライブラリ", "icon"), useLibraryIconForPlaylistControl) { AppSettings.setUseLibraryIconForPlaylistControl(!useLibraryIconForPlaylistControl) },
        SettingsSearchEntry(stringResource(R.string.prevent_play_at_zero_volume), stringResource(R.string.prevent_play_at_zero_volume_subtitle), listOf("volume", "音量", "zero", "0"), preventPlayAtZeroVolume) { AppSettings.setPreventPlayAtZeroVolume(!preventPlayAtZeroVolume) },
        SettingsSearchEntry(stringResource(R.string.preload_album_art), stringResource(R.string.preload_album_art_subtitle), listOf("artwork", "album art", "preload", "先読み", "アルバムアート"), preloadAlbumArtOnStartup) { AppSettings.setPreloadAlbumArtOnStartup(!preloadAlbumArtOnStartup) },
        SettingsSearchEntry(stringResource(R.string.persistent_local_artwork), stringResource(R.string.persistent_local_artwork_subtitle), listOf("artwork", "album art", "storage", "画像", "ストレージ"), persistentLocalArtwork) { AppSettings.setPersistentLocalArtwork(!persistentLocalArtwork) },
        SettingsSearchEntry(stringResource(R.string.synced_lyrics), stringResource(R.string.synced_lyrics_subtitle), listOf("lyrics", "歌詞", "同期"), syncedLyrics) { AppSettings.setSyncedLyrics(!syncedLyrics) },
        SettingsSearchEntry(stringResource(R.string.blur_unfocused_lyrics), stringResource(R.string.blur_unfocused_lyrics_subtitle), listOf("lyrics", "歌詞", "blur", "ぼかし"), lyricsBlur) { AppSettings.setLyricsBlur(!lyricsBlur) },
        SettingsSearchEntry(stringResource(R.string.auto_embed_lyrics), stringResource(R.string.auto_embed_lyrics_subtitle), listOf("lyrics", "歌詞", "embed", "埋め込み"), autoEmbedLyrics) { AppSettings.setAutoEmbedLyrics(!autoEmbedLyrics) },
        SettingsSearchEntry(stringResource(R.string.full_screen_cover_art), stringResource(R.string.full_screen_cover_art_subtitle), listOf("artwork", "cover", "full screen", "アート", "全画面"), fullBleedArtwork) { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
        SettingsSearchEntry(stringResource(R.string.keep_artwork_full_size_paused), stringResource(R.string.keep_artwork_full_size_paused_subtitle), listOf("artwork", "cover", "paused", "停止中", "アート"), keepArtworkFullSizeWhenPaused) { AppSettings.setKeepArtworkFullSizeWhenPaused(!keepArtworkFullSizeWhenPaused) },
        SettingsSearchEntry(stringResource(R.string.reduce_animation), stringResource(R.string.reduce_animation_subtitle), listOf("animation", "reduce", "アニメーション"), reduceAnimation) { AppSettings.setReduceAnimation(!reduceAnimation) },
        SettingsSearchEntry(stringResource(R.string.reduce_dynamic_blur), stringResource(R.string.reduce_dynamic_blur_subtitle), listOf("blur", "ぼかし", "performance", "動的"), reduceDynamicBlur) { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
        SettingsSearchEntry(stringResource(R.string.classic_nav_bar), stringResource(R.string.classic_nav_bar_subtitle), listOf("navigation", "nav bar", "ナビゲーション", "classic"), classicNavBar) { AppSettings.setClassicNavBar(!classicNavBar) },
        SettingsSearchEntry(stringResource(R.string.hide_nav_bar_labels), stringResource(R.string.hide_nav_bar_labels_subtitle), listOf("navigation", "nav bar", "label", "ラベル", "ナビゲーション"), hideNavBarLabels) { AppSettings.setHideNavigationBarLabels(!hideNavBarLabels) },
        SettingsSearchEntry(stringResource(R.string.legacy_mesh_gradient), stringResource(R.string.legacy_mesh_gradient_subtitle), listOf("theme", "gradient", "テーマ", "グラデーション"), legacyMeshGradient) { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
        SettingsSearchEntry(stringResource(R.string.filter_non_music_audio), stringResource(R.string.filter_non_music_audio_subtitle), listOf("filter", "audio", "music", "音楽", "フィルター"), filterNonMusicAudio) { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
        SettingsSearchEntry(stringResource(R.string.playlist_song_artwork), stringResource(R.string.playlist_song_artwork_subtitle), listOf("playlist", "artwork", "プレイリスト", "アルバムアート"), showPlaylistSongArtwork) { AppSettings.setShowPlaylistSongArtwork(!showPlaylistSongArtwork) },
    ) + PlayerDetailsAction.entries.map { action ->
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
        })
        SettingsSearchEntry(
            title = stringResource(R.string.show_tab_setting, tabTitle),
            subtitle = stringResource(R.string.show_tab_setting_subtitle, tabTitle),
            keywords = listOf("tab", "タブ", tabTitle),
            checked = tab in visibleMainTabs,
        ) {
            AppSettings.setMainNavigationTabVisible(tab, tab !in visibleMainTabs)
        }
    }

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

    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    val localPlaylists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()

    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showManageFoldersSheet by remember { mutableStateOf(false) }
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
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
            val matches = (settingsSearchEntries + settingsActionSearchEntries).filter { entry ->
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
                        )
                        val sliderValue = entry.sliderValue
                        val onSliderValueChange = entry.onSliderValueChange
                        if (sliderValue != null && onSliderValueChange != null) {
                            Slider(
                                value = sliderValue,
                                onValueChange = onSliderValueChange,
                                steps = entry.sliderSteps,
                                valueRange = entry.sliderValueRange,
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
                subtitle = buildString {
                    append(outputStatus.sink)
                    append(" · ")
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
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
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.equalizer),
                subtitle = stringResource(R.string.equalizer_subtitle),
                onClick = { showEqualizerSheet = true },
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
        }

        SettingsGroup(header = stringResource(R.string.appearance)) {
            SettingsRow(icon = Icons.Rounded.Brightness4, title = stringResource(R.string.theme))
            SegmentedControl(
                options = ThemeMode.entries.map { it.localizedLabel() },
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
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
                title = stringResource(R.string.show_status_bar),
                subtitle = stringResource(R.string.system_bar_visibility_subtitle),
                trailing = {
                    Switch(checked = showStatusBar, onCheckedChange = AppSettings::setShowStatusBar)
                },
                onClick = { AppSettings.setShowStatusBar(!showStatusBar) },
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
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                SettingsRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = stringResource(R.string.full_screen_cover_art),
                    subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                    trailing = {
                        Switch(
                            checked = fullBleedArtwork,
                            onCheckedChange = AppSettings::setFullBleedArtwork,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                )
                RowDivider()
            }
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
                    steps = 15,
                    valueRange = 14f..30f,
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
                }
                val icon = when (tab) {
                    MainNavigationTab.SONGS -> Icons.Rounded.MusicNote
                    MainNavigationTab.ALBUMS -> Icons.Rounded.Album
                    MainNavigationTab.ARTISTS -> Icons.Rounded.Person
                    MainNavigationTab.LIBRARY -> Icons.Rounded.LibraryMusic
                    MainNavigationTab.SEARCH -> BitChordIcons.Search
                }
                val checked = tab in visibleMainTabs
                SettingsRow(
                    icon = icon,
                    title = stringResource(titleRes),
                    subtitle = stringResource(R.string.navigation_tab_order_subtitle),
                    enabled = checked || visibleMainTabs.size > 1,
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

        SettingsGroup(header = stringResource(R.string.local_music)) {
            SettingsRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.music_folders),
                subtitle = stringResource(R.string.music_folders_subtitle),
                trailing = { Chevron() },
                onClick = { showManageFoldersSheet = true },
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
                    onClick = {
                        runCatching {
                            allFilesSettingsLauncher.launch(LocalMediaRepository.createAllFilesAccessIntent(context))
                        }.onFailure {
                            LocalMediaRepository.requestAllFilesAccess(context)
                        }
                    },
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
        }

        SettingsGroup(header = stringResource(R.string.storage)) {
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

        if (showStartupTabPicker) {
            AlertDialog(
                onDismissRequest = { showStartupTabPicker = false },
                title = { Text(stringResource(R.string.startup_tab)) },
                text = {
                    Column {
                        mainTabOrder.filter { it in visibleMainTabs }.forEach { tab ->
                            val label = when (tab) {
                                MainNavigationTab.SONGS -> stringResource(R.string.songs)
                                MainNavigationTab.ALBUMS -> stringResource(R.string.albums)
                                MainNavigationTab.ARTISTS -> stringResource(R.string.artists)
                                MainNavigationTab.LIBRARY -> stringResource(R.string.library)
                                MainNavigationTab.SEARCH -> stringResource(R.string.search)
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

    if (showEqualizerSheet) {
        com.music.bitchord.ui.screens.equalizer.EqualizerSheet(
            onDismiss = { showEqualizerSheet = false },
        )
    }

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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
