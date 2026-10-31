package com.music.bitchord

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.session.MediaController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import com.music.bitchord.feature.localmusic.ui.components.LocalPermissionCard
import com.music.bitchord.feature.localmusic.ui.FolderBrowserScreen
import androidx.compose.material.icons.rounded.LibraryMusic
import com.music.bitchord.feature.library.ui.LibraryScreen
import com.music.bitchord.feature.library.ui.components.LibraryFilter
import com.music.bitchord.ui.screens.LOCAL_TAB_SONGS
import com.music.bitchord.ui.screens.LOCAL_TAB_ALBUMS
import com.music.bitchord.ui.screens.LOCAL_TAB_ALBUM_ARTISTS
import com.music.bitchord.ui.screens.LOCAL_TAB_ARTISTS
import com.music.bitchord.ui.screens.LOCAL_TAB_FOLDERS
import com.music.bitchord.ui.screens.LOCAL_TAB_PLAYLISTS
import com.music.bitchord.ui.screens.RadioScreen
import com.music.bitchord.feature.localsearch.ui.LocalSearchScreen
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.feature.localmusic.ui.components.LocalAudioPermissionDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SavedYoutubeCollection
import com.music.bitchord.data.settings.MainNavigationTab
import com.music.bitchord.data.settings.MainNavigationTabs
import com.music.bitchord.data.settings.PlayerQuickAction
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.screens.SettingsScreen
import com.music.bitchord.ui.screens.WearOsScreen
import com.music.bitchord.ui.screens.SourcesScreen
import com.music.bitchord.ui.screens.SourceEditorAlert
import com.music.bitchord.playback.LinkRequest
import com.music.bitchord.playback.MusicLink
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.PlayerDeepLink
import com.music.bitchord.playback.MediaControlDeepLink
import com.music.bitchord.playback.MediaControlDestination
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.autoplaySectionStart
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.commitRadioQueue
import com.music.bitchord.playback.fromAutoplay
import com.music.bitchord.playback.hasYouTubeOriginal
import com.music.bitchord.playback.loadAutoplayTracks
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toDirectYouTubeMediaItem
import com.music.bitchord.playback.previewExternalAudio
import com.music.bitchord.playback.toggleAutoplay
import com.music.bitchord.playback.upgradeQuality
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.ArtworkBackdrop
import com.music.bitchord.ui.components.DownloadManagerSheet
import com.music.bitchord.ui.components.PlaylistPickerSheet
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.BottomFadeScrim
import com.music.bitchord.ui.components.BottomTab
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.bitchord.ui.components.FloatingBottomBar
import com.music.bitchord.ui.components.GlassNavBar
import com.music.bitchord.ui.components.PopupNavigationBar
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarInlineBehavior
import com.music.bitchord.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.music.bitchord.ui.components.FrostedTopBar
import com.music.bitchord.ui.components.LastfmLoginAlert
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.ui.components.FieldConfig
import com.music.bitchord.ui.components.ServerEditorHost
import com.music.bitchord.ui.components.ListenBrainzTokenAlert
import com.music.bitchord.ui.components.MiniPlayer
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.components.TopFadeBlur
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.components.AppLanguageDialog
import com.music.bitchord.ui.components.LyricsSourcesDialog
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.media3.common.Player
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.playback.SingleSongPlaybackManager
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.player.TabletLyricsPane
import com.music.bitchord.ui.player.TabletPlayerBackdrop
import com.music.bitchord.ui.player.LocalSharedPlayerBackdrop
import com.music.bitchord.ui.player.LocalPlayerDock
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.player.PlayerSheetMotion
import com.music.bitchord.ui.player.ThinSlider
import com.music.bitchord.ui.player.dockedPlayerAvailable
import com.music.bitchord.ui.player.dockedPlayerWidth
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.feature.lyricseditor.ui.LyricsEditorScreen
import com.music.bitchord.feature.localsongactions.ui.LocalSongActionsHelper
import com.music.bitchord.feature.localsongactions.ui.components.LocalAddToPlaylistSheet
import com.music.bitchord.feature.localsongactions.ui.components.LocalArtistPickerSheet
import com.music.bitchord.feature.localsongactions.ui.components.LocalSongActionsSheet
import com.music.bitchord.feature.localsongactions.ui.components.LocalSongDetailsSheet
import com.music.bitchord.feature.localsongactions.ui.components.AudioCutterSheet
import com.music.bitchord.feature.tageditor.ui.TagEditorScreen
import com.music.bitchord.ui.screens.equalizer.EqualizerSheet
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.music.bitchord.feature.localmusic.ui.components.DrillDownSongList
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import com.music.bitchord.widget.QuickPlayTileState
import com.music.bitchord.ui.screens.LocalMusicScreen
import com.music.bitchord.ui.replay.ReplayScreen
import com.music.bitchord.ui.replay.cards
import com.music.bitchord.ui.replay.ReplayShareSheet
import com.music.bitchord.ui.replay.ReplayStories
import com.music.bitchord.ui.replay.ReplayStoryPage
import com.music.bitchord.ui.replay.rememberReplayState
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.ui.theme.SystemBarIcons
import com.music.bitchord.ui.utils.rememberIosOverscrollFactory
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Locale

/** A full first screen of a native YouTube Music radio before AutoPlay tops it up. */
private const val INITIAL_RADIO_TRACKS = 24
private const val PLAYER_SHEET_SCRIM_ALPHA = 0.32f

class MainActivity : AppCompatActivity() {
    /** The expanded player is a modal surface on compact layouts. */
    private var expandedPlayerSurfaceActive = false
    private var musicOnlyModeLockTaskStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickPlayTileState.attach(this)
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time BitChordApp first reads it.
        PlayerDeepLink.consume(intent)
        MediaControlDeepLink.consume(intent)
        // Likewise for a link tapped or shared from another app — see [MusicLink].
        MusicLink.retainPersistableAudioPermission(this, intent)
        MusicLink.consume(intent)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val customThemeImageUri by AppSettings.customThemeImageUri.collectAsStateWithLifecycle()
            val matchButtonAccentToArtwork by AppSettings.matchButtonAccentToArtwork.collectAsStateWithLifecycle()
            val controller = rememberMediaController()
            val player = rememberPlayerState(controller)
            val currentArtwork = when {
                theme == ThemeMode.CUSTOM_IMAGE -> customThemeImageUri
                theme == ThemeMode.ARTWORK || matchButtonAccentToArtwork -> player.song?.artworkAt(PLAYER_ART_PX)
                else -> null
            }
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val keepScreenOn by AppSettings.keepScreenOn.collectAsStateWithLifecycle()
            val showStatusBar by AppSettings.showStatusBar.collectAsStateWithLifecycle()
            val showPlayerStatusBar by AppSettings.showPlayerStatusBar.collectAsStateWithLifecycle()
            val showNavigationBar by AppSettings.showNavigationBar.collectAsStateWithLifecycle()
            val forceWhiteNavigationBar by AppSettings.forceWhiteNavigationBar.collectAsStateWithLifecycle()
            val musicOnlyDeviceMode by AppSettings.musicOnlyDeviceMode.collectAsStateWithLifecycle()
            val screenOrientation by AppSettings.screenOrientationMode.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            SideEffect { composeView.keepScreenOn = keepScreenOn }
            LaunchedEffect(musicOnlyDeviceMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(musicOnlyDeviceMode)
                } else if (musicOnlyDeviceMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                }
                if (musicOnlyDeviceMode && !musicOnlyModeLockTaskStarted) {
                    runCatching { startLockTask() }
                        .onSuccess { musicOnlyModeLockTaskStarted = true }
                    // Android's screen-pinning confirmation and exit gesture
                    // remain controlled by the system; this mode never changes
                    // the device's secure lock or replaces its Home app.
                } else if (!musicOnlyDeviceMode && musicOnlyModeLockTaskStarted) {
                    runCatching { stopLockTask() }
                    musicOnlyModeLockTaskStarted = false
                }
            }
            DisposableEffect(composeView) {
                onDispose { composeView.keepScreenOn = false }
            }
            LaunchedEffect(showStatusBar, showPlayerStatusBar, showNavigationBar, forceWhiteNavigationBar) {
                applySystemBarPreferences()
            }
            LaunchedEffect(screenOrientation) {
                requestedOrientation = when (screenOrientation) {
                    com.music.bitchord.data.settings.ScreenOrientationMode.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    com.music.bitchord.data.settings.ScreenOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    com.music.bitchord.data.settings.ScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.AMOLED -> true
                ThemeMode.ARTWORK -> isSystemInDarkTheme()
                ThemeMode.CUSTOM_IMAGE -> true
            }
            BitChordTheme(
                darkTheme = darkTheme,
                amoledBlack = theme == ThemeMode.AMOLED,
                artworkUrl = currentArtwork,
                matchArtwork = theme == ThemeMode.ARTWORK || theme == ThemeMode.CUSTOM_IMAGE,
                matchButtonAccentToArtwork = matchButtonAccentToArtwork,
            ) {
                // The glass surfaces sample this layer, and a layer records only
                // what is drawn into it — which, for BitChord, is a page that
                // paints no background of its own. Everywhere a page is not
                // showing artwork the recording is transparent, so the glass had
                // nothing to blur there and you saw straight through it to the
                // sharp page underneath: album art came through the bar blurred
                // and text came through it untouched. The window's background is
                // the floor the pages have always been drawn against, so it is
                // laid down here too and the recording is opaque like the screen.
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                Box(Modifier.fillMaxSize()) {
                    if (theme == ThemeMode.CUSTOM_IMAGE && !customThemeImageUri.isNullOrBlank()) {
                        AsyncImage(
                            model = customThemeImageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().alpha(0.72f),
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.36f)),
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        BitChordApp(
                            darkTheme = darkTheme,
                            windowWidth = maxWidth,
                            windowHeight = maxHeight,
                            appBackdrop = appBackdrop,
                            controller = controller,
                            player = player,
                            onPlayerSurfaceVisibilityChanged = { visible ->
                                if (expandedPlayerSurfaceActive != visible) {
                                    expandedPlayerSurfaceActive = visible
                                    applySystemBarPreferences()
                                }
                            },
                        )
                    }
                }
                }
            }
        }
    }

    override fun onDestroy() {
        QuickPlayTileState.detach(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarPreferences()
    }

    private fun applySystemBarPreferences() {
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val showStatusBar = if (expandedPlayerSurfaceActive) {
            AppSettings.showPlayerStatusBar.value
        } else {
            AppSettings.showStatusBar.value
        }
        if (showStatusBar) bars.show(WindowInsetsCompat.Type.statusBars())
        else bars.hide(WindowInsetsCompat.Type.statusBars())
        if (AppSettings.showNavigationBar.value) bars.show(WindowInsetsCompat.Type.navigationBars())
        else bars.hide(WindowInsetsCompat.Type.navigationBars())
        if (AppSettings.forceWhiteNavigationBar.value) {
            bars.isAppearanceLightNavigationBars = false
        }
    }

    /**
     * Requests a window refresh rate without forcing a display mode or
     * resolution. Android may still lower it for temperature, battery state or
     * hardware limits, which is why Settings describes this as a preference.
     */
    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        MediaControlDeepLink.consume(intent)
        MusicLink.retainPersistableAudioPermission(this, intent)
        MusicLink.consume(intent)
    }
}

private data class PendingLocalArtistRename(
    val previousName: String,
    val newName: String,
    val songs: List<Song>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitChordApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    /** The measured height of the same app window, including freeform bounds. */
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    controller: MediaController?,
    player: PlayerState,
    onPlayerSurfaceVisibilityChanged: (Boolean) -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    val playerHazeState = remember { HazeState() }
    // Recording the backdrop layer costs a draw pass, so it only runs when the
    // nav bar's glass surface actually has something to sample.
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    // "Reduce dynamic blur" keeps the glass bar's *shape* — the folding
    // now-playing-and-tabs component is a layout, not an effect, and dropping
    // back to the two stacked bars would be answering a question about material
    // with a different screen. What it drops is the sampling: the surfaces fill
    // solid (see [Modifier.liquidGlass]) and the whole-page layer recording
    // below goes with them, which is the part that costs a draw pass.
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val classicNavBar by AppSettings.classicNavBar.collectAsStateWithLifecycle()
    val mainNavigationTabsPopup by AppSettings.mainNavigationTabsPopup.collectAsStateWithLifecycle()
    val useCollapsibleNavBar = !classicNavBar
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    var selectedTab by rememberSaveable { mutableIntStateOf(AppSettings.startupTab.value.index) }
    val mediaControlRequest by MediaControlDeepLink.pending.collectAsStateWithLifecycle()
    val hideCollectionTopBar by AppSettings.hideCollectionTopBar.collectAsStateWithLifecycle()
    val hideFloatingCollectionControls by AppSettings.hideFloatingCollectionControls.collectAsStateWithLifecycle()
    val collectionBackHandlers = remember { mutableStateMapOf<String, () -> Unit>() }
    var localCollectionArtwork by remember { mutableStateOf<Pair<Int, ArtworkPalette>?>(null) }
    val onLocalArtworkPaletteChanged: (ArtworkPalette?) -> Unit = remember(selectedTab) {
        { palette -> localCollectionArtwork = palette?.let { selectedTab to it } }
    }
    val visibleMainNavigationTabs by AppSettings.visibleMainNavigationTabs.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val radioAvailable = !offlineMode && !context.packageName.endsWith(".nointernet")
    val visibleLocalLibraryTabs by AppSettings.visibleLocalLibraryTabs.collectAsStateWithLifecycle()
    val localMusicFolderRoot by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val rememberLastOpenedFolder by AppSettings.rememberLastOpenedFolder.collectAsStateWithLifecycle()
    val lastOpenedFolderPath by AppSettings.lastOpenedFolderPath.collectAsStateWithLifecycle()
    val mainNavigationTabOrder by AppSettings.mainNavigationTabOrder.collectAsStateWithLifecycle()
    val effectiveVisibleMainTabs = remember(visibleMainNavigationTabs, visibleLocalLibraryTabs, radioAvailable) {
        val tabs = if (com.music.bitchord.data.settings.LocalLibraryTab.ALBUM_ARTISTS in visibleLocalLibraryTabs) {
            visibleMainNavigationTabs + MainNavigationTab.ALBUM_ARTISTS
        } else {
            visibleMainNavigationTabs - MainNavigationTab.ALBUM_ARTISTS
        }
        if (radioAvailable) tabs else tabs - MainNavigationTab.RADIO
    }
    val visibleTabIndices = remember(effectiveVisibleMainTabs, mainNavigationTabOrder) {
        mainNavigationTabOrder
            .filter { it in effectiveVisibleMainTabs }
            .map(MainNavigationTab::index)
    }
    LaunchedEffect(visibleTabIndices, mainNavigationTabOrder) {
        if (selectedTab !in visibleTabIndices && visibleTabIndices.isNotEmpty()) {
            selectedTab = MainNavigationTabs.fallback(
                selectedTab,
                effectiveVisibleMainTabs,
                mainNavigationTabOrder,
            ).index
            viewModel.clearDetail()
        }
    }
    val tabletFullscreenPlayer by AppSettings.tabletFullscreenPlayer.collectAsStateWithLifecycle()
    // Foldables in their open posture are often below the wide-pane docking
    // threshold, but still have room for the full player plus synced lyrics.
    val widePlayerLayout = windowWidth >= 600.dp
    // On wide screens, the player can either stay beside the page or use the
    // phone-style mini-player/full-screen flow. The latter keeps navigation
    // available and opens the existing player over the entire window.
    var expandedDockedPlayer by rememberSaveable { mutableStateOf(false) }
    val playerDocked = dockedPlayerAvailable(windowWidth) &&
        !tabletFullscreenPlayer &&
        !expandedDockedPlayer
    /**
     * Whether the player's *sheet* is up.
     *
     * Only ever set where there is a sheet to set it for. Docked, the player is
     * open whatever this says, and the things that read it — the light status
     * bar glyphs the artwork needs, the sheet itself — are all asking the one
     * question this used to answer on its own: is the player covering the page?
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(showNowPlaying) {
        if (!showNowPlaying) expandedDockedPlayer = false
    }
    LaunchedEffect(showNowPlaying, playerDocked) {
        onPlayerSurfaceVisibilityChanged(showNowPlaying && !playerDocked)
    }
    val reducePlayerMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val playerArtworkTransitionEnabled by AppSettings.playerArtworkTransition.collectAsStateWithLifecycle()
    val playerTransitionScope = rememberCoroutineScope()
    val playerDock = remember { PlayerDock() }
    val playerSheetMotion = remember(playerDock, playerTransitionScope) {
        PlayerSheetMotion(
            scope = playerTransitionScope,
            dock = playerDock,
            shown = { showNowPlaying },
            setShown = { showNowPlaying = it },
        )
    }
    val motionWindowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val motionDensity = LocalDensity.current
    LaunchedEffect(motionWindowInfo, motionDensity) {
        playerSheetMotion.windowInfo = motionWindowInfo
        playerSheetMotion.flingVelocity = with(motionDensity) { 400.dp.toPx() }
    }
    val normalNowPlayingSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && AppSettings.reduceAnimation.value) {
                showNowPlaying = false
                false
            } else {
                true
            }
        },
    )
    fun openNowPlaying() {
        if (widePlayerLayout) {
            expandedDockedPlayer = dockedPlayerAvailable(windowWidth)
            showNowPlaying = true
            return
        }
        if (playerDocked) return
        if (playerArtworkTransitionEnabled) playerSheetMotion.open() else showNowPlaying = true
    }
    fun dismissNowPlaying() {
        if (!showNowPlaying) return
        if (playerArtworkTransitionEnabled && !widePlayerLayout) {
            playerSheetMotion.close()
            return
        }
        if (reducePlayerMotion) {
            showNowPlaying = false
        } else {
            playerTransitionScope.launch {
                if (normalNowPlayingSheetState.currentValue != SheetValue.Hidden) {
                    normalNowPlayingSheetState.hide()
                }
                showNowPlaying = false
            }
        }
    }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects. Docked there is nothing to raise: the player is already up, and
    // the tap has been honoured by the time it arrives.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            openNowPlaying()
            PlayerDeepLink.handled()
        }
    }

    LaunchedEffect(Unit) {
        SingleSongPlaybackManager.closePlayerEvent.collect {
            dismissNowPlaying()
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showWearOs by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<SourceConfig?>(null) }
    var showWebDavEditor by remember { mutableStateOf(false) }
    var showSmbEditor by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }
    var replayStory by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by remember { mutableStateOf(false) }
    var replaySharePage by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    var showDownloadManager by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showPlaybackTuning by remember { mutableStateOf(false) }
    var targetAlbumDrillDown by remember { mutableStateOf<String?>(null) }
    var targetArtistDrillDown by remember { mutableStateOf<String?>(null) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    var pendingPlayerQuickAction by remember { mutableStateOf<PlayerQuickAction?>(null) }
    LaunchedEffect(songActions) {
        if (songActions == null) pendingPlayerQuickAction = null
    }
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songActions = song
    }
    // Whether the player's album/artist lookup (below, for the current track)
    // is still in flight — read by the long-press sheet so it can show a
    // loading row instead of the two just being absent while it waits.
    var linksLoading by remember { mutableStateOf(false) }
    // Which track the playlist picker is adding, or null when it's closed.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    // A collection action needs every page's tracks before the picker opens;
    // keep its label with the list so the picker can identify what will be added.
    var playlistCollectionTarget by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    // App-local playlists accept remote catalogue IDs without requiring a
    // Google account or writing anything to the user's YouTube account.
    var localPlaylistSongTarget by remember { mutableStateOf<Song?>(null) }
    var localPlaylistCollectionTarget by remember { mutableStateOf<List<Song>?>(null) }
    // The picker opened from the Library tab, where there is no track and
    // creating the playlist is the whole errand.
    var creatingPlaylist by remember { mutableStateOf(false) }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    // Incremented each time the search tab is re-tapped while already selected,
    // which SearchScreen uses as a signal to focus the input field.
    var searchFocusTrigger by remember { mutableIntStateOf(0) }
    val searchSaveableStateHolder = rememberSaveableStateHolder()
    var libraryFilterRequest by remember { mutableStateOf<LibraryFilter?>(null) }
    var requestedMediaPanel by remember { mutableStateOf<MediaControlDestination?>(null) }
    LaunchedEffect(mediaControlRequest) {
        when (mediaControlRequest) {
            null -> Unit
            MediaControlDestination.PLAYLISTS -> {
                libraryFilterRequest = LibraryFilter.PLAYLISTS
                selectedTab = MainNavigationTab.LIBRARY.index
                viewModel.clearDetail()
                MediaControlDeepLink.handled()
            }
            MediaControlDestination.LYRICS,
            MediaControlDestination.QUEUE -> {
                requestedMediaPanel = mediaControlRequest
                openNowPlaying()
            }
        }
    }
    // Invalidates an in-flight radio lookup when a later play request wins.
    var playRequestGeneration by remember { mutableIntStateOf(0) }
    // Single-flight playback job to cancel previous in-flight requests on rapid taps
    var playJob by remember { mutableStateOf<Job?>(null) }
    // Starting radio from the item already playing must not replace that media
    // item just to add UI metadata. This temporary label covers that seed; all
    // following radio items carry radioName in their MediaItem extras.
    var activeRadioSeed by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The player fills the screen with dark artwork whichever theme is on, so
    // it keeps light glyphs; every other surface follows the theme. Replay's
    // page and stories are the same case — dark artwork either way.
    SystemBarIcons(dark = !darkTheme && !showNowPlaying && !showReplay && replayStory == null)

    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val detail = detailStack.lastOrNull()
    val isLocalArtistDetail = detail?.browseId?.startsWith("local:artist:") == true
    var localArtistSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedLocalArtistSongIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showLocalArtistRenameDialog by remember { mutableStateOf(false) }
    var localArtistRenameValue by remember { mutableStateOf("") }
    var localArtistRenameTargets by remember { mutableStateOf<List<Song>>(emptyList()) }
    var pendingLocalArtistRename by remember { mutableStateOf<PendingLocalArtistRename?>(null) }
    LaunchedEffect(detail?.browseId) {
        if (!isLocalArtistDetail) {
            localArtistSelectionMode = false
            selectedLocalArtistSongIds = emptySet()
            showLocalArtistRenameDialog = false
            localArtistRenameTargets = emptyList()
        }
    }
    // Local Music has no artwork to wash the bar in, so it renders with a
    // plain status bar rather than the artwork-driven blur other detail
    // pages (album/artist/playlist) get. Downloads is the same page, and the
    // tab row it now carries sits directly under the bar, so it needs the same
    // treatment — an artwork blur over it would tint the tabs.
    //
    // A downloaded playlist's page is under `local:` too and is none of that: it
    // has a cover and a track list, so it takes the bar every other release page
    // takes. Hence the folder question rather than the prefix.
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    // Not keyed on the browse id and not remembered here: each page's choice
    // lives in AppSettings keyed by that page — Spotify-style, one playlist's
    // order never imposes itself on another, and every page keeps its own
    // across visits.
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = detail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    // Which tracks are being held on YouTube's own upload, so the player's menu
    // offers the way back out of a revert rather than the revert again.
    val pinnedToOriginal by OriginalVersion.pinned.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsVideoId by viewModel.lyricsVideoId.collectAsStateWithLifecycle()
    val savedYoutubeCollections by AppSettings.savedYoutubeCollections.collectAsStateWithLifecycle()
    val settingsTopBarUsesMoreIcon by AppSettings.settingsTopBarUsesMoreIcon.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()

    // Settings has no tab of its own — it sits on top of whatever tab was
    // selected. A pushed album/artist page (from the player, search, etc.)
    // should surface above it rather than being hidden behind it.
    LaunchedEffect(detail) { if (detail != null) showSettings = false }

    // The Downloads page is a snapshot of the folder, taken when it was opened.
    // Saving a track or deleting one while it is on screen changes what belongs
    // on it — and now that the page groups by artist and album, a stale list is
    // stale counts and a missing row in three places rather than one. So it is
    // taken again whenever the record of what's on disk changes.
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    // The releases those files were asked for as — read here rather than in the
    // page so the Downloads folder recomposes when one is added, the same way it
    // does when a file is.
    val savedCollections by Downloads.collections.collectAsStateWithLifecycle()
    // The playlists among them, for the Library page's On Device shelf. Read off
    // both records: the collection record is what says a playlist was downloaded
    // as a playlist, and what is on disk is what says it still has anything left
    // to open.
    val downloadedPlaylists = remember(savedCollections, savedDownloads) {
        Downloads.savedPlaylists()
    }
    // What a browse id is recorded under in Downloads.collections, when it names
    // a release downloaded whole — see BrowseTarget.downloadId. A downloaded
    // playlist's own page and its card both carry the id under the
    // `local:playlist:` prefix; a release still reachable by its real id (an
    // album's own page, a search hit) is looked up directly under that instead.
    val downloadIdFor: (String?) -> String? = { id ->
        id?.let { Downloads.recordIdOf(it) ?: it }?.takeIf { it in savedCollections }
    }
    LaunchedEffect(savedDownloads, savedCollections, detail?.browseId) {
        val open = detail?.browseId ?: return@LaunchedEffect
        // A downloaded playlist's page is a snapshot of the same folder and goes
        // stale for the same reasons — and it is the one page a delete can empty
        // out entirely, which is worth saying rather than leaving rows behind
        // that play nothing.
        if (open == "local:downloads" || Downloads.recordIdOf(open) != null) {
            viewModel.reloadLocalDetail(open)
        }
    }
    LaunchedEffect(filterNonMusicAudio) {
        if (detail?.browseId == "local:all") {
            viewModel.reloadLocalDetail("local:all")
        }
    }

    val hasMusicBar = player.song != null && !playerDocked
    val navBarScroll = rememberFloatingTabBarScrollConnection(
        inlineBehavior = if (hasMusicBar) FloatingTabBarInlineBehavior.OnScrollDown else FloatingTabBarInlineBehavior.Never,
    )
    val onDismissMusicBar: () -> Unit = {
        controller?.stop()
        controller?.clearMediaItems()
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    // A conversion is deliberately scoped to the current listening session.
    // Keeping the complete original row here lets Revert restore the exact
    // video upload, including its title and playlist identity, rather than
    // trying to reconstruct it from the catalogue match.
    var convertedFromVideo by remember { mutableStateOf<Song?>(null) }
    var convertedAudioId by remember { mutableStateOf<String?>(null) }
    var switchingAudioVersion by remember { mutableStateOf(false) }

    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off applies to
    // the track already playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    val preferEmbeddedLocalLyrics by AppSettings.preferEmbeddedLocalLyrics.collectAsStateWithLifecycle()
    val autoShowLyricsOnPlayback by AppSettings.autoShowLyricsOnPlayback.collectAsStateWithLifecycle()
    LaunchedEffect(
        player.song?.videoId,
        player.durationMs,
        syncedLyricsEnabled,
        lyricsSources,
        lyricsSourceOrder,
        prioritizeSyllableSync,
        preferEmbeddedLocalLyrics,
        autoShowLyricsOnPlayback,
    ) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                player.durationMs,
                it.albumName,
                it.localUri,
                song = it,
            )
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(LocalMediaRepository.hasStoragePermission(context))
    }
    var hasAllFilesPermission by remember {
        mutableStateOf(LocalMediaRepository.hasAllFilesPermission(context))
    }
    val allFilesAsked by AppSettings.allFilesPermissionAsked.collectAsStateWithLifecycle()
    var showFirstLaunchPermissionDialog by rememberSaveable {
        mutableStateOf(!allFilesAsked)
    }

    val localSongsState by viewModel.localSongs.collectAsStateWithLifecycle()
    val youtubeMusicSearchResults by viewModel.results.collectAsStateWithLifecycle()
    val youtubeMusicSearchHasMore by viewModel.searchHasMore.collectAsStateWithLifecycle()
    val youtubeMusicSearchLoadingMore by viewModel.searchLoadingMore.collectAsStateWithLifecycle()
    val musicServerSearchResults by viewModel.musicServerResults.collectAsStateWithLifecycle()
    val configuredSourceSearchResults by viewModel.configuredSourceResults.collectAsStateWithLifecycle()
    val remoteServerConfigs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val individuallySearchableSources = remember(remoteServerConfigs, offlineMode, context.packageName) {
        if (offlineMode || context.packageName.endsWith(".nointernet")) {
            emptyList()
        } else {
            remoteServerConfigs.filter { config ->
                config.enabled && config.isComplete && config.kind != SourceKind.YOUTUBE
            }
        }
    }
    val canSearchMusicServers = !offlineMode &&
        !context.packageName.endsWith(".nointernet") &&
        remoteServerConfigs.any { it.enabled && it.kind.isRemoteMusicServer && it.isComplete }
    val isRefreshingLocalMusic by viewModel.isRefreshingLocalMusic.collectAsStateWithLifecycle()
    val localArtworkPreload by viewModel.localArtworkPreload.collectAsStateWithLifecycle()
    val localSongs = (localSongsState as? UiState.Success)?.data.orEmpty()
    val localEmptyMessage = (localSongsState as? UiState.Error)?.message
    val includeRemoteSourceMedia by AppSettings.includeRemoteSourceMediaInLibrary.collectAsStateWithLifecycle()
    val showCollectionSongArtwork by AppSettings.showCollectionSongArtwork.collectAsStateWithLifecycle()
    val webdavUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val webdavUsername by AppSettings.webdavUsername.collectAsStateWithLifecycle()
    val webdavPassword by AppSettings.webdavPassword.collectAsStateWithLifecycle()
    val webdavAllowInsecureHttp by AppSettings.webdavAllowInsecureHttp.collectAsStateWithLifecycle()
    var remoteLibraryRefresh by remember { mutableIntStateOf(0) }
    var remoteLibrarySongs by remember { mutableStateOf<List<com.music.bitchord.data.model.Song>>(emptyList()) }
    LaunchedEffect(
        includeRemoteSourceMedia,
        offlineMode,
        context.packageName,
        remoteServerConfigs,
        webdavUrl,
        webdavUsername,
        webdavPassword,
        webdavAllowInsecureHttp,
        remoteLibraryRefresh,
    ) {
        if (!includeRemoteSourceMedia || offlineMode || context.packageName.endsWith(".nointernet")) {
            remoteLibrarySongs = emptyList()
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val serverSongs = SourceRegistry.remoteLibraryTracks()
            val webdavSongs = try {
                WebDavRepository.getSongs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            (serverSongs + webdavSongs).distinctBy { it.videoId }
        }
        remoteLibrarySongs = loaded
    }
    val catalogSongs = remember(localSongs, remoteLibrarySongs, includeRemoteSourceMedia) {
        if (includeRemoteSourceMedia) (localSongs + remoteLibrarySongs).distinctBy { it.videoId }
        else localSongs
    }
    val catalogEmptyMessage = localEmptyMessage.takeIf { catalogSongs.isEmpty() }
    val refreshCatalog: () -> Unit = {
        viewModel.loadLocalMusic(isPullToRefresh = true)
        if (includeRemoteSourceMedia) remoteLibraryRefresh++
    }
    val preloadArtworkOnStartup by AppSettings.preloadAlbumArtOnStartup.collectAsStateWithLifecycle()

    LaunchedEffect(preloadArtworkOnStartup, hasStoragePermission) {
        if (preloadArtworkOnStartup && hasStoragePermission) {
            viewModel.preloadLocalArtwork(startup = true)
        }
    }

    LaunchedEffect(Unit) {
        if (LocalMediaRepository.hasStoragePermission(context)) {
            hasStoragePermission = true
            viewModel.loadLocalMusic()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val currentStorage = LocalMediaRepository.hasStoragePermission(context)
                val currentAllFiles = LocalMediaRepository.hasAllFilesPermission(context)
                hasAllFilesPermission = currentAllFiles
                if (currentStorage != hasStoragePermission) {
                    hasStoragePermission = currentStorage
                    if (currentStorage) {
                        viewModel.reloadLocalDetail("local:all")
                        viewModel.loadLocalMusic()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val searchListState = rememberLazyListState()
    val currentListState = searchListState
    val refreshing = false
    val currentPull = null
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    // A pushed album/artist/playlist page has a large header of its own — the
    // sleeve, or an artist's photo running edge to edge — which owns the title
    // until it is scrolled away, exactly as a tab's big heading does. The state
    // is hoisted because the bar lives beside that page rather than inside it,
    // and is rebuilt per page: pushing a second one must not inherit the
    // first's scroll offset.
    // As [detailListState], for Replay: its own large heading owns the title
    // until it is scrolled away, and the bar lives out here rather than on the
    // page. Rebuilt per opening so reopening starts at the top.
    val replayListState = rememberLazyListState()
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    val detailListState = remember(detail?.browseId) { LazyListState() }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Held, not rebuilt. `listOf` hands back a new instance on every pass, and a
    // List is not a type the compiler can call stable, so under strong skipping
    // the bar this is handed to compares it by identity, never matches, and so
    // can never skip. This composable re-runs on every frame of a scroll — it
    // reads [scrolled] — which made the whole floating bar, both of its states
    // and every glass surface on them recompose once per frame for the length of
    // a fold. Keyed on the labels so a locale change still rebuilds it.
    val songsLabel = stringResource(R.string.songs)
    val albumsLabel = stringResource(R.string.albums)
    val artistsLabel = stringResource(R.string.artists)
    val albumArtistsLabel = stringResource(R.string.album_artists)
    val libraryLabel = stringResource(R.string.library)
    val searchLabel = stringResource(R.string.search)
    val foldersLabel = stringResource(R.string.folders)
    val radioLabel = stringResource(R.string.radio)
    val allTabs = remember(songsLabel, albumsLabel, artistsLabel, albumArtistsLabel, libraryLabel, searchLabel, foldersLabel, radioLabel) {
        listOf(
            BottomTab(songsLabel, Icons.Rounded.MusicNote),
            BottomTab(albumsLabel, Icons.Rounded.Album),
            BottomTab(artistsLabel, Icons.Rounded.Person),
            BottomTab(libraryLabel, Icons.Rounded.LibraryMusic),
            BottomTab(searchLabel, BitChordIcons.Search),
            BottomTab(albumArtistsLabel, Icons.Rounded.Person),
            BottomTab(foldersLabel, Icons.Rounded.Folder),
            BottomTab(radioLabel, Icons.Rounded.Radio),
        )
    }
    val tabs = remember(allTabs, visibleTabIndices) {
        visibleTabIndices.mapNotNull(allTabs::getOrNull)
    }

    val scope = rememberCoroutineScope()

    suspend fun writeLocalArtistRename(pending: PendingLocalArtistRename) {
        val updatedTracks = withContext(Dispatchers.IO) {
            buildList {
                pending.songs.distinctBy { it.localUri ?: it.localPath ?: it.videoId }.forEach { song ->
                    try {
                        val current = TagLibWriter.readTags(context, song)
                        val updated = current.copy(artist = pending.newName)
                        if (TagLibWriter.writeTags(context, song, updated)) {
                            add(song to updated)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep processing the remaining selected tracks.
                    }
                }
            }
        }
        val updatedSongs = updatedTracks.map { (song, tags) ->
            song.copy(
                title = tags.title.trim().ifBlank { song.title },
                artist = tags.artist.trim().ifBlank { "Unknown Artist" },
                albumName = tags.album.trim().takeIf(String::isNotBlank),
                albumArtist = tags.albumArtist.trim().takeIf(String::isNotBlank),
            )
        }
        updatedTracks.zip(updatedSongs).forEach { (tagged, updatedSong) ->
            val tags = tagged.second
            LocalPlaylistStore.updateEditedSongMetadata(
                song = updatedSong,
                title = tags.title,
                artist = tags.artist,
                albumName = tags.album,
                albumArtist = tags.albumArtist,
            )
            viewModel.updateLocalSongMetadata(updatedSong)
        }
        if (updatedSongs.isNotEmpty()) {
            viewModel.renameLocalArtistPage(pending.previousName, pending.newName)
            viewModel.loadLocalMusic(isPullToRefresh = true)
        }
        Toast.makeText(
            context,
            context.getString(R.string.bulk_tag_saved, updatedSongs.size, pending.songs.size),
            Toast.LENGTH_LONG,
        ).show()
        selectedLocalArtistSongIds = emptySet()
        localArtistSelectionMode = false
        localArtistRenameTargets = emptyList()
    }

    val localArtistTagWritePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pending = pendingLocalArtistRename
        pendingLocalArtistRename = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            scope.launch { writeLocalArtistRename(pending) }
        } else if (pending != null) {
            Toast.makeText(context, R.string.bulk_tag_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun saveLocalArtistRename(targets: List<Song>, newName: String) {
        val cleanName = newName.trim()
        val previousName = detail?.title?.trim().orEmpty()
        if (!isLocalArtistDetail || cleanName.isBlank() || previousName.isBlank() || targets.isEmpty()) return
        val pending = PendingLocalArtistRename(previousName, cleanName, targets)
        val mediaStoreUris = targets.mapNotNull { song ->
            (song.localUri ?: song.videoId)
                .takeIf { it.startsWith("content://") }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.authority == MediaStore.AUTHORITY }
        }.distinct()
        val needsApproval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaStoreUris.filter { uri ->
                context.checkUriPermission(
                    uri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) != PackageManager.PERMISSION_GRANTED
            }
        } else emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && needsApproval.isNotEmpty()) {
            val request = runCatching {
                MediaStore.createWriteRequest(context.contentResolver, needsApproval)
            }.getOrNull()
            if (request != null) {
                pendingLocalArtistRename = pending
                localArtistTagWritePermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }
        }
        scope.launch { writeLocalArtistRename(pending) }
    }

    val play: (List<Song>, Int) -> Unit = { songs, index ->
        playJob?.cancel()
        playRequestGeneration++
        activeRadioSeed = null
        playJob = scope.launch {
            if (songs.isEmpty()) return@launch
            try {
                val resolvedSongs = songs.map { s ->
                    if (s.localUri != null && !Downloads.isMissingLocalFile(s.localUri)) {
                        s
                    } else if (!s.localPath.isNullOrBlank() && java.io.File(s.localPath).isFile) {
                        s.copy(localUri = android.net.Uri.fromFile(java.io.File(s.localPath)).toString())
                    } else if (SourceRegistry.parseTrackKey(s.videoId) != null) {
                        // A server catalogue result has a source-qualified id.
                        // Preserve it as-is; matching only its title and artist
                        // here could silently replace it with a different local
                        // recording before the source stream resolver sees it.
                        s
                    } else {
                        localSongs.firstOrNull { local ->
                            local.videoId == s.videoId ||
                            (local.title.equals(s.title, ignoreCase = true) &&
                             (s.artist.isBlank() || local.artist.equals(s.artist, ignoreCase = true)))
                        } ?: s
                    }
                }

                // A stale file:// entry can survive in an imported/saved playlist.
                // Do not hand that known-missing URI to Media3, where it can fail
                // while the rest of the queue is being prepared. Keep the clicked
                // row's position by starting at the next remaining track.
                val playable = resolvedSongs.mapIndexedNotNull { originalIndex, song ->
                    val fileUri = song.localUri?.takeIf { it.startsWith("file://") }
                        ?: song.videoId.takeIf { it.startsWith("file://") }
                    if (fileUri != null && Downloads.isMissingLocalFile(fileUri)) null
                    else originalIndex to song
                }
                if (playable.isEmpty()) {
                    Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val requestedIndex = index.coerceIn(songs.indices)
                val startIndex = playable.indexOfFirst { (originalIndex, _) -> originalIndex >= requestedIndex }
                    .takeIf { it >= 0 } ?: 0
                controller?.playSongs(playable.map { it.second }, startIndex)
                // Nothing to raise where the player is already open beside the page.
                openNowPlaying()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TrackLog.e("Player", "Could not start the selected queue", error)
                Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(player.song?.videoId) {
        if (activeRadioSeed?.first != player.song?.videoId) activeRadioSeed = null
        if (player.song?.videoId != convertedAudioId) {
            convertedFromVideo = null
            convertedAudioId = null
            switchingAudioVersion = false
        }
    }

    /**
     * A song picked on its own — off a home card or a search hit — starts a
     * station rather than queueing the list it was shown in. Searching
     * "Perfect" and tapping the top hit otherwise queues twenty covers and
     * remixes of the same song. Album, artist and playlist pages keep [play],
     * where the surrounding list *is* the thing the user asked for.
     */
    val playRadio: (Song) -> Unit = { song ->
        playJob?.cancel()
        playRequestGeneration++
        activeRadioSeed = null
        playJob = scope.launch {
            controller?.playSongs(listOf(song), 0)
            openNowPlaying()
        }
    }

    /**
     * Starts the explicit station offered by every song overflow menu.
     *
     * The related tracks come from YouTube Music's own RDAMVM watch queue.
     * Loading happens before the player is touched so a failed request cannot
     * destroy the queue already playing. Once ready, the whole old queue is
     * replaced in one Media3 operation.
     */
    val startRadio: (Song) -> Unit = { song ->
        val originalController = controller
        if (originalController != null) {
            val request = ++playRequestGeneration
            // Ignore AutoPlay's tail: it may legitimately grow while the
            // request is in flight and does not mean the listener chose a
            // different queue. A new album/song queue does.
            val originalManualQueue = (0 until originalController.mediaItemCount)
                .map { originalController.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            scope.launch {
                val seed = song.copy(radioName = song.title)
                val related = loadAutoplayTracks(
                    existing = listOf(seed),
                    seedSong = seed,
                    limit = INITIAL_RADIO_TRACKS,
                ).getOrElse {
                    if (request == playRequestGeneration) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (related.isEmpty()) {
                    if (request == playRequestGeneration) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val activeController = controller
                val activeManualQueue = (0 until activeController.mediaItemCount)
                    .map { activeController.getMediaItemAt(it) }
                    .filterNot { it.fromAutoplay }
                    .map { it.mediaId }
                if (request != playRequestGeneration || activeManualQueue != originalManualQueue) {
                    return@launch
                }
                // Cancel any armed crossfade/AutoPlay work and erase the old
                // cold-start snapshot before the visible queue is replaced.
                activeController.beginRadioQueue()
                val currentIndex = activeController.currentMediaItemIndex
                val currentItem = activeController.currentMediaItem
                if (currentIndex >= 0 && currentItem?.mediaId == song.videoId) {
                    // Keep the current MediaItem itself untouched. Replacing it,
                    // even with the same song, reparses the source at position
                    // zero and audibly stops/restarts the track.
                    if (currentIndex + 1 < activeController.mediaItemCount) {
                        activeController.removeMediaItems(currentIndex + 1, activeController.mediaItemCount)
                    }
                    if (currentIndex > 0) activeController.removeMediaItems(0, currentIndex)
                    activeController.addMediaItems(1, related.map { it.toMediaItem() })
                    activeRadioSeed = song.videoId to song.title
                } else {
                    activeRadioSeed = null
                    activeController.playSongs(listOf(seed) + related, 0)
                }
                // Make this station — never the queue from before it — what a
                // fresh process restores, even if it is killed immediately.
                activeController.commitRadioQueue()
                Toast.makeText(
                    context,
                    context.getString(R.string.radio_started, song.title),
                    Toast.LENGTH_SHORT,
                ).show()
                openNowPlaying()
            }
        }
    }
    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            // The end of what the user queued, not the end of the queue: a song
            // asked for by name outranks whatever AutoPlay lined up behind it.
            controller?.let {
                val queued = song.copy(radioName = it.currentMediaItem?.toSong()?.radioName)
                it.addMediaItem(it.autoplaySectionStart(), queued.toMediaItem())
            }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            controller?.let {
                val queued = song.copy(radioName = it.currentMediaItem?.toSong()?.radioName)
                it.addMediaItem(
                    (it.currentMediaItemIndex + 1).coerceAtMost(it.mediaItemCount),
                    queued.toMediaItem(),
                )
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    /**
     * Opens an artist or release page given its browse id — or, failing that,
     * its name.
     *
     * Replay's charts are the reason this exists. An artist there is counted by
     * *name*, because a name is the only thing every track carries: a browse id
     * rides along only when the row that queued the track happened to have one,
     * which for a home-feed card or an AutoPlay suggestion it does not. So half
     * the rows on a chart would have nothing to open, and a row that does
     * nothing when tapped is worse than a row that isn't tappable — it reads as
     * the app having failed rather than as the app not offering.
     *
     * Searching for the name is what the user would do next anyway, and it is
     * what the app already does to find a video's catalogue release (see
     * [YtMusicRepository.resolveAudio]). A search that finds nothing says so,
     * which is at least an answer.
     */
    val openLocalAlbum: (String, String?) -> Unit = { albumName, art ->
        val clean = albumName.trim()
        if (clean.isNotBlank()) {
            viewModel.openDetail(
                browseId = "local:album:$clean",
                title = clean,
                thumbnailUrl = art,
                type = BrowseType.ALBUM,
            )
        }
    }

    val openLocalArtist: (String, String?) -> Unit = { artistName, art ->
        val clean = artistName.trim()
        if (clean.isNotBlank()) {
            viewModel.openDetail(
                browseId = "local:artist:$clean",
                title = clean,
                thumbnailUrl = art,
                type = BrowseType.ARTIST,
            )
        }
    }

    fun openByName(
        browseId: String?,
        name: String,
        subtitle: String?,
        type: BrowseType,
        artwork: String? = null,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        if (!browseId.isNullOrBlank() && !browseId.startsWith("local:")) {
            viewModel.openDetail(
                browseId = browseId,
                title = cleanName,
                subtitle = subtitle.orEmpty(),
                thumbnailUrl = artwork,
                type = type,
            )
        } else if (type == BrowseType.ARTIST) {
            openLocalArtist(cleanName, artwork)
        } else {
            openLocalAlbum(cleanName, artwork)
        }
    }

    /**
     * A whole album, playlist or library list onto the queue in one go.
     *
     * [next] picks which of the two positions the single-track menu already
     * offers it lands in — straight after the current track, or behind
     * everything else the user queued but still ahead of AutoPlay (see
     * [addToQueue]). The list keeps its own running order either way: this
     * *adds* a release, it doesn't start one, so [QueueShuffle] has no say here.
     *
     * There is usually nothing on screen to show for it — the queue panel is
     * shut, the current track carries on — so the count is said out loud, the
     * same way a batch download is.
     */
    val queueSongs: (List<Song>, Boolean) -> Unit = { songs, next ->
        if (songs.isNotEmpty()) {
            scope.launch {
                val c = controller
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing to queue behind. "Add to queue" on a silent
                    // player can only mean start here — and adding without
                    // preparing would leave the list sitting in a player that
                    // never gets round to it.
                    play(songs, 0)
                } else {
                    val at = if (next) {
                        (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
                    } else {
                        c.autoplaySectionStart()
                    }
                    val radioName = c.currentMediaItem?.toSong()?.radioName
                    c.addMediaItems(at, songs.map { it.copy(radioName = radioName).toMediaItem() })
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        songs.size,
                        songs.size,
                    )
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val addSongsToQueue: (List<Song>) -> Unit = { songs -> queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> queueSongs(songs, true) }

    // ---- Links from outside the app ----

    /**
     * A YouTube Music link tapped elsewhere on the device, a link shared into
     * BitChord, or "play something" said to the assistant — see [MusicLink].
     *
     * Keyed on the controller as well as the request, because a link is as
     * often as not what cold-starts the app: the session it has to play into is
     * still connecting the first time this runs, and returning empty-handed
     * without spending the request is what lets the second run serve it.
     */
    val linkRequest by MusicLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(linkRequest, controller) {
        val request = linkRequest ?: return@LaunchedEffect
        // Nothing here can be served without somewhere to play it — even the
        // branches that only push a page are a beat away from a tap on one of
        // its rows, and half-serving a request would spend it.
        val session = controller ?: return@LaunchedEffect
        when (request) {
            is LinkRequest.ExternalAudio -> {
                // The service parks and restores the current queue around this
                // one-file preview, so opening a file never loses the user's
                // playlist or its exact playhead.
                session.previewExternalAudio(request.uri)
                openNowPlaying()
            }
            is LinkRequest.Track -> {
                val song = YtMusicRepository.trackLinks(request.videoId).getOrNull()
                if (song == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.couldnt_open_link),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    // A link is one song named on purpose, which is exactly the
                    // case [playRadio] exists for: play it and let AutoPlay
                    // carry on, rather than queueing something around it.
                    playRadio(song)
                }
            }
            is LinkRequest.Page -> {
                dismissNowPlaying()
                // Titled by the page itself once it lands — a link carries a
                // browse id and nothing else. See MainViewModel.openDetail.
                viewModel.openDetail(request.browseId, title = "")
            }
            is LinkRequest.Search -> {
                val songs = if (!request.play) null else {
                    YtMusicRepository.search(request.query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                }
                val top = songs?.firstOrNull()?.song
                if (top != null) {
                    playRadio(top)
                } else {
                    // Either the link was a search to look at, or "play X"
                    // found nothing to start — and the results are a better
                    // answer to a spoken request than silence is.
                    dismissNowPlaying()
                    selectedTab = TAB_SEARCH
                    viewModel.searchFor(request.query)
                }
            }
            // "Play music", nothing named. The playback service restores its
            // bounded queue before the controller connects, so this resumes
            // both a live session and one recovered after process death.
            LinkRequest.Resume -> if (session.mediaItemCount > 0) session.play()
        }
        MusicLink.handled()
    }

    // ---- Album / playlist menu ----

    /**
     * Holding an album or playlist card, wherever one is drawn.
     *
     * Artists are left out. An artist page is a selection of their work rather
     * than a running order, and "add Radiohead to the queue" has no answer that
     * isn't a guess — so holding an artist card does nothing, as it did before.
     */
    val onBrowseLongPress: (ShelfItem) -> Unit = { item ->
        val id = item.browseId
        val type = id?.let { viewModel.browseTypeOf(it) }
        if (id != null && type != BrowseType.ARTIST) {
            browseActions = BrowseTarget(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                type = type ?: BrowseType.OTHER,
                downloadId = downloadIdFor(id),
            )
        }
    }

    /**
     * The track a song card stands for, or null if the card is a collection.
     *
     * The card's own subtitle is billed as "Song • Chelsea Wolfe"; only the
     * credit belongs in the field the player, mini player and everything
     * downstream read.
     */
    val shelfSong: (ShelfItem) -> Song? = { item ->
        item.videoId?.let { videoId ->
            Song(
                videoId = videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
            )
        }
    }

    /**
     * Holding a card on a feed whose shelves mix tracks with collections —
     * Quick picks and Recently played are songs, Listen again is either.
     *
     * [onBrowseLongPress] alone answered only half of them: a track card
     * carries a videoId and no browse id, so holding one fell through its
     * null check and nothing opened. Dispatched on the same test as the tap
     * below, so a card that plays a song offers the track menu and a card that
     * opens a page offers the album / playlist one.
     */
    val onShelfLongPress: (ShelfItem) -> Unit = { item ->
        val song = shelfSong(item)
        if (song != null) openSongMenu(song) else onBrowseLongPress(item)
    }

    /**
     * Hands [action] the target's whole track list.
     *
     * A card has no tracks behind it — its page was never opened — so the
     * listing is fetched first, all of it, and the menu that asked has already
     * closed by the time it lands. A release page's overflow passes the rows it
     * is already showing and this is immediate.
     *
     * Album rows arrive with no album name of their own, the same way they do on
     * the page (see `withAlbum` below), so the title is stamped on here too —
     * otherwise a track queued from an album card reaches the player and the
     * download folder with nothing to file it under.
     */
    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                if (target.type == BrowseType.ALBUM) {
                    songs.map { it.copy(albumName = it.albumName ?: target.title) }
                } else {
                    songs
                },
            )
        }
        when {
            // A remote detail page only holds its first visible page. Actions
            // such as Download all and Add to queue must follow the page's
            // continuation instead of treating that partial list as complete.
            target.browseId == null && target.songs.isNotEmpty() -> stamp(target.songs)
            target.browseId == null ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
            else -> viewModel.collectSongs(target.browseId, target.thumbnailUrl) { result ->
                result.fold(
                    onSuccess = stamp,
                    onFailure = {
                        val message = it.message ?: context.getString(R.string.couldnt_load_tracks)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    // ---- Downloads ----
    // Two permissions, and never both on one device: writing to the shared
    // Music folder needs storage access below API 29 and none at all from
    // 29 on, where MediaStore grants an app its own rows; notifications are
    // only asked for from API 33. So the branches below are mutually exclusive
    // by SDK level, and nothing here can stack two dialogs on each other.
    var downloadPending by remember { mutableStateOf<List<Song>>(emptyList()) }
    /**
     * What the pending batch was asked for as, held alongside it for the same
     * reason: the storage-permission dialog is a round trip through another
     * process, and the release has to survive it or a whole album granted
     * permission arrives as forty loose tracks.
     */
    var downloadPendingFrom by remember { mutableStateOf<DownloadTarget?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Refusing costs the progress notification, not the download. */ }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songs = downloadPending
        val from = downloadPendingFrom
        downloadPending = emptyList()
        downloadPendingFrom = null
        when {
            songs.isEmpty() -> Unit
            granted -> {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
            // The one case where refusing is fatal: below API 29 there is no
            // other way to reach the Music folder.
            else -> Toast
                .makeText(context, context.getString(R.string.storage_required_save), Toast.LENGTH_SHORT)
                .show()
        }
    }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = LocalMediaRepository.hasStoragePermission(context)
        hasStoragePermission = granted
        hasAllFilesPermission = LocalMediaRepository.hasAllFilesPermission(context)
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
            viewModel.loadLocalMusic()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasStoragePermission = granted || LocalMediaRepository.hasStoragePermission(context)
        if (hasStoragePermission) {
            viewModel.reloadLocalDetail("local:all")
            viewModel.loadLocalMusic()
        } else {
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    val requestAudioPermission: () -> Unit = {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mediaPermissionLauncher.launch(perm)
    }
    val requestAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = LocalMediaRepository.createAllFilesAccessIntent(context)
                allFilesLauncher.launch(intent)
            }.onFailure {
                LocalMediaRepository.requestAllFilesAccess(context)
            }
        } else {
            requestAudioPermission()
        }
    }
    val requestDefaultPermission: () -> Unit = requestAudioPermission
    // Shared by the Library tab itself and by a shelf's "Show all" page, so a
    // card opens the same way from either.
    val onLibraryItemClick: (ShelfItem) -> Unit = { item ->
        item.browseId?.let { id ->
            if (id == "local:all" && !LocalMediaRepository.hasStoragePermission(context)) {
                requestDefaultPermission()
            }
            // Left set rather than cleared: a card opened from a shelf's
            // "Show all" page stacks a detail page over it exactly as one
            // opened from the Library tab stacks over that, so back from the
            // release lands on the grid rather than skipping past it. Every
            // place that reads `libraryShowAll` alongside `detail` favours
            // `detail` while both are set — see the AnimatedContent below.
            viewModel.openDetail(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
    }
    // Takes a list so a single tap on an album/playlist header can queue the
    // whole thing — the permission dance only needs to happen once for the
    // batch, not once per track.
    //
    // [from] is what the list *is*, when it is a release rather than a
    // selection: an album or a playlist. It is recorded whole, so the Downloads
    // page can offer the thing that was tapped back rather than the forty rows
    // it decomposed into — see [Downloads.rememberCollection]. Null for a single
    // track, which is not a release however many of them are asked for one at a
    // time.
    val startDownload: (List<Song>, DownloadTarget?) -> Unit = { requested, from ->
        val saved = Downloads.saved.value
        // Already on disk, and already queued or running: neither needs asking
        // again. What's left is what a tap on "Download" actually means.
        //
        // The release's cover is stamped onto any row that hasn't got one, as a
        // last check before the tap becomes a file.
        //
        // An album page bills its artwork once, in the header — its track rows
        // carry no thumbnail at all, see [InnertubeParser.parseResponsiveListItem]
        // — and a row that reaches [MediaTagger.artworkFor] with a null url is a
        // track saved with no cover in the file and none in [SavedSongMetadata]
        // either, so nothing downstream can draw one afterwards.
        // `MainViewModel.withArtwork` normally fills those in as a page loads and
        // covers the usual route here; this is the backstop for a list that
        // reached this function some other way, and it is worth having precisely
        // because the failure is silent and permanent — the file is written
        // without a cover, and re-downloading adopts the untagged copy rather
        // than replacing it.
        val songs = requested
            .filter { it.videoId !in saved }
            .map { song ->
                val cover = from?.thumbnailUrl
                if (song.thumbnailUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                    song.copy(thumbnailUrl = cover)
                } else {
                    song
                }
            }
        // Asked here as well as inside [Downloads.enqueue] — not instead of it.
        // Enqueue is the invariant and has to refuse whoever calls it, including
        // the storage-permission continuation below, which resumes long enough
        // after this check for the connection to have changed under it. This is
        // the one place that knows the tap was for forty tracks and can say so
        // once, rather than leaving forty identical failed rows to be read.
        val blocked = songs.isNotEmpty() && !AppSettings.downloadsAllowedNow
        if (songs.isNotEmpty() && !blocked) {
            val needsStorage = AppSettings.exportDownloads.value && DownloadStore.needsLegacyPermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED

            // Asked for here rather than at launch because here is where it means
            // something: a download is the first thing this app does that the user
            // is expected to walk away from.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                downloadPending = songs
                downloadPendingFrom = from
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
        }
        // Recorded for everything that was asked for, not just what still has to
        // be fetched: a release whose tracks are already on the device is still
        // that release, and the point of the record is to group them. Skipped
        // only when the whole batch was refused, since then there will be
        // nothing on disk for it to group.
        if (from != null && !blocked) {
            Downloads.rememberCollection(from, requested)
        }
        when {
            // The row's own icon reports a queued download, so a single tap
            // normally needs no toast — but a refused one leaves the row exactly
            // as it was, and a button that visibly does nothing is worse than a
            // long message. So this one is said whatever the count.
            blocked -> Toast.makeText(
                context,
                context.getString(R.string.wifi_only_download_refusal),
                Toast.LENGTH_LONG,
            ).show()
            requested.size > 1 -> {
                val message = if (songs.isEmpty()) {
                    context.getString(R.string.already_downloaded)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.downloading_song_count,
                        songs.size,
                        songs.size,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    /**
     * One track on its own, which is never a release.
     *
     * A track reached through AutoPlay or a radio queue often has no duration
     * string at all — YouTube's watch-queue rows don't always send
     * `lengthText` — while the player itself knows exactly how long the same
     * track runs once it has loaded. Backfilled from there when it's the song
     * on screen, so downloading it isn't handed a duration of zero and
     * silently skipped for lyrics — see [LyricsTag.forTrack].
     */
    val downloadSong: (Song) -> Unit = { song ->
        val withDuration = if (song.durationMillis() <= 0L &&
            player.song?.isSameTrackAs(song) == true && player.durationMs > 0L
        ) {
            song.copy(durationText = formatDurationText(player.durationMs))
        } else {
            song
        }
        startDownload(listOf(withDuration), null)
    }

    // Content padding leaves room for the frosted bar above and the tab bar
    // (plus mini player) below, so nothing is ever trapped under the glass.
    //
    // The top is measured off the bar rather than guessed at: the bar is pinned
    // under the status bar inset, and that inset varies by device and by window,
    // so a fixed number only ever lines up on the one device it was picked on.
    // See [topBarContentPadding].
    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null && !playerDocked) 210.dp else 140.dp,
    )

    // What colour the page currently under the bars is. The fades either end
    // of the screen are flat colour wherever their blur has least to say, so
    // handing them the theme's background puts a black band on a page that is
    // washed in an artwork's colour instead. Off a detail page this resolves
    // to the theme's background anyway, which is exactly right there.
    val detailPalette = rememberArtworkPalette(detail?.thumbnailUrl)
    val localCollectionPalette = localCollectionArtwork
        ?.takeIf { (tab, _) -> tab == selectedTab }
        ?.second

    // One set of numbers for the cards, the page, the stories and the shared
    // picture, so they cannot disagree. Read while any of them is on screen —
    // which includes the Library tab, since the cards live at the top of it.
    // See [rememberReplayState].
    val replayOpen = showReplay || replayStory != null || showReplayShare
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) {
        replay.summary?.takeUnless { it.isEmpty }?.cards(context).orEmpty()
    }

    // ---- The track in the player ----
    // Whatever started this track knew its title and its artwork, but rarely
    // which album or artist page it belongs to. Fill that in while the player
    // is actually up: on a tablet that is from the moment the track starts,
    // since the pane never goes down; on a phone it is when the sheet is
    // raised, so playing an album from the mini player still costs nothing.
    val playerShowing = playerDocked || showNowPlaying
    var links by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(player.song?.videoId, playerShowing) {
        links = null
        linksLoading = false
        if (!playerShowing) return@LaunchedEffect
        val current = player.song ?: return@LaunchedEffect
        // File-manager previews and local-library tracks already have their
        // metadata on device; never send their content:// or file:// identity
        // to the online track-link resolver.
        if (current.isExternalPreview || !current.localUri.isNullOrBlank() || !current.localPath.isNullOrBlank()) {
            return@LaunchedEffect
        }
        if (current.albumId != null && current.artistId != null) return@LaunchedEffect
        linksLoading = true
        links = YtMusicRepository.trackLinks(current.videoId).getOrNull()
        linksLoading = false
    }
    val playerSong = player.song?.let { current ->
        val extra = links?.takeIf { it.videoId == current.videoId }
        val enriched = if (extra != null) {
            current.copy(
                artistId = current.artistId ?: extra.artistId,
                albumId = current.albumId ?: extra.albumId,
                albumName = current.albumName ?: extra.albumName,
            )
        } else {
            current
        }
        // For local songs that never received YouTube browse IDs, synthesize
        // non-null IDs so the title/artist text in NowPlayingScreen remains
        // clickable. The onOpenAlbum/onOpenArtist callbacks use albumName and
        // artist directly and ignore the browse ID parameter, so these are
        // purely a clickability trigger.
        val isDeviceSong = enriched.isExternalPreview ||
            !enriched.localUri.isNullOrBlank() || !enriched.localPath.isNullOrBlank()
        enriched.copy(
            albumId = enriched.albumId ?: if (isDeviceSong) {
                enriched.albumName?.trim()?.takeIf(String::isNotBlank)?.let { "local:album:$it" }
            } else null,
            artistId = enriched.artistId ?: if (isDeviceSong) {
                enriched.artist.trim().takeIf(String::isNotBlank)?.let { "local:artist:$it" }
            } else null,
        )
    }
    // The three-dot menu snapshots the track into songActions when it's opened,
    // so a menu opened before the lookup above resolves would otherwise be
    // stuck without album/artist rows even after the ids come in. Keep it in
    // sync while it's showing this track.
    LaunchedEffect(playerSong) {
        if (playerSong != null && songActions?.let { (it.localUri ?: it.videoId) == (playerSong.localUri ?: playerSong.videoId) } == true) {
            songActions = playerSong
        }
    }

    // A wide-window player is raised as one full-screen player surface.
    // Lyrics are opened by the existing player control inside that surface;
    // they never replace the library with a second pane.
    val tabletPlayerFullscreen = showNowPlaying &&
        widePlayerLayout &&
        playerSong != null

    // The player's whole parameter list, in one place for both the docked
    // preview and the full-window player sheet.
    val nowPlaying: @Composable (Song, Boolean, Dp) -> Unit = { song, docked, playerWidth ->
        val displayedSong = activeRadioSeed
            ?.takeIf { (videoId, _) -> song.radioName == null && videoId == song.videoId }
            ?.let { (_, name) -> song.copy(radioName = name) }
            ?: song
        NowPlayingScreen(
            song = displayedSong,
            tabletFullscreenPlayer = tabletPlayerFullscreen,
            tabletLandscapeBannerHeight = if (
                tabletPlayerFullscreen && windowWidth >= windowHeight * 1.45f
            ) windowHeight * 0.9f else 0.dp,
            playerHaze = playerHazeState,
            windowWidth = playerWidth,
            isPlaying = player.isPlaying,
            isLoading = player.isLoading,
            positionMs = player.position.positionMs,
            durationMs = player.durationMs,
            isAudioVersion = convertedAudioId == song.videoId,
            audioVersionSwitching = switchingAudioVersion,
            qualityUpgraded = player.isQualityUpgraded,
            onToggleAudioVersion = audioVersion@{
                val c = controller ?: return@audioVersion
                val index = c.currentMediaItemIndex
                if (index !in 0 until c.mediaItemCount) return@audioVersion
                val original = convertedFromVideo
                if (original != null && convertedAudioId == song.videoId) {
                    val position = c.currentPosition
                    val wasPlaying = c.isPlaying
                    c.replaceMediaItem(index, original.toMediaItem())
                    c.seekTo(index, position)
                    if (wasPlaying) c.play()
                    convertedFromVideo = null
                    convertedAudioId = null
                    return@audioVersion
                }
                if (!song.isVideo || switchingAudioVersion) return@audioVersion
                scope.launch {
                    switchingAudioVersion = true
                    TrackLog.d("Player", "audio switch requested for '${song.title}'", song.videoId)
                    val audio = try {
                        YtMusicRepository.resolveAudio(song)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        TrackLog.w("Player", "audio version lookup failed", error, song.videoId)
                        null
                    }
                    switchingAudioVersion = false
                    // A match can be absent or the listener may have skipped
                    // while it was being found. Neither should change a queue.
                    if (audio == null || audio.videoId == song.videoId) {
                        TrackLog.w("Player", "audio switch found no distinct official song", song.videoId)
                        Toast.makeText(context, R.string.video_audio_version_unavailable, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (c.currentMediaItemIndex != index || c.currentMediaItem?.mediaId != song.videoId) {
                        TrackLog.d("Player", "audio switch discarded; listener changed track", song.videoId)
                        return@launch
                    }
                    val position = c.currentPosition
                    val wasPlaying = c.isPlaying
                    convertedFromVideo = song
                    convertedAudioId = audio.videoId
                    TrackLog.d("Player", "audio switch applying '${audio.title}' (${audio.videoId})", song.videoId)
                    c.replaceMediaItem(index, audio.copy(isVideoOrigin = true).toMediaItem())
                    c.seekTo(index, position)
                    if (wasPlaying) c.play()
                }
            },
            onPlayPause = {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    // Read at the moment of the seek, not from the
                    // polled snapshot the screen draws with: a track
                    // change updates the current item before it updates
                    // the duration, so a fraction dropped seconds after
                    // a transition would otherwise be scaled by the
                    // previous song's length.
                    val duration = player.duration.takeIf { it > 0 }
                        ?: player.currentMediaItem?.toSong()?.durationMillis()
                        ?: playerSong?.durationMillis()
                        ?: 0L
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    // Clamped here rather than at each caller because
                    // not every caller can clamp. The scrubber's target
                    // is a fraction of the duration and cannot overrun,
                    // but a tapped lyric line seeks to a timestamp from
                    // whichever transcription matched on title, artist
                    // and duration — and a match against a slightly
                    // longer master puts every line late, so a tap near
                    // the end asks for a position past the end of this
                    // stream. Media3 answers that by clamping to the
                    // final millisecond, which ends the track and starts
                    // the next one: tapping the last line of a song
                    // skipped it.
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplay,
            signedIn = signedIn,
            likeStatus = likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT,
            onToggleLike = { viewModel.toggleLike(song.videoId) },
            onToggleShuffle = { controller?.let { it2 -> QueueShuffle.toggle(it2); AppSettings.setShuffleEnabled(QueueShuffle.enabled.value) } },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Persist the new repeat mode so it survives app restarts.
                    AppSettings.setRepeatMode(next)
                    // Nothing else to do here: PlaybackService watches the
                    // repeat mode itself and takes AutoPlay's tracks out of
                    // the queue for the duration of repeat-all — and, unlike
                    // this screen, is still around to put them back when the
                    // loop ends.
                    it.repeatMode = next
                }
            },
            onToggleAutoplay = {
                // PlaybackService owns the setting and queue extension so
                // this path and the notification use exactly one loader.
                controller?.toggleAutoplay()
            },
            onJumpTo = { controller?.seekToDefaultPosition(it) },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            // The enriched copy, not player.song — otherwise the menu
            // hides the album and artist rows even once their browse
            // ids have been resolved.
            onOpenMenu = {
                menuFromPlayer = true
                songActions = song
            },
            onQuickAddToPlaylist = {
                if (signedIn) playlistTarget = song else localPlaylistSongTarget = song
            },
            onQuickPlaybackTuning = { showPlaybackTuning = true },
            onQuickMenuAction = { action ->
                when (action) {
                    PlayerQuickAction.EQUALIZER -> showEqualizerSheet = true
                    PlayerQuickAction.SHARE_FILE -> {
                        if (song.localUri != null || song.localPath != null) {
                            LocalSongActionsHelper.shareSong(context, song)
                        } else {
                            menuFromPlayer = true
                            songActions = song
                        }
                    }
                    PlayerQuickAction.TAG_EDITOR,
                    PlayerQuickAction.EDIT_LYRICS,
                    PlayerQuickAction.DETAILS -> {
                        pendingPlayerQuickAction = action
                        menuFromPlayer = true
                        songActions = song
                    }
                    else -> {
                        menuFromPlayer = true
                        songActions = song
                    }
                }
            },
            onLoadSavedQueue = { savedQueue ->
                controller?.let { mediaController ->
                    val wasPlayRequested = mediaController.playWhenReady
                    mediaController.setMediaItems(
                        savedQueue.songs.map { it.toMediaItem() },
                        savedQueue.currentIndex.coerceIn(savedQueue.songs.indices),
                        savedQueue.positionMs,
                    )
                    mediaController.prepare()
                    if (wasPlayRequested) mediaController.play() else mediaController.pause()
                }
            },
            onOpenPlaylists = {
                dismissNowPlaying()
                showSettings = false
                showReplay = false
                viewModel.clearDetail()
                libraryFilterRequest = LibraryFilter.PLAYLISTS
                selectedTab = TAB_LIBRARY
            },
            onOpenSearch = {
                dismissNowPlaying()
                showSettings = false
                showReplay = false
                viewModel.clearDetail()
                selectedTab = TAB_SEARCH
                searchFocusTrigger++
            },
            onOpenAlbum = { browseId ->
                dismissNowPlaying()
                val albumName = song.albumName?.trim().orEmpty().ifBlank { song.title }
                if (albumName.isNotBlank()) {
                    openByName(browseId, albumName, song.artist, BrowseType.ALBUM, song.thumbnailUrl)
                } else {
                    Toast.makeText(context, "No album information", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenArtist = { browseId ->
                dismissNowPlaying()
                val artists = com.music.bitchord.feature.artistimage.util.ArtistSplitter.split(song.artist)
                if (browseId.isNotBlank() && !browseId.startsWith("local:")) {
                    openByName(browseId, song.artist, null, BrowseType.ARTIST, song.thumbnailUrl)
                } else if (artists.size > 1) {
                    songActions = song
                } else {
                    val artistName = (artists.firstOrNull() ?: song.artist).trim()
                    if (artistName.isNotBlank()) {
                        openByName(browseId, artistName, null, BrowseType.ARTIST, null)
                    } else {
                        Toast.makeText(context, "No artist information", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsReadyForCurrentSong = lyricsChecked && lyricsVideoId == player.song?.videoId,
            docked = docked,
            artworkGesturesEnabled = true,
            openLyricsOnShow = requestedMediaPanel == MediaControlDestination.LYRICS,
            openQueueOnShow = requestedMediaPanel == MediaControlDestination.QUEUE,
            onExternalPanelRequestHandled = {
                requestedMediaPanel = null
                MediaControlDeepLink.handled()
            },
            onClearQueue = {
                // Keep what's playing; drop everything queued after it.
                controller?.let { c ->
                    if (c.mediaItemCount > c.currentMediaItemIndex + 1) {
                        c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
                    }
                }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // A pushed album/artist/playlist page replaces the tab content but
        // leaves the tab bar and mini player in place.
        // Replay's three layers unwind in the order they were opened. Ahead of
        // every other handler because they are drawn over everything else.
        BackHandler(enabled = showReplayShare) { showReplayShare = false }
        BackHandler(enabled = replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(
            enabled = showReplay && !showSettings && replayStory == null && !showReplayShare,
        ) {
            showReplay = false
        }
        BackHandler(
            enabled = detail != null,
        ) { viewModel.closeDetail() }
        BackHandler(enabled = showSources) { showSources = false }
        BackHandler(enabled = showWearOs) { showWearOs = false }
        BackHandler(enabled = showSettings && !showWearOs) {
            showSettings = false
            if (detail == null && !showReplay) selectedTab = TAB_SONGS
        }
        BackHandler(
            enabled = detail == null && !showSettings && !showReplay && selectedTab != TAB_SONGS,
        ) {
            selectedTab = TAB_SONGS
        }
        BackHandler(enabled = showListenBrainzLogin) { showListenBrainzLogin = false }
        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }
        BackHandler(enabled = tabletPlayerFullscreen) { dismissNowPlaying() }

        // Keep the library content at its normal full width. The player is
        // shown above it as a full-window sheet when opened on a tablet.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = when {
                        showSources -> "sources"
                        showWearOs -> "wear_os"
                        showSettings -> "settings"
                        showReplay -> "replay"
                        detail != null -> detail.browseId
                        else -> "$TAB_KEY$selectedTab"
                    },
                    transitionSpec = {
                        val tabSwap = initialState.startsWith(TAB_KEY) && targetState.startsWith(TAB_KEY)
                        if (tabSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (useCollapsibleNavBar) {
                                Modifier
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { key ->
                    val onCollectionNavigationChanged: (Boolean, (() -> Unit)?) -> Unit = { isOpen, backHandler ->
                        val routeKey = key.toString()
                        if (isOpen && backHandler != null) {
                            collectionBackHandlers[routeKey] = backHandler
                        } else {
                            collectionBackHandlers.remove(routeKey)
                        }
                    }
                    val live = detailStack.lastOrNull()?.takeIf {
                        it.browseId == key && key != "settings" && key != "replay" && key != "sources" && key != "wear_os"
                    }
                    // Held for the same reason, one step further on: a popped
                    // page is off the stack before it has finished animating
                    // out, so `live` goes null under it and it would spend its
                    // exit drawing whatever is underneath instead of itself.
                    // Per slot, since each is remembered against its own key.
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    if (key == "wear_os") {
                        WearOsScreen(contentPadding = listPadding)
                    } else if (key == "replay") {
                        ReplayScreen(
                            state = replay,
                            holder = "",
                            onPeriodChange = setReplayPeriod,
                            onOpenStory = { replayStory = it },
                            onPlaySong = { replaySong ->
                                val matched = localSongs.firstOrNull { local ->
                                    local.videoId == replaySong.videoId ||
                                    (local.title.equals(replaySong.title, ignoreCase = true) &&
                                     (replaySong.artist.isBlank() || local.artist.equals(replaySong.artist, ignoreCase = true)))
                                } ?: replaySong.takeIf { it.localUri != null || !it.localPath.isNullOrBlank() }
                                if (matched != null) {
                                    play(listOf(matched), 0)
                                } else {
                                    Toast.makeText(context, context.getString(R.string.couldnt_find, replaySong.title), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenArtist = { _, name ->
                                openLocalArtist(name, null)
                            },
                            onOpenAlbum = { _, title, _, art ->
                                openLocalAlbum(title, art)
                            },
                            onShare = {
                                replaySharePage = null
                                showReplayShare = true
                            },
                            contentPadding = listPadding,
                            listState = replayListState,
                        )
                    } else if (key == "settings") {
                        SettingsScreen(
                            windowWidth = windowWidth,
                            onOpenReplay = {
                                showSettings = false
                                showReplay = true
                            },
                            onLyricsSources = { showLyricsSources = true },
                            onAppLanguage = { showAppLanguage = true },
                            onPreloadArtworkNow = { viewModel.preloadLocalArtwork() },
                            onPlayCueSheet = { cueSongs -> play(cueSongs, 0) },
                            onManageSources = { showSources = true },
                            onWearOs = { showWearOs = true },
                            onCdRipComplete = { viewModel.loadLocalMusic(isPullToRefresh = true) },
                            contentPadding = listPadding,
                        )
                    } else if (key == "sources") {
                        SourcesScreen(
                            contentPadding = listPadding,
                            onEditSource = { editingSource = it },
                            onEditWebDav = { showWebDavEditor = true },
                            onEditSmb = { showSmbEditor = true },
                        )
                    } else if (page != null && page.browseId.isDeviceFolder()) {
                        // Local Music and Downloads — both the tabbed Songs / Artists /
                        // Albums view. Two folders of tracks already on the device, so
                        // there is nothing to tell them apart on screen beyond what is
                        // in them and what to say when that is nothing.
                        //
                        // A single downloaded playlist is not one of these: it has one
                        // running order and nothing to tab through, so it falls to the
                        // release page below.
                        val localState = page.songs
                        val localSongs = (localState as? com.music.bitchord.data.model.UiState.Success)
                            ?.data.orEmpty()
                        // Only the Downloads folder has releases behind it: Local
                        // Music is files this app never asked for, so there is
                        // nothing on record about how they were grouped. Keyed on
                        // the record as well as the list, so downloading an album
                        // while its folder is open adds the folder rather than
                        // waiting for the page to be reopened.
                        val downloadCollections = remember(localSongs, savedCollections) {
                            if (page.browseId == "local:downloads") {
                                Downloads.collectionsAmong(localSongs)
                            } else {
                                emptyList()
                            }
                        }
                        LocalMusicScreen(
                            songs = localSongs,
                            collections = downloadCollections,
                            isDownloads = page.browseId == "local:downloads",
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            playbackPositionMs = player.position.positionMs,
                            playbackDurationMs = player.durationMs,
                            onLyricsEditorPlaybackToggle = { target ->
                                val active = player.song
                                val sameTrack = active?.videoId == target.videoId ||
                                    (active?.localUri != null && active.localUri == target.localUri)
                                controller?.let { c ->
                                    if (sameTrack) {
                                        if (c.isPlaying) c.pause() else c.play()
                                    } else {
                                        play(listOf(target), 0)
                                    }
                                }
                            },
                            onLyricsEditorSeek = { target -> controller?.seekTo(target) },
                            onLyricsEditorPrevious = { controller?.seekToPrevious() },
                            onLyricsEditorNext = { controller?.seekToNextMediaItem() },
                            isRefreshing = isRefreshingLocalMusic,
                            onRefresh = {
                                if (page.browseId == "local:downloads") {
                                    viewModel.reloadLocalDetail("local:downloads", isPullToRefresh = true)
                                } else if (page.browseId == "local:all") {
                                    viewModel.reloadLocalDetail("local:all", isPullToRefresh = true)
                                }
                                viewModel.loadLocalMusic(isPullToRefresh = true)
                            },
                            onDeleteDownloads = { selected ->
                                scope.launch {
                                    selected.forEach { song -> Downloads.delete(context, song.videoId) }
                                }
                            },
                            onSongClick = play,
                            onSongLongPress = openSongMenu,
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                QueueShuffle.enableForNextQueue()
                                play(songs, songs.indices.random())
                            },
                            onShuffleArtist = { songs ->
                                QueueShuffle.enableForNextQueue(preserveOrder = true)
                                play(songs, 0)
                            },
                            emptyMessage = (localState as? com.music.bitchord.data.model.UiState.Error)
                                ?.message,
                            // An album or artist here is a grouping of rows rather than
                            // a page, so the menu is handed the rows themselves — there
                            // is no id anything could be fetched with.
                            onCollectionLongPress = { label, grouped ->
                                // An artist grouping is never one of these — only a
                                // release downloaded whole has a record to match,
                                // which is exactly the distinction `asked` draws in
                                // `albumEntries`.
                                val downloadId = downloadCollections.firstOrNull {
                                    it.title == label && it.songs == grouped
                                }?.id
                                browseActions = BrowseTarget(
                                    browseId = null,
                                    title = label,
                                    subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                        .takeUnless { it == label }
                                        .orEmpty(),
                                    thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                    songs = grouped,
                                    downloadId = downloadId,
                                )
                            },
                            onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                            onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                            contentPadding = listPadding,
                            showTabRow = true,
                            onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                            onCollectionNavigationChanged = onCollectionNavigationChanged,
                        )
                    } else if (page != null) {
                        val withAlbum: (Song) -> Song = { song ->
                            if (page.type == BrowseType.ALBUM) {
                                song.copy(albumName = song.albumName ?: page.title)
                            } else {
                                song
                            }
                        }
                        val pageSongs = (page.songs as? com.music.bitchord.data.model.UiState.Success)
                            ?.data.orEmpty()
                        val isEditableLocalArtist = page.browseId.startsWith("local:artist:")
                        val localArtistSongKeys = remember(pageSongs) {
                            pageSongs.mapTo(linkedSetOf()) { it.localUri ?: it.videoId }
                        }
                        val toggleLocalArtistSelection: (Song) -> Unit = { song ->
                            val id = song.localUri ?: song.videoId
                            selectedLocalArtistSongIds = if (id in selectedLocalArtistSongIds) {
                                selectedLocalArtistSongIds - id
                            } else {
                                selectedLocalArtistSongIds + id
                            }
                        }
                        DrillDownSongList(
                            label = page.title,
                            artworkUrl = page.thumbnailUrl,
                            songs = pageSongs,
                            isArtist = page.type == BrowseType.ARTIST,
                            isPlaylist = page.type == BrowseType.PLAYLIST,
                            selectedIds = selectedLocalArtistSongIds,
                            selectionMode = isEditableLocalArtist && localArtistSelectionMode,
                            onSelectionToggle = if (isEditableLocalArtist) toggleLocalArtistSelection else null,
                            selectionToolbar = if (isEditableLocalArtist && localArtistSelectionMode) {
                                { contentColor ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = pluralStringResource(
                                                R.plurals.song_count_plural,
                                                selectedLocalArtistSongIds.size,
                                                selectedLocalArtistSongIds.size,
                                            ),
                                            color = contentColor,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                        )
                                        TextButton(
                                            onClick = {
                                                selectedLocalArtistSongIds = if (
                                                    localArtistSongKeys.isNotEmpty() &&
                                                    selectedLocalArtistSongIds.containsAll(localArtistSongKeys)
                                                ) emptySet() else localArtistSongKeys
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (
                                                        localArtistSongKeys.isNotEmpty() &&
                                                        selectedLocalArtistSongIds.containsAll(localArtistSongKeys)
                                                    ) R.string.deselect_all else R.string.select_all,
                                                ),
                                                color = contentColor,
                                            )
                                        }
                                        IconButton(
                                            enabled = selectedLocalArtistSongIds.isNotEmpty(),
                                            onClick = {
                                                localArtistRenameTargets = pageSongs.filter {
                                                    (it.localUri ?: it.videoId) in selectedLocalArtistSongIds
                                                }
                                                localArtistRenameValue = page.title
                                                showLocalArtistRenameDialog = true
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = stringResource(R.string.bulk_tag_edit),
                                                tint = contentColor,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                localArtistSelectionMode = false
                                                selectedLocalArtistSongIds = emptySet()
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.cancel),
                                                tint = contentColor,
                                            )
                                        }
                                    }
                                }
                            } else null,
                            isSavedToLibrary = savedYoutubeCollections.any { it.browseId == page.browseId },
                            onToggleLibrary = if (
                                !page.browseId.startsWith("local:") &&
                                page.type in setOf(BrowseType.ALBUM, BrowseType.PLAYLIST)
                            ) {
                                {
                                    AppSettings.toggleSavedYoutubeCollection(
                                        SavedYoutubeCollection(
                                            browseId = page.browseId,
                                            title = page.title,
                                            subtitle = page.subtitle,
                                            thumbnailUrl = page.thumbnailUrl,
                                            type = page.type.name,
                                        ),
                                    )
                                }
                            } else null,
                            showCollectionArtworkInList = showCollectionSongArtwork &&
                                page.type in setOf(BrowseType.ALBUM, BrowseType.ARTIST),
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            onSongClick = { songs, index ->
                                if (isEditableLocalArtist && localArtistSelectionMode) {
                                    songs.getOrNull(index)?.let(toggleLocalArtistSelection)
                                } else {
                                    play(songs, index)
                                }
                            },
                            onSongLongPress = { song ->
                                if (isEditableLocalArtist) {
                                    localArtistSelectionMode = true
                                    toggleLocalArtistSelection(song)
                                } else {
                                    openSongMenu(withAlbum(song))
                                }
                            },
                            onSongMore = { openSongMenu(withAlbum(it)) },
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                QueueShuffle.enableForNextQueue()
                                play(songs, songs.indices.random())
                            },
                            onMore = {
                                browseActions = BrowseTarget(
                                    browseId = page.browseId,
                                    title = page.title,
                                    subtitle = page.subtitle,
                                    thumbnailUrl = page.thumbnailUrl,
                                    type = page.type,
                                    songs = pageSongs.map(withAlbum),
                                    fromCard = false,
                                    downloadId = downloadIdFor(page.browseId),
                                )
                            },
                            onBack = { viewModel.closeDetail() },
                            contentPadding = listPadding,
                        )
                        if (isEditableLocalArtist && showLocalArtistRenameDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    showLocalArtistRenameDialog = false
                                    localArtistRenameTargets = emptyList()
                                },
                                title = { Text(stringResource(R.string.bulk_tag_edit)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            pluralStringResource(
                                                R.plurals.song_count_plural,
                                                localArtistRenameTargets.size,
                                                localArtistRenameTargets.size,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        OutlinedTextField(
                                            value = localArtistRenameValue,
                                            onValueChange = { localArtistRenameValue = it },
                                            label = { Text(stringResource(R.string.bulk_tag_artist)) },
                                            singleLine = true,
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = localArtistRenameValue.isNotBlank() && localArtistRenameTargets.isNotEmpty(),
                                        onClick = {
                                            val targets = localArtistRenameTargets
                                            val newName = localArtistRenameValue
                                            showLocalArtistRenameDialog = false
                                            saveLocalArtistRename(targets, newName)
                                        },
                                    ) { Text(stringResource(R.string.save)) }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showLocalArtistRenameDialog = false
                                            localArtistRenameTargets = emptyList()
                                        },
                                    ) { Text(stringResource(R.string.cancel)) }
                                },
                            )
                        }
                    } else when (key.removePrefix(TAB_KEY).toIntOrNull() ?: selectedTab) {
                        TAB_SONGS -> if (!hasStoragePermission && catalogSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            LocalMusicScreen(
                                songs = catalogSongs,
                                collections = emptyList(),
                                isDownloads = false,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                isRefreshing = isRefreshingLocalMusic,
                                onRefresh = refreshCatalog,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                emptyMessage = catalogEmptyMessage,
                                onCollectionLongPress = { label, grouped ->
                                    browseActions = BrowseTarget(
                                        browseId = null,
                                        title = label,
                                        subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                            .takeUnless { it == label }
                                            .orEmpty(),
                                        thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                        songs = grouped,
                                        downloadId = null,
                                    )
                                },
                                contentPadding = listPadding,
                                initialTab = LOCAL_TAB_SONGS,
                                showTabRow = false,
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                onPlayNext = playNext,
                                onAddToQueue = addToQueue,
                                onDeleteSong = { viewModel.loadLocalMusic() },
                                onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                                onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                                onCollectionNavigationChanged = onCollectionNavigationChanged,
                            )
                        }
                        TAB_ALBUMS -> if (!hasStoragePermission && catalogSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            LocalMusicScreen(
                                songs = catalogSongs,
                                collections = emptyList(),
                                isDownloads = false,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                isRefreshing = isRefreshingLocalMusic,
                                onRefresh = refreshCatalog,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                emptyMessage = catalogEmptyMessage,
                                onCollectionLongPress = { label, grouped ->
                                    browseActions = BrowseTarget(
                                        browseId = null,
                                        title = label,
                                        subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                            .takeUnless { it == label }
                                            .orEmpty(),
                                        thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                        songs = grouped,
                                        downloadId = null,
                                    )
                                },
                                contentPadding = listPadding,
                                initialTab = LOCAL_TAB_ALBUMS,
                                showTabRow = false,
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                initialDrillDownLabel = targetAlbumDrillDown,
                                onDrillDownDismiss = { targetAlbumDrillDown = null },
                                onPlayNext = playNext,
                                onAddToQueue = addToQueue,
                                onDeleteSong = { viewModel.loadLocalMusic() },
                                onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                                onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                                onCollectionNavigationChanged = onCollectionNavigationChanged,
                            )
                        }
                        TAB_ARTISTS -> if (!hasStoragePermission && catalogSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            LocalMusicScreen(
                                songs = catalogSongs,
                                collections = emptyList(),
                                isDownloads = false,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                isRefreshing = isRefreshingLocalMusic,
                                onRefresh = refreshCatalog,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                emptyMessage = catalogEmptyMessage,
                                onCollectionLongPress = { label, grouped ->
                                    browseActions = BrowseTarget(
                                        browseId = null,
                                        title = label,
                                        subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                            .takeUnless { it == label }
                                            .orEmpty(),
                                        thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                        songs = grouped,
                                        downloadId = null,
                                    )
                                },
                                contentPadding = listPadding,
                                initialTab = LOCAL_TAB_ARTISTS,
                                showTabRow = false,
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                initialDrillDownLabel = targetArtistDrillDown,
                                onDrillDownDismiss = { targetArtistDrillDown = null },
                                onPlayNext = playNext,
                                onAddToQueue = addToQueue,
                                onDeleteSong = { viewModel.loadLocalMusic() },
                                onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                                onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                                onCollectionNavigationChanged = onCollectionNavigationChanged,
                            )
                        }
                        TAB_ALBUM_ARTISTS -> if (!hasStoragePermission && catalogSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            LocalMusicScreen(
                                songs = catalogSongs,
                                collections = emptyList(),
                                isDownloads = false,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                isRefreshing = isRefreshingLocalMusic,
                                onRefresh = refreshCatalog,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                emptyMessage = catalogEmptyMessage,
                                onCollectionLongPress = { label, grouped ->
                                    browseActions = BrowseTarget(
                                        browseId = null,
                                        title = label,
                                        subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                            .takeUnless { it == label }
                                            .orEmpty(),
                                        thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                        songs = grouped,
                                        downloadId = null,
                                    )
                                },
                                contentPadding = listPadding,
                                initialTab = LOCAL_TAB_ALBUM_ARTISTS,
                                showTabRow = false,
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                onPlayNext = playNext,
                                onAddToQueue = addToQueue,
                                onDeleteSong = { viewModel.loadLocalMusic() },
                                onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                                onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                                onCollectionNavigationChanged = onCollectionNavigationChanged,
                            )
                        }
                        TAB_LIBRARY -> if (!hasStoragePermission && catalogSongs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            LibraryScreen(
                                songs = catalogSongs,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                contentPadding = listPadding,
                                onPlayNext = playNext,
                                onAddToQueue = addToQueue,
                                onDeleteSong = { viewModel.loadLocalMusic() },
                                onSongTagsOrLyricsSaved = { viewModel.reloadLyrics(it) },
                                onSongMetadataUpdated = viewModel::updateLocalSongMetadata,
                                onLocalMusicChanged = { viewModel.loadLocalMusic() },
                                initialFilterRequest = libraryFilterRequest,
                                onInitialFilterApplied = { libraryFilterRequest = null },
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                onCollectionNavigationChanged = onCollectionNavigationChanged,
                                onOpenYoutubeCollection = { collection ->
                                    val type = runCatching { BrowseType.valueOf(collection.type) }
                                        .getOrDefault(BrowseType.OTHER)
                                    viewModel.openDetail(
                                        browseId = collection.browseId,
                                        title = collection.title,
                                        subtitle = collection.subtitle,
                                        thumbnailUrl = collection.thumbnailUrl,
                                        type = type,
                                    )
                                },
                            )
                        }
                        TAB_SEARCH -> searchSaveableStateHolder.SaveableStateProvider("main-search") {
                            if (!hasStoragePermission) {
                                Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                    LocalPermissionCard(
                                        onRequestPermission = requestDefaultPermission,
                                    )
                                }
                            } else {
                                LocalSearchScreen(
                                songs = localSongs,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                focusTrigger = searchFocusTrigger,
                                recentSearches = searchHistory,
                                onRecordSearch = viewModel::recordSearch,
                                onRemoveSearch = viewModel::removeSearch,
                                onClearSearchHistory = viewModel::clearSearchHistory,
                                youtubeMusicResults = youtubeMusicSearchResults,
                                youtubeMusicHasMore = youtubeMusicSearchHasMore,
                                youtubeMusicLoadingMore = youtubeMusicSearchLoadingMore,
                                onLoadMoreYoutubeMusicResults = viewModel::loadMoreSearchResults,
                                onYoutubeMusicSearch = { term -> viewModel.searchFor(term, youtubeMusicOnly = true) },
                                onYoutubeMusicTrackClick = playRadio,
                                musicServerResults = musicServerSearchResults,
                                canSearchMusicServers = canSearchMusicServers,
                                onMusicServersSearch = viewModel::searchMusicServers,
                                onMusicServerTrackClick = { song -> play(listOf(song), 0) },
                                configuredSearchSources = individuallySearchableSources,
                                configuredSourceResults = configuredSourceSearchResults,
                                onConfiguredSourceSearch = viewModel::searchConfiguredSource,
                                onConfiguredSourceTrackClick = { song -> play(listOf(song), 0) },
                                onYoutubeMusicBrowseClick = { item ->
                                    viewModel.openDetail(
                                        browseId = item.browseId,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        type = item.type,
                                    )
                                },
                                    contentPadding = listPadding,
                                )
                            }
                        }
                        TAB_FOLDERS -> if (!hasStoragePermission) {
                            Box(modifier = Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                                LocalPermissionCard(
                                    onRequestPermission = requestDefaultPermission,
                                )
                            }
                        } else {
                            FolderBrowserScreen(
                                songs = localSongs,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onSongClick = play,
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onShuffle = { songs ->
                                    QueueShuffle.enableForNextQueue()
                                    play(songs, songs.indices.random())
                                },
                                contentPadding = listPadding,
                                customRootPath = localMusicFolderRoot.ifBlank { null },
                                rememberLastOpenedFolder = rememberLastOpenedFolder,
                                lastOpenedFolderPath = lastOpenedFolderPath,
                                onLastOpenedFolderPathChange = AppSettings::setLastOpenedFolderPath,
                                onArtworkPaletteChanged = onLocalArtworkPaletteChanged,
                                onNavigationChanged = onCollectionNavigationChanged,
                            )
                        }
                        TAB_RADIO -> RadioScreen(
                            controller = controller,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            contentPadding = listPadding,
                        )
                        else -> Box(modifier = Modifier.fillMaxSize())
                    }
                }

                // Every top bar is a fade rather than a pane — see [TopFadeBlur].
                // Drawn before the bar so the bar's own content sits on top of it.
                val isDetailVisible = detail != null && !isLocalDetail && !showSettings && !showReplay
                val isLocalCollectionArtworkVisible = localCollectionPalette != null &&
                    detail == null && !showSettings && !showReplay
                val currentCollectionRouteKey = detail?.browseId ?: "$TAB_KEY$selectedTab"
                val localCollectionBackHandler = collectionBackHandlers[currentCollectionRouteKey]
                // Local collection details are drilled down inside their tab
                // screen rather than pushed onto detailStack, so include the
                // back handler reported by that screen. Root category pages do
                // not report one and keep their labels visible.
                val hideCollectionHeaderNow = hideCollectionTopBar && !showSettings && !showReplay &&
                    ((detail != null && !isLocalDetail) || localCollectionBackHandler != null)
                val isAlbumOrPlaylistArtworkDetail = isLocalCollectionArtworkVisible ||
                    (detail != null && !isLocalDetail && detail.type in setOf(BrowseType.ALBUM, BrowseType.PLAYLIST))
                val hideFloatingCollectionControlsNow =
                    hideFloatingCollectionControls && hideCollectionHeaderNow && isAlbumOrPlaylistArtworkDetail
                if (!hideCollectionHeaderNow) {
                    TopFadeBlur(
                        hazeState = hazeState,
                        // Replay paints its own full-bleed black backdrop up under the
                        // status bar, exactly as a release page's artwork does.
                        pageColor = when {
                            showReplay -> Color.Black
                            isDetailVisible -> detailPalette.wash
                            isLocalCollectionArtworkVisible -> localCollectionPalette!!.wash
                            else -> MaterialTheme.colorScheme.background
                        },
                        scrimColor = when {
                            showReplay -> Color.Black
                            isDetailVisible -> detailPalette.background
                            isLocalCollectionArtworkVisible -> localCollectionPalette!!.background
                            else -> MaterialTheme.colorScheme.background
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                val showCollectionSortAction = detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST
                if (!hideFloatingCollectionControlsNow || showCollectionSortAction) {
                    FrostedTopBar(
                        title = when {
                            showSources -> stringResource(R.string.sources)
                            showWearOs -> stringResource(R.string.wear_os_page_title)
                            showSettings -> stringResource(R.string.settings)
                            showReplay -> stringResource(R.string.replay)
                            detail != null -> detail.title
                            else -> allTabs.getOrElse(selectedTab) { allTabs.first() }.label
                        },
                        scrolled = when {
                            showSources || showWearOs -> true
                            showSettings -> true
                            showReplay -> replayScrolled
                            detail != null -> detailScrolled
                            else -> scrolled || selectedTab == TAB_SEARCH
                        },
                        refreshing = isRefreshingLocalMusic,
                        pullFraction = { 0f },
                        artworkTint = when {
                            isLocalCollectionArtworkVisible -> localCollectionPalette?.wash
                            isDetailVisible -> detailPalette.wash
                            else -> null
                        },
                        floatingOnly = hideCollectionHeaderNow,
                        onBack = if (hideFloatingCollectionControlsNow) null else when {
                            showSources -> ({ showSources = false })
                            showWearOs -> ({ showWearOs = false })
                            showSettings -> ({ showSettings = false })
                            showReplay -> ({ showReplay = false })
                            localCollectionBackHandler != null -> localCollectionBackHandler
                            detail != null -> ({ viewModel.closeDetail(); Unit })
                            else -> null
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                        actions = {
                            if (!showSettings) {
                                if (showCollectionSortAction) {
                                    IconButton(onClick = { songSortMenuOpen = true }) {
                                        Icon(
                                            Icons.Rounded.Sort,
                                            contentDescription = stringResource(R.string.sort_songs),
                                            tint = if (hideCollectionHeaderNow) Color.White else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (!hideFloatingCollectionControlsNow) {
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(
                                            if (settingsTopBarUsesMoreIcon) Icons.Rounded.MoreVert else Icons.Rounded.Settings,
                                            contentDescription = stringResource(R.string.settings),
                                            tint = if (hideCollectionHeaderNow) Color.White else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }

                // Drawn before the bars so their own glass reads on top of it.
                BottomFadeScrim(
                    withMiniPlayer = player.song != null && !playerDocked,
                    // Not the wash: by the foot of the screen the page has finished
                    // easing out of it and into this, so this is what is actually
                    // under the tab bar.
                    pageColor = if (isDetailVisible) detailPalette.background else MaterialTheme.colorScheme.background,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // One tab handler, whichever bar is drawing it.
                val onTabSelected: (Int) -> Unit = onTabSelected@{ visibleIndex ->
                    val index = visibleTabIndices.getOrNull(visibleIndex) ?: return@onTabSelected
                    if (index == TAB_SEARCH && selectedTab == TAB_SEARCH) {
                        searchFocusTrigger++
                    } else {
                        if (index != TAB_SEARCH) {
                            searchFocusTrigger = 0
                        }
                        viewModel.clearDetail()
                        showSources = false
                        showSettings = false
                        showReplay = false
                        if (index == TAB_ALBUMS && selectedTab == TAB_ALBUMS) {
                            targetAlbumDrillDown = null
                        }
                        if (index == TAB_ARTISTS && selectedTab == TAB_ARTISTS) {
                            targetArtistDrillDown = null
                        }
                        selectedTab = index
                    }
                }

                CompositionLocalProvider(LocalPlayerDock provides playerDock) {
                if (mainNavigationTabsPopup) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        player.song?.takeUnless { playerDocked }?.let { song ->
                            MiniPlayer(
                                song = song,
                                isPlaying = player.isPlaying,
                                isLoading = player.isLoading,
                                hazeState = hazeState,
                                onPlayPause = {
                                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                },
                                onPrevious = { controller?.seekToPreviousMediaItem() },
                                onNext = { controller?.seekToNextMediaItem() },
                                onExpand = ::openNowPlaying,
                                onDismiss = onDismissMusicBar,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        PopupNavigationBar(
                            tabs = tabs,
                            selectedIndex = visibleTabIndices.indexOf(selectedTab).coerceAtLeast(0),
                            hazeState = hazeState,
                            onTabSelected = onTabSelected,
                            modifier = Modifier.fillMaxWidth(),
                            song = player.song?.takeUnless { playerDocked },
                        )
                    }
                } else if (useCollapsibleNavBar) {
                    // Collapsible floating bar replaces the two stacked bars with a
                    // unified component: now playing controls dock into the tab bar,
                    // and the pair folds together on scroll. Works with or without liquid glass.
                    GlassNavBar(
                        tabs = tabs,
                        selectedIndex = visibleTabIndices.indexOf(selectedTab).coerceAtLeast(0),
                        onTabSelected = onTabSelected,
                        scrollConnection = navBarScroll,
                        song = player.song?.takeUnless { playerDocked },
                        isPlaying = player.isPlaying,
                        isLoading = player.isLoading,
                        onPlayPause = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onNext = { controller?.seekToNextMediaItem() },
                        onExpand = ::openNowPlaying,
                        onDismiss = onDismissMusicBar,
                        useGlass = glassActive,
                        hazeState = hazeState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                            .fillMaxWidth(),
                    )
                } else Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Capped and centred rather than run to the page's edges
                        // — see [FLOATING_BAR_MAX_WIDTH]. It sits on the Column
                        // rather than on each bar so the two are held to the same
                        // width and keep the shared left and right edge they have
                        // on a phone. Before fillMaxWidth, so the fill has
                        // already been bounded by the time it is applied.
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only where the player isn't already open beside the page:
                    // a bar whose whole job is to stand in for the player, next
                    // to the player, is a second copy of what is already there.
                    player.song?.takeUnless { playerDocked }?.let { song ->
                        MiniPlayer(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = player.isLoading,
                            hazeState = hazeState,
                            onPlayPause = {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onNext = { controller?.seekToNextMediaItem() },
                            onExpand = ::openNowPlaying,
                            onDismiss = onDismissMusicBar,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FloatingBottomBar(
                        tabs = tabs,
                        selectedIndex = visibleTabIndices.indexOf(selectedTab).coerceAtLeast(0),
                        hazeState = hazeState,
                        onTabSelected = onTabSelected,
                        song = player.song?.takeUnless { playerDocked },
                    )
                }
                }
            }

            // Keep the optional side player for users who prefer it. Tablet
            // full-screen mode uses the same mini-player and sheet as phones.
            if (playerDocked) {
                DockedPlayer(
                    song = playerSong,
                    width = dockedPlayerWidth(windowWidth),
                    onExpand = ::openNowPlaying,
                    content = { current -> nowPlaying(current, true, dockedPlayerWidth(windowWidth)) },
                )
            }
        }

        // ---- Now Playing ----
        // Tablets use the full-window sheet. Keep the custom artwork transition
        // for phone-sized windows, where its dock geometry matches the layout.
        val useArtworkTransitionSheet = playerArtworkTransitionEnabled &&
            !widePlayerLayout
        LaunchedEffect(showNowPlaying, useArtworkTransitionSheet) {
            if (
                showNowPlaying && !useArtworkTransitionSheet &&
                normalNowPlayingSheetState.currentValue != SheetValue.Expanded
            ) {
                normalNowPlayingSheetState.show()
            }
        }
        val playerRaised = !playerDocked && showNowPlaying && playerSong != null
        if (playerRaised && useArtworkTransitionSheet) {
            val sheetDensity = LocalDensity.current
            val confirmSheetValue: (SheetValue) -> Boolean = { target ->
                if (target == SheetValue.Hidden) {
                    playerSheetMotion.close()
                    false
                } else {
                    true
                }
            }
            val animatedSheetState = rememberSaveable(
                saver = SheetState.Saver(
                    skipPartiallyExpanded = true,
                    confirmValueChange = confirmSheetValue,
                    density = sheetDensity,
                    skipHiddenState = false,
                ),
            ) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = sheetDensity,
                    initialValue = if (playerSheetMotion.holding) SheetValue.Expanded else SheetValue.Hidden,
                    confirmValueChange = confirmSheetValue,
                )
            }
            DisposableEffect(animatedSheetState) {
                playerSheetMotion.sheet = animatedSheetState
                playerDock.sheetOffset = playerSheetMotion::position
                onDispose {
                    playerDock.sheetOffset = null
                    playerSheetMotion.onSheetGone()
                }
            }
            ModalBottomSheet(
                onDismissRequest = { playerSheetMotion.close() },
                sheetState = animatedSheetState,
                scrimColor = Color.Transparent,
                // The player fills the screen and paints its own background to
                // the very top, so the sheet's default 28.dp top corners would
                // only cut two notches out of the artwork behind the status bar.
                // A taller outline leaves room for the sleeve as it docks down
                // to the mini-player below the sheet's normal bounds.
                shape = PlayerSheetShape,
                sheetMaxWidth = Dp.Unspecified,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            ) {
                BackHandler { playerSheetMotion.close() }
                val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                DisposableEffect(sheetWindow) {
                    playerSheetMotion.attachWindow(sheetWindow)
                    onDispose { }
                }
                Box(
                    Modifier
                        .offset { IntOffset(0, playerSheetMotion.contentOffsetPx()) }
                        .drawBehind {
                            val open = playerDock.openFraction()
                            if (open > 0f && !(open >= 1f && playerDock.attached)) {
                                val top = playerSheetMotion.position().takeUnless { it.isNaN() } ?: 0f
                                drawRect(
                                    color = Color.Black.copy(alpha = PLAYER_SHEET_SCRIM_ALPHA * open * open),
                                    topLeft = Offset(0f, -top.toInt().toFloat()),
                                    size = Size(size.width, size.height),
                                )
                            }
                        }
                        .onSizeChanged { playerSheetMotion.onMeasured(it.height.toFloat()) },
                ) {
                    CompositionLocalProvider(LocalPlayerDock provides playerDock) {
                        nowPlaying(playerSong, false, windowWidth)
                    }
                }
            }
        } else if (playerRaised) {
            ModalBottomSheet(
                onDismissRequest = { dismissNowPlaying() },
                sheetState = normalNowPlayingSheetState,
                shape = RectangleShape,
                sheetMaxWidth = Dp.Unspecified,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (tabletPlayerFullscreen) {
                        TabletPlayerBackdrop(
                            song = playerSong,
                            modifier = Modifier.matchParentSize(),
                        )
                        CompositionLocalProvider(LocalSharedPlayerBackdrop provides true) {
                            Row(Modifier.fillMaxSize()) {
                                BoxWithConstraints(
                                    modifier = Modifier.weight(0.56f).fillMaxHeight(),
                                ) {
                                    nowPlaying(playerSong, false, maxWidth)
                                }
                                TabletLyricsPane(
                                    lines = lyrics,
                                    trackKey = playerSong.videoId,
                                    positionMs = player.position.positionMs,
                                    isPlaying = player.isPlaying,
                                    onSeek = { target -> controller?.seekTo(target) },
                                    artworkUrl = playerSong.thumbnailUrl,
                                    modifier = Modifier.weight(0.44f).fillMaxHeight(),
                                )
                            }
                        }
                    } else {
                        nowPlaying(playerSong, false, windowWidth)
                    }
                    if (tabletPlayerFullscreen) {
                        val dismissThresholdPx = with(LocalDensity.current) { 76.dp.toPx() }
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 4.dp)
                                .size(width = 140.dp, height = 56.dp)
                                .zIndex(2f)
                                .pointerInput(tabletPlayerFullscreen, dismissThresholdPx) {
                                    var downwardDistance = 0f
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (downwardDistance >= dismissThresholdPx) {
                                                dismissNowPlaying()
                                            }
                                            downwardDistance = 0f
                                        },
                                        onDragCancel = { downwardDistance = 0f },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dragAmount > 0f || downwardDistance > 0f) {
                                                downwardDistance =
                                                    (downwardDistance + dragAmount).coerceAtLeast(0f)
                                                change.consume()
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }

        // ---- Replay stories ----
        // Mounted out here rather than inside the pane above, because a story
        // covers the window: on a tablet the page is only the left half of the
        // row, and a story laid out inside it would run alongside the player
        // instead of over it.
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card ->
                        replaySharePage = card
                        showReplayShare = true
                    },
                    paused = showReplayShare,
                )
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    // Straight to full height. The sheet is a picture and two
                    // buttons, and half-open it showed the picture with both
                    // buttons below the fold — a sheet whose only two controls
                    // need a drag to reach.
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = "",
                        memberSince = replay.memberSince,
                        page = replaySharePage,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }

        // ---- Album / playlist detail ----
        // ---- Long-press track actions ----
        songActions?.let { originalSong ->
            // Local tracks can share a title, artist, and even an imported ID.
            // Resolve by the stable file URI first so opening actions for one
            // playlist row can never silently switch to another folder's file.
            val originalSongKey = originalSong.localUri ?: originalSong.videoId
            val resolvedSong = localSongs.firstOrNull { local ->
                (local.localUri ?: local.videoId) == originalSongKey
            } ?: originalSong
            val song = resolvedSong
            val songIdentityKey = song.localUri ?: song.videoId
            var showTagEditor by remember(songIdentityKey, pendingPlayerQuickAction) {
                mutableStateOf(pendingPlayerQuickAction == PlayerQuickAction.TAG_EDITOR)
            }
            var showLyricsEditor by remember(songIdentityKey, pendingPlayerQuickAction) {
                mutableStateOf(pendingPlayerQuickAction == PlayerQuickAction.EDIT_LYRICS)
            }
            var showDetailsSheet by remember(songIdentityKey, pendingPlayerQuickAction) {
                mutableStateOf(pendingPlayerQuickAction == PlayerQuickAction.DETAILS)
            }
            var showAudioCutterSheet by remember(songIdentityKey, pendingPlayerQuickAction) {
                mutableStateOf(false)
            }
            var showAddToPlaylist by remember { mutableStateOf(false) }
            var showArtistPicker by remember { mutableStateOf(false) }

            if (showTagEditor) {
                Dialog(
                    onDismissRequest = {
                        showTagEditor = false
                        songActions = null
                    },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    TagEditorScreen(
                        song = song,
                        onNavigateBack = {
                            showTagEditor = false
                            songActions = null
                        },
                        onTagsSaved = { updatedTags ->
                            showTagEditor = false
                            songActions = null
                            val updatedSong = song.copy(
                                title = updatedTags.title.trim().ifBlank { song.title },
                                artist = updatedTags.artist.trim().ifBlank { "Unknown Artist" },
                                albumName = updatedTags.album.trim().takeIf(String::isNotBlank),
                                albumArtist = updatedTags.albumArtist.trim().takeIf(String::isNotBlank),
                            )
                            LocalPlaylistStore.updateEditedSongMetadata(
                                song = updatedSong,
                                title = updatedTags.title,
                                artist = updatedTags.artist,
                                albumName = updatedTags.album,
                                albumArtist = updatedTags.albumArtist,
                            )
                            viewModel.updateLocalSongMetadata(updatedSong)
                            viewModel.loadLocalMusic()
                            viewModel.reloadLyrics(updatedSong)
                        },
                    )
                }
            } else if (showLyricsEditor) {
                Dialog(
                    onDismissRequest = {
                        showLyricsEditor = false
                        songActions = null
                    },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    val isActiveLyricsTrack = player.song?.isSameTrackAs(song) == true
                    LyricsEditorScreen(
                        song = song,
                        playbackPositionMs = if (isActiveLyricsTrack) player.position.positionMs else 0L,
                        playbackDurationMs = if (isActiveLyricsTrack) player.durationMs else 0L,
                        playbackIsPlaying = player.isPlaying && isActiveLyricsTrack,
                        onSyncPlaybackToggle = {
                            controller?.let { c ->
                                if (isActiveLyricsTrack) {
                                    if (c.isPlaying) c.pause() else c.play()
                                } else {
                                    play(listOf(song), 0)
                                }
                            }
                        },
                        onSyncSeek = if (isActiveLyricsTrack) ({ target -> controller?.seekTo(target) }) else null,
                        onSyncPrevious = if (isActiveLyricsTrack) ({ controller?.seekToPrevious() }) else null,
                        onSyncNext = if (isActiveLyricsTrack) ({ controller?.seekToNextMediaItem() }) else null,
                        onBackClick = {
                            showLyricsEditor = false
                            songActions = null
                        },
                        onLyricsSaved = {
                            viewModel.reloadLyrics(song)
                        },
                    )
                }
            } else if (showDetailsSheet) {
                LocalSongDetailsSheet(
                    song = song,
                    onDismissRequest = {
                        showDetailsSheet = false
                        songActions = null
                    },
                    onLyricsEditorClick = {
                        showDetailsSheet = false
                        showLyricsEditor = true
                    },
                    onTagEditorClick = {
                        showDetailsSheet = false
                        showTagEditor = true
                    },
                    onAudioCutterClick = {
                        showDetailsSheet = false
                        showAudioCutterSheet = true
                    },
                )
            } else if (showAudioCutterSheet) {
                AudioCutterSheet(
                    song = song,
                    onDismissRequest = {
                        showAudioCutterSheet = false
                        songActions = null
                    },
                )
            } else if (showAddToPlaylist) {
                LocalAddToPlaylistSheet(
                    songs = listOf(song),
                    onDismissRequest = {
                        showAddToPlaylist = false
                        songActions = null
                    },
                )
            } else if (showArtistPicker) {
                val artists = remember(song.artist) {
                    val split = com.music.bitchord.feature.artistimage.util.ArtistSplitter.split(song.artist)
                    if (split.isEmpty() && song.artist.isNotBlank()) listOf(song.artist) else split
                }
                LocalArtistPickerSheet(
                    song = song,
                    artists = artists,
                    onDismissRequest = {
                        showArtistPicker = false
                        songActions = null
                    },
                    onSelectArtist = { selectedArtist ->
                        showArtistPicker = false
                        songActions = null
                        dismissNowPlaying()
                        val cleanArtist = selectedArtist.trim()
                        if (cleanArtist.isNotBlank()) {
                            openLocalArtist(cleanArtist, null)
                        }
                    },
                )
            } else {
                LocalSongActionsSheet(
                    song = song,
                    onDismissRequest = { songActions = null },
                    onGoToAlbum = {
                        songActions = null
                        dismissNowPlaying()
                        val albumName = song.albumName?.trim().orEmpty()
                        if (albumName.isNotBlank()) {
                            openByName(song.albumId, albumName, song.artist, BrowseType.ALBUM, song.thumbnailUrl)
                        } else {
                            Toast.makeText(context, "No album information", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onGoToArtist = {
                        val artists = com.music.bitchord.feature.artistimage.util.ArtistSplitter.split(song.artist)
                        if (!song.artistId.isNullOrBlank() && !song.artistId.startsWith("local:")) {
                            songActions = null
                            dismissNowPlaying()
                            openByName(song.artistId, song.artist, null, BrowseType.ARTIST, song.thumbnailUrl)
                        } else if (artists.size > 1) {
                            showArtistPicker = true
                        } else {
                            songActions = null
                            dismissNowPlaying()
                            val artistName = (artists.firstOrNull() ?: song.artist).trim()
                            if (artistName.isNotBlank()) {
                                openByName(song.artistId, artistName, null, BrowseType.ARTIST, null)
                            } else {
                                Toast.makeText(context, "No artist information", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAddToPlaylist = {
                        val isDeviceSong = song.isExternalPreview ||
                            !song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()
                        if (isDeviceSong || !signedIn) {
                            // A signed-out listener still gets a fully local
                            // playlist picker; no Google account is required.
                            showAddToPlaylist = true
                        } else {
                            // Keep the existing account-playlist route for
                            // signed-in YouTube Music users.
                            songActions = null
                            playlistTarget = song
                            viewModel.loadPlaylists()
                        }
                    },
                    onEqualizer = {
                        songActions = null
                        showEqualizerSheet = true
                    },
                    onPlaybackTuning = {
                        songActions = null
                        showPlaybackTuning = true
                    },
                    showPlayerActions = menuFromPlayer,
                    onCastUnavailable = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chromecast_unavailable),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onTagEditor = {
                        showTagEditor = true
                    },
                    onEditLyrics = {
                        showLyricsEditor = true
                    },
                    onDetails = {
                        showDetailsSheet = true
                    },
                    onShareFile = {
                        songActions = null
                        LocalSongActionsHelper.shareSong(context, song)
                    },
                )
            }
        }

        // ---- Download manager ----
        // The batch view of what the top-bar indicator is counting. Dismissing
        // it is what marks the batch seen, and marking it on the way *out*
        // rather than on the way in is deliberate: it is the outcome the user is
        // signing off on, and while the sheet is up there may not be one yet.
        if (showDownloadManager) {
            val closeDownloadManager = {
                showDownloadManager = false
                DownloadSession.markSeen()
            }
            BackHandler(onBack = closeDownloadManager)
            ModalBottomSheet(
                onDismissRequest = closeDownloadManager,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                DownloadManagerSheet(onDismiss = closeDownloadManager)
            }
        }

        if (showEqualizerSheet) {
            EqualizerSheet(
                onDismiss = { showEqualizerSheet = false },
            )
        }

        if (showPlaybackTuning) {
            PlaybackTuningDialog(
                song = player.song,
                onDismiss = { showPlaybackTuning = false },
            )
        }

        // ---- Add to playlist / new playlist ----
        // One sheet for both, because they are one decision: the list of
        // playlists with a way to make another. `creatingPlaylist` opens it
        // straight onto the form, which is what the Library tile means.
        LaunchedEffect(playlistTarget != null || playlistCollectionTarget != null) {
            if (playlistTarget != null || playlistCollectionTarget != null) viewModel.loadPlaylists()
        }
        localPlaylistSongTarget?.let { targetSong ->
            LocalAddToPlaylistSheet(
                songs = listOf(targetSong),
                onDismissRequest = { localPlaylistSongTarget = null },
            )
        }
        localPlaylistCollectionTarget?.let { songs ->
            LocalAddToPlaylistSheet(
                songs = songs,
                onDismissRequest = { localPlaylistCollectionTarget = null },
            )
        }
        if (playlistTarget != null || playlistCollectionTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val collection = playlistCollectionTarget
            val dismiss = {
                playlistTarget = null
                playlistCollectionTarget = null
                creatingPlaylist = false
            }
            ModalBottomSheet(
                onDismissRequest = dismiss,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistPickerSheet(
                    playlists = playlists,
                    loading = playlistsLoading,
                    song = target,
                    songs = collection?.second.orEmpty(),
                    collectionTitle = collection?.first,
                    startCreating = target == null && collection == null,
                    onPick = { playlist ->
                        target?.let { targetSong ->
                            viewModel.addToPlaylist(playlist, targetSong) { result ->
                                val message = if (result.isSuccess) {
                                    context.getString(R.string.youtube_playlist_track_added, playlist.title)
                                } else {
                                    context.getString(R.string.youtube_playlist_track_add_failed)
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                        collection?.let { (_, songs) ->
                            viewModel.addSongsToPlaylist(playlist, songs) { result ->
                                val message = result.fold(
                                    onSuccess = { count ->
                                        context.getString(R.string.youtube_playlist_collection_added, count, playlist.title)
                                    },
                                    onFailure = {
                                        context.getString(R.string.youtube_playlist_collection_add_failed)
                                    },
                                )
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                        dismiss()
                    },
                    onCreate = { title, privacy ->
                        viewModel.createPlaylist(
                            title = title,
                            privacy = privacy,
                            song = target,
                            songs = collection?.second.orEmpty(),
                        )
                        dismiss()
                    },
                )
            }
        }

        // ---- Album / playlist actions ----
        // Opened by holding a card on any tab, or from the release page's own
        // overflow. What a track's long-press menu is to one song, this is to
        // the whole release — the queue rows above all.
        browseActions?.let { target ->
            // Every row here closes the menu first: the tracks may still have to
            // be fetched, and leaving the sheet up over a request nothing on it
            // reports on reads as a tap that didn't land.
            val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
                {
                    browseActions = null
                    withBrowseSongs(target, action)
                }
            }
            // Whose playlist this is, asked here rather than carried in by
            // whatever opened the sheet.
            //
            // Only the playlist's own page states it (see
            // InnertubeParser.parsePlaylistOwned), so a card has to send for the
            // answer and the sheet is already up by the time it lands — hence
            // read as state rather than settled once when the target was built.
            // Rename and Delete are absent until the answer says they apply, so
            // the sheet's worst moment is a beat without them on the user's own
            // playlist, rather than offering to delete a stranger's.
            LaunchedEffect(target.browseId) {
                viewModel.resolvePlaylistOwnership(target.browseId)
            }
            // Spelt out here rather than left to MainViewModel.editablePlaylist,
            // which is the same rule over the same two lists: that reads them as
            // plain values, which is right for a click handler and invisible to
            // Compose. Both are read from collected state so this sheet actually
            // recomposes when the answer arrives.
            val ownedPlaylists by viewModel.playlistOwned.collectAsStateWithLifecycle()
            val playlist = target.browseId
                ?.takeIf { signedIn && ownedPlaylists[it] == true }
                ?.let { id -> playlists.firstOrNull { it.browseId == id } }
            val remote = target.browseId?.startsWith("local:") == false
            val pinnedPlaylists by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
            val pinnableId = target.browseId?.takeIf { target.type == BrowseType.PLAYLIST }
            val savableCollectionId = target.browseId?.takeIf {
                remote && target.type in setOf(BrowseType.ALBUM, BrowseType.PLAYLIST)
            }
            val savedToLibrary = savableCollectionId?.let { id ->
                savedYoutubeCollections.any { it.browseId == id }
            } == true
            ModalBottomSheet(
                onDismissRequest = { browseActions = null },
                containerColor = Color.Transparent,
            ) {
                BrowseActionsSheet(
                    // The live answer, not the one the target was built with.
                    target = target.copy(playlist = playlist),
                    onPlayNext = act(playSongsNext),
                    onAddToQueue = act(addSongsToQueue),
                    onAddToPlaylist = savableCollectionId?.let {
                        act { songs ->
                            if (songs.isEmpty()) {
                                Toast.makeText(context, R.string.no_tracks_here, Toast.LENGTH_SHORT).show()
                            } else if (signedIn) {
                                playlistCollectionTarget = target.title to songs
                                viewModel.loadPlaylists()
                            } else {
                                // The local playlist store accepts catalogue IDs;
                                // signing in to YouTube is not required to keep a
                                // collection in this app's own playlists.
                                localPlaylistCollectionTarget = songs
                            }
                        }
                    },
                    isSavedToLibrary = savedToLibrary,
                    onToggleLibrary = savableCollectionId?.let { id ->
                        {
                            val added = AppSettings.toggleSavedYoutubeCollection(
                                SavedYoutubeCollection(
                                    browseId = id,
                                    title = target.title,
                                    subtitle = target.subtitle,
                                    thumbnailUrl = target.thumbnailUrl,
                                    type = target.type.name,
                                ),
                            )
                            Toast.makeText(
                                context,
                                if (added) R.string.youtube_collection_added_to_library
                                else R.string.remove_saved_youtube_collection,
                                Toast.LENGTH_SHORT,
                            ).show()
                            browseActions = null
                        }
                    },
                    onPlay = act { songs -> play(songs, 0) }.takeIf { target.fromCard },
                    onShuffle = act { songs ->
                        // As on a release page: shuffle goes on before the queue
                        // is built, so it is built shuffled rather than played
                        // out of order.
                        QueueShuffle.enableForNextQueue()
                        play(songs, songs.indices.random())
                    }.takeIf { target.fromCard },
                    onOpen = target.browseId
                        ?.takeIf { target.fromCard }
                        ?.let { id ->
                            {
                                browseActions = null
                                viewModel.openDetail(
                                    browseId = id,
                                    title = target.title,
                                    subtitle = target.subtitle,
                                    thumbnailUrl = target.thumbnailUrl,
                                    type = target.type,
                                )
                            }
                        },
                    // The one place a whole release is asked for, from a card and
                    // from the release's own page alike — its header spends that
                    // spot on the search now. Nothing on this device needs
                    // fetching to be on it, so a local page is the exception.
                    // What the target carries that the tracks don't is the
                    // release's own name and cover, which is exactly what the
                    // record wants.
                    onDownloadAll = act { songs ->
                        startDownload(
                            songs,
                            target.browseId
                                ?.takeIf { target.type != BrowseType.ARTIST }
                                ?.let { id ->
                                    DownloadTarget(
                                        id = id,
                                        title = target.title,
                                        subtitle = target.subtitle,
                                        thumbnailUrl = target.thumbnailUrl,
                                        playlist = target.type == BrowseType.PLAYLIST,
                                    )
                                },
                        )
                    }.takeIf { remote },
                    isPinned = pinnableId != null && pinnableId in pinnedPlaylists,
                    onTogglePin = pinnableId?.let { id ->
                        {
                            val nowPinned = AppSettings.togglePinnedPlaylist(id)
                            if (!nowPinned && id !in pinnedPlaylists) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.pinned_playlist_limit,
                                        AppSettings.MAX_PINNED_PLAYLISTS,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            browseActions = null
                        }
                    },
                    onRename = playlist?.let { p ->
                        { name: String ->
                            browseActions = null
                            viewModel.renamePlaylist(p, name)
                        }
                    },
                    onDelete = playlist?.let { p ->
                        {
                            browseActions = null
                            viewModel.deletePlaylist(p)
                        }
                    },
                    onDeleteDownload = target.downloadId?.let { id ->
                        {
                            browseActions = null
                            scope.launch { Downloads.deleteCollection(context, id) }
                        }
                    },
                )
            }
        }

        if (songSortMenuOpen) {
            BackHandler { songSortMenuOpen = false }
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                onFlipDateDirection = {
                    detail?.browseId?.let { browseId ->
                        val next = when (songSort) {
                            SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                            SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                            else -> SongSort.DATE_ADDED_DESC
                        }
                        AppSettings.setDetailSongSort(browseId, next)
                    }
                },
                onDismiss = { songSortMenuOpen = false },
            )
        }

        if (showAppLanguage) {
            BackHandler { showAppLanguage = false }
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
            )
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (localArtworkPreload.isRunning || localArtworkPreload.isComplete || localArtworkPreload.errorMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissLocalArtworkPreload() },
                title = {
                    Text(
                        stringResource(
                            when {
                                localArtworkPreload.isRunning -> R.string.preload_album_art_progress_title
                                localArtworkPreload.errorMessage != null ||
                                    (localArtworkPreload.isComplete && localArtworkPreload.skipped > 0) ->
                                    R.string.preload_album_art_failed_title
                                else -> R.string.preload_album_art_complete_title
                            },
                        ),
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            localArtworkPreload.isRunning && localArtworkPreload.total > 0 -> {
                                Text(
                                    stringResource(
                                        R.string.preload_album_art_progress,
                                        localArtworkPreload.processed,
                                        localArtworkPreload.total,
                                        localArtworkPreload.loaded,
                                        localArtworkPreload.skipped,
                                    ),
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        (localArtworkPreload.processed.toFloat() /
                                            localArtworkPreload.total.toFloat()).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            localArtworkPreload.isRunning -> {
                                Text(stringResource(R.string.preload_album_art_progress_scanning))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            localArtworkPreload.errorMessage != null -> {
                                Text(localArtworkPreload.errorMessage!!)
                            }
                            localArtworkPreload.isComplete && localArtworkPreload.skipped > 0 -> {
                                Text(stringResource(R.string.preload_album_art_partial_failure))
                                Text(
                                    stringResource(
                                        R.string.preload_album_art_result,
                                        localArtworkPreload.loaded,
                                        localArtworkPreload.skipped,
                                    ),
                                )
                            }
                            else -> {
                                Text(
                                    stringResource(
                                        R.string.preload_album_art_result,
                                        localArtworkPreload.loaded,
                                        localArtworkPreload.skipped,
                                    ),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !localArtworkPreload.isRunning,
                        onClick = { viewModel.dismissLocalArtworkPreload() },
                    ) {
                        Text(
                            stringResource(
                                if (localArtworkPreload.isRunning) R.string.preload_album_art_working
                                else R.string.close,
                            ),
                        )
                    }
                },
            )
        }

        if (showListenBrainzLogin) {
            var tokenInput by remember { mutableStateOf(listenBrainzToken) }
            ListenBrainzTokenAlert(
                hazeState = hazeState,
                tokenInput = tokenInput,
                onTokenInputChange = { tokenInput = it },
                onSave = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzLogin = false
                },
                onDismiss = { showListenBrainzLogin = false },
            )
        }

        if (showLastfmLogin) {
            var usernameInput by remember { mutableStateOf("") }
            var passwordInput by remember { mutableStateOf("") }
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                usernameInput = usernameInput,
                onUsernameInputChange = { usernameInput = it },
                passwordInput = passwordInput,
                onPasswordInputChange = { passwordInput = it },
                error = lastfmError,
                loading = lastfmLoading,
                onSignIn = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            LastFM.initialize(
                                apiKey = AppSettings.lastfmApiKey.value,
                                secret = AppSettings.lastfmSecret.value,
                            )
                            LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                .onSuccess { auth ->
                                    AppSettings.setLastfmSessionKey(auth.session.key)
                                    AppSettings.setLastfmUsername(auth.session.name)
                                    AppSettings.setLastfmEnabled(true)
                                    showLastfmLogin = false
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
                onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
            )
        }

        if (showWebDavEditor) {
            BackHandler { showWebDavEditor = false }
            ServerEditorHost(
                hazeState = hazeState,
                title = stringResource(R.string.webdav),
                description = stringResource(R.string.webdav_description),
                fields = listOf(
                    FieldConfig(AppSettings.webdavUrl.value, stringResource(R.string.webdav_server_url_hint), KeyboardType.Uri),
                    FieldConfig(AppSettings.webdavUsername.value, stringResource(R.string.username)),
                    FieldConfig(AppSettings.webdavPassword.value, stringResource(R.string.password), KeyboardType.Password, true),
                ),
                canSubmit = { values -> values.firstOrNull()?.isNotBlank() == true },
                testFailedRes = R.string.webdav_test_failed,
                onTest = { values ->
                    com.music.bitchord.data.webdav.WebDavRepository.testConnection(
                        values[0].trim(), values[1].trim(), values[2], AppSettings.webdavAllowInsecureHttp.value,
                    )
                },
                onSave = { values ->
                    AppSettings.setWebDavUrl(values[0].trim())
                    AppSettings.setWebDavUsername(values[1].trim())
                    AppSettings.setWebDavPassword(values[2])
                    showWebDavEditor = false
                },
                onDismiss = { showWebDavEditor = false },
            )
        }

        if (showSmbEditor) {
            BackHandler { showSmbEditor = false }
            ServerEditorHost(
                hazeState = hazeState,
                title = stringResource(R.string.smb),
                description = stringResource(R.string.smb_description),
                fields = listOf(
                    FieldConfig(AppSettings.smbHost.value, stringResource(R.string.smb_server_hint), KeyboardType.Uri),
                    FieldConfig(AppSettings.smbShare.value, stringResource(R.string.smb_share_hint)),
                    FieldConfig(AppSettings.smbBasePath.value, stringResource(R.string.smb_folder_hint)),
                    FieldConfig(AppSettings.smbUsername.value, stringResource(R.string.username)),
                    FieldConfig(AppSettings.smbPassword.value, stringResource(R.string.password), KeyboardType.Password, true),
                ),
                canSubmit = { values -> values.size >= 2 && values[0].isNotBlank() && values[1].isNotBlank() },
                testFailedRes = R.string.smb_test_failed,
                onTest = { values ->
                    com.music.bitchord.data.smb.SmbRepository.testConnection(
                        values[0].trim(), values[1].trim(), values[2].trim(), values[3].trim(), values[4],
                    )
                },
                onSave = { values ->
                    AppSettings.setSmbHost(values[0].trim())
                    AppSettings.setSmbShare(values[1].trim())
                    AppSettings.setSmbBasePath(values[2].trim())
                    AppSettings.setSmbUsername(values[3].trim())
                    AppSettings.setSmbPassword(values[4])
                    showSmbEditor = false
                },
                onDismiss = { showSmbEditor = false },
            )
        }

        editingSource?.let { config ->
            SourceEditorAlert(
                hazeState = hazeState,
                config = config,
                onDismiss = { editingSource = null },
                onSaved = { editingSource = null },
                onDelete = {
                    SourceRegistry.remove(config.id)
                    editingSource = null
                },
                scope = scope,
            )
        }

        if (showFirstLaunchPermissionDialog) {
            LocalAudioPermissionDialog(
                onRequestPermission = {
                    AppSettings.setAllFilesPermissionAsked(true)
                    showFirstLaunchPermissionDialog = false
                    if (LocalMediaRepository.hasStoragePermission(context)) {
                        hasStoragePermission = true
                        viewModel.reloadLocalDetail("local:all")
                        viewModel.loadLocalMusic()
                    } else {
                        requestAudioPermission()
                    }
                },
                onRequestAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    {
                        AppSettings.setAllFilesPermissionAsked(true)
                        showFirstLaunchPermissionDialog = false
                        requestAllFilesAccess()
                    }
                } else {
                    null
                },
                onDismissRequest = {
                    AppSettings.setAllFilesPermissionAsked(true)
                    showFirstLaunchPermissionDialog = false
                },
            )
        }

    }
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

/**
 * The track-list sort menu, styled after the account switcher: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onFlipDateDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Date added is one row, Spotify-style: the arrow on it shows
                // the direction — up for newest first, down for oldest — and
                // tapping flips it, the rotation animating the flip. Up is
                // also where a fresh activation lands, newest first being the
                // point of the feature.
                val dateActive = selected == SongSort.DATE_ADDED_ASC ||
                    selected == SongSort.DATE_ADDED_DESC
                val arrowRotation by animateFloatAsState(
                    targetValue = if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "dateAddedArrow",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onFlipDateDirection() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sort_date_added_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (dateActive) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(arrowRotation),
                        )
                    }
                }
                SongSort.entries
                    .filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }
                    .forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { onSelect(option) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.localizedLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (option == selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

/**
 * Whether this page id is one of the two device folders — `local:downloads` and
 * `local:all`, the tabbed Songs / Artists / Albums view.
 *
 * Asked rather than `startsWith("local:")` because that prefix now covers two
 * unlike pages: a folder, and one downloaded playlist, which is a plain track
 * listing under its own cover and wants the same chrome every other release page
 * gets. See [Downloads.PLAYLIST_PREFIX] for why they share a namespace at all.
 */
private fun String?.isDeviceFolder(): Boolean =
    this == "local:all" || this == "local:downloads"

/** `M:SS`/`H:MM:SS`, the same shape [String?.durationMillis] parses back. */
private fun formatDurationText(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * The pane a wide window keeps the player in, down the right-hand edge.
 *
 * It is a fixed [width] rather than a share of the row because the player has a
 * width it wants and a page does not: past a point the sleeve and the transport
 * stop being improved by more room and the feed beside them still is, so the
 * pane takes what it needs and the page has the rest — see [dockedPlayerWidth].
 *
 * The pane is there whether or not anything is playing. A player that appears
 * and disappears would take a third of the page's width with it every time
 * something started or stopped, which is the layout jumping under the finger
 * rather than the app reacting to it; so with nothing to show it says so.
 */
@Composable
private fun DockedPlayer(
    song: Song?,
    width: Dp,
    onExpand: () -> Unit,
    content: @Composable (Song) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (song != null) {
            content(song)
            IconButton(
                onClick = onExpand,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 6.dp, end = 6.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fullscreen,
                    contentDescription = stringResource(R.string.expand_player),
                    tint = Color.White,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.nothing_playing),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.pick_something),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        // The status bar runs across both panes and its glyphs can only be one
        // colour, and that colour follows the page: in a light theme they are
        // dark ink, which over a plain surface is a clock nobody can read. Only
        // painted for the empty state, where the pane really is flat
        // [colorScheme.surface] behind the placeholder copy.
        //
        // A song mounts [NowPlayingScreen] instead, and that already runs its
        // own backdrop — the mesh gradient, and the hero banner's artwork —
        // up behind the inset, with its own scrim once the banner settles (see
        // its [heroT] scrim). Painting flat over that here was covering the
        // player's own backdrop with a solid rectangle every frame, which is
        // the black bar across the top of a playing dock: the artwork stopped
        // at this box instead of running to the edge like it does on a phone.
        if (song == null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTuningDialog(
    song: Song?,
    onDismiss: () -> Unit,
) {
    var speed by remember { mutableStateOf(AppSettings.playbackSpeed.value) }
    var pitch by remember { mutableStateOf(AppSettings.playbackPitch.value) }
    val palette = rememberArtworkPalette(song?.thumbnailUrl, dark = true, artPx = ROW_ART_PX)
    val useArtworkBackdrop by AppSettings.frostedPlayerDetails.collectAsStateWithLifecycle()
    val hideTitle by AppSettings.hidePlaybackTuningTitle.collectAsStateWithLifecycle()
    val useThinSliders by AppSettings.playbackTuningThinSliders.collectAsStateWithLifecycle()
    val foreground = if (useArtworkBackdrop) palette.onBackground else MaterialTheme.colorScheme.onSurface
    val secondary = if (useArtworkBackdrop) palette.onBackgroundVariant else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (useArtworkBackdrop) palette.accent else MaterialTheme.colorScheme.primary
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = sheetShape,
        containerColor = if (useArtworkBackdrop) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background((if (useArtworkBackdrop) palette.onBackground else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.38f)),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().clip(sheetShape)) {
            if (useArtworkBackdrop) {
                ArtworkBackdrop(
                    palette = palette,
                    imageUrl = song?.thumbnailUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 0.75f,
                    artPx = ROW_ART_PX,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                if (!hideTitle) {
                    Text(
                        text = stringResource(R.string.playback_tuning),
                        style = MaterialTheme.typography.titleLarge,
                        color = foreground,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.playback_speed) + " · " + String.format(Locale.ROOT, "%.1fx", speed),
                    style = MaterialTheme.typography.titleMedium,
                    color = foreground,
                )
                if (useThinSliders) {
                    ThinSlider(
                        value = ((speed - 0.1f) / 9.9f).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            speed = kotlin.math.round((0.1f + fraction * 9.9f) * 10f) / 10f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        activeColor = accent,
                        inactiveColor = secondary.copy(alpha = 0.28f),
                    )
                } else {
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.1f..10.0f,
                        steps = 98,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = secondary.copy(alpha = 0.28f),
                            activeTickColor = foreground.copy(alpha = 0.65f),
                            inactiveTickColor = secondary.copy(alpha = 0.45f),
                        ),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.playback_pitch) + " · " + String.format(Locale.ROOT, "%.2fx", pitch),
                    style = MaterialTheme.typography.titleMedium,
                    color = foreground,
                )
                if (useThinSliders) {
                    ThinSlider(
                        value = ((pitch - 0.5f) / 1.5f).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            val raw = 0.5f + fraction * 1.5f
                            pitch = (kotlin.math.round(raw * 20f) / 20f).coerceIn(0.5f, 2f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        activeColor = accent,
                        inactiveColor = secondary.copy(alpha = 0.28f),
                    )
                } else {
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = 0.5f..2.0f,
                        steps = 29,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = secondary.copy(alpha = 0.28f),
                            activeTickColor = foreground.copy(alpha = 0.65f),
                            inactiveTickColor = secondary.copy(alpha = 0.45f),
                        ),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel), color = accent)
                    }
                    TextButton(onClick = {
                        AppSettings.setPlaybackSpeed(speed)
                        AppSettings.setPlaybackPitch(pitch)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.save), color = accent)
                    }
                }
            }
        }
    }
}

/**
 * How far short of the end a seek is allowed to land.
 *
 * Seeking to the final millisecond is indistinguishable from the track running
 * out, so it starts the next song — which is not what anyone dragging to the end
 * of the bar, or tapping the last line of a lyric, is asking for. A second back
 * from the end plays the outro instead.
 */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * How far a detail page scrolls before its title moves up into the bar.
 *
 * Roughly the height of the sleeve and the credit stacked above the Play pair,
 * so the two titles hand over as the header one leaves rather than sitting on
 * screen together. The bar cross-fades over 220ms, which absorbs the difference
 * between that estimate and a particular page's real header.
 */
private val DETAIL_TITLE_DROP = 320.dp

private const val TAB_SONGS = 0
private const val TAB_ALBUMS = 1
private const val TAB_ARTISTS = 2
private const val TAB_LIBRARY = 3
private const val TAB_SEARCH = 4
private const val TAB_ALBUM_ARTISTS = 5
private const val TAB_FOLDERS = 6
private const val TAB_RADIO = 7



/**
 * What a tab's key is prefixed with in the content switcher above.
 *
 * The index is read back off it there rather than off `selectedTab`, so the
 * prefix has to be the one thing both the writing and the reading agree on.
 */
private const val TAB_KEY = "tab:"

/** Lets the artwork travel above the modal sheet's top edge while it docks. */
private object PlayerSheetShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, -size.height, size.width, size.height))
}
