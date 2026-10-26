package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.YoutubeStreamBootstrap
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.feature.artistimage.model.ArtistSort
import com.music.bitchord.playback.equalizer.EelEqParser
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID

/** Maximum blur radius for the player-only artwork backdrop, in dp. */
const val MAX_PLAYER_ARTWORK_BLUR_DP = 128f

/**
 * Stream bitrate ceiling on the YouTube fallback path — MEDIUM, HIGH and
 * LOSSLESS all mean "whatever the best available Opus format is" there; what
 * actually tells them apart is which other sources are allowed to answer
 * *before* YouTube gets asked. That part is [permits], and the rungs read:
 *
 * - [LOSSLESS] — the user's own addons and JioSaavn both asked.
 * - [HIGH] — the addons skipped, JioSaavn asked.
 * - [MEDIUM] and [LOW] — both skipped; YouTube's own Opus ladder is all there
 *   is, capped at [maxKbps].
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(Int.MAX_VALUE, "Medium", "Best available · ~171 kbps Opus", "77 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available", "300+ MB/hr"),
    ;

    /**
     * Whether a stream started under this ceiling may be served by [kind].
     *
     * Asked per stream rather than written into
     * [SourceConfig.enabled][com.music.bitchord.data.sources.SourceConfig.enabled],
     * which is what this used to do — an `applyQualityPreset` call flipped the
     * module and JioSaavn switches the moment a rung was picked. Two things
     * were wrong with that and both were reported together: picking a rung for
     * *mobile data* turned the sources off while sitting on Wi-Fi, and nothing
     * turned them back on when the connection changed, so a Wi-Fi ceiling of
     * Lossless still had no lossless source to reach. A ceiling is a property
     * of the connection in force; the switches on the Sources screen are the
     * user's standing choice. Storing the first in the second lost the second.
     *
     * [SourceKind.YOUTUBE] is permitted on every rung: it is what [maxKbps]
     * caps, and it is the only source that can answer at all when the ones
     * above it are skipped.
     */
    fun permits(kind: SourceKind): Boolean = when (this) {
        LOSSLESS -> true
        // No lossless answer is wanted here, and a source that can serve one is
        // the slow half of the list: an addon fronting several catalogues walks
        // all of them before it answers, which is seconds spent to land on a
        // transcode JioSaavn already has at 320.
        HIGH -> !kind.canServeLossless
        MEDIUM, LOW -> kind == SourceKind.YOUTUBE
    }
}

/**
 * PCM format requested from Media3's AudioTrack sink.
 *
 * FLOAT_32 is not a cosmetic "hi-res" switch: it makes Media3 convert
 * high-resolution integer PCM to IEEE-754 float and configure AudioTrack for
 * PCM_FLOAT. Android may still route/resample it according to the selected
 * output device, which is why the player exposes the negotiated format.
 */
enum class OutputPcmMode(val label: String) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

/** Text alignment for the full-screen lyrics panel. */
enum class LyricsTextAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

/** Presentation mode for dedicated seek buttons beside the transport controls. */
enum class SeekButtonMode {
    OFF,
    ALWAYS,
    LONG_TRACKS_ONLY,
}

/** Optional actions placed in the two short-seek positions beside previous/next. */
enum class TransportSideAction {
    NONE,
    SHUFFLE,
    REPEAT,
    FAVORITE,
    LYRICS,
    QUEUE,
    PLAYLISTS,
    SEARCH,
}

/** Destinations within the device-local music library. */
enum class LocalLibraryTab { SONGS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS, ALBUM_ARTISTS }

/**
 * What to keep when a track is saved to the device.
 *
 * Deliberately not [AudioQuality]. That enum budgets a *stream*, and is priced
 * per hour because the same bytes are spent again on every replay. A download is
 * the opposite trade — paid for once, kept, played from disk forever after — so
 * the figure that decides it is what one track costs, and the rung worth
 * defaulting to is the top one rather than the cheap one.
 *
 * The rungs themselves differ too. On the YouTube fallback path a download is
 * Opus-in-WebM (see
 * [StreamResolver.resolveForDownload][com.music.bitchord.data.innertube.StreamResolver.resolveForDownload]),
 * while configured sources can supply their own AAC or lossless copy. And
 * [LOSSLESS] has no streaming counterpart at all: it is the only rung that lets
 * a configured source's bit-exact file end up as a file on disk.
 */
enum class DownloadQuality(
    /** Ceiling for the YouTube Opus ladder. [Int.MAX_VALUE] means "whichever rung is best". */
    val maxKbps: Int,
    val label: String,
    val detail: String,
    /** Roughly what one four-minute track costs at this rung, sans unit context. */
    val perTrack: String,
    /** Whether a source's bit-exact file is worth keeping, or a transcode will do. */
    val keepsLossless: Boolean,
) {
    STANDARD(128, "Standard", "~128 kbps Opus · fits more on the device", "~4 MB", false),
    HIGH(Int.MAX_VALUE, "High", "Best audio on offer; source quality first", "~8 MB", false),
    LOSSLESS(
        Int.MAX_VALUE,
        "Lossless",
        "Bit-exact if a source has it, best Opus if not",
        "~35 MB",
        true,
    ),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED black"), ARTWORK("Album art"), CUSTOM_IMAGE("Custom image")
}

/** Secondary action shown beside the favorite action in Android media controls. */
enum class MediaControlSecondaryAction { SHUFFLE, PLAYLISTS, LYRICS, QUEUE }

/** Motion used by the floating navigation selection indicator. */
enum class PillMotionStyle {
    FAST,
    FLUID,
    RELAXED;

    companion object {
        val DEFAULT = FLUID
    }
}


/** Stable persisted ordering for each on-device music library. */
enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    ARTIST_ASC,
    DATE_ADDED,
    DATE_MODIFIED,
    DURATION_DESC,
}

/** Secondary controls shown beneath the main play/pause controls. */
enum class PlayerControl {
    SHUFFLE,
    REPEAT,
    LYRICS,
    LIKE,
    QUEUE,
    PLAYLIST,
    SEARCH,
}

object PlayerControlOrdering {
    fun normalize(order: List<PlayerControl>): List<PlayerControl> =
        (order + PlayerControl.entries).distinct()

    fun move(order: List<PlayerControl>, control: PlayerControl, offset: Int): List<PlayerControl> {
        val normalized = normalize(order).toMutableList()
        val from = normalized.indexOf(control)
        val to = (from + offset).coerceIn(0, normalized.lastIndex)
        if (from != to) normalized.add(to, normalized.removeAt(from))
        return normalized
    }
}

private val DEFAULT_PLAYER_CONTROLS = setOf(
    PlayerControl.SHUFFLE,
    PlayerControl.REPEAT,
    PlayerControl.LIKE,
    PlayerControl.QUEUE,
)

/**
 * Stable persisted ordering for a Library "Show all" grid — playlists or
 * albums. A card there only ever carries a title, so unlike [LocalMusicSort]
 * there is nothing date-based to offer.
 */
enum class LibrarySort {
    /** Whatever order the shelf itself arrived in — YouTube Music's own. */
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
}

/**
 * Ordering for the track list on an album or playlist page — the same idea as
 * the Downloads folder's sort, with a date option for the one thing a
 * catalogue row can still be dated by: the position it sits at. A playlist's
 * running order is the order songs were added in — YouTube Music appends each
 * addition at the foot — so read backwards it *is* a date order, newest first.
 * Drill-down and playlist views hold the sort itself. Persisted app-wide rather than per
 * page: one choice, kept until the user makes another.
 */
enum class SongSort {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}

/** Display mode for music lists: compact rows or grid cards. */
enum class LibraryViewType {
    LIST,
    GRID,
}

/** Stable identities for the destinations in the app's bottom navigation. */
enum class MainNavigationTab(val index: Int) {
    SONGS(0),
    ALBUMS(1),
    ARTISTS(2),
    LIBRARY(3),
    SEARCH(4),
    ALBUM_ARTISTS(5),
    FOLDERS(6),
    RADIO(7),
}

enum class ScreenOrientationMode { SYSTEM, PORTRAIT, LANDSCAPE }

/** Player shortcuts shown immediately to the left of the favorite action. */
enum class PlayerQuickAction {
    LYRICS,
    ADD_TO_PLAYLIST,
    PLAYBACK_TUNING,
    QUEUE,
    PLAYLISTS,
    SEARCH,
    ALBUM,
    ARTIST,
    EQUALIZER,
    CHROMECAST,
    SLEEP_TIMER,
    TAG_EDITOR,
    EDIT_LYRICS,
    DETAILS,
    SHARE_FILE,
    FAVORITE,
    SHUFFLE,
    REPEAT,
}

/** Items shown in the player’s song actions sheet opened from the More button. */
enum class PlayerDetailsAction {
    ALBUM,
    ARTIST,
    ADD_TO_PLAYLIST,
    EQUALIZER,
    CHROMECAST,
    PLAYBACK_TUNING,
    SLEEP_TIMER,
    TAG_EDITOR,
    EDIT_LYRICS,
    DETAILS,
    SHARE_FILE,
}

object MainNavigationTabs {
    fun normalizeOrder(order: List<MainNavigationTab>): List<MainNavigationTab> =
        (order + MainNavigationTab.entries).distinct()

    fun move(
        order: List<MainNavigationTab>,
        tab: MainNavigationTab,
        offset: Int,
    ): List<MainNavigationTab> {
        val normalized = normalizeOrder(order).toMutableList()
        val from = normalized.indexOf(tab)
        val to = (from + offset).coerceIn(0, normalized.lastIndex)
        if (from != to) normalized.add(to, normalized.removeAt(from))
        return normalized
    }

    fun setVisible(
        current: Set<MainNavigationTab>,
        tab: MainNavigationTab,
        visible: Boolean,
    ): Set<MainNavigationTab> {
        val baseline = current.ifEmpty { MainNavigationTab.entries.toSet() }
        if (visible) return baseline + tab
        if (baseline.size <= 1) return baseline
        return baseline - tab
    }

    fun fallback(
        selectedIndex: Int,
        visible: Set<MainNavigationTab>,
        order: List<MainNavigationTab> = MainNavigationTab.entries,
    ): MainNavigationTab {
        val available = visible.ifEmpty { MainNavigationTab.entries.toSet() }
        val ordered = normalizeOrder(order)
        val current = MainNavigationTab.entries.firstOrNull { it.index == selectedIndex }
        current?.takeIf { it in available }?.let { return it }
        val cursor = current?.let(ordered::indexOf)?.takeIf { it >= 0 } ?: -1
        return (1..ordered.size)
            .asSequence()
            .map { ordered[(cursor + it) % ordered.size] }
            .firstOrNull { it in available }
            ?: MainNavigationTab.SONGS
    }
}

/**
 * App settings, backed by SharedPreferences and exposed as flows.
 *
 * PlaybackService runs in the same process as the UI, so it observes these
 * same flows and applies changes to the live ExoPlayer instance immediately —
 * no restart, no rebinding.
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    /** Only for the Discord token — everything else on here is plain prefs. */
    private lateinit var authStore: AuthStore

    /**
     * Quality ceilings, one per kind of connection — the point of the split is
     * that Wi-Fi can stay on Lossless while mobile data is capped. Both
     * default to Lossless; the mobile plan is the user's to budget, not ours
     * to assume.
     */
    val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)

    /** Temporary quality ceiling while audio is streamed to a Wear OS watch. */
    val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)

    /**
     * What a saved file should be, answered on its own terms.
     *
     * Kept apart from the two ceilings above on purpose. Those are about what
     * this minute's connection costs, and a download outlives the minute it was
     * started in — capping a permanent file at whichever network happened to be
     * in hand bakes a temporary decision into a lasting artefact, and the
     * reverse (a High ceiling on Wi-Fi implying 35MB FLACs of everything) is
     * just as wrong in the other direction.
     *
     * Data spend on a download is [wifiOnlyDownloads]' problem, not this
     * setting's, which is what lets this one be purely about the file.
     *
     * Defaults to [DownloadQuality.LOSSLESS] because that is what the download
     * path already did on an uncapped connection, and [migrateDownloadQuality]
     * keeps it that way for the people it didn't.
     */
    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)

    /**
     * Refuse to start a download while the connection charges for data.
     *
     * Metered rather than literally-Wi-Fi, the same test [effectiveAudioQuality]
     * makes, because the thing worth protecting is the bill and not the radio: a
     * tethered hotspot is Wi-Fi that costs money, and an unmetered home
     * connection is worth using whether or not it arrives over Wi-Fi.
     *
     * On by default, and that is a deliberate change of behaviour for anyone
     * updating. [downloadQuality] defaulting to Lossless means a tap that used
     * to spend four megabytes of mobile data can now spend thirty-five, and of
     * the two ways to get that wrong — silently overspending a data plan, or
     * refusing with a sentence naming the switch that would allow it — only the
     * second is recoverable by the person it happens to.
     */
    val wifiOnlyDownloads = MutableStateFlow(true)

    /**
     * Keep ordinary downloads in Music/BitChord where other music apps can see
     * them. Off (the default) keeps downloads in this app's private storage.
     * HLS downloads always stay private because they are a playlist package,
     * not one portable audio file.
     */
    val exportDownloads = MutableStateFlow(false)

    /** Whether the active network charges for data. `null` while offline. */
    val meteredConnection = MutableStateFlow<Boolean?>(null)

    // `losslessAudio` used to live here, behind a "Prefer lossless" switch on
    // the Sources screen. It is gone: sources are asked for their best and each
    // degrades on its own terms, so the switch's only real effect was to ask a
    // module for a worse file than it was holding. See
    // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow],
    // which now reads [effectiveAudioQuality] and nothing else.

    /** Equal-power overlap between consecutive tracks; zero keeps current transitions unchanged. */
    val crossfadeSeconds = MutableStateFlow(0)

    /** Settings for optional USB CD-DA ripping. The SAF folder URI is device-local. */
    val cdRippingOutputTreeUri = MutableStateFlow<String?>(null)
    val cdRippingRetries = MutableStateFlow(2)
    val cdRippingSpeedX = MutableStateFlow(4)

    /** Lets the analyzer plan transition timing from each track; falls back to fixed crossfade while analysis is unavailable. */
    val smartFadeEnabled = MutableStateFlow(false)
    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
    val smartMixInProgress = MutableStateFlow(false)
    val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    val smartAnalysis = MutableStateFlow(SmartAnalysis())
    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)

    /** Optional high-frequency cutoff for PCM playback. Zero leaves audio untouched. */
    val resamplerCutoffHz = MutableStateFlow(0)

    val skipSilence = MutableStateFlow(false)
    /** Requests Media3 audio offload when no crossfade is configured. */
    val audioOffloadEnabled = MutableStateFlow(false)

    /** Requested PCM representation at the Android AudioTrack boundary. */
    val outputPcmMode = MutableStateFlow(OutputPcmMode.PCM_16)

    /** Bypasses app DSP and requests Android's bit-perfect USB mixer when supported. */
    val bitPerfectPlayback = MutableStateFlow(false)

    /** Prefer an attached USB audio output over the system's normal route. */
    val preferUsbDac = MutableStateFlow(false)
    /** When disabled, pause local playback unless Android routes it to headphones, Bluetooth, or USB. */
    val speakerPlaybackAllowed = MutableStateFlow(true)

    /**
     * Whether a source offering a Dolby Atmos rendition is allowed to serve it.
     *
     * Off by default. When enabled, a source that offers an Atmos rendition
     * may choose it if this device can decode it. Atmos is E-AC-3,
     * which is *lossy* — a track with an Atmos master is frequently also held
     * as a FLAC, and someone listening on wired headphones may well prefer the
     * bit-exact stereo copy to a spatial mix their output can't render. Turning
     * this off keeps the source on its usual version; see
     * [ModuleSource.unplayable][com.music.bitchord.data.sources.ModuleSource],
     * which is where the refusal is applied.
     *
     * Independent of whether the device *can* decode it — that question is
     * [DeviceCodecs.playsDolbyAtmos][com.music.bitchord.data.sources.DeviceCodecs],
     * and the two are deliberately not folded together: this one is the
     * listener's answer, is persisted, and must survive being read on a phone
     * that cannot honour it (a restored backup, a swapped device) without
     * quietly rewriting itself.
     */
    val dolbyAtmos = MutableStateFlow(false)

    /** Dirac-style system EQ curve; this is not the vendor's proprietary DSP. */
    val diracAudioEnabled = MutableStateFlow(false)
    val diracAudioPreset = MutableStateFlow(0)

    /**
     * Widens stereo output via [com.music.bitchord.playback.SpatialAudioProcessor],
     * a stereo widening + cross-feed effect running inside ExoPlayer's own
     * pipeline. Not true object-based spatial audio — YouTube only ever hands
     * us a stereo stream, so there's no Atmos-style source to render.
     */
    val spatialAudio = MutableStateFlow(false)
    /** Optional software PEQ; legacy flows remain for existing settings and callers. */
    val parametricEqEnabled = MutableStateFlow(false)
    /** Applies optional TPDF dither only when the software PEQ quantizes processed integer PCM. */
    val tpdfDitherEnabled = MutableStateFlow(false)
    val parametricEqBands = MutableStateFlow(ParametricEqBand.DEFAULTS)
    val parametricEqConfig = MutableStateFlow(ParametricEqConfig())
    val parametricEqPresets = MutableStateFlow<List<ParametricEqPreset>>(emptyList())
    /** Stereo balance trim, centred by default and bypassed during bit-perfect playback. */
    val equalizerBalance = MutableStateFlow(0f)
    val channelSwapEnabled = MutableStateFlow(false)
    /** Optional room reverb, attached to the active audio session when supported. */
    val reverbEnabled = MutableStateFlow(false)
    val reverbAmount = MutableStateFlow(35)
    val playbackSpeed = MutableStateFlow(1.0f)
    val playbackPitch = MutableStateFlow(1.0f)
    val themeMode = MutableStateFlow(ThemeMode.DARK)
    /** Persisted SAF URI used by the optional user-selected image theme. */
    val customThemeImageUri = MutableStateFlow<String?>(null)
    /** Uses the current track artwork for Material accents without recolouring app surfaces. */
    val matchButtonAccentToArtwork = MutableStateFlow(false)
    /** Tints the mini-player surface from the current song sleeve. */
    val miniPlayerArtworkColor = MutableStateFlow(false)
    /** Lets the main navigation surface use the mini-player's artwork tint. */
    val navigationBarMatchesMiniPlayerColor = MutableStateFlow(false)
    /** Shows the mini-player outline while its surface is tinted from artwork. */
    val miniPlayerArtworkOutline = MutableStateFlow(false)
    /** Shows a previous-track control in expanded mini-player surfaces. */
    val miniPlayerPreviousButton = MutableStateFlow(true)
    /** Lets horizontal swipes on the mini-player move between tracks. */
    val miniPlayerSwipeChangesTrack = MutableStateFlow(false)
    /** Lets an upward swipe on the mini-player open the expanded player. */
    val miniPlayerSwipeUpExpands = MutableStateFlow(true)
    /** Uses the playlist page artwork palette for track menus opened inside it. */
    val playlistTrackMenuUsesArtworkColor = MutableStateFlow(true)
    val launcherIcon = MutableStateFlow(LauncherIcon.PINK)
    val frostedQueueManager = MutableStateFlow(true)
    val frostedPlayerDetails = MutableStateFlow(true)
    /** Artwork-colored background for local track metadata and file details. */
    val detailsSheetArtworkBackdrop = MutableStateFlow(true)
    /** Shows a non-empty COMMENT tag in the local track details sheet. */
    val showEmbeddedComment = MutableStateFlow(true)
    /** Replaces the queue manager's text action with a compact equals glyph. */
    val queueManagerEqualsIcon = MutableStateFlow(false)
    /** Uses the compact native-style saved queue manager in place of its legacy sheet. */
    val redesignedSavedQueueManager = MutableStateFlow(true)
    /** Hides only the save-current-queue section heading in the redesigned queue manager. */
    val hideSavedQueueHeading = MutableStateFlow(true)
    /** Hides the queue heading while keeping its actions available. */
    val hideQueueLabel = MutableStateFlow(true)
    /** Replaces the clear-queue label with a trash icon. */
    val queueClearTrashIcon = MutableStateFlow(false)
    /** Hides the heading and its vertical gap in the playback tuning sheet. */
    val hidePlaybackTuningTitle = MutableStateFlow(true)
    /** Draws speed and pitch controls with the player's hairline seek/volume bars. */
    val playbackTuningThinSliders = MutableStateFlow(true)

    /** Keep playing similar music once the queue runs out. */
    val autoplay = MutableStateFlow(true)

    /** Whether the queue is held in shuffled order (Mix button). */
    val shuffleEnabled = MutableStateFlow(false)

    /** Android audio-focus gain request: 0 disables focus and 1–4 select focus gain types. */
    val audioFocusLevel = MutableStateFlow(1)

    /** Repeat mode for the player — Off, All, or One. */
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    /** Restart the first queue item after the last item ends. */
    val restartQueueWhenEnded = MutableStateFlow(false)
    /** Advance through queued tracks with Repeat off, then stop at the queue end. */
    val advanceQueueWhenRepeatOff = MutableStateFlow(true)
    val mediaControlFavoriteUsesStar = MutableStateFlow(false)
    val mediaControlSecondaryAction = MutableStateFlow(MediaControlSecondaryAction.SHUFFLE)
    val hideMediaControlCustomActions = MutableStateFlow(false)

    /** Put the playing track's codec, bitrate and sample rate on the player. */
    val showNerdStats = MutableStateFlow(false)

    /** Freezes the main player's mesh gradient instead of letting it drift/crossfade. */
    val reduceAnimation = MutableStateFlow(false)

    /** Moves the player artwork between the mini and full player, and animates lyric changes. */
    val playerArtworkTransition = MutableStateFlow(true)
    /** Opt-in Walkman-inspired layout for the expanded player surface. */
    val walkmanPlayerStyle = MutableStateFlow(false)

    /** Speed and fluidity of the floating tab selection animation. */
    val pillMotionStyle = MutableStateFlow(PillMotionStyle.DEFAULT)

    /** Requests a sustained high-refresh UI. Off keeps Android's automatic policy. */
    val highPerformanceMode = MutableStateFlow(false)

    /** Preferred UI refresh rate while [highPerformanceMode] is enabled. */
    val performanceRefreshRate = MutableStateFlow(DEFAULT_PERFORMANCE_REFRESH_RATE)

    /** Stop playback when the app is swiped away from the recent apps screen. */
    val stopOnTaskRemoved = MutableStateFlow(false)

    /** Hides the volume slider on the main player, leaving the rest of the layout to reflow. */
    val hideVolumeBar = MutableStateFlow(false)

    /** Hides the seek bar and timestamps while a live radio station is playing. */
    val hideRadioPlaybackBar = MutableStateFlow(true)
    /** Visible player seek-bar thickness in dp. */
    val playerSeekBarThicknessDp = MutableStateFlow(7)
    /** Visible player volume-bar thickness in dp. */
    val playerVolumeBarThicknessDp = MutableStateFlow(6)

    /** Swiping a song row plays it next instead of adding it to the end of the queue. */
    val swipeToPlayNext = MutableStateFlow(false)

    /** Once a song has been suggested or played this session, AutoPlay won't offer it again. */
    val dontRepeatSuggestions = MutableStateFlow(false)

    /** Drops haze blur (status bar, mini player, bottom fade, lyrics focus) for a solid-fill look. */
    val reduceDynamicBlur = MutableStateFlow(false)

    /** Real backdrop-sampled glass (blur, lens refraction) on the floating nav bar, Android 12+ only. */
    val liquidGlass = MutableStateFlow(false)

    /** When true, uses the classic stacked mini player + bottom bar instead of the collapsible floating bar. */
    val classicNavBar = MutableStateFlow(false)

    /** When true, hides labels in the bottom navigation bar and displays only icons. */
    val hideNavigationBarLabels = MutableStateFlow(false)

    /** Replaces the bottom navigation tabs with a single popup-menu button. */
    val mainNavigationTabsPopup = MutableStateFlow(false)

    /** Main navigation destinations the user wants to keep in the bottom bar. */
    val visibleMainNavigationTabs = MutableStateFlow(MainNavigationTab.entries.toSet())
    val mainNavigationTabOrder = MutableStateFlow<List<MainNavigationTab>>(MainNavigationTab.entries)
    val startupTab = MutableStateFlow(MainNavigationTab.SONGS)

    /** System UI preferences; bars remain transiently revealable by swipe when hidden. */
    val showStatusBar = MutableStateFlow(true)
    /** Status bar visibility while the player is expanded over the library. */
    val showPlayerStatusBar = MutableStateFlow(true)
    val showNavigationBar = MutableStateFlow(true)
    /** Uses Android's user-confirmed screen pinning and exposes the player over the lock screen. */
    val musicOnlyDeviceMode = MutableStateFlow(false)
    val keepScreenOn = MutableStateFlow(false)
    /** Keeps the display awake while the full lyrics panel is open. */
    val keepScreenOnDuringLyrics = MutableStateFlow(true)
    val screenOrientationMode = MutableStateFlow(ScreenOrientationMode.SYSTEM)
    val favoriteUsesStar = MutableStateFlow(false)
    val playerQuickActions = MutableStateFlow<List<PlayerQuickAction>>(emptyList())
    /** Removes translucent circular discs behind quick action glyphs. */
    val hidePlayerQuickActionCircles = MutableStateFlow(false)
    val hiddenPlayerDetailsActions = MutableStateFlow<Set<PlayerDetailsAction>>(emptySet())
    /** The cutter is opt-in because it exports a re-encoded, edited copy of a track. */
    val audioCutterEnabled = MutableStateFlow(false)
    /** Player backdrop blur in dp; zero draws sampled colours sharply. */
    val artworkBackdropBlurDp = MutableStateFlow(32f)
    val persistentLocalArtwork = MutableStateFlow(false)

    /** Blurs unfocused lyric lines, keeping the active line sharp. */
    val lyricsBlur = MutableStateFlow(true)

    /** Hides generated playback/mood copy in the one-line lyrics strip. */
    val hideLyricsStatusText = MutableStateFlow(false)

    /** Hides the lyrics-panel note when lyrics were stored in a downloaded file. */
    val hideLyricsSavedMessage = MutableStateFlow(false)

    /** Hides the empty-result label in the lyrics panel and the one-line player strip. */
    val hideLyricsUnavailableLabel = MutableStateFlow(false)

    /** Hides the music-note glyph used to mark instrumental gaps in synced lyrics. */
    val hideLyricsGapNote = MutableStateFlow(false)

    /** Optional transport shortcuts shown in the lyrics header when its status pill is hidden. */
    val showLyricsPreviousControl = MutableStateFlow(false)
    val showLyricsPlayPauseControl = MutableStateFlow(false)
    val showLyricsNextControl = MutableStateFlow(false)
    /** Icon size for optional transport controls in the lyrics header, in dp. */
    val lyricsTransportControlSize = MutableStateFlow(22f)
    /** Centers optional transport controls in the lyrics header instead of aligning them to the close button. */
    val lyricsTransportControlsCentered = MutableStateFlow(false)

    /** Multiplier applied to the lyrics panel's existing synced/unsynced type sizes. */
    val lyricsFontScale = MutableStateFlow(1f)
    /** Shows Japanese readings written as `漢字((かんじ))` above their base text. */
    val showLyricsFurigana = MutableStateFlow(false)
    /** Shrinks one-line lyric text to fit the available width without truncation. */
    val autoFitOneLineLyrics = MutableStateFlow(false)

    val lyricsTextAlignment = MutableStateFlow(LyricsTextAlignment.CENTER)

    /** Whether a tap on the player artwork opens the full lyrics panel. */
    val artworkTapOpensLyrics = MutableStateFlow(false)

    /** Uses the artwork area as an expanded lyrics view while leaving playback controls visible. */
    val lyricsReplaceArtworkOnly = MutableStateFlow(false)

    /** Hides the compact song/artwork/actions header while the player lyrics view is open. */
    val hidePlayerHeaderDuringLyrics = MutableStateFlow(false)

    /** Presentation-only switch for the one-line lyric above the seek bar. */
    val showPlayerLyricsStrip = MutableStateFlow(true)

    /** Blocks app-managed network requests while keeping local and cached media available. */
    val offlineMode = MutableStateFlow(false)
    /** Last selected Radiko prefecture; kept in the shared settings backup. */
    val radioAreaId = MutableStateFlow("JP13")

    /** YouTube Music catalog/search/playback is opt-in; fresh installs make no YouTube requests. */
    val youtubeMusicEnabled = MutableStateFlow(false)

    /** Whether the search screen should start on YouTube Music instead of this device. */
    val youtubeMusicSearchDefault = MutableStateFlow(false)
    /** Include ordinary YouTube video uploads among YouTube Music search results. */
    val includeYoutubeMusicVideosInSearch = MutableStateFlow(true)

    /** Whether an app-managed YouTube Music request may leave this device. */
    val youtubeMusicNetworkAllowed: Boolean
        get() = youtubeMusicEnabled.value && !offlineMode.value

    /** Hides the confirmed lossless quality badge on the player, without affecting audio. */
    val hideLosslessLabel = MutableStateFlow(false)

    /** Shows the track's full duration instead of the remaining time on the player scrubber. */
    val showTrackDurationInsteadOfRemaining = MutableStateFlow(false)

    /** Shows externally opened audio in a small overlay above other apps. */
    val popupExternalAudioPlayer = MutableStateFlow(false)

    /** Applies ReplayGain metadata to decoded local PCM when enabled. */
    val replayGainEnabled = MutableStateFlow(false)
    val replayGainAlbumMode = MutableStateFlow(false)
    val replayGainPreampDb = MutableStateFlow(0f)
    val replayGainPreventClipping = MutableStateFlow(true)

    /** Adds a bounded, clipping-limited gain stage to decoded PCM. Disabled by default. */
    val volumeBoostEnabled = MutableStateFlow(false)
    val volumeBoostDb = MutableStateFlow(6f)

    /** Opacity of the background surface on the dedicated wide pill widget. */
    val pillWidgetOpacity = MutableStateFlow(65f)

    /** Normalizes YouTube-origin tracks from the loudness metadata already returned by StreamResolver. */
    val loudnessNormalization = MutableStateFlow(true)
    /** Skips loudness normalization while the phone's built-in speaker is the active output. */
    val loudnessOffOnSpeaker = MutableStateFlow(true)

    /** Places the favorite action beside the track menu instead of under playback controls. */
    val favoriteBesideTrackMenu = MutableStateFlow(false)

    /** Refuses a play command while Android's music stream volume is zero. */
    val preventPlayAtZeroVolume = MutableStateFlow(false)

    /** Pauses an already-playing local track when Android's music volume reaches zero. */
    val pausePlaybackAtZeroVolume = MutableStateFlow(false)

    /** Presentation-only visibility for the four secondary player controls. */
    val visiblePlayerControls = MutableStateFlow(DEFAULT_PLAYER_CONTROLS)
    val playerControlOrder = MutableStateFlow<List<PlayerControl>>(PlayerControl.entries)

    /** Uses the library glyph for the player shortcut that opens playlists. */
    val useLibraryIconForPlaylistControl = MutableStateFlow(false)
    val hidePlayerArtist = MutableStateFlow(false)
    val hideUnknownPlayerArtist = MutableStateFlow(false)
    val centerPlayerTrackInfo = MutableStateFlow(false)
    val playerDetailsVerticalMenu = MutableStateFlow(false)
    val preloadAlbumArtOnStartup = MutableStateFlow(false)

    /**
     * Plays a looping video behind the cover art on the player when one is
     * published for the track — Spotify's Canvas, Apple's motion artwork.
     *
     * Costs a video stream on top of the audio one and reaches three
     * services that have nothing to do with playback, so it stays a switch —
     * but it is the better default, and most tracks resolve to no canvas at
     * all. See [CanvasRepository][com.music.bitchord.data.canvas.CanvasRepository].
     */
    val animatedCanvas = MutableStateFlow(true)

    /**
     * Whether [animatedCanvas] is allowed to actually stream on a metered
     * connection, as distinct from the switch that turns the feature off
     * altogether.
     *
     * Off by default. A canvas clip loops for as long as its track plays,
     * and every loop past the first re-fetches the same few seconds of video
     * — see [CanvasCache][com.music.bitchord.data.canvas.CanvasCache] for why
     * that costs network at all rather than being answered from a buffer —
     * so a few-second clip behind a four-minute track on cellular is not a
     * flat video cost, it is that cost repeated dozens of times per song.
     * That is the shape of the reported 8GB day: still art costs nothing
     * here and stays up regardless of this setting.
     */
    val canvasOverCellular = MutableStateFlow(false)

    /**
     * Blows the player's cover art out to a full-bleed banner running off the
     * top of the screen, rather than sitting it in a square card.
     *
     * The treatment motion artwork has always had, applied to still sleeves too.
     * Off restores the card: the sleeve keeps its corners, its shadow and its
     * shrink-while-paused, and only a clip goes full-bleed. Phones only either
     * way — see the hero notes in
     * [NowPlayingScreen][com.music.bitchord.ui.player.NowPlayingScreen].
     */
    val fullBleedArtwork = MutableStateFlow(true)

    /** Preserves non-square still-cover proportions when the full-bleed treatment is off. */
    val keepOriginalArtworkAspectRatio = MutableStateFlow(false)

    /** Shows album and playlist artwork without cropping it into a square tile. */
    val keepCollectionArtworkAspectRatio = MutableStateFlow(false)

    /** Uses the active language for the Library's collection count label. */
    val localizeLibraryCollectionCount = MutableStateFlow(true)

    /** Fits preserved non-square covers into square mini-player and lyrics transition frames. */
    val preserveArtworkShapeInCompactTransitions = MutableStateFlow(false)

    /** Keeps the sleeve at its playing size while playback is paused. */
    val keepArtworkFullSizeWhenPaused = MutableStateFlow(false)

    /** Double-tapping the left or right side of the album art seeks 5 seconds backward or forward. */
    val doubleTapToSeek = MutableStateFlow(true)
    /** User-selected seek interval shared by double-tap and optional transport buttons. */
    val seekIntervalSeconds = MutableStateFlow(5)
    /** Minimum audible playback before a track enters recent/play-count history. */
    val recentlyPlayedThresholdSeconds = MutableStateFlow(DEFAULT_RECENT_PLAY_THRESHOLD_SECONDS)
    /** Optional seek-back/forward buttons beside previous/next. */
    val seekButtonMode = MutableStateFlow(SeekButtonMode.LONG_TRACKS_ONLY)
    /** Replaces the short seek button on either side of the transport when selected. */
    val transportLeftAction = MutableStateFlow(TransportSideAction.NONE)
    val transportRightAction = MutableStateFlow(TransportSideAction.NONE)
    /** Hides the numeric seconds caption beneath optional seek buttons. */
    val hideSeekButtonLabels = MutableStateFlow(false)

    /**
     * Puts v1.5's backdrop back on the player: four quantised blobs drifting
     * behind the whole screen, rather than the artwork's own colours hung off
     * the sleeve's bottom edge.
     *
     * Off by default, because the current backdrop replaced it for two reasons
     * that have not gone away — see [ArtworkMesh][com.music.bitchord.ui.player.ArtworkMesh]
     * for the colour one (a cover that is nine-tenths black with a red stripe
     * comes back from the quantiser as a red screen) and
     * [ArtworkMeshBackdrop][com.music.bitchord.ui.player.ArtworkMeshBackdrop]
     * for the cost one (blobs that drift are a full-screen blur redrawn while
     * they move, where a mesh is drawn once per track and then composited).
     * Kept as a switch because people asked for the old look back, and neither
     * reason is one a listener has to agree with.
     */
    val legacyMeshGradient = MutableStateFlow(false)

    /**
     * Time-synced lyrics on the player, lit up as they are sung.
     *
     * On by default — it is most of the point of the player screen — but it
     * reaches third-party lyric databases for every track played, so it stays
     * a switch, and [lyricsSources] narrows which of them get asked.
     */
    val syncedLyrics = MutableStateFlow(true)

    /** The databases [syncedLyrics] may ask. Empty is the same as off. Populated dynamically from extensions. */
    val lyricsSources = MutableStateFlow<Set<LyricsSource>>(emptySet())

    /** Optional PaxSenix credential, mirrored only from the encrypted AuthStore. */
    val paxSenixApiKey = MutableStateFlow("")

    /**
     * The order [lyricsSources] are asked in.
     * Reordered from Settings, populated dynamically from extensions.
     */
    val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(emptyList())

    /**
     * Off, the highest-priority source to answer at all is taken as the
     * lyrics, word-synced or not. On, a merely line-synced answer is held as
     * a fallback while the rest of [lyricsSourceOrder] is still checked for a
     * word-synced one — worth the extra network calls to some, not to others,
     * which is why it defaults off rather than being how [LyricsRepository]
     * always behaved.
     */
    val prioritizeSyllableSync = MutableStateFlow(false)

    /** When enabled, shows lyrics fetching and Genius scraping logs in the lyrics menu/panel. */
    val showLyricsLogs = MutableStateFlow(false)

    /** When enabled, automatically writes online lyrics into local music files when played. */
    val autoEmbedLyrics = MutableStateFlow(true)

    /** Configurable repository URL for remote lyrics extensions (like SpotiFLAC). */
    val lyricsExtensionRepoUrl = MutableStateFlow(DEFAULT_LYRICS_EXTENSION_REPO_URL)

    /** Dynamic lyrics scripts are opt-in because they can make network requests and are third-party code. */
    val lyricsExtensionsEnabled = MutableStateFlow(false)

    /** When enabled, automatically syncs lyrics extensions from the remote repository. */
    val lyricsAutoUpdate = MutableStateFlow(false)

    /** Timestamp of the last successful lyrics extensions sync. */
    val lastLyricsExtensionSyncMs = MutableStateFlow(0L)

    /** Disk budget for cached audio. [AudioCache][com.music.bitchord.playback.AudioCache] evicts past it. */
    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    // ── Replay ──────────────────────────────────────────────────────────────

    /**
     * Whether Replay may work out a genre chart.
     *
     * Its own switch because it is the one part of Replay that isn't purely
     * local: everything else on that page is counted on this device and never
     * leaves it, while a genre has to be looked up by artist name — see
     * [ArtistFacts][com.music.bitchord.data.stats.ArtistFacts]. On by default,
     * since it sends a name and nothing else and the answer is what makes a
     * quarter of the page exist; off, the genre chart simply isn't drawn.
     */
    val replayGenres = MutableStateFlow(true)

    // ── Library ─────────────────────────────────────────────────────────────

    /** Hides short clips, recorder output and non-music formats from Local Music. */
    val filterNonMusicAudio = MutableStateFlow(true)
    /** Includes configured WebDAV/Jellyfin/OpenSubsonic libraries in local collection views. */
    val includeRemoteSourceMediaInLibrary = MutableStateFlow(false)

    val localMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val downloadedMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localArtistSort = MutableStateFlow(ArtistSort.MOST_SONGS)
    val downloadedArtistSort = MutableStateFlow(ArtistSort.MOST_SONGS)
    val localSongsViewType = MutableStateFlow(LibraryViewType.LIST)
    val localAlbumsViewType = MutableStateFlow(LibraryViewType.GRID)
    val localArtistsViewType = MutableStateFlow(LibraryViewType.GRID)
    val visibleLocalLibraryTabs = MutableStateFlow(LocalLibraryTab.entries.toSet())
    val localLibraryTabOrder = MutableStateFlow<List<LocalLibraryTab>>(LocalLibraryTab.entries.toList())
    val localFolderHierarchy = MutableStateFlow(false)
    val fullScreenCollectionArtwork = MutableStateFlow(false)
    /** Hides collection page titles while keeping floating back/settings actions available. */
    val hideCollectionTopBar = MutableStateFlow(true)
    /** Hides floating back/settings controls only on album or playlist artwork pages. */
    val hideFloatingCollectionControls = MutableStateFlow(true)
    val artistsOpenAlbums = MutableStateFlow(false)
    val shuffleArtistAlbums = MutableStateFlow(false)
    /** Deezer lookup for album-artist portraits; off keeps album artists local by default. */
    val onlineAlbumArtistArtwork = MutableStateFlow(false)
    val localMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    val downloadedSongsViewType = MutableStateFlow(LibraryViewType.LIST)
    val downloadedAlbumsViewType = MutableStateFlow(LibraryViewType.GRID)
    val downloadedArtistsViewType = MutableStateFlow(LibraryViewType.GRID)
    val downloadedMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    val libraryPlaylistsViewType = MutableStateFlow(LibraryViewType.GRID)
    val librarySongsViewType = MutableStateFlow(LibraryViewType.LIST)
    /** Show each track's cover in a playlist detail list instead of its number. */
    val showPlaylistSongArtwork = MutableStateFlow(false)
    /** Show track covers in album, artist, and album-artist detail lists instead of ordinal numbers. */
    val showCollectionSongArtwork = MutableStateFlow(true)
    val localDrillDownSongsViewType = MutableStateFlow(LibraryViewType.LIST)
    val downloadedDrillDownSongsViewType = MutableStateFlow(LibraryViewType.LIST)
    val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    /**
     * Each album/playlist page's track-list order, keyed by browse id —
     * Spotify-style, every page keeps its own. A page never touched reads as
     * [SongSort.DEFAULT].
     */
    val detailSongSorts = MutableStateFlow<Map<String, SongSort>>(emptyMap())

    /** Empty means every MediaStore folder; otherwise this is a persisted SAF tree URI. */
    val localMusicFolderUri = MutableStateFlow("")

    /** Remote file-library connection details. Credentials are Keystore-backed. */
    val webdavUrl = MutableStateFlow("")
    val webdavUsername = MutableStateFlow("")
    val webdavPassword = MutableStateFlow("")
    val webdavAllowInsecureHttp = MutableStateFlow(false)
    val smbHost = MutableStateFlow("")
    val smbShare = MutableStateFlow("")
    val smbBasePath = MutableStateFlow("")
    val smbUsername = MutableStateFlow("")
    val smbPassword = MutableStateFlow("")

    val secureRemoteCredentialsAvailable: Boolean
        get() = this::authStore.isInitialized && authStore.secureStorageAvailable

    /** Normalized paths of folders blacklisted/excluded from local music scanning. */
    val blacklistedFolders = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Browse ids of the playlists pinned to the top of the Library tab, in the
     * order they were pinned.
     *
     * A [List] rather than a [Set]: pin order is part of what a pin means here —
     * the whole point is a small, hand-picked front row, and a set would leave
     * that order to hash iteration. Capped at [MAX_PINNED_PLAYLISTS] by
     * [togglePinnedPlaylist], the only way this is ever written.
     */
    val pinnedPlaylists = MutableStateFlow<List<String>>(emptyList())

    /** How many playlists [pinnedPlaylists] can hold at once. */
    const val MAX_PINNED_PLAYLISTS = 5

    // ── Scrobbling ──────────────────────────────────────────────────────

    /** One release gate shared by the settings UI and the playback service. */
    val scrobblingAvailable = true

    val lastfmEnabled = MutableStateFlow(false)
    val lastfmUsername = MutableStateFlow("")
    val lastfmSessionKey = MutableStateFlow("")
    val lastfmApiKey = MutableStateFlow("")
    val lastfmSecret = MutableStateFlow("")
    val lastfmEndpoint = MutableStateFlow("")
    val lastfmScrobbleEnabled = MutableStateFlow(false)
    val lastfmNowPlaying = MutableStateFlow(false)
    val lastfmPrimaryArtistOnly = MutableStateFlow(false)
    val scrobbleMinDuration = MutableStateFlow(30)
    val scrobbleDelayPercent = MutableStateFlow(0.5f)
    val scrobbleDelaySeconds = MutableStateFlow(180)
    val listenBrainzEnabled = MutableStateFlow(false)
    val listenBrainzToken = MutableStateFlow("")
    val listenBrainzPrimaryArtistOnly = MutableStateFlow(false)
    val spotifySpdcToken = MutableStateFlow("")

    // ── Discord Rich Presence ───────────────────────────────────────────

    /**
     * The connected Discord account's token, mirrored out of [AuthStore] so
     * [PlaybackService][com.music.bitchord.playback.PlaybackService] can pick
     * up a login without polling for one. Empty means not connected.
     *
     * Only the mirror is here — the persisted copy is encrypted, because unlike
     * a scrobbler key this one is the account itself.
     */
    val discordToken = MutableStateFlow("")

    /**
     * Who the token belongs to, cached at login. Kept so the settings screen
     * can show the account without a round trip every time it opens, and can
     * still show it offline.
     */
    val discordUsername = MutableStateFlow("")
    val discordName = MutableStateFlow("")
    val discordAvatar = MutableStateFlow("")

    val discordRpcEnabled = MutableStateFlow(true)

    /** Put the track title on the bold profile line, in place of the artist. */
    val discordUseDetails = MutableStateFlow(false)

    /** Reveals the presence-shape controls: status, activity type/name, buttons. */
    val discordAdvancedMode = MutableStateFlow(false)

    val discordStatus = MutableStateFlow("online")
    val discordActivityType = MutableStateFlow("listening")

    /** Overrides the "Listening to ___" line; empty means the app's own name. */
    val discordActivityName = MutableStateFlow("")

    val discordButton1Text = MutableStateFlow("")
    val discordButton1Visible = MutableStateFlow(true)
    val discordButton2Text = MutableStateFlow("")
    val discordButton2Visible = MutableStateFlow(true)

    /** The notice about what connecting an account actually does has been read. */
    val discordInfoDismissed = MutableStateFlow(false)

    /** Whether All Files Access permission has already been prompted to the user on first launch. */
    val allFilesPermissionAsked = MutableStateFlow(false)

    /** Published by PlaybackService so the UI can open the system equalizer. */
    val audioSessionId = MutableStateFlow(0)


    /** The ceiling that applies to a stream started right now. */
    val effectiveAudioQuality: AudioQuality
        get() {
            val chosen = if (meteredConnection.value == true) {
                audioQualityCellular.value
            } else {
                audioQualityWifi.value
            }
            val cap = streamCeilingOverride.value ?: return chosen
            return if (chosen.ordinal > cap.ordinal) cap else chosen
        }

    /**
     * Whether a download may start on the connection in hand.
     *
     * A null [meteredConnection] means there is no active network, and that is
     * deliberately allowed through: a download with nothing to download over
     * fails on the network and says so, which is true, where refusing it here
     * would blame a Wi-Fi setting for an outage.
     */
    val downloadsAllowedNow: Boolean
        get() = !wifiOnlyDownloads.value || meteredConnection.value != true

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        authStore = AuthStore(appContext)
        migratePlaintextCredentials()
        readAll()
        watchConnection(context)
    }

    /** Moves legacy account tokens into Keystore-backed storage, then removes the plaintext copies. */
    private fun migratePlaintextCredentials() {
        val credentialKeys = listOf(
            KEY_LASTFM_SESSION_KEY,
            KEY_LASTFM_API_KEY,
            KEY_LASTFM_SECRET,
            KEY_LISTENBRAINZ_TOKEN,
            KEY_SPOTIFY_SPDC_TOKEN,
            KEY_WEBDAV_USERNAME,
            KEY_SMB_USERNAME,
        )
        val editor = prefs.edit()
        credentialKeys.forEach { key ->
            val legacyValue = prefs.all[key] as? String
            if (!legacyValue.isNullOrEmpty() && authStore.getProtectedValue(key) == null) {
                // A failed encrypted write deliberately loses the old value;
                // retaining a plaintext credential would defeat the migration.
                authStore.migrateProtectedValue(key, legacyValue)
            }
            editor.remove(key)
        }
        if (!runCatching { editor.commit() }.getOrDefault(false)) {
            android.util.Log.w("BitChord", "Could not clear legacy plaintext credential preferences")
        }
    }

    /**
     * Re-reads every setting off disk.
     *
     * The one caller is an import ([Backup][com.music.bitchord.data.stats.Backup]),
     * which writes the whole preference file underneath these flows. Nothing
     * else in the app changes a preference without going through the setter
     * beside it, so nothing else has a reason to ask.
     *
     * Deliberately not re-registering the network callback: that watches the
     * device, not the preferences, and a second one would have both firing.
     */
    fun reload() {
        if (!this::prefs.isInitialized) return
        readAll()
        YoutubeStreamBootstrap.initializeIfAllowed(appContext)
        com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager
            .setEnabled(lyricsExtensionsEnabled.value)
        // The selected lyrics providers are persisted as IDs, while the
        // available provider objects are loaded asynchronously by the
        // extension manager. Re-apply imported IDs to the already-loaded list
        // now; if that list is still empty, the manager will refresh them once
        // loading completes.
        val availableLyricsSources = lyricsSourceOrder.value.ifEmpty { lyricsSources.value.toList() }
        if (availableLyricsSources.isNotEmpty()) refreshLyricsSources(availableLyricsSources)
    }

    private fun readAll() {
        migrateSingleQuality()
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        migrateDownloadQuality()
        downloadQuality.value = readDownloadQuality()
        wifiOnlyDownloads.value = prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        exportDownloads.value = prefs.getBoolean(KEY_EXPORT_DOWNLOADS, false)
        cdRippingOutputTreeUri.value = prefs.getString(KEY_CD_RIPPING_OUTPUT_TREE_URI, null)
        cdRippingRetries.value = prefs.getInt(KEY_CD_RIPPING_RETRIES, 2).coerceIn(0, 5)
        cdRippingSpeedX.value = prefs.getInt(KEY_CD_RIPPING_SPEED_X, 4).coerceIn(1, 12)
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE_SECONDS, 0).coerceIn(0, MAX_CROSSFADE_SECONDS)
        smartFadeEnabled.value = prefs.getBoolean(KEY_SMART_FADE, false)
        // Older backups can contain both. Keep the requested fade mode and
        // clear the incompatible offload switch before the player is rebuilt.
        val savedAudioOffload = prefs.getBoolean(KEY_AUDIO_OFFLOAD_ENABLED, false)
        audioOffloadEnabled.value = savedAudioOffload && crossfadeSeconds.value == 0 && !smartFadeEnabled.value
        if (savedAudioOffload && !audioOffloadEnabled.value) {
            prefs.edit().putBoolean(KEY_AUDIO_OFFLOAD_ENABLED, false).apply()
        }
        automixPerformanceMode.value = runCatching {
            AutomixPerformanceMode.valueOf(
                prefs.getString(KEY_AUTOMIX_PERFORMANCE_MODE, null) ?: AutomixPerformanceMode.BALANCED.name,
            )
        }.getOrDefault(AutomixPerformanceMode.BALANCED)
        resamplerCutoffHz.value = normalizeResamplerCutoffHz(prefs.getInt(KEY_RESAMPLER_CUTOFF_HZ, 0))
        outputPcmMode.value = runCatching {
            OutputPcmMode.valueOf(
                prefs.getString(KEY_OUTPUT_PCM_MODE, OutputPcmMode.PCM_16.name)
                    ?: OutputPcmMode.PCM_16.name,
            )
        }.getOrDefault(OutputPcmMode.PCM_16)
        bitPerfectPlayback.value = prefs.getBoolean(KEY_BIT_PERFECT_PLAYBACK, false)
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        speakerPlaybackAllowed.value = prefs.getBoolean(KEY_SPEAKER_PLAYBACK_ALLOWED, true)
        dolbyAtmos.value = prefs.getBoolean(KEY_DOLBY_ATMOS, false)
        diracAudioEnabled.value = prefs.getBoolean(KEY_DIRAC_AUDIO_ENABLED, false)
        diracAudioPreset.value = prefs.getInt(KEY_DIRAC_AUDIO_PRESET, 0).coerceIn(0, 2)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, false)
        val legacyPeqEnabled = prefs.getBoolean(KEY_PARAMETRIC_EQ_ENABLED, false)
        tpdfDitherEnabled.value = prefs.getBoolean(KEY_TPDF_DITHER_ENABLED, false)
        val legacyPeqBands = ParametricEqBand.decode(prefs.getString(KEY_PARAMETRIC_EQ_BANDS, null))
        val savedPeqConfig = ParametricEqConfig.fromJsonString(prefs.getString(KEY_PARAMETRIC_EQ_CONFIG_JSON, null))
            ?: ParametricEqConfig(
                enabled = legacyPeqEnabled,
                swapChannels = prefs.getBoolean(KEY_CHANNEL_SWAP_ENABLED, false),
                globalBands = legacyPeqBands,
            )
        parametricEqConfig.value = savedPeqConfig
        parametricEqEnabled.value = savedPeqConfig.enabled
        parametricEqBands.value = savedPeqConfig.globalBands
        channelSwapEnabled.value = savedPeqConfig.swapChannels
        parametricEqPresets.value = ParametricEqPreset.decodeList(
            prefs.getString(KEY_PARAMETRIC_EQ_PRESETS_JSON, null),
        )
        // One-time preset seed is bundled as data only; EEL code is never executed.
        if (!prefs.getBoolean(KEY_PARAMETRIC_EQ_SEEDED_SAMPLE, false) && this::appContext.isInitialized) {
            runCatching {
                val sampleText = appContext.assets.open(BUNDLED_EEL_ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
                val sample = EelEqParser.parse(sampleText, BUNDLED_EEL_ASSET_PATH.substringAfterLast('/')).preset
                val existing = parametricEqPresets.value
                val seeded = if (existing.any { it.name == sample.name }) existing else listOf(sample) + existing
                parametricEqPresets.value = seeded
                prefs.edit()
                    .putString(KEY_PARAMETRIC_EQ_PRESETS_JSON, ParametricEqPreset.encodeList(seeded))
                    .putBoolean(KEY_PARAMETRIC_EQ_SEEDED_SAMPLE, true)
                    .apply()
            }.onFailure {
                // A missing or malformed optional sample must never block startup.
                prefs.edit().putBoolean(KEY_PARAMETRIC_EQ_SEEDED_SAMPLE, true).apply()
            }
        }
        equalizerBalance.value = prefs.getFloat(KEY_EQUALIZER_BALANCE, 0f).coerceIn(-1f, 1f)
        reverbEnabled.value = prefs.getBoolean(KEY_REVERB_ENABLED, false)
        reverbAmount.value = prefs.getInt(KEY_REVERB_AMOUNT, 35).coerceIn(0, 100)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(0.1f, 10.0f)
        playbackPitch.value = prefs.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
        val savedThemeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "DARK")
        }.getOrDefault(ThemeMode.DARK)
        // Older builds stored artwork tint as a separate toggle. Upgrade that
        // choice into the matching theme mode without changing other themes.
        themeMode.value = if (
            savedThemeMode == ThemeMode.ARTWORK || prefs.getBoolean(KEY_THEME_MATCHES_ARTWORK, false)
        ) ThemeMode.ARTWORK else savedThemeMode
        customThemeImageUri.value = prefs.getString(KEY_CUSTOM_THEME_IMAGE_URI, null)
        matchButtonAccentToArtwork.value = prefs.getBoolean(KEY_MATCH_BUTTON_ACCENT_TO_ARTWORK, false)
        miniPlayerArtworkColor.value = prefs.getBoolean(KEY_MINI_PLAYER_ARTWORK_COLOR, false)
        navigationBarMatchesMiniPlayerColor.value = prefs.getBoolean(KEY_NAVIGATION_BAR_MATCHES_MINI_PLAYER_COLOR, false)
        miniPlayerArtworkOutline.value = prefs.getBoolean(KEY_MINI_PLAYER_ARTWORK_OUTLINE, false)
        miniPlayerPreviousButton.value = prefs.getBoolean(KEY_MINI_PLAYER_PREVIOUS_BUTTON, true)
        miniPlayerSwipeChangesTrack.value = prefs.getBoolean(KEY_MINI_PLAYER_SWIPE_CHANGES_TRACK, false)
        miniPlayerSwipeUpExpands.value = prefs.getBoolean(KEY_MINI_PLAYER_SWIPE_UP_EXPANDS, true)
        playlistTrackMenuUsesArtworkColor.value = prefs.getBoolean(KEY_PLAYLIST_TRACK_MENU_ARTWORK_COLOR, true)
        launcherIcon.value = runCatching {
            LauncherIcon.valueOf(prefs.getString(KEY_LAUNCHER_ICON, LauncherIcon.PINK.name) ?: LauncherIcon.PINK.name)
        }.getOrDefault(LauncherIcon.PINK)
        frostedQueueManager.value = prefs.getBoolean(KEY_FROSTED_QUEUE_MANAGER, true)
        frostedPlayerDetails.value = prefs.getBoolean(KEY_FROSTED_PLAYER_DETAILS, true)
        detailsSheetArtworkBackdrop.value = prefs.getBoolean(KEY_DETAILS_SHEET_ARTWORK_BACKDROP, true)
        showEmbeddedComment.value = prefs.getBoolean(KEY_SHOW_EMBEDDED_COMMENT, true)
        queueManagerEqualsIcon.value = prefs.getBoolean(KEY_QUEUE_MANAGER_EQUALS_ICON, false)
        redesignedSavedQueueManager.value = prefs.getBoolean(KEY_REDESIGNED_SAVED_QUEUE_MANAGER, true)
        hideSavedQueueHeading.value = prefs.getBoolean(KEY_HIDE_SAVED_QUEUE_HEADING, true)
        hideQueueLabel.value = prefs.getBoolean(KEY_HIDE_QUEUE_LABEL, true)
        queueClearTrashIcon.value = prefs.getBoolean(KEY_QUEUE_CLEAR_TRASH_ICON, false)
        hidePlaybackTuningTitle.value = prefs.getBoolean(KEY_HIDE_PLAYBACK_TUNING_TITLE, true)
        playbackTuningThinSliders.value = prefs.getBoolean(KEY_PLAYBACK_TUNING_THIN_SLIDERS, true)
        autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        shuffleEnabled.value = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        audioFocusLevel.value = prefs.getInt(KEY_AUDIO_FOCUS_LEVEL, 1).coerceIn(0, 4)
        repeatMode.value = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        restartQueueWhenEnded.value = prefs.getBoolean(KEY_RESTART_QUEUE_WHEN_ENDED, false)
        advanceQueueWhenRepeatOff.value = prefs.getBoolean(KEY_ADVANCE_QUEUE_WHEN_REPEAT_OFF, true)
        mediaControlFavoriteUsesStar.value = prefs.getBoolean(KEY_MEDIA_CONTROL_FAVORITE_USES_STAR, false)
        mediaControlSecondaryAction.value = runCatching {
            MediaControlSecondaryAction.valueOf(
                prefs.getString(KEY_MEDIA_CONTROL_SECONDARY_ACTION, null)
                    ?: MediaControlSecondaryAction.SHUFFLE.name,
            )
        }.getOrDefault(MediaControlSecondaryAction.SHUFFLE)
        hideMediaControlCustomActions.value = prefs.getBoolean(KEY_HIDE_MEDIA_CONTROL_CUSTOM_ACTIONS, false)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        playerArtworkTransition.value = prefs.getBoolean(KEY_PLAYER_ARTWORK_TRANSITION, true)
        walkmanPlayerStyle.value = prefs.getBoolean(KEY_WALKMAN_PLAYER_STYLE, false)
        pillMotionStyle.value = runCatching {
            PillMotionStyle.valueOf(prefs.getString(KEY_PILL_MOTION_STYLE, null) ?: PillMotionStyle.DEFAULT.name)
        }.getOrDefault(PillMotionStyle.DEFAULT)
        highPerformanceMode.value = prefs.getBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        performanceRefreshRate.value = normalizePerformanceRefreshRate(
            prefs.getInt(KEY_PERFORMANCE_REFRESH_RATE, DEFAULT_PERFORMANCE_REFRESH_RATE),
        )
        stopOnTaskRemoved.value = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, false)
        hideVolumeBar.value = prefs.getBoolean(KEY_HIDE_VOLUME_BAR, false)
        hideRadioPlaybackBar.value = prefs.getBoolean(KEY_HIDE_RADIO_PLAYBACK_BAR, true)
        playerSeekBarThicknessDp.value = prefs.getInt(KEY_PLAYER_SEEK_BAR_THICKNESS_DP, 7).coerceIn(2, 14)
        playerVolumeBarThicknessDp.value = prefs.getInt(KEY_PLAYER_VOLUME_BAR_THICKNESS_DP, 6).coerceIn(2, 14)
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        dontRepeatSuggestions.value = prefs.getBoolean(KEY_DONT_REPEAT_SUGGESTIONS, false)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        liquidGlass.value = prefs.getBoolean(KEY_LIQUID_GLASS, false)
        classicNavBar.value = prefs.getBoolean(KEY_CLASSIC_NAV_BAR, false)
        hideNavigationBarLabels.value = prefs.getBoolean(KEY_HIDE_NAVIGATION_BAR_LABELS, false)
        mainNavigationTabsPopup.value = prefs.getBoolean(KEY_MAIN_NAVIGATION_TABS_POPUP, false)
        visibleLocalLibraryTabs.value = readVisibleLocalLibraryTabs()
        localLibraryTabOrder.value = readLocalLibraryTabOrder()
        visibleMainNavigationTabs.value = readVisibleMainNavigationTabs()
        mainNavigationTabOrder.value = readMainNavigationTabOrder()
        startupTab.value = readStartupTab()
        showStatusBar.value = prefs.getBoolean(KEY_SHOW_STATUS_BAR, true)
        showPlayerStatusBar.value = if (prefs.contains(KEY_SHOW_PLAYER_STATUS_BAR)) {
            prefs.getBoolean(KEY_SHOW_PLAYER_STATUS_BAR, true)
        } else {
            // Preserve the old single-toggle choice on upgrade, then let the
            // newly split player and library choices diverge independently.
            showStatusBar.value.also { migratedValue ->
                prefs.edit().putBoolean(KEY_SHOW_PLAYER_STATUS_BAR, migratedValue).apply()
            }
        }
        showNavigationBar.value = prefs.getBoolean(KEY_SHOW_NAVIGATION_BAR, true)
        musicOnlyDeviceMode.value = prefs.getBoolean(KEY_MUSIC_ONLY_DEVICE_MODE, false)
        keepScreenOn.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        keepScreenOnDuringLyrics.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON_DURING_LYRICS, true)
        screenOrientationMode.value = runCatching {
            ScreenOrientationMode.valueOf(prefs.getString(KEY_SCREEN_ORIENTATION, ScreenOrientationMode.SYSTEM.name) ?: ScreenOrientationMode.SYSTEM.name)
        }.getOrDefault(ScreenOrientationMode.SYSTEM)
        favoriteUsesStar.value = prefs.getBoolean(KEY_FAVORITE_USES_STAR, false)
        playerQuickActions.value = readPlayerQuickActions()
        hidePlayerQuickActionCircles.value = prefs.getBoolean(KEY_HIDE_PLAYER_QUICK_ACTION_CIRCLES, false)
        hiddenPlayerDetailsActions.value = readHiddenPlayerDetailsActions()
        audioCutterEnabled.value = prefs.getBoolean(KEY_AUDIO_CUTTER_ENABLED, false)
        artworkBackdropBlurDp.value = prefs.getFloat(KEY_ARTWORK_BACKDROP_BLUR_DP, 32f)
            .coerceIn(0f, MAX_PLAYER_ARTWORK_BLUR_DP)
        persistentLocalArtwork.value = prefs.getBoolean(KEY_PERSISTENT_LOCAL_ARTWORK, false)
        lyricsBlur.value = prefs.getBoolean(KEY_LYRICS_BLUR, true)
        hideLyricsStatusText.value = prefs.getBoolean(KEY_HIDE_LYRICS_STATUS_TEXT, false)
        hideLyricsSavedMessage.value = prefs.getBoolean(KEY_HIDE_LYRICS_SAVED_MESSAGE, false)
        hideLyricsUnavailableLabel.value = prefs.getBoolean(KEY_HIDE_LYRICS_UNAVAILABLE_LABEL, false)
        hideLyricsGapNote.value = prefs.getBoolean(KEY_HIDE_LYRICS_GAP_NOTE, false)
        showLyricsPreviousControl.value = prefs.getBoolean(KEY_SHOW_LYRICS_PREVIOUS_CONTROL, false)
        showLyricsPlayPauseControl.value = prefs.getBoolean(KEY_SHOW_LYRICS_PLAY_PAUSE_CONTROL, false)
        showLyricsNextControl.value = prefs.getBoolean(KEY_SHOW_LYRICS_NEXT_CONTROL, false)
        lyricsTransportControlSize.value = prefs.getFloat(KEY_LYRICS_TRANSPORT_CONTROL_SIZE, 22f)
            .coerceIn(14f, MAX_LYRICS_TRANSPORT_CONTROL_SIZE)
        lyricsTransportControlsCentered.value = prefs.getBoolean(KEY_LYRICS_TRANSPORT_CONTROLS_CENTERED, false)
        lyricsFontScale.value = prefs.getFloat(KEY_LYRICS_FONT_SCALE, 1f).coerceIn(0.6f, 1.6f)
        lyricsTextAlignment.value = runCatching {
            LyricsTextAlignment.valueOf(
                prefs.getString(KEY_LYRICS_TEXT_ALIGNMENT, LyricsTextAlignment.CENTER.name)
                    ?: LyricsTextAlignment.CENTER.name,
            )
        }.getOrDefault(LyricsTextAlignment.CENTER)
        artworkTapOpensLyrics.value = prefs.getBoolean(KEY_ARTWORK_TAP_OPENS_LYRICS, false)
        lyricsReplaceArtworkOnly.value = prefs.getBoolean(KEY_LYRICS_REPLACE_ARTWORK_ONLY, false)
        hidePlayerHeaderDuringLyrics.value = prefs.getBoolean(KEY_HIDE_PLAYER_HEADER_DURING_LYRICS, false)
        showPlayerLyricsStrip.value = prefs.getBoolean(KEY_SHOW_PLAYER_LYRICS_STRIP, true)
        val wasOffline = offlineMode.value
        offlineMode.value = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        radioAreaId.value = prefs.getString(KEY_RADIO_AREA_ID, "JP13")
            ?.takeIf { it.matches(Regex("JP\\d{1,2}")) }
            ?: "JP13"
        val wasYoutubeMusicEnabled = youtubeMusicEnabled.value
        youtubeMusicEnabled.value = prefs.getBoolean(KEY_YOUTUBE_MUSIC_ENABLED, false)
        youtubeMusicSearchDefault.value = prefs.getBoolean(KEY_YOUTUBE_MUSIC_SEARCH_DEFAULT, false)
        includeYoutubeMusicVideosInSearch.value = prefs.getBoolean(KEY_INCLUDE_YOUTUBE_MUSIC_VIDEOS_IN_SEARCH, true)
        if (wasYoutubeMusicEnabled && !youtubeMusicEnabled.value) Http.cancelYoutubeMusicRequests()
        if (!wasOffline && offlineMode.value) Http.cancelAllNetworkRequests()
        hideLosslessLabel.value = prefs.getBoolean(KEY_HIDE_LOSSLESS_LABEL, false)
        showTrackDurationInsteadOfRemaining.value =
            prefs.getBoolean(KEY_SHOW_TRACK_DURATION_INSTEAD_OF_REMAINING, false)
        popupExternalAudioPlayer.value = prefs.getBoolean(KEY_POPUP_EXTERNAL_AUDIO_PLAYER, false)
        replayGainEnabled.value = prefs.getBoolean(KEY_REPLAYGAIN_ENABLED, false)
        replayGainAlbumMode.value = prefs.getBoolean(KEY_REPLAYGAIN_ALBUM_MODE, false)
        replayGainPreampDb.value = prefs.getFloat(KEY_REPLAYGAIN_PREAMP_DB, 0f).coerceIn(-12f, 12f)
        replayGainPreventClipping.value = prefs.getBoolean(KEY_REPLAYGAIN_PREVENT_CLIPPING, true)
        volumeBoostEnabled.value = prefs.getBoolean(KEY_VOLUME_BOOST_ENABLED, false)
        volumeBoostDb.value = prefs.getFloat(KEY_VOLUME_BOOST_DB, 6f).coerceIn(0f, 12f)
        pillWidgetOpacity.value = prefs.getFloat(KEY_PILL_WIDGET_OPACITY, 65f).coerceIn(0f, 100f)
        loudnessNormalization.value = prefs.getBoolean(KEY_LOUDNESS_NORMALIZATION, true)
        loudnessOffOnSpeaker.value = prefs.getBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, true)
        favoriteBesideTrackMenu.value = prefs.getBoolean(KEY_FAVORITE_BESIDE_TRACK_MENU, false)
        preventPlayAtZeroVolume.value = prefs.getBoolean(KEY_PREVENT_PLAY_AT_ZERO_VOLUME, false)
        pausePlaybackAtZeroVolume.value = prefs.getBoolean(KEY_PAUSE_PLAYBACK_AT_ZERO_VOLUME, false)
        visiblePlayerControls.value = readVisiblePlayerControls()
        playerControlOrder.value = readPlayerControlOrder()
        useLibraryIconForPlaylistControl.value = prefs.getBoolean(KEY_LIBRARY_ICON_FOR_PLAYLIST_CONTROL, false)
        hidePlayerArtist.value = prefs.getBoolean(KEY_HIDE_PLAYER_ARTIST, false)
        hideUnknownPlayerArtist.value = prefs.getBoolean(KEY_HIDE_UNKNOWN_PLAYER_ARTIST, false)
        centerPlayerTrackInfo.value = prefs.getBoolean(KEY_CENTER_PLAYER_TRACK_INFO, false)
        playerDetailsVerticalMenu.value = prefs.getBoolean(KEY_PLAYER_DETAILS_VERTICAL_MENU, false)
        preloadAlbumArtOnStartup.value = prefs.getBoolean(KEY_PRELOAD_ALBUM_ART_ON_STARTUP, false)
        if (preloadAlbumArtOnStartup.value && !persistentLocalArtwork.value) {
            persistentLocalArtwork.value = true
            prefs.edit().putBoolean(KEY_PERSISTENT_LOCAL_ARTWORK, true).apply()
        }
        if (highPerformanceMode.value) reduceAnimation.value = false
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        canvasOverCellular.value = prefs.getBoolean(KEY_CANVAS_OVER_CELLULAR, false)
        fullBleedArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_ARTWORK, true)
        keepOriginalArtworkAspectRatio.value = prefs.getBoolean(KEY_KEEP_ORIGINAL_ARTWORK_ASPECT_RATIO, false)
        keepCollectionArtworkAspectRatio.value = prefs.getBoolean(KEY_KEEP_COLLECTION_ARTWORK_ASPECT_RATIO, false)
        localizeLibraryCollectionCount.value = prefs.getBoolean(KEY_LOCALIZE_LIBRARY_COLLECTION_COUNT, true)
        preserveArtworkShapeInCompactTransitions.value =
            prefs.getBoolean(KEY_PRESERVE_ARTWORK_SHAPE_IN_COMPACT_TRANSITIONS, false)
        keepArtworkFullSizeWhenPaused.value = prefs.getBoolean(KEY_KEEP_ARTWORK_FULL_SIZE_PAUSED, false)
        doubleTapToSeek.value = prefs.getBoolean(KEY_DOUBLE_TAP_TO_SEEK, true)
        seekIntervalSeconds.value = prefs.getInt(KEY_SEEK_INTERVAL_SECONDS, 5).coerceIn(1, 60)
        recentlyPlayedThresholdSeconds.value = prefs.getInt(
            KEY_RECENT_PLAY_THRESHOLD_SECONDS,
            DEFAULT_RECENT_PLAY_THRESHOLD_SECONDS,
        ).coerceIn(0, MAX_RECENT_PLAY_THRESHOLD_SECONDS)
        seekButtonMode.value = runCatching {
            SeekButtonMode.valueOf(
                prefs.getString(KEY_SEEK_BUTTON_MODE, SeekButtonMode.LONG_TRACKS_ONLY.name).orEmpty(),
            )
        }.getOrDefault(SeekButtonMode.LONG_TRACKS_ONLY)
        transportLeftAction.value = runCatching {
            TransportSideAction.valueOf(
                prefs.getString(KEY_TRANSPORT_LEFT_ACTION, TransportSideAction.NONE.name).orEmpty(),
            )
        }.getOrDefault(TransportSideAction.NONE)
        transportRightAction.value = runCatching {
            TransportSideAction.valueOf(
                prefs.getString(KEY_TRANSPORT_RIGHT_ACTION, TransportSideAction.NONE.name).orEmpty(),
            )
        }.getOrDefault(TransportSideAction.NONE)
        hideSeekButtonLabels.value = prefs.getBoolean(KEY_HIDE_SEEK_BUTTON_LABELS, false)
        legacyMeshGradient.value = prefs.getBoolean(KEY_LEGACY_MESH_GRADIENT, false)
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        showLyricsFurigana.value = prefs.getBoolean(KEY_SHOW_LYRICS_FURIGANA, false)
        autoFitOneLineLyrics.value = prefs.getBoolean(KEY_AUTO_FIT_ONE_LINE_LYRICS, false)
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, false)
        showLyricsLogs.value = prefs.getBoolean(KEY_SHOW_LYRICS_LOGS, false)
        autoEmbedLyrics.value = prefs.getBoolean(KEY_AUTO_EMBED_LYRICS, true)
        lyricsExtensionRepoUrl.value = prefs.getString(KEY_LYRICS_EXTENSION_REPO_URL, DEFAULT_LYRICS_EXTENSION_REPO_URL)
            .orEmpty().ifBlank { DEFAULT_LYRICS_EXTENSION_REPO_URL }
        lyricsExtensionsEnabled.value = prefs.getBoolean(KEY_LYRICS_EXTENSIONS_ENABLED, false)
        lyricsAutoUpdate.value = prefs.getBoolean(KEY_LYRICS_AUTO_UPDATE, false)
        lastLyricsExtensionSyncMs.value = prefs.getLong(KEY_LAST_LYRICS_EXTENSION_SYNC, 0L)
        paxSenixApiKey.value = authStore.getProtectedValue(KEY_PAXSENIX_API_KEY).orEmpty()
        com.music.bitchord.data.lyrics.PaxSenix.setApiKey(paxSenixApiKey.value)
        audioCacheLimitBytes.value = prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES)
            .coerceIn(DEFAULT_CACHE_LIMIT_BYTES, MAX_CACHE_LIMIT_BYTES)
        lastfmEnabled.value = prefs.getBoolean(KEY_LASTFM_ENABLED, false)
        lastfmUsername.value = prefs.getString(KEY_LASTFM_USERNAME, "").orEmpty()
        lastfmSessionKey.value = authStore.getProtectedValue(KEY_LASTFM_SESSION_KEY).orEmpty()
        lastfmApiKey.value = authStore.getProtectedValue(KEY_LASTFM_API_KEY).orEmpty().ifBlank { BuildConfig.LASTFM_API_KEY }
        lastfmSecret.value = authStore.getProtectedValue(KEY_LASTFM_SECRET).orEmpty().ifBlank { BuildConfig.LASTFM_SECRET }
        lastfmEndpoint.value = prefs.getString(KEY_LASTFM_ENDPOINT, "").orEmpty()
        lastfmScrobbleEnabled.value = prefs.getBoolean(KEY_LASTFM_SCROBBLE_ENABLED, false)
        lastfmNowPlaying.value = prefs.getBoolean(KEY_LASTFM_NOW_PLAYING, false) && lastfmScrobbleEnabled.value
        lastfmPrimaryArtistOnly.value = prefs.getBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, false)
        scrobbleMinDuration.value = prefs.getInt(KEY_SCROBBLE_MIN_DURATION, 30)
        scrobbleDelayPercent.value = prefs.getFloat(KEY_SCROBBLE_DELAY_PERCENT, 0.5f)
        scrobbleDelaySeconds.value = prefs.getInt(KEY_SCROBBLE_DELAY_SECONDS, 180)
        listenBrainzEnabled.value = prefs.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
        listenBrainzToken.value = authStore.getProtectedValue(KEY_LISTENBRAINZ_TOKEN).orEmpty()
        listenBrainzPrimaryArtistOnly.value = prefs.getBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, false)
        spotifySpdcToken.value = authStore.getProtectedValue(KEY_SPOTIFY_SPDC_TOKEN).orEmpty()
        replayGenres.value = prefs.getBoolean(KEY_REPLAY_GENRES, true)
        filterNonMusicAudio.value = prefs.getBoolean(KEY_FILTER_NON_MUSIC_AUDIO, true)
        includeRemoteSourceMediaInLibrary.value = prefs.getBoolean(KEY_INCLUDE_REMOTE_SOURCE_MEDIA_IN_LIBRARY, false)
        localMusicSort.value = readLocalMusicSort(KEY_LOCAL_MUSIC_SORT)
        downloadedMusicSort.value = readLocalMusicSort(KEY_DOWNLOADED_MUSIC_SORT)
        localArtistSort.value = readArtistSort(KEY_LOCAL_ARTIST_SORT)
        downloadedArtistSort.value = readArtistSort(KEY_DOWNLOADED_ARTIST_SORT)
        localMusicViewType.value = readLibraryViewType(KEY_LOCAL_MUSIC_VIEW_TYPE, LibraryViewType.LIST)
        localSongsViewType.value = readLibraryViewType(KEY_LOCAL_SONGS_VIEW_TYPE, localMusicViewType.value)
        localAlbumsViewType.value = readLibraryViewType(KEY_LOCAL_ALBUMS_VIEW_TYPE, LibraryViewType.GRID)
        localArtistsViewType.value = readLibraryViewType(KEY_LOCAL_ARTISTS_VIEW_TYPE, LibraryViewType.GRID)
        localFolderHierarchy.value = prefs.getBoolean(KEY_LOCAL_FOLDER_HIERARCHY, false)
        localMusicFolderUri.value = prefs.getString(KEY_LOCAL_MUSIC_FOLDER_PATH, "").orEmpty()
        webdavUrl.value = prefs.getString(KEY_WEBDAV_URL, "").orEmpty()
        webdavAllowInsecureHttp.value = prefs.getBoolean(KEY_WEBDAV_ALLOW_HTTP, false)
        webdavUsername.value = authStore.getProtectedValue(KEY_WEBDAV_USERNAME).orEmpty()
        webdavPassword.value = authStore.getProtectedValue(KEY_WEBDAV_PASSWORD).orEmpty()
        smbHost.value = prefs.getString(KEY_SMB_HOST, "").orEmpty()
        smbShare.value = prefs.getString(KEY_SMB_SHARE, "").orEmpty()
        smbBasePath.value = prefs.getString(KEY_SMB_BASE_PATH, "").orEmpty()
        smbUsername.value = authStore.getProtectedValue(KEY_SMB_USERNAME).orEmpty()
        smbPassword.value = authStore.getProtectedValue(KEY_SMB_PASSWORD).orEmpty()
        com.music.bitchord.data.smb.SmbAuth.update(
            smbHost.value, smbShare.value, smbBasePath.value, smbUsername.value, smbPassword.value,
        )
        com.music.bitchord.data.webdav.WebDavAuth.update(
            webdavUrl.value, webdavUsername.value, webdavPassword.value, webdavAllowInsecureHttp.value,
        )
        fullScreenCollectionArtwork.value = prefs.getBoolean(KEY_FULL_SCREEN_COLLECTION_ARTWORK, false)
        hideCollectionTopBar.value = prefs.getBoolean(KEY_HIDE_COLLECTION_TOP_BAR, true)
        hideFloatingCollectionControls.value = prefs.getBoolean(KEY_HIDE_FLOATING_COLLECTION_CONTROLS, true)
        artistsOpenAlbums.value = prefs.getBoolean(KEY_ARTISTS_OPEN_ALBUMS, false)
        shuffleArtistAlbums.value = prefs.getBoolean(KEY_SHUFFLE_ARTIST_ALBUMS, false)
        onlineAlbumArtistArtwork.value = prefs.getBoolean(KEY_ONLINE_ALBUM_ARTIST_ARTWORK, false)
        downloadedMusicViewType.value = readLibraryViewType(KEY_DOWNLOADED_MUSIC_VIEW_TYPE, LibraryViewType.LIST)
        downloadedSongsViewType.value = readLibraryViewType(KEY_DOWNLOADED_SONGS_VIEW_TYPE, downloadedMusicViewType.value)
        downloadedAlbumsViewType.value = readLibraryViewType(KEY_DOWNLOADED_ALBUMS_VIEW_TYPE, LibraryViewType.GRID)
        downloadedArtistsViewType.value = readLibraryViewType(KEY_DOWNLOADED_ARTISTS_VIEW_TYPE, LibraryViewType.GRID)
        libraryPlaylistsViewType.value = readLibraryViewType(KEY_LIBRARY_PLAYLISTS_VIEW_TYPE, LibraryViewType.GRID)
        librarySongsViewType.value = readLibraryViewType(KEY_LIBRARY_SONGS_VIEW_TYPE, LibraryViewType.LIST)
        showPlaylistSongArtwork.value = prefs.getBoolean(KEY_SHOW_PLAYLIST_SONG_ARTWORK, false)
        showCollectionSongArtwork.value = prefs.getBoolean(KEY_SHOW_COLLECTION_SONG_ARTWORK, true)
        localDrillDownSongsViewType.value = readLibraryViewType(KEY_LOCAL_DRILLDOWN_SONGS_VIEW_TYPE, LibraryViewType.LIST)
        downloadedDrillDownSongsViewType.value = readLibraryViewType(KEY_DOWNLOADED_DRILLDOWN_SONGS_VIEW_TYPE, LibraryViewType.LIST)
        librarySort.value = prefs.getString(KEY_LIBRARY_SORT, null)
            ?.let { saved -> LibrarySort.entries.firstOrNull { it.name == saved } }
            ?: LibrarySort.DEFAULT
        detailSongSorts.value = readDetailSongSorts()
        LauncherIconManager.apply(appContext, launcherIcon.value)
        blacklistedFolders.value = prefs.getStringSet(KEY_BLACKLISTED_FOLDERS, emptySet()).orEmpty()
        pinnedPlaylists.value = readPinnedPlaylists()
        discordToken.value = authStore.discordToken.orEmpty()
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
        discordInfoDismissed.value = prefs.getBoolean(KEY_DISCORD_INFO_DISMISSED, false)
        allFilesPermissionAsked.value = prefs.getBoolean(KEY_ALL_FILES_PERMISSION_ASKED, false)
    }

    /**
     * True the first time this is called after [currentVersionCode] rises above
     * whatever was last recorded — i.e. once per update, on the first launch
     * after it installs. A fresh install has nothing to compare against, so
     * the very first call seeds the stored value from [currentVersionCode]
     * rather than reporting an update.
     *
     * BitChord ships sideloaded,
     * so installing a new APK over the old one is the only "update" there is —
     * app data, this pref included, survives it exactly like a Play Store
     * update. Call once per process start, before anything reads a cache that
     * an update should invalidate.
     */
    fun consumeVersionUpdate(currentVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, currentVersionCode)
        if (last != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
        return currentVersionCode > last
    }

    /**
     * A ceiling saved when there was only one applies to both connections.
     * Someone who picked Low to protect a data plan would not thank us for
     * quietly putting Wi-Fi *and* mobile back on High.
     */
    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.LOSSLESS
        return runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOSSLESS)
    }

    /**
     * Write down what the download path was already doing, before it starts
     * being asked instead.
     *
     * Download quality used to be derived rather than chosen: a lossless copy
     * was kept when `SourceResolver.requestForNow()` said Lossless, which meant
     * a download quietly turned on the lossless preference and off again with
     * it. Someone who switched that off on the Sources screen was getting AAC
     * downloads on purpose, and defaulting them to Lossless now would answer a
     * question they had already answered — with thirty-five megabytes a track.
     *
     * The ceilings are deliberately *not* consulted. They were only in that
     * derivation because there was nowhere else to say "not on mobile data",
     * and [wifiOnlyDownloads] is now where that is said.
     */
    private fun migrateDownloadQuality() {
        if (prefs.contains(KEY_QUALITY_DOWNLOAD)) return
        // Was derived from the old `losslessAudio` switch, which defaulted to
        // on; LOSSLESS is what that produced for all but the few installs that
        // had turned it off, and is the default a fresh install gets anyway.
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, DownloadQuality.LOSSLESS.name).apply()
    }

    private fun readDownloadQuality(): DownloadQuality {
        val stored = prefs.getString(KEY_QUALITY_DOWNLOAD, null) ?: return DownloadQuality.LOSSLESS
        return runCatching { DownloadQuality.valueOf(stored) }.getOrDefault(DownloadQuality.LOSSLESS)
    }

    /**
     * Track the active network so [effectiveAudioQuality] can answer without
     * touching ConnectivityManager. Stream resolution happens off the main
     * thread mid-playback; a callback keeps that lookup off the hot path and
     * lets the settings page show which ceiling is currently in force.
     */
    private fun watchConnection(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val refresh = {
            meteredConnection.value = runCatching {
                if (manager.activeNetwork == null) null else manager.isActiveNetworkMetered
            }.getOrNull()
        }
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = refresh()
                },
            )
        }
    }

    fun setAutoplay(value: Boolean) {
        autoplay.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.edit().putInt(KEY_REPEAT_MODE, value).apply()
    }

    fun setRestartQueueWhenEnded(value: Boolean) {
        restartQueueWhenEnded.value = value
        prefs.edit().putBoolean(KEY_RESTART_QUEUE_WHEN_ENDED, value).apply()
    }

    fun setAdvanceQueueWhenRepeatOff(value: Boolean) {
        advanceQueueWhenRepeatOff.value = value
        prefs.edit().putBoolean(KEY_ADVANCE_QUEUE_WHEN_REPEAT_OFF, value).apply()
    }

    fun setMediaControlFavoriteUsesStar(value: Boolean) {
        mediaControlFavoriteUsesStar.value = value
        prefs.edit().putBoolean(KEY_MEDIA_CONTROL_FAVORITE_USES_STAR, value).apply()
    }

    fun setMediaControlSecondaryAction(value: MediaControlSecondaryAction) {
        mediaControlSecondaryAction.value = value
        prefs.edit().putString(KEY_MEDIA_CONTROL_SECONDARY_ACTION, value.name).apply()
    }

    fun setHideMediaControlCustomActions(value: Boolean) {
        hideMediaControlCustomActions.value = value
        prefs.edit().putBoolean(KEY_HIDE_MEDIA_CONTROL_CUSTOM_ACTIONS, value).apply()
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        audioQualityWifi.value = value
        prefs.edit().putString(KEY_QUALITY_WIFI, value.name).apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        audioQualityCellular.value = value
        prefs.edit().putString(KEY_QUALITY_CELLULAR, value.name).apply()
    }

    fun setDownloadQuality(value: DownloadQuality) {
        downloadQuality.value = value
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, value.name).apply()
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        wifiOnlyDownloads.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()
    }


    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    fun setAudioOffloadEnabled(value: Boolean) {
        val allowed = value && crossfadeSeconds.value == 0 && !smartFadeEnabled.value
        audioOffloadEnabled.value = allowed
        prefs.edit().putBoolean(KEY_AUDIO_OFFLOAD_ENABLED, allowed).apply()
    }

    fun setCrossfadeSeconds(value: Int) {
        val normalized = value.coerceIn(0, MAX_CROSSFADE_SECONDS)
        if (normalized > 0) setAudioOffloadEnabled(false)
        crossfadeSeconds.value = normalized
        prefs.edit().putInt(KEY_CROSSFADE_SECONDS, normalized).apply()
    }

    fun setCdRippingOutputTreeUri(value: String?) {
        cdRippingOutputTreeUri.value = value
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_CD_RIPPING_OUTPUT_TREE_URI)
            else putString(KEY_CD_RIPPING_OUTPUT_TREE_URI, value)
        }.apply()
    }

    fun setCdRippingRetries(value: Int) {
        val normalized = value.coerceIn(0, 5)
        cdRippingRetries.value = normalized
        prefs.edit().putInt(KEY_CD_RIPPING_RETRIES, normalized).apply()
    }

    fun setCdRippingSpeedX(value: Int) {
        val normalized = value.coerceIn(1, 12)
        cdRippingSpeedX.value = normalized
        prefs.edit().putInt(KEY_CD_RIPPING_SPEED_X, normalized).apply()
    }

    fun setSmartFadeEnabled(value: Boolean) {
        if (value) setAudioOffloadEnabled(false)
        smartFadeEnabled.value = value
        prefs.edit().putBoolean(KEY_SMART_FADE, value).apply()
    }

    fun setAutomixPerformanceMode(value: AutomixPerformanceMode) {
        automixPerformanceMode.value = value
        prefs.edit().putString(KEY_AUTOMIX_PERFORMANCE_MODE, value.name).apply()
    }

    fun setResamplerCutoffHz(value: Int) {
        val normalized = normalizeResamplerCutoffHz(value)
        resamplerCutoffHz.value = normalized
        prefs.edit().putInt(KEY_RESAMPLER_CUTOFF_HZ, normalized).apply()
    }

    fun setDolbyAtmos(value: Boolean) {
        dolbyAtmos.value = value
        prefs.edit().putBoolean(KEY_DOLBY_ATMOS, value).apply()
    }

    fun setDiracAudioEnabled(value: Boolean) {
        diracAudioEnabled.value = value
        prefs.edit().putBoolean(KEY_DIRAC_AUDIO_ENABLED, value).apply()
    }

    fun setDiracAudioPreset(value: Int) {
        val normalized = value.coerceIn(0, 2)
        diracAudioPreset.value = normalized
        prefs.edit().putInt(KEY_DIRAC_AUDIO_PRESET, normalized).apply()
    }

    fun setSpatialAudio(value: Boolean) {
        spatialAudio.value = value
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply()
    }

    fun setPlaybackSpeed(value: Float) {
        val normalized = value.coerceIn(0.1f, 10.0f)
        playbackSpeed.value = normalized
        prefs.edit().putFloat(KEY_SPEED, normalized).apply()
    }

    fun setPlaybackPitch(value: Float) {
        val normalized = value.coerceIn(0.5f, 2.0f)
        playbackPitch.value = normalized
        prefs.edit().putFloat(KEY_PITCH, normalized).apply()
    }

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
        prefs.edit().putBoolean(KEY_NERD_STATS, value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit()
            .putString(KEY_THEME, value.name)
            .putBoolean(KEY_THEME_MATCHES_ARTWORK, value == ThemeMode.ARTWORK)
            .apply()
    }

    fun setCustomThemeImageUri(value: String?) {
        customThemeImageUri.value = value
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_CUSTOM_THEME_IMAGE_URI)
            else putString(KEY_CUSTOM_THEME_IMAGE_URI, value)
        }.apply()
    }

    fun setMatchButtonAccentToArtwork(value: Boolean) {
        matchButtonAccentToArtwork.value = value
        prefs.edit().putBoolean(KEY_MATCH_BUTTON_ACCENT_TO_ARTWORK, value).apply()
    }

    fun setMiniPlayerArtworkColor(value: Boolean) {
        miniPlayerArtworkColor.value = value
        prefs.edit().putBoolean(KEY_MINI_PLAYER_ARTWORK_COLOR, value).apply()
    }

    fun setNavigationBarMatchesMiniPlayerColor(value: Boolean) {
        navigationBarMatchesMiniPlayerColor.value = value
        prefs.edit().putBoolean(KEY_NAVIGATION_BAR_MATCHES_MINI_PLAYER_COLOR, value).apply()
    }

    fun setMiniPlayerArtworkOutline(value: Boolean) {
        miniPlayerArtworkOutline.value = value
        prefs.edit().putBoolean(KEY_MINI_PLAYER_ARTWORK_OUTLINE, value).apply()
    }

    fun setMiniPlayerPreviousButton(value: Boolean) {
        miniPlayerPreviousButton.value = value
        prefs.edit().putBoolean(KEY_MINI_PLAYER_PREVIOUS_BUTTON, value).apply()
    }

    fun setMiniPlayerSwipeChangesTrack(value: Boolean) {
        miniPlayerSwipeChangesTrack.value = value
        prefs.edit().putBoolean(KEY_MINI_PLAYER_SWIPE_CHANGES_TRACK, value).apply()
    }

    fun setMiniPlayerSwipeUpExpands(value: Boolean) {
        miniPlayerSwipeUpExpands.value = value
        prefs.edit().putBoolean(KEY_MINI_PLAYER_SWIPE_UP_EXPANDS, value).apply()
    }

    fun setPlaylistTrackMenuUsesArtworkColor(value: Boolean) {
        playlistTrackMenuUsesArtworkColor.value = value
        prefs.edit().putBoolean(KEY_PLAYLIST_TRACK_MENU_ARTWORK_COLOR, value).apply()
    }

    fun setParametricEqEnabled(value: Boolean) {
        setParametricEqConfig(parametricEqConfig.value.copy(enabled = value))
    }

    fun setTpdfDitherEnabled(value: Boolean) {
        tpdfDitherEnabled.value = value
        prefs.edit().putBoolean(KEY_TPDF_DITHER_ENABLED, value).apply()
    }

    fun setEqualizerBalance(value: Float) {
        val safeValue = value.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        equalizerBalance.value = safeValue
        prefs.edit().putFloat(KEY_EQUALIZER_BALANCE, safeValue).apply()
    }

    fun setChannelSwapEnabled(value: Boolean) {
        setParametricEqSwapChannels(value)
    }

    fun setParametricEqBand(index: Int, band: ParametricEqBand) {
        val updated = parametricEqConfig.value.globalBands.toMutableList()
        if (index !in updated.indices) return
        updated[index] = band.sanitized(updated[index])
        setParametricEqChannelBands(ParametricEqChannelTarget.GLOBAL, updated)
    }

    fun setParametricEqConfig(newConfig: ParametricEqConfig) {
        val safe = newConfig.sanitized()
        parametricEqConfig.value = safe
        parametricEqEnabled.value = safe.enabled
        parametricEqBands.value = safe.globalBands
        channelSwapEnabled.value = safe.swapChannels
        prefs.edit()
            .putBoolean(KEY_PARAMETRIC_EQ_ENABLED, safe.enabled)
            .putBoolean(KEY_CHANNEL_SWAP_ENABLED, safe.swapChannels)
            .putString(KEY_PARAMETRIC_EQ_BANDS, ParametricEqBand.encode(safe.globalBands))
            .putString(KEY_PARAMETRIC_EQ_CONFIG_JSON, safe.toJsonString())
            .apply()
    }

    fun setParametricEqSwapChannels(
        swap: Boolean,
        swapBeforeEq: Boolean = parametricEqConfig.value.swapBeforeEq,
    ) {
        setParametricEqConfig(
            parametricEqConfig.value.copy(
                swapChannels = swap,
                swapBeforeEq = swapBeforeEq,
            ),
        )
    }

    fun setParametricEqPreamps(
        globalDb: Float = parametricEqConfig.value.globalPreampDb,
        lrDb: Float = parametricEqConfig.value.lrPreampDb,
        leftDb: Float = parametricEqConfig.value.leftGainDb,
        rightDb: Float = parametricEqConfig.value.rightGainDb,
    ) {
        setParametricEqConfig(
            parametricEqConfig.value.copy(
                globalPreampDb = globalDb,
                lrPreampDb = lrDb,
                leftGainDb = leftDb,
                rightGainDb = rightDb,
                activePresetId = null,
            ),
        )
    }

    fun setParametricEqChannelBands(target: ParametricEqChannelTarget, bands: List<ParametricEqBand>) {
        val current = parametricEqConfig.value
        val updated = when (target) {
            ParametricEqChannelTarget.GLOBAL -> current.copy(globalBands = bands, activePresetId = null)
            ParametricEqChannelTarget.LEFT -> current.copy(leftBands = bands, activePresetId = null)
            ParametricEqChannelTarget.RIGHT -> current.copy(rightBands = bands, activePresetId = null)
        }
        setParametricEqConfig(updated)
    }

    fun importEelPreset(content: String, fileName: String? = null): EelEqParser.ParseResult {
        val result = EelEqParser.parse(content, fileName)
        val incoming = result.preset
        val existing = parametricEqPresets.value.toMutableList()
        val matchIndex = existing.indexOfFirst {
            it.id == incoming.id || it.name.equals(incoming.name, ignoreCase = true)
        }
        val saved = if (matchIndex >= 0) {
            incoming.copy(id = existing[matchIndex].id, createdAt = System.currentTimeMillis())
                .sanitized()
                .also { existing[matchIndex] = it }
        } else {
            incoming.sanitized().also { existing.add(0, it) }
        }
        saveParametricEqPresets(existing)
        setParametricEqConfig(saved.toConfig(enabled = true))
        return result.copy(preset = saved)
    }

    fun loadBundledSampleEel(): EelEqParser.ParseResult? {
        if (!this::appContext.isInitialized) return null
        return runCatching {
            val text = appContext.assets.open(BUNDLED_EEL_ASSET_PATH).bufferedReader().use { it.readText() }
            importEelPreset(text, BUNDLED_EEL_ASSET_PATH.substringAfterLast('/'))
        }.getOrNull()
    }

    fun saveCurrentAsParametricEqPreset(name: String): ParametricEqPreset {
        val cleanName = name.trim().take(MAX_PARAMETRIC_EQ_PRESET_NAME_LENGTH).ifEmpty { "Custom PEQ" }
        val current = parametricEqConfig.value
        val existing = parametricEqPresets.value.toMutableList()
        val matchIndex = existing.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        val preset = ParametricEqPreset(
            id = if (matchIndex >= 0) existing[matchIndex].id else UUID.randomUUID().toString(),
            name = cleanName,
            fileName = current.sourceFileName,
            sourceFileName = current.sourceFileName,
            notes = current.notes,
            createdAt = System.currentTimeMillis(),
            swapChannels = current.swapChannels,
            swapBeforeEq = current.swapBeforeEq,
            globalPreampDb = current.globalPreampDb,
            lrPreampDb = current.lrPreampDb,
            leftGainDb = current.leftGainDb,
            rightGainDb = current.rightGainDb,
            globalBands = current.globalBands,
            leftBands = current.leftBands,
            rightBands = current.rightBands,
        ).sanitized()
        if (matchIndex >= 0) existing[matchIndex] = preset else existing.add(0, preset)
        saveParametricEqPresets(existing)
        setParametricEqConfig(current.copy(activePresetId = preset.id, activePresetName = preset.name))
        return preset
    }

    fun selectParametricEqPreset(presetId: String) {
        val preset = parametricEqPresets.value.firstOrNull { it.id == presetId } ?: return
        setParametricEqConfig(preset.toConfig(enabled = true))
    }

    fun deleteParametricEqPreset(presetId: String) {
        saveParametricEqPresets(parametricEqPresets.value.filterNot { it.id == presetId })
        if (parametricEqConfig.value.activePresetId == presetId) {
            setParametricEqConfig(parametricEqConfig.value.copy(activePresetId = null, activePresetName = null))
        }
    }

    fun resetParametricEqConfig() {
        setParametricEqConfig(
            ParametricEqConfig(
                enabled = parametricEqConfig.value.enabled,
                globalBands = ParametricEqBand.DEFAULTS,
            ),
        )
    }

    private fun saveParametricEqPresets(presets: List<ParametricEqPreset>) {
        val safe = presets.take(MAX_SAVED_PARAMETRIC_EQ_PRESETS).map(ParametricEqPreset::sanitized)
        parametricEqPresets.value = safe
        prefs.edit().putString(KEY_PARAMETRIC_EQ_PRESETS_JSON, ParametricEqPreset.encodeList(safe)).apply()
    }

    fun setReverbEnabled(value: Boolean) {
        reverbEnabled.value = value
        prefs.edit().putBoolean(KEY_REVERB_ENABLED, value).apply()
    }

    fun setReverbAmount(value: Int) {
        val safeValue = value.coerceIn(0, 100)
        reverbAmount.value = safeValue
        prefs.edit().putInt(KEY_REVERB_AMOUNT, safeValue).apply()
    }

    fun setLauncherIcon(value: LauncherIcon) {
        launcherIcon.value = value
        prefs.edit().putString(KEY_LAUNCHER_ICON, value.name).apply()
        if (this::appContext.isInitialized) LauncherIconManager.apply(appContext, value)
    }

    fun setFrostedQueueManager(value: Boolean) {
        frostedQueueManager.value = value
        prefs.edit().putBoolean(KEY_FROSTED_QUEUE_MANAGER, value).apply()
    }

    fun setFrostedPlayerDetails(value: Boolean) {
        frostedPlayerDetails.value = value
        prefs.edit().putBoolean(KEY_FROSTED_PLAYER_DETAILS, value).apply()
    }

    fun setDetailsSheetArtworkBackdrop(value: Boolean) {
        detailsSheetArtworkBackdrop.value = value
        prefs.edit().putBoolean(KEY_DETAILS_SHEET_ARTWORK_BACKDROP, value).apply()
    }

    fun setShowEmbeddedComment(value: Boolean) {
        showEmbeddedComment.value = value
        prefs.edit().putBoolean(KEY_SHOW_EMBEDDED_COMMENT, value).apply()
    }

    fun setQueueManagerEqualsIcon(value: Boolean) {
        queueManagerEqualsIcon.value = value
        prefs.edit().putBoolean(KEY_QUEUE_MANAGER_EQUALS_ICON, value).apply()
    }

    fun setRedesignedSavedQueueManager(value: Boolean) {
        redesignedSavedQueueManager.value = value
        prefs.edit().putBoolean(KEY_REDESIGNED_SAVED_QUEUE_MANAGER, value).apply()
    }

    fun setHideSavedQueueHeading(value: Boolean) {
        hideSavedQueueHeading.value = value
        prefs.edit().putBoolean(KEY_HIDE_SAVED_QUEUE_HEADING, value).apply()
    }

    fun setHideQueueLabel(value: Boolean) {
        hideQueueLabel.value = value
        prefs.edit().putBoolean(KEY_HIDE_QUEUE_LABEL, value).apply()
    }

    fun setQueueClearTrashIcon(value: Boolean) {
        queueClearTrashIcon.value = value
        prefs.edit().putBoolean(KEY_QUEUE_CLEAR_TRASH_ICON, value).apply()
    }

    fun setHidePlaybackTuningTitle(value: Boolean) {
        hidePlaybackTuningTitle.value = value
        prefs.edit().putBoolean(KEY_HIDE_PLAYBACK_TUNING_TITLE, value).apply()
    }

    fun setPlaybackTuningThinSliders(value: Boolean) {
        playbackTuningThinSliders.value = value
        prefs.edit().putBoolean(KEY_PLAYBACK_TUNING_THIN_SLIDERS, value).apply()
    }

    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setPlayerArtworkTransition(value: Boolean) {
        playerArtworkTransition.value = value
        prefs.edit().putBoolean(KEY_PLAYER_ARTWORK_TRANSITION, value).apply()
    }

    fun setWalkmanPlayerStyle(value: Boolean) {
        walkmanPlayerStyle.value = value
        prefs.edit().putBoolean(KEY_WALKMAN_PLAYER_STYLE, value).apply()
    }

    fun setPillMotionStyle(value: PillMotionStyle) {
        pillMotionStyle.value = value
        prefs.edit().putString(KEY_PILL_MOTION_STYLE, value.name).apply()
    }

    fun setStopOnTaskRemoved(value: Boolean) {
        stopOnTaskRemoved.value = value
        prefs.edit().putBoolean(KEY_STOP_ON_TASK_REMOVED, value).apply()
    }

    fun setHideVolumeBar(value: Boolean) {
        hideVolumeBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_VOLUME_BAR, value).apply()
    }

    fun setHideRadioPlaybackBar(value: Boolean) {
        hideRadioPlaybackBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_RADIO_PLAYBACK_BAR, value).apply()
    }

    fun setPlayerSeekBarThicknessDp(value: Int) {
        val normalized = value.coerceIn(2, 14)
        playerSeekBarThicknessDp.value = normalized
        prefs.edit().putInt(KEY_PLAYER_SEEK_BAR_THICKNESS_DP, normalized).apply()
    }

    fun setPlayerVolumeBarThicknessDp(value: Int) {
        val normalized = value.coerceIn(2, 14)
        playerVolumeBarThicknessDp.value = normalized
        prefs.edit().putInt(KEY_PLAYER_VOLUME_BAR_THICKNESS_DP, normalized).apply()
    }

    fun setSwipeToPlayNext(value: Boolean) {
        swipeToPlayNext.value = value
        prefs.edit().putBoolean(KEY_SWIPE_TO_PLAY_NEXT, value).apply()
    }

    fun setDontRepeatSuggestions(value: Boolean) {
        dontRepeatSuggestions.value = value
        prefs.edit().putBoolean(KEY_DONT_REPEAT_SUGGESTIONS, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        prefs.edit().putBoolean(KEY_REDUCE_BLUR, value).apply()
    }

    fun setLiquidGlass(value: Boolean) {
        liquidGlass.value = value
        prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
    }

    fun setClassicNavBar(value: Boolean) {
        classicNavBar.value = value
        prefs.edit().putBoolean(KEY_CLASSIC_NAV_BAR, value).apply()
    }

    fun setHideNavigationBarLabels(value: Boolean) {
        hideNavigationBarLabels.value = value
        prefs.edit().putBoolean(KEY_HIDE_NAVIGATION_BAR_LABELS, value).apply()
    }

    fun setMainNavigationTabsPopup(value: Boolean) {
        mainNavigationTabsPopup.value = value
        prefs.edit().putBoolean(KEY_MAIN_NAVIGATION_TABS_POPUP, value).apply()
    }

    /** Keeps at least one destination available for navigation. */
    fun setMainNavigationTabVisible(tab: MainNavigationTab, visible: Boolean) {
        if (tab == MainNavigationTab.ALBUM_ARTISTS) {
            setLocalLibraryTabVisible(LocalLibraryTab.ALBUM_ARTISTS, visible)
            return
        }
        val current = visibleMainNavigationTabs.value
        val updated = MainNavigationTabs.setVisible(current, tab, visible)
        if (updated == current) return
        visibleMainNavigationTabs.value = updated
        prefs.edit()
            .putStringSet(KEY_VISIBLE_MAIN_NAVIGATION_TABS, updated.map { it.name }.toSet())
            .putBoolean(KEY_MAIN_FOLDER_TAB_MIGRATED, true)
            .apply()
        if (startupTab.value !in effectiveMainNavigationTabs()) setStartupTab(MainNavigationTabs.fallback(
            startupTab.value.index, effectiveMainNavigationTabs(), mainNavigationTabOrder.value,
        ))
    }

    fun effectiveMainNavigationTabs(): Set<MainNavigationTab> =
        if (LocalLibraryTab.ALBUM_ARTISTS in visibleLocalLibraryTabs.value) {
            visibleMainNavigationTabs.value + MainNavigationTab.ALBUM_ARTISTS
        } else {
            visibleMainNavigationTabs.value - MainNavigationTab.ALBUM_ARTISTS
        }

    fun moveMainNavigationTab(tab: MainNavigationTab, offset: Int) {
        val updated = MainNavigationTabs.move(mainNavigationTabOrder.value, tab, offset)
        if (updated == mainNavigationTabOrder.value) return
        mainNavigationTabOrder.value = updated
        prefs.edit().putString(KEY_MAIN_NAVIGATION_TAB_ORDER, updated.joinToString(",") { it.name }).apply()
    }

    fun setStartupTab(tab: MainNavigationTab) {
        val visible = effectiveMainNavigationTabs()
        val normalized = tab.takeIf { it in visible } ?: MainNavigationTabs.fallback(
            tab.index, visible, mainNavigationTabOrder.value,
        )
        startupTab.value = normalized
        prefs.edit().putString(KEY_STARTUP_TAB, normalized.name).apply()
    }

    private fun readStartupTab(): MainNavigationTab {
        val saved = prefs.getString(KEY_STARTUP_TAB, MainNavigationTab.SONGS.name)
            ?.let { name -> MainNavigationTab.entries.firstOrNull { it.name == name } }
            ?: MainNavigationTab.SONGS
        val visible = effectiveMainNavigationTabs()
        return saved.takeIf { it in visible }
            ?: MainNavigationTabs.fallback(saved.index, visible, mainNavigationTabOrder.value)
    }

    private fun readVisibleLocalLibraryTabs(): Set<LocalLibraryTab> {
        var storedNames: Set<String>? = prefs.getStringSet(KEY_VISIBLE_LOCAL_LIBRARY_TABS, null)?.toSet()
        if (!prefs.getBoolean(KEY_ALBUM_ARTISTS_TAB_MIGRATED, false)) {
            // Album Artists was added after local-tab visibility had already
            // been persisted on existing installs. It should appear on upgrade
            // once, while a later explicit hide remains respected.
            val currentNames = storedNames.orEmpty()
            if (currentNames.isNotEmpty() && LocalLibraryTab.ALBUM_ARTISTS.name !in currentNames) {
                storedNames = currentNames + LocalLibraryTab.ALBUM_ARTISTS.name
                prefs.edit()
                    .putStringSet(KEY_VISIBLE_LOCAL_LIBRARY_TABS, storedNames)
                    .apply()
            }
            prefs.edit().putBoolean(KEY_ALBUM_ARTISTS_TAB_MIGRATED, true).apply()
        }
        val stored = storedNames
            ?.mapNotNull { name -> LocalLibraryTab.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return stored?.takeIf { it.isNotEmpty() } ?: LocalLibraryTab.entries.toSet()
    }

    private fun readLocalLibraryTabOrder(): List<LocalLibraryTab> {
        val stored = prefs.getString(KEY_LOCAL_LIBRARY_TAB_ORDER, null)
            ?.split(',')
            ?.mapNotNull { name -> LocalLibraryTab.entries.firstOrNull { it.name == name } }
            .orEmpty()
        return (stored + LocalLibraryTab.entries.filterNot { it in stored }).distinct()
    }

    fun setShowStatusBar(value: Boolean) {
        showStatusBar.value = value
        prefs.edit().putBoolean(KEY_SHOW_STATUS_BAR, value).apply()
    }

    fun setShowPlayerStatusBar(value: Boolean) {
        showPlayerStatusBar.value = value
        prefs.edit().putBoolean(KEY_SHOW_PLAYER_STATUS_BAR, value).apply()
    }

    fun setShowNavigationBar(value: Boolean) {
        showNavigationBar.value = value
        prefs.edit().putBoolean(KEY_SHOW_NAVIGATION_BAR, value).apply()
    }

    fun setMusicOnlyDeviceMode(value: Boolean) {
        musicOnlyDeviceMode.value = value
        prefs.edit().putBoolean(KEY_MUSIC_ONLY_DEVICE_MODE, value).apply()
    }

    fun setKeepScreenOn(value: Boolean) {
        keepScreenOn.value = value
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun setKeepScreenOnDuringLyrics(value: Boolean) {
        keepScreenOnDuringLyrics.value = value
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON_DURING_LYRICS, value).apply()
    }

    fun setScreenOrientationMode(value: ScreenOrientationMode) {
        screenOrientationMode.value = value
        prefs.edit().putString(KEY_SCREEN_ORIENTATION, value.name).apply()
    }

    fun setFavoriteUsesStar(value: Boolean) {
        favoriteUsesStar.value = value
        prefs.edit().putBoolean(KEY_FAVORITE_USES_STAR, value).apply()
    }

    fun setPlayerQuickActions(actions: List<PlayerQuickAction>) {
        // The list is bounded by the enum itself; keep every distinct entry in
        // the order selected by the user rather than imposing a UI count limit.
        val normalized = actions.distinct()
        playerQuickActions.value = normalized
        prefs.edit().putString(KEY_PLAYER_QUICK_ACTIONS, normalized.joinToString(",") { it.name }).apply()
        val showFavoriteBesideMenu = PlayerQuickAction.FAVORITE in normalized
        favoriteBesideTrackMenu.value = showFavoriteBesideMenu
        prefs.edit().putBoolean(KEY_FAVORITE_BESIDE_TRACK_MENU, showFavoriteBesideMenu).apply()
    }

    fun setHidePlayerQuickActionCircles(value: Boolean) {
        hidePlayerQuickActionCircles.value = value
        prefs.edit().putBoolean(KEY_HIDE_PLAYER_QUICK_ACTION_CIRCLES, value).apply()
    }

    fun setPlayerDetailsActionVisible(action: PlayerDetailsAction, visible: Boolean) {
        val hidden = hiddenPlayerDetailsActions.value.toMutableSet().apply {
            if (visible) remove(action) else add(action)
        }
        hiddenPlayerDetailsActions.value = hidden
        prefs.edit().putStringSet(KEY_HIDDEN_PLAYER_DETAILS_ACTIONS, hidden.map { it.name }.toSet()).apply()
    }

    private fun readHiddenPlayerDetailsActions(): Set<PlayerDetailsAction> =
        prefs.getStringSet(KEY_HIDDEN_PLAYER_DETAILS_ACTIONS, emptySet())
            .orEmpty()
            .mapNotNull { name -> PlayerDetailsAction.entries.firstOrNull { it.name == name } }
            .toSet()

    fun setAudioCutterEnabled(value: Boolean) {
        audioCutterEnabled.value = value
        prefs.edit().putBoolean(KEY_AUDIO_CUTTER_ENABLED, value).apply()
    }

    fun setArtworkBackdropBlurDp(value: Float) {
        val normalized = value.coerceIn(0f, MAX_PLAYER_ARTWORK_BLUR_DP)
        artworkBackdropBlurDp.value = normalized
        prefs.edit().putFloat(KEY_ARTWORK_BACKDROP_BLUR_DP, normalized).apply()
    }

    fun movePlayerQuickAction(action: PlayerQuickAction, offset: Int) {
        val ordered = playerQuickActions.value.toMutableList()
        val from = ordered.indexOf(action)
        if (from < 0) return
        val to = (from + offset).coerceIn(0, ordered.lastIndex)
        if (from == to) return
        ordered.add(to, ordered.removeAt(from))
        setPlayerQuickActions(ordered)
    }

    private fun readPlayerQuickActions(): List<PlayerQuickAction> =
        (prefs.getString(KEY_PLAYER_QUICK_ACTIONS, null)
            ?.split(",")
            ?.mapNotNull { name -> PlayerQuickAction.entries.firstOrNull { it.name == name } }
            ?.distinct()
            .orEmpty() + if (prefs.getBoolean(KEY_FAVORITE_BESIDE_TRACK_MENU, false)) {
                listOf(PlayerQuickAction.FAVORITE)
            } else emptyList()).distinct()

    fun setPersistentLocalArtwork(value: Boolean) {
        persistentLocalArtwork.value = value
        prefs.edit().putBoolean(KEY_PERSISTENT_LOCAL_ARTWORK, value).apply()
    }

    private fun readMainNavigationTabOrder(): List<MainNavigationTab> {
        val saved = prefs.getString(KEY_MAIN_NAVIGATION_TAB_ORDER, null)
            ?.split(",")
            ?.mapNotNull { name -> MainNavigationTab.entries.firstOrNull { it.name == name } }
            .orEmpty()
        return MainNavigationTabs.normalizeOrder(saved)
    }

    private fun readVisibleMainNavigationTabs(): Set<MainNavigationTab> {
        val raw = prefs.getStringSet(KEY_VISIBLE_MAIN_NAVIGATION_TABS, null)
        val saved = raw.orEmpty()
            .mapNotNull { name -> MainNavigationTab.entries.firstOrNull { it.name == name } }
            .toSet()
        val migrateFolders = !prefs.getBoolean(KEY_MAIN_FOLDER_TAB_MIGRATED, false)
        val migrateRadio = !prefs.getBoolean(KEY_RADIO_TAB_MIGRATED, false)
        if (raw == null) {
            if (migrateFolders || migrateRadio) {
                prefs.edit()
                    .putBoolean(KEY_MAIN_FOLDER_TAB_MIGRATED, true)
                    .putBoolean(KEY_RADIO_TAB_MIGRATED, true)
                    .putStringSet(KEY_VISIBLE_MAIN_NAVIGATION_TABS, MainNavigationTab.entries.map { it.name }.toSet())
                    .apply()
            }
            return MainNavigationTab.entries.toSet()
        }
        val migrated = saved.toMutableSet().apply {
            if (migrateFolders) add(MainNavigationTab.FOLDERS)
            if (migrateRadio) add(MainNavigationTab.RADIO)
        }
        if (migrateFolders || migrateRadio) {
            prefs.edit()
                .putBoolean(KEY_MAIN_FOLDER_TAB_MIGRATED, true)
                .putBoolean(KEY_RADIO_TAB_MIGRATED, true)
                .putStringSet(KEY_VISIBLE_MAIN_NAVIGATION_TABS, migrated.map { it.name }.toSet())
                .apply()
        }
        return migrated.ifEmpty { MainNavigationTab.entries.toSet() }
    }

    fun setHighPerformanceMode(value: Boolean) {
        highPerformanceMode.value = value
        if (value) {
            reduceAnimation.value = false
        }
        val editor = prefs.edit().putBoolean(KEY_HIGH_PERFORMANCE_MODE, value)
        if (value) {
            editor.putBoolean(KEY_REDUCE_ANIMATION, false)
        }
        editor.apply()
    }

    fun setPerformanceRefreshRate(value: Int) {
        val normalized = normalizePerformanceRefreshRate(value)
        performanceRefreshRate.value = normalized
        prefs.edit().putInt(KEY_PERFORMANCE_REFRESH_RATE, normalized).apply()
    }

    fun setLyricsBlur(value: Boolean) {
        lyricsBlur.value = value
        prefs.edit().putBoolean(KEY_LYRICS_BLUR, value).apply()
    }

    fun setHideLyricsStatusText(value: Boolean) {
        hideLyricsStatusText.value = value
        prefs.edit().putBoolean(KEY_HIDE_LYRICS_STATUS_TEXT, value).apply()
    }

    fun setHideLyricsSavedMessage(value: Boolean) {
        hideLyricsSavedMessage.value = value
        prefs.edit().putBoolean(KEY_HIDE_LYRICS_SAVED_MESSAGE, value).apply()
    }

    fun setHideLyricsUnavailableLabel(value: Boolean) {
        hideLyricsUnavailableLabel.value = value
        prefs.edit().putBoolean(KEY_HIDE_LYRICS_UNAVAILABLE_LABEL, value).apply()
    }

    fun setHideLyricsGapNote(value: Boolean) {
        hideLyricsGapNote.value = value
        prefs.edit().putBoolean(KEY_HIDE_LYRICS_GAP_NOTE, value).apply()
    }

    fun setShowLyricsPreviousControl(value: Boolean) {
        showLyricsPreviousControl.value = value
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_PREVIOUS_CONTROL, value).apply()
    }

    fun setShowLyricsPlayPauseControl(value: Boolean) {
        showLyricsPlayPauseControl.value = value
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_PLAY_PAUSE_CONTROL, value).apply()
    }

    fun setShowLyricsNextControl(value: Boolean) {
        showLyricsNextControl.value = value
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_NEXT_CONTROL, value).apply()
    }

    fun setLyricsTransportControlSize(value: Float) {
        val normalized = value.coerceIn(14f, MAX_LYRICS_TRANSPORT_CONTROL_SIZE)
        lyricsTransportControlSize.value = normalized
        prefs.edit().putFloat(KEY_LYRICS_TRANSPORT_CONTROL_SIZE, normalized).apply()
    }

    fun setLyricsTransportControlsCentered(value: Boolean) {
        lyricsTransportControlsCentered.value = value
        prefs.edit().putBoolean(KEY_LYRICS_TRANSPORT_CONTROLS_CENTERED, value).apply()
    }

    fun setLyricsFontScale(value: Float) {
        val normalized = value.coerceIn(0.6f, 1.6f)
        lyricsFontScale.value = normalized
        prefs.edit().putFloat(KEY_LYRICS_FONT_SCALE, normalized).apply()
    }

    fun setLyricsTextAlignment(value: LyricsTextAlignment) {
        lyricsTextAlignment.value = value
        prefs.edit().putString(KEY_LYRICS_TEXT_ALIGNMENT, value.name).apply()
    }

    fun setArtworkTapOpensLyrics(value: Boolean) {
        artworkTapOpensLyrics.value = value
        prefs.edit().putBoolean(KEY_ARTWORK_TAP_OPENS_LYRICS, value).apply()
    }

    fun setLyricsReplaceArtworkOnly(value: Boolean) {
        lyricsReplaceArtworkOnly.value = value
        prefs.edit().putBoolean(KEY_LYRICS_REPLACE_ARTWORK_ONLY, value).apply()
    }

    fun setHidePlayerHeaderDuringLyrics(value: Boolean) {
        hidePlayerHeaderDuringLyrics.value = value
        prefs.edit().putBoolean(KEY_HIDE_PLAYER_HEADER_DURING_LYRICS, value).apply()
    }

    fun setShowPlayerLyricsStrip(value: Boolean) {
        showPlayerLyricsStrip.value = value
        prefs.edit().putBoolean(KEY_SHOW_PLAYER_LYRICS_STRIP, value).apply()
    }

    fun setOfflineMode(value: Boolean) {
        val wasOffline = offlineMode.value
        offlineMode.value = value
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()
        if (!wasOffline && value) Http.cancelAllNetworkRequests()
        if (value) com.music.bitchord.radio.RadioPlaybackSession.clear()
        if (!value && youtubeMusicEnabled.value) YoutubeStreamBootstrap.initializeIfAllowed(appContext)
    }

    fun setRadioAreaId(value: String) {
        if (!value.matches(Regex("JP\\d{1,2}"))) return
        radioAreaId.value = value
        prefs.edit().putString(KEY_RADIO_AREA_ID, value).apply()
    }

    fun setYoutubeMusicEnabled(value: Boolean) {
        val wasEnabled = youtubeMusicEnabled.value
        youtubeMusicEnabled.value = value
        prefs.edit().putBoolean(KEY_YOUTUBE_MUSIC_ENABLED, value).apply()
        if (wasEnabled && !value) Http.cancelYoutubeMusicRequests()
        if (value && !offlineMode.value) YoutubeStreamBootstrap.initializeIfAllowed(appContext)
    }

    fun setYoutubeMusicSearchDefault(value: Boolean) {
        youtubeMusicSearchDefault.value = value
        prefs.edit().putBoolean(KEY_YOUTUBE_MUSIC_SEARCH_DEFAULT, value).apply()
    }

    fun setIncludeYoutubeMusicVideosInSearch(value: Boolean) {
        includeYoutubeMusicVideosInSearch.value = value
        prefs.edit().putBoolean(KEY_INCLUDE_YOUTUBE_MUSIC_VIDEOS_IN_SEARCH, value).apply()
    }

    fun setHideLosslessLabel(value: Boolean) {
        hideLosslessLabel.value = value
        prefs.edit().putBoolean(KEY_HIDE_LOSSLESS_LABEL, value).apply()
    }

    fun setShowTrackDurationInsteadOfRemaining(value: Boolean) {
        showTrackDurationInsteadOfRemaining.value = value
        prefs.edit().putBoolean(KEY_SHOW_TRACK_DURATION_INSTEAD_OF_REMAINING, value).apply()
    }

    fun setPopupExternalAudioPlayer(value: Boolean) {
        popupExternalAudioPlayer.value = value
        prefs.edit().putBoolean(KEY_POPUP_EXTERNAL_AUDIO_PLAYER, value).apply()
    }

    fun setReplayGainEnabled(value: Boolean) {
        replayGainEnabled.value = value
        prefs.edit().putBoolean(KEY_REPLAYGAIN_ENABLED, value).apply()
    }

    fun setReplayGainAlbumMode(value: Boolean) {
        replayGainAlbumMode.value = value
        prefs.edit().putBoolean(KEY_REPLAYGAIN_ALBUM_MODE, value).apply()
    }

    fun setReplayGainPreampDb(value: Float) {
        val normalized = value.coerceIn(-12f, 12f)
        replayGainPreampDb.value = normalized
        prefs.edit().putFloat(KEY_REPLAYGAIN_PREAMP_DB, normalized).apply()
    }

    fun setReplayGainPreventClipping(value: Boolean) {
        replayGainPreventClipping.value = value
        prefs.edit().putBoolean(KEY_REPLAYGAIN_PREVENT_CLIPPING, value).apply()
    }

    fun setVolumeBoostEnabled(value: Boolean) {
        volumeBoostEnabled.value = value
        prefs.edit().putBoolean(KEY_VOLUME_BOOST_ENABLED, value).apply()
    }

    fun setVolumeBoostDb(value: Float) {
        val normalized = value.coerceIn(0f, 12f)
        volumeBoostDb.value = normalized
        prefs.edit().putFloat(KEY_VOLUME_BOOST_DB, normalized).apply()
    }

    fun setPillWidgetOpacity(value: Float) {
        val normalized = value.coerceIn(0f, 100f)
        pillWidgetOpacity.value = normalized
        prefs.edit().putFloat(KEY_PILL_WIDGET_OPACITY, normalized).apply()
        if (this::appContext.isInitialized) {
            com.music.bitchord.widget.MediaWidgetPill.refresh(appContext)
        }
    }

    fun setLoudnessNormalization(value: Boolean) {
        loudnessNormalization.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_NORMALIZATION, value).apply()
    }

    fun setLoudnessOffOnSpeaker(value: Boolean) {
        loudnessOffOnSpeaker.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, value).apply()
    }

    fun setFavoriteBesideTrackMenu(value: Boolean) {
        favoriteBesideTrackMenu.value = value
        prefs.edit().putBoolean(KEY_FAVORITE_BESIDE_TRACK_MENU, value).apply()
        val actions = playerQuickActions.value.toMutableList()
        if (value && PlayerQuickAction.FAVORITE !in actions) actions += PlayerQuickAction.FAVORITE
        if (!value) actions.remove(PlayerQuickAction.FAVORITE)
        playerQuickActions.value = actions
        prefs.edit().putString(KEY_PLAYER_QUICK_ACTIONS, actions.joinToString(",") { it.name }).apply()
    }

    fun setPreventPlayAtZeroVolume(value: Boolean) {
        preventPlayAtZeroVolume.value = value
        prefs.edit().putBoolean(KEY_PREVENT_PLAY_AT_ZERO_VOLUME, value).apply()
    }

    fun setPausePlaybackAtZeroVolume(value: Boolean) {
        pausePlaybackAtZeroVolume.value = value
        prefs.edit().putBoolean(KEY_PAUSE_PLAYBACK_AT_ZERO_VOLUME, value).apply()
    }

    fun setAudioFocusLevel(value: Int) {
        val normalized = value.coerceIn(0, 4)
        audioFocusLevel.value = normalized
        prefs.edit().putInt(KEY_AUDIO_FOCUS_LEVEL, normalized).apply()
    }

    fun setUseLibraryIconForPlaylistControl(value: Boolean) {
        useLibraryIconForPlaylistControl.value = value
        prefs.edit().putBoolean(KEY_LIBRARY_ICON_FOR_PLAYLIST_CONTROL, value).apply()
    }

    fun movePlayerControl(control: PlayerControl, offset: Int) {
        val updated = PlayerControlOrdering.move(playerControlOrder.value, control, offset)
        if (updated == playerControlOrder.value) return
        playerControlOrder.value = updated
        prefs.edit().putString(KEY_PLAYER_CONTROL_ORDER, updated.joinToString(",") { it.name }).apply()
    }

    private fun readPlayerControlOrder(): List<PlayerControl> {
        val saved = prefs.getString(KEY_PLAYER_CONTROL_ORDER, null)
            ?.split(",")
            ?.mapNotNull { name -> PlayerControl.entries.firstOrNull { it.name == name } }
            .orEmpty()
        return PlayerControlOrdering.normalize(saved)
    }

    fun setHidePlayerArtist(value: Boolean) {
        hidePlayerArtist.value = value
        prefs.edit().putBoolean(KEY_HIDE_PLAYER_ARTIST, value).apply()
    }

    fun setHideUnknownPlayerArtist(value: Boolean) {
        hideUnknownPlayerArtist.value = value
        prefs.edit().putBoolean(KEY_HIDE_UNKNOWN_PLAYER_ARTIST, value).apply()
    }

    fun setCenterPlayerTrackInfo(value: Boolean) {
        centerPlayerTrackInfo.value = value
        prefs.edit().putBoolean(KEY_CENTER_PLAYER_TRACK_INFO, value).apply()
    }

    fun setPlayerDetailsVerticalMenu(value: Boolean) {
        playerDetailsVerticalMenu.value = value
        prefs.edit().putBoolean(KEY_PLAYER_DETAILS_VERTICAL_MENU, value).apply()
    }

    fun setPreloadAlbumArtOnStartup(value: Boolean) {
        if (value) setPersistentLocalArtwork(true)
        preloadAlbumArtOnStartup.value = value
        prefs.edit().putBoolean(KEY_PRELOAD_ALBUM_ART_ON_STARTUP, value).apply()
    }

    fun setPlayerControlVisible(control: PlayerControl, visible: Boolean) {
        val updated = visiblePlayerControls.value.toMutableSet().apply {
            if (visible) add(control) else remove(control)
        }
        if (updated.isEmpty()) return
        visiblePlayerControls.value = updated
        prefs.edit().putStringSet(KEY_VISIBLE_PLAYER_CONTROLS, updated.mapTo(mutableSetOf()) { it.name }).apply()
    }

    private fun readVisiblePlayerControls(): Set<PlayerControl> =
        prefs.getStringSet(KEY_VISIBLE_PLAYER_CONTROLS, null)
            ?.mapNotNullTo(mutableSetOf()) { saved -> PlayerControl.entries.firstOrNull { it.name == saved } }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PLAYER_CONTROLS

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.edit().putBoolean(KEY_SYNCED_LYRICS, value).apply()
    }

    fun setShowLyricsFurigana(value: Boolean) {
        showLyricsFurigana.value = value
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_FURIGANA, value).apply()
    }

    fun setAutoFitOneLineLyrics(value: Boolean) {
        autoFitOneLineLyrics.value = value
        prefs.edit().putBoolean(KEY_AUTO_FIT_ONE_LINE_LYRICS, value).apply()
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        lyricsSources.value = value
        prefs.edit().putString(KEY_LYRICS_SOURCES, value.joinToString(",") { it.id }).apply()
    }

    /** Stores an optional provider key encrypted; failure never falls back to plain preferences. */
    fun setPaxSenixApiKey(value: String): Boolean {
        val normalized = com.music.bitchord.data.lyrics.normalizePaxSenixApiKey(value)
        if (!authStore.setProtectedValueStrict(KEY_PAXSENIX_API_KEY, normalized)) return false
        paxSenixApiKey.value = normalized
        com.music.bitchord.data.lyrics.PaxSenix.setApiKey(normalized)
        return true
    }

    /**
     * Stored as a joined list of IDs rather than a string set: an ID that
     * no longer exists — a source dropped or deleted from the repo — falls out
     * quietly, and the default when nothing has been saved is "all of them".
     */
    private fun readLyricsSources(allSources: List<LyricsSource>): Set<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCES, null)
            ?: return allSources.toSet()
        val ids = stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val filtered = allSources.filter { it.id in ids }.toSet()
        return filtered.ifEmpty { allSources.toSet() }
    }

    fun setLyricsSourceOrder(value: List<LyricsSource>) {
        lyricsSourceOrder.value = value
        prefs.edit().putString(KEY_LYRICS_SOURCE_ORDER, value.joinToString(",") { it.id }).apply()
    }

    private fun readLyricsSourceOrder(allSources: List<LyricsSource>): List<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCE_ORDER, null)
            ?: return allSources
        val ids = stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val saved = ids.mapNotNull { id -> allSources.firstOrNull { it.id == id } }
        return saved + allSources.filter { it !in saved }
    }

    fun refreshLyricsSources(allSources: List<LyricsSource>) {
        if (allSources.isEmpty()) return
        val currentEnabled = readLyricsSources(allSources)
        val currentOrder = readLyricsSourceOrder(allSources)
        lyricsSources.value = currentEnabled
        lyricsSourceOrder.value = currentOrder
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, value).apply()
    }

    fun setShowLyricsLogs(value: Boolean) {
        showLyricsLogs.value = value
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_LOGS, value).apply()
    }

    fun setAutoEmbedLyrics(value: Boolean) {
        autoEmbedLyrics.value = value
        prefs.edit().putBoolean(KEY_AUTO_EMBED_LYRICS, value).apply()
    }

    fun setLyricsExtensionRepoUrl(value: String) {
        val normalized = value.trim()
        lyricsExtensionRepoUrl.value = normalized
        prefs.edit().putString(KEY_LYRICS_EXTENSION_REPO_URL, normalized).apply()
    }

    fun setLyricsExtensionsEnabled(value: Boolean) {
        lyricsExtensionsEnabled.value = value
        prefs.edit().putBoolean(KEY_LYRICS_EXTENSIONS_ENABLED, value).apply()
        com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager.setEnabled(value)
    }

    fun setLyricsAutoUpdate(value: Boolean) {
        lyricsAutoUpdate.value = value
        prefs.edit().putBoolean(KEY_LYRICS_AUTO_UPDATE, value).apply()
    }

    fun setLastLyricsExtensionSync(value: Long) {
        lastLyricsExtensionSyncMs.value = value
        prefs.edit().putLong(KEY_LAST_LYRICS_EXTENSION_SYNC, value).apply()
    }

    /**
     * Puts the source list, its order and [prioritizeSyllableSync] back the
     * way a fresh install finds them. [syncedLyrics] itself is left alone —
     * this is "start over on *which* lyrics", not "turn lyrics off".
     */
    fun resetLyricsSourceSettings() {
        val all = com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager.dynamicSources.value
        setLyricsSources(all.toSet())
        setLyricsSourceOrder(all)
        setPrioritizeSyllableSync(false)
        setShowLyricsLogs(false)
    }

    fun setAnimatedCanvas(value: Boolean) {
        animatedCanvas.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_CANVAS, value).apply()
    }

    fun setCanvasOverCellular(value: Boolean) {
        canvasOverCellular.value = value
        prefs.edit().putBoolean(KEY_CANVAS_OVER_CELLULAR, value).apply()
    }

    fun setFullBleedArtwork(value: Boolean) {
        fullBleedArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_ARTWORK, value).apply()
    }

    fun setKeepOriginalArtworkAspectRatio(value: Boolean) {
        keepOriginalArtworkAspectRatio.value = value
        prefs.edit().putBoolean(KEY_KEEP_ORIGINAL_ARTWORK_ASPECT_RATIO, value).apply()
    }

    fun setKeepCollectionArtworkAspectRatio(value: Boolean) {
        keepCollectionArtworkAspectRatio.value = value
        prefs.edit().putBoolean(KEY_KEEP_COLLECTION_ARTWORK_ASPECT_RATIO, value).apply()
    }

    fun setLocalizeLibraryCollectionCount(value: Boolean) {
        localizeLibraryCollectionCount.value = value
        prefs.edit().putBoolean(KEY_LOCALIZE_LIBRARY_COLLECTION_COUNT, value).apply()
    }

    fun setPreserveArtworkShapeInCompactTransitions(value: Boolean) {
        preserveArtworkShapeInCompactTransitions.value = value
        prefs.edit().putBoolean(KEY_PRESERVE_ARTWORK_SHAPE_IN_COMPACT_TRANSITIONS, value).apply()
    }

    fun setKeepArtworkFullSizeWhenPaused(value: Boolean) {
        keepArtworkFullSizeWhenPaused.value = value
        prefs.edit().putBoolean(KEY_KEEP_ARTWORK_FULL_SIZE_PAUSED, value).apply()
    }

    fun setDoubleTapToSeek(value: Boolean) {
        doubleTapToSeek.value = value
        prefs.edit().putBoolean(KEY_DOUBLE_TAP_TO_SEEK, value).apply()
    }

    fun setSeekIntervalSeconds(value: Int) {
        val safe = value.coerceIn(1, 60)
        seekIntervalSeconds.value = safe
        prefs.edit().putInt(KEY_SEEK_INTERVAL_SECONDS, safe).apply()
    }

    fun setRecentlyPlayedThresholdSeconds(value: Int) {
        val safe = value.coerceIn(0, MAX_RECENT_PLAY_THRESHOLD_SECONDS)
        recentlyPlayedThresholdSeconds.value = safe
        prefs.edit().putInt(KEY_RECENT_PLAY_THRESHOLD_SECONDS, safe).apply()
    }

    fun setSeekButtonMode(value: SeekButtonMode) {
        seekButtonMode.value = value
        prefs.edit().putString(KEY_SEEK_BUTTON_MODE, value.name).apply()
    }

    fun setTransportLeftAction(value: TransportSideAction) {
        transportLeftAction.value = value
        prefs.edit().putString(KEY_TRANSPORT_LEFT_ACTION, value.name).apply()
    }

    fun setTransportRightAction(value: TransportSideAction) {
        transportRightAction.value = value
        prefs.edit().putString(KEY_TRANSPORT_RIGHT_ACTION, value.name).apply()
    }

    fun setHideSeekButtonLabels(value: Boolean) {
        hideSeekButtonLabels.value = value
        prefs.edit().putBoolean(KEY_HIDE_SEEK_BUTTON_LABELS, value).apply()
    }

    fun setLegacyMeshGradient(value: Boolean) {
        legacyMeshGradient.value = value
        prefs.edit().putBoolean(KEY_LEGACY_MESH_GRADIENT, value).apply()
    }

    /** Clamped to [DEFAULT_CACHE_LIMIT_BYTES]..[MAX_CACHE_LIMIT_BYTES] — the floor is the default, not zero. */
    fun setAudioCacheLimitBytes(value: Long) {
        val clamped = value.coerceIn(DEFAULT_CACHE_LIMIT_BYTES, MAX_CACHE_LIMIT_BYTES)
        audioCacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    fun setLastfmEnabled(value: Boolean) {
        lastfmEnabled.value = value
        prefs.edit().putBoolean(KEY_LASTFM_ENABLED, value).apply()
    }

    fun setLastfmUsername(value: String) {
        lastfmUsername.value = value
        prefs.edit().putString(KEY_LASTFM_USERNAME, value).apply()
    }

    fun setLastfmSessionKey(value: String) {
        lastfmSessionKey.value = value
        authStore.setProtectedValue(KEY_LASTFM_SESSION_KEY, value)
    }

    fun setLastfmApiKey(value: String) {
        lastfmApiKey.value = value
        authStore.setProtectedValue(KEY_LASTFM_API_KEY, value)
    }

    fun setLastfmSecret(value: String) {
        lastfmSecret.value = value
        authStore.setProtectedValue(KEY_LASTFM_SECRET, value)
    }

    fun setLastfmEndpoint(value: String) {
        lastfmEndpoint.value = value
        prefs.edit().putString(KEY_LASTFM_ENDPOINT, value).apply()
    }

    fun setSpotifySpdcToken(value: String) {
        spotifySpdcToken.value = value
        authStore.setProtectedValue(KEY_SPOTIFY_SPDC_TOKEN, value)
    }

    fun setLastfmScrobbleEnabled(value: Boolean) {
        lastfmScrobbleEnabled.value = value
        if (!value) lastfmNowPlaying.value = false
        prefs.edit()
            .putBoolean(KEY_LASTFM_SCROBBLE_ENABLED, value)
            .putBoolean(KEY_LASTFM_NOW_PLAYING, if (value) lastfmNowPlaying.value else false)
            .apply()
    }

    fun setLastfmNowPlaying(value: Boolean) {
        if (!lastfmScrobbleEnabled.value && value) return
        lastfmNowPlaying.value = value
        prefs.edit().putBoolean(KEY_LASTFM_NOW_PLAYING, value).apply()
    }

    fun setOutputPcmMode(value: OutputPcmMode) {
        outputPcmMode.value = value
        prefs.edit().putString(KEY_OUTPUT_PCM_MODE, value.name).apply()
    }

    fun setBitPerfectPlayback(value: Boolean) {
        bitPerfectPlayback.value = value
        prefs.edit().putBoolean(KEY_BIT_PERFECT_PLAYBACK, value).apply()
    }

    fun setPreferUsbDac(value: Boolean) {
        preferUsbDac.value = value
        prefs.edit().putBoolean(KEY_PREFER_USB_DAC, value).apply()
    }

    fun setSpeakerPlaybackAllowed(value: Boolean) {
        speakerPlaybackAllowed.value = value
        prefs.edit().putBoolean(KEY_SPEAKER_PLAYBACK_ALLOWED, value).apply()
    }

    fun setExportDownloads(value: Boolean) {
        exportDownloads.value = value
        prefs.edit().putBoolean(KEY_EXPORT_DOWNLOADS, value).apply()
    }

    fun setLastfmPrimaryArtistOnly(value: Boolean) {
        lastfmPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, value).apply()
    }

    fun setScrobbleMinDuration(value: Int) {
        scrobbleMinDuration.value = value
        prefs.edit().putInt(KEY_SCROBBLE_MIN_DURATION, value).apply()
    }

    fun setScrobbleDelayPercent(value: Float) {
        scrobbleDelayPercent.value = value
        prefs.edit().putFloat(KEY_SCROBBLE_DELAY_PERCENT, value).apply()
    }

    fun setScrobbleDelaySeconds(value: Int) {
        scrobbleDelaySeconds.value = value
        prefs.edit().putInt(KEY_SCROBBLE_DELAY_SECONDS, value).apply()
    }

    fun setListenBrainzEnabled(value: Boolean) {
        listenBrainzEnabled.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_ENABLED, value).apply()
    }

    fun setListenBrainzToken(value: String) {
        listenBrainzToken.value = value
        authStore.setProtectedValue(KEY_LISTENBRAINZ_TOKEN, value)
    }

    fun setListenBrainzPrimaryArtistOnly(value: Boolean) {
        listenBrainzPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, value).apply()
    }

    /** Writes through to the encrypted store; pass "" to disconnect. */
    fun setDiscordToken(value: String) {
        discordToken.value = value
        authStore.discordToken = value.ifEmpty { null }
    }

    fun setDiscordAccount(username: String, name: String, avatar: String?) {
        discordUsername.value = username
        discordName.value = name
        discordAvatar.value = avatar.orEmpty()
        prefs.edit()
            .putString(KEY_DISCORD_USERNAME, username)
            .putString(KEY_DISCORD_NAME, name)
            .putString(KEY_DISCORD_AVATAR, avatar.orEmpty())
            .apply()
    }

    fun setDiscordRpcEnabled(value: Boolean) {
        discordRpcEnabled.value = value
        prefs.edit().putBoolean(KEY_DISCORD_RPC_ENABLED, value).apply()
    }

    fun setDiscordUseDetails(value: Boolean) {
        discordUseDetails.value = value
        prefs.edit().putBoolean(KEY_DISCORD_USE_DETAILS, value).apply()
    }

    fun setDiscordAdvancedMode(value: Boolean) {
        discordAdvancedMode.value = value
        prefs.edit().putBoolean(KEY_DISCORD_ADVANCED_MODE, value).apply()
    }

    fun setDiscordStatus(value: String) {
        discordStatus.value = value
        prefs.edit().putString(KEY_DISCORD_STATUS, value).apply()
    }

    fun setDiscordActivityType(value: String) {
        discordActivityType.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_TYPE, value).apply()
    }

    fun setDiscordActivityName(value: String) {
        discordActivityName.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_NAME, value).apply()
    }

    fun setDiscordButton1Text(value: String) {
        discordButton1Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_1_TEXT, value).apply()
    }

    fun setDiscordButton1Visible(value: Boolean) {
        discordButton1Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, value).apply()
    }

    fun setDiscordButton2Text(value: String) {
        discordButton2Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_2_TEXT, value).apply()
    }

    fun setDiscordButton2Visible(value: Boolean) {
        discordButton2Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, value).apply()
    }

    fun setDiscordInfoDismissed(value: Boolean) {
        discordInfoDismissed.value = value
        prefs.edit().putBoolean(KEY_DISCORD_INFO_DISMISSED, value).apply()
    }

    fun setReplayGenres(value: Boolean) {
        replayGenres.value = value
        prefs.edit().putBoolean(KEY_REPLAY_GENRES, value).apply()
    }

    fun setFilterNonMusicAudio(value: Boolean) {
        filterNonMusicAudio.value = value
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC_AUDIO, value).apply()
    }

    fun setAllFilesPermissionAsked(value: Boolean) {
        allFilesPermissionAsked.value = value
        prefs.edit().putBoolean(KEY_ALL_FILES_PERMISSION_ASKED, value).apply()
    }

    fun setLocalMusicSort(value: LocalMusicSort) {
        localMusicSort.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_SORT, value.name).apply()
    }

    fun setDownloadedMusicSort(value: LocalMusicSort) {
        downloadedMusicSort.value = value
        prefs.edit().putString(KEY_DOWNLOADED_MUSIC_SORT, value.name).apply()
    }

    fun setLocalArtistSort(value: ArtistSort) {
        localArtistSort.value = value
        prefs.edit().putString(KEY_LOCAL_ARTIST_SORT, value.name).apply()
    }

    fun setDownloadedArtistSort(value: ArtistSort) {
        downloadedArtistSort.value = value
        prefs.edit().putString(KEY_DOWNLOADED_ARTIST_SORT, value.name).apply()
    }

    fun setLibrarySort(value: LibrarySort) {
        librarySort.value = value
        prefs.edit().putString(KEY_LIBRARY_SORT, value.name).apply()
    }

    fun setDetailSongSort(browseId: String, value: SongSort) {
        detailSongSorts.value = detailSongSorts.value + (browseId to value)
        prefs.edit().putString(
            KEY_DETAIL_SONG_SORTS,
            detailSongSorts.value.entries.joinToString(",") { (id, sort) -> "$id=${sort.name}" },
        ).apply()
    }

    fun setLocalSongsViewType(value: LibraryViewType) {
        localSongsViewType.value = value
        localMusicViewType.value = value
        prefs.edit().putString(KEY_LOCAL_SONGS_VIEW_TYPE, value.name).apply()
    }

    fun setLocalAlbumsViewType(value: LibraryViewType) {
        localAlbumsViewType.value = value
        prefs.edit().putString(KEY_LOCAL_ALBUMS_VIEW_TYPE, value.name).apply()
    }

    fun setLocalArtistsViewType(value: LibraryViewType) {
        localArtistsViewType.value = value
        prefs.edit().putString(KEY_LOCAL_ARTISTS_VIEW_TYPE, value.name).apply()
    }

    fun setLocalLibraryTabVisible(tab: LocalLibraryTab, visible: Boolean) {
        val current = visibleLocalLibraryTabs.value
        if (!visible && current.size <= 1) return
        val updated = if (visible) current + tab else current - tab
        if (updated == current) return
        visibleLocalLibraryTabs.value = updated
        prefs.edit().putStringSet(KEY_VISIBLE_LOCAL_LIBRARY_TABS, updated.map { it.name }.toSet()).apply()
        if (tab == LocalLibraryTab.ALBUM_ARTISTS && !visible && startupTab.value == MainNavigationTab.ALBUM_ARTISTS) {
            setStartupTab(MainNavigationTabs.fallback(
                startupTab.value.index,
                effectiveMainNavigationTabs(),
                mainNavigationTabOrder.value,
            ))
        }
    }

    fun moveLocalLibraryTab(tab: LocalLibraryTab, offset: Int) {
        val current = localLibraryTabOrder.value
        val from = current.indexOf(tab)
        if (from < 0) return
        val to = (from + offset).coerceIn(0, current.lastIndex)
        if (from == to) return
        val updated = current.toMutableList().apply { add(to, removeAt(from)) }
        localLibraryTabOrder.value = updated
        prefs.edit().putString(KEY_LOCAL_LIBRARY_TAB_ORDER, updated.joinToString(",") { it.name }).apply()
    }

    fun setLocalFolderHierarchy(value: Boolean) {
        localFolderHierarchy.value = value
        prefs.edit().putBoolean(KEY_LOCAL_FOLDER_HIERARCHY, value).apply()
    }

    fun setIncludeRemoteSourceMediaInLibrary(value: Boolean) {
        includeRemoteSourceMediaInLibrary.value = value
        prefs.edit().putBoolean(KEY_INCLUDE_REMOTE_SOURCE_MEDIA_IN_LIBRARY, value).apply()
    }

    fun setLocalMusicFolderPath(path: String) {
        val safePath = path.trim().take(4096)
        localMusicFolderUri.value = safePath
        prefs.edit().putString(KEY_LOCAL_MUSIC_FOLDER_PATH, safePath).apply()
    }

    fun setWebDavUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        webdavUrl.value = normalized
        prefs.edit().putString(KEY_WEBDAV_URL, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavAllowInsecureHttp(value: Boolean) {
        webdavAllowInsecureHttp.value = value
        prefs.edit().putBoolean(KEY_WEBDAV_ALLOW_HTTP, value).apply()
        publishWebDavAuth()
    }

    fun setWebDavUsername(value: String) {
        val normalized = value.trim()
        webdavUsername.value = normalized
        authStore.setProtectedValue(KEY_WEBDAV_USERNAME, normalized)
        publishWebDavAuth()
    }

    fun setWebDavPassword(value: String) {
        webdavPassword.value = value
        authStore.setProtectedValue(KEY_WEBDAV_PASSWORD, value)
        publishWebDavAuth()
    }

    fun setSmbHost(value: String) {
        val normalized = value.trim()
        smbHost.value = normalized
        prefs.edit().putString(KEY_SMB_HOST, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbShare(value: String) {
        val normalized = value.trim().trim('/')
        smbShare.value = normalized
        prefs.edit().putString(KEY_SMB_SHARE, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbBasePath(value: String) {
        val normalized = value.trim().trim('/').replace('\\', '/')
        smbBasePath.value = normalized
        prefs.edit().putString(KEY_SMB_BASE_PATH, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbUsername(value: String) {
        val normalized = value.trim()
        smbUsername.value = normalized
        authStore.setProtectedValue(KEY_SMB_USERNAME, normalized)
        publishSmbAuth()
    }

    fun setSmbPassword(value: String) {
        smbPassword.value = value
        authStore.setProtectedValue(KEY_SMB_PASSWORD, value)
        publishSmbAuth()
    }

    fun clearWebDav() {
        setWebDavUrl("")
        setWebDavAllowInsecureHttp(false)
        setWebDavUsername("")
        setWebDavPassword("")
    }

    fun clearSmb() {
        setSmbHost("")
        setSmbShare("")
        setSmbBasePath("")
        setSmbUsername("")
        setSmbPassword("")
    }

    private fun publishSmbAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.music.bitchord.data.smb.SmbAuth.update(
            smbHost.value, smbShare.value, smbBasePath.value, smbUsername.value, smbPassword.value,
        )
    }

    private fun publishWebDavAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.music.bitchord.data.webdav.WebDavAuth.update(
            webdavUrl.value, webdavUsername.value, webdavPassword.value, webdavAllowInsecureHttp.value,
        )
    }

    fun setFullScreenCollectionArtwork(value: Boolean) {
        fullScreenCollectionArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_SCREEN_COLLECTION_ARTWORK, value).apply()
    }

    fun setHideCollectionTopBar(value: Boolean) {
        hideCollectionTopBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_COLLECTION_TOP_BAR, value).apply()
    }

    fun setHideFloatingCollectionControls(value: Boolean) {
        hideFloatingCollectionControls.value = value
        prefs.edit().putBoolean(KEY_HIDE_FLOATING_COLLECTION_CONTROLS, value).apply()
    }

    fun setArtistsOpenAlbums(value: Boolean) {
        artistsOpenAlbums.value = value
        prefs.edit().putBoolean(KEY_ARTISTS_OPEN_ALBUMS, value).apply()
    }

    fun setShuffleArtistAlbums(value: Boolean) {
        shuffleArtistAlbums.value = value
        prefs.edit().putBoolean(KEY_SHUFFLE_ARTIST_ALBUMS, value).apply()
    }

    fun setOnlineAlbumArtistArtwork(value: Boolean) {
        onlineAlbumArtistArtwork.value = value
        prefs.edit().putBoolean(KEY_ONLINE_ALBUM_ARTIST_ARTWORK, value).apply()
    }

    fun setLocalMusicViewType(value: LibraryViewType) {
        setLocalSongsViewType(value)
    }

    fun setDownloadedSongsViewType(value: LibraryViewType) {
        downloadedSongsViewType.value = value
        downloadedMusicViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_SONGS_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedAlbumsViewType(value: LibraryViewType) {
        downloadedAlbumsViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_ALBUMS_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedArtistsViewType(value: LibraryViewType) {
        downloadedArtistsViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_ARTISTS_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedMusicViewType(value: LibraryViewType) {
        setDownloadedSongsViewType(value)
    }

    fun setLibraryPlaylistsViewType(value: LibraryViewType) {
        libraryPlaylistsViewType.value = value
        prefs.edit().putString(KEY_LIBRARY_PLAYLISTS_VIEW_TYPE, value.name).apply()
    }

    fun setLibrarySongsViewType(value: LibraryViewType) {
        librarySongsViewType.value = value
        prefs.edit().putString(KEY_LIBRARY_SONGS_VIEW_TYPE, value.name).apply()
    }

    fun setShowPlaylistSongArtwork(value: Boolean) {
        showPlaylistSongArtwork.value = value
        prefs.edit().putBoolean(KEY_SHOW_PLAYLIST_SONG_ARTWORK, value).apply()
    }

    fun setShowCollectionSongArtwork(value: Boolean) {
        showCollectionSongArtwork.value = value
        prefs.edit().putBoolean(KEY_SHOW_COLLECTION_SONG_ARTWORK, value).apply()
    }

    fun setLocalDrillDownSongsViewType(value: LibraryViewType) {
        localDrillDownSongsViewType.value = value
        prefs.edit().putString(KEY_LOCAL_DRILLDOWN_SONGS_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedDrillDownSongsViewType(value: LibraryViewType) {
        downloadedDrillDownSongsViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_DRILLDOWN_SONGS_VIEW_TYPE, value.name).apply()
    }


    fun setFolderBlacklisted(folderPath: String, blacklisted: Boolean) {
        val normalized = folderPath.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
        val current = blacklistedFolders.value
        val updated = if (blacklisted) current + normalized else current - normalized
        blacklistedFolders.value = updated
        prefs.edit().putStringSet(KEY_BLACKLISTED_FOLDERS, updated).apply()
    }

    fun isFolderBlacklisted(folderPath: String?): Boolean {
        if (folderPath.isNullOrBlank()) return false
        val normalized = folderPath.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
        val blacklisted = blacklistedFolders.value
        return blacklisted.any { b ->
            normalized == b || normalized.startsWith("$b/")
        }
    }

    private fun readLocalMusicSort(key: String): LocalMusicSort =
        prefs.getString(key, null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } }
            ?: LocalMusicSort.TITLE_ASC

    private fun readArtistSort(key: String): ArtistSort =
        prefs.getString(key, null)
            ?.let { saved -> ArtistSort.entries.firstOrNull { it.name == saved } }
            ?: ArtistSort.MOST_SONGS

    private fun readLibraryViewType(key: String, default: LibraryViewType = LibraryViewType.LIST): LibraryViewType =
        prefs.getString(key, null)
            ?.let { saved -> LibraryViewType.entries.firstOrNull { it.name == saved } }
            ?: default

    /**
     * Pins or unpins [browseId], returning whether it is pinned afterwards.
     *
     * Pinning past [MAX_PINNED_PLAYLISTS] is refused rather than evicting the
     * oldest pin: a silent swap would mean a playlist someone pinned on purpose
     * disappears from the row without them ever having touched it, the moment
     * they pin a sixth. Unpinning always succeeds.
     */
    fun togglePinnedPlaylist(browseId: String): Boolean {
        val current = pinnedPlaylists.value
        val updated = when {
            browseId in current -> current - browseId
            current.size >= MAX_PINNED_PLAYLISTS -> return false
            else -> current + browseId
        }
        pinnedPlaylists.value = updated
        prefs.edit().putString(KEY_PINNED_PLAYLISTS, updated.joinToString(",")).apply()
        return browseId in updated
    }

    private fun readDetailSongSorts(): Map<String, SongSort> =
        prefs.getString(KEY_DETAIL_SONG_SORTS, null)
            ?.split(",")
            ?.mapNotNull { entry ->
                val id = entry.substringBefore('=', "")
                val sort = SongSort.entries.firstOrNull { it.name == entry.substringAfter('=', "") }
                if (id.isBlank() || sort == null) null else id to sort
            }
            ?.toMap()
            ?: emptyMap()

    private fun readPinnedPlaylists(): List<String> {
        val stored = prefs.getString(KEY_PINNED_PLAYLISTS, null) ?: return emptyList()
        return stored.split(",").filter { it.isNotBlank() }
    }

    /** Forgets the account: token and cached profile. */
    fun clearDiscordAccount() {
        setDiscordToken("")
        setDiscordAccount("", "", null)
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    /**
     * Every stored preference, for an export.
     *
     * Read off the preference file wholesale rather than assembled from the
     * flows above, so a setting added in a later build is in the backup the day
     * it is added instead of the day somebody remembers to list it here. What is
     * *left out* is therefore the part worth stating explicitly, and it is
     * [SECRETS], [PROFILE_DATA], and [DEVICE_LOCAL]. Credentials stay on the
     * device because an exported file may be shared. Non-secret integration
     * preferences, including scrobbling and Discord presence options, are
     * included with the rest of the settings.
     *
     * The Discord token is not here for the same reason and one more: it never
     * reaches this file. It lives in the encrypted store — see [AuthStore] — and
     * so does the YouTube cookie, which means neither can be exported by
     * accident.
     */
    fun exportPrefs(): Map<String, Any?> {
        if (!this::prefs.isInitialized) return emptyMap()
        return prefs.all.filterKeys {
            it !in SECRETS &&
                it !in PROFILE_DATA &&
                it !in DEVICE_LOCAL &&
                it !in OBSOLETE_ONLINE_KEYS
        }
    }

    /**
     * Replaces the portable preferences with [values] and re-reads them.
     *
     * A replace, not a merge: a partial restore leaves a device holding half of
     * one configuration and half of another, which is the one outcome nobody
     * asked for. Credentials, account identity, and device-local data survive
     * untouched; a backup never contains those values and cannot sign the user
     * out or replace the identity already on this device.
     */
    fun importPrefs(values: Map<String, Any?>) {
        if (!this::prefs.isInitialized) return
        val kept = prefs.all.filterKeys { it in PROFILE_DATA || it in DEVICE_LOCAL }
        prefs.edit().apply {
            clear()
            val incoming = values.filterKeys {
                it !in SECRETS &&
                    it !in PROFILE_DATA &&
                    it !in DEVICE_LOCAL &&
                    it !in OBSOLETE_ONLINE_KEYS
            }
            (kept + incoming).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }.apply()
        reload()
    }

    /**
     * Preferences an export must not carry — credentials, not configuration.
     * See [exportPrefs].
     */
    private val SECRETS = setOf(
        KEY_LASTFM_SESSION_KEY,
        KEY_LASTFM_API_KEY,
        KEY_LASTFM_SECRET,
        KEY_LISTENBRAINZ_TOKEN,
        KEY_SPOTIFY_SPDC_TOKEN,
        KEY_WEBDAV_USERNAME,
        KEY_WEBDAV_PASSWORD,
        KEY_SMB_USERNAME,
        KEY_SMB_PASSWORD,
    )

    /** Account identity is kept on this device but is not an application setting. */
    private val PROFILE_DATA = setOf(
        KEY_LASTFM_USERNAME,
        KEY_DISCORD_USERNAME,
        KEY_DISCORD_NAME,
        KEY_DISCORD_AVATAR,
    )

    /**
     * Preferences that describe *this device* rather than this configuration,
     * and so are neither exported nor overwritten by an import.
     */
    private val DEVICE_LOCAL = setOf(
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
        KEY_LAST_VERSION_CODE,
        KEY_LOCAL_MUSIC_FOLDER_PATH,
        KEY_CD_RIPPING_OUTPUT_TREE_URI,
    )

    private val OBSOLETE_ONLINE_KEYS = setOf(
        "audio_quality",
        "audio_quality_wifi",
        "audio_quality_cellular",
        "audio_quality_download",
        "wifi_only_downloads",
        "export_downloads",
        "lossless_audio",
        "audio_cache_limit_bytes",
        "animated_canvas",
        "canvas_over_cellular",
        "lyrics_sourceOrder",
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
    )

    const val DEFAULT_CACHE_LIMIT_BYTES = 512L * 1024 * 1024
    const val MAX_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    private const val DEFAULT_PERFORMANCE_REFRESH_RATE = 120
    private const val MAX_LYRICS_TRANSPORT_CONTROL_SIZE = 64f
    const val MAX_CROSSFADE_SECONDS = 12
    const val MAX_RECENT_PLAY_THRESHOLD_SECONDS = 300
    private const val DEFAULT_RECENT_PLAY_THRESHOLD_SECONDS = 30

    private fun normalizeResamplerCutoffHz(value: Int): Int =
        if (value <= 0) 0 else value.coerceIn(8_000, 22_000)

    private fun normalizePerformanceRefreshRate(value: Int): Int =
        value.takeIf { it in 50..240 } ?: DEFAULT_PERFORMANCE_REFRESH_RATE

    private const val KEY_QUALITY_LEGACY = "audio_quality"
    private const val KEY_QUALITY_WIFI = "audio_quality_wifi"
    private const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
    private const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
    private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
    private const val KEY_EXPORT_DOWNLOADS = "export_downloads"
    private const val KEY_LOSSLESS = "lossless_audio"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_AUDIO_OFFLOAD_ENABLED = "audio_offload_enabled"
    private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
    private const val KEY_CD_RIPPING_OUTPUT_TREE_URI = "cd_ripping_output_tree_uri"
    private const val KEY_CD_RIPPING_RETRIES = "cd_ripping_retries"
    private const val KEY_CD_RIPPING_SPEED_X = "cd_ripping_speed_x"
    private const val KEY_SMART_FADE = "smart_fade_enabled"
    private const val KEY_AUTOMIX_PERFORMANCE_MODE = "automix_performance_mode"
    private const val KEY_RESAMPLER_CUTOFF_HZ = "resampler_cutoff_hz"
    private const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
    private const val KEY_BIT_PERFECT_PLAYBACK = "bit_perfect_playback"
    private const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
    private const val KEY_SPEAKER_PLAYBACK_ALLOWED = "speaker_playback_allowed"
    private const val KEY_DOLBY_ATMOS = "dolby_atmos"
    private const val KEY_DIRAC_AUDIO_ENABLED = "dirac_audio_enabled"
    private const val KEY_DIRAC_AUDIO_PRESET = "dirac_audio_preset"
    private const val KEY_SPATIAL_AUDIO = "spatial_audio"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_PITCH = "playback_pitch"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_THEME_MATCHES_ARTWORK = "theme_matches_artwork"
    private const val KEY_CUSTOM_THEME_IMAGE_URI = "custom_theme_image_uri"
    private const val KEY_MATCH_BUTTON_ACCENT_TO_ARTWORK = "match_button_accent_to_artwork"
    private const val KEY_MINI_PLAYER_ARTWORK_COLOR = "mini_player_artwork_color"
    private const val KEY_NAVIGATION_BAR_MATCHES_MINI_PLAYER_COLOR = "navigation_bar_matches_mini_player_color"
    private const val KEY_MINI_PLAYER_ARTWORK_OUTLINE = "mini_player_artwork_outline"
    private const val KEY_MINI_PLAYER_PREVIOUS_BUTTON = "mini_player_previous_button"
    private const val KEY_MINI_PLAYER_SWIPE_CHANGES_TRACK = "mini_player_swipe_changes_track"
    private const val KEY_MINI_PLAYER_SWIPE_UP_EXPANDS = "mini_player_swipe_up_expands"
    private const val KEY_PLAYLIST_TRACK_MENU_ARTWORK_COLOR = "playlist_track_menu_artwork_color"
    private const val KEY_PARAMETRIC_EQ_ENABLED = "parametric_eq_enabled"
    private const val KEY_TPDF_DITHER_ENABLED = "tpdf_dither_enabled"
    private const val KEY_PARAMETRIC_EQ_BANDS = "parametric_eq_bands"
    private const val KEY_PARAMETRIC_EQ_CONFIG_JSON = "parametric_eq_config_json"
    private const val KEY_PARAMETRIC_EQ_PRESETS_JSON = "parametric_eq_presets_json"
    private const val KEY_PARAMETRIC_EQ_SEEDED_SAMPLE = "parametric_eq_seeded_sample"
    private const val BUNDLED_EEL_ASSET_PATH = "eq/upper 6kHz no touch ig955 eq_swap.eel"
    private const val MAX_SAVED_PARAMETRIC_EQ_PRESETS = 32
    private const val MAX_PARAMETRIC_EQ_PRESET_NAME_LENGTH = 80
    private const val KEY_EQUALIZER_BALANCE = "equalizer_balance"
    private const val KEY_CHANNEL_SWAP_ENABLED = "channel_swap_enabled"
    private const val KEY_REVERB_ENABLED = "reverb_enabled"
    private const val KEY_REVERB_AMOUNT = "reverb_amount"
    private const val KEY_LAUNCHER_ICON = "launcher_icon"
    private const val KEY_FROSTED_QUEUE_MANAGER = "frosted_queue_manager"
    private const val KEY_FROSTED_PLAYER_DETAILS = "frosted_player_details"
    private const val KEY_DETAILS_SHEET_ARTWORK_BACKDROP = "details_sheet_artwork_backdrop"
    private const val KEY_SHOW_EMBEDDED_COMMENT = "show_embedded_comment"
    private const val KEY_QUEUE_MANAGER_EQUALS_ICON = "queue_manager_equals_icon"
    private const val KEY_HIDE_QUEUE_LABEL = "hide_queue_label"
    private const val KEY_QUEUE_CLEAR_TRASH_ICON = "queue_clear_trash_icon"
    private const val KEY_HIDE_PLAYBACK_TUNING_TITLE = "hide_playback_tuning_title"
    private const val KEY_REDESIGNED_SAVED_QUEUE_MANAGER = "redesigned_saved_queue_manager"
    private const val KEY_HIDE_SAVED_QUEUE_HEADING = "hide_saved_queue_heading"
    private const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
    private const val KEY_PLAYBACK_TUNING_THIN_SLIDERS = "playback_tuning_thin_sliders"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_AUDIO_FOCUS_LEVEL = "audio_focus_level"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_RESTART_QUEUE_WHEN_ENDED = "restart_queue_when_ended"
    private const val KEY_ADVANCE_QUEUE_WHEN_REPEAT_OFF = "advance_queue_when_repeat_off"
    private const val KEY_MEDIA_CONTROL_FAVORITE_USES_STAR = "media_control_favorite_uses_star"
    private const val KEY_MEDIA_CONTROL_SECONDARY_ACTION = "media_control_secondary_action"
    private const val KEY_HIDE_MEDIA_CONTROL_CUSTOM_ACTIONS = "hide_media_control_custom_actions"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
    private const val KEY_REDUCE_ANIMATION = "reduce_animation"
    private const val KEY_PLAYER_ARTWORK_TRANSITION = "player_artwork_transition"
    private const val KEY_WALKMAN_PLAYER_STYLE = "walkman_player_style"
    private const val KEY_PILL_MOTION_STYLE = "pill_motion_style"
    private const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
    private const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
    private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
    private const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    private const val KEY_HIDE_RADIO_PLAYBACK_BAR = "hide_radio_playback_bar"
    private const val KEY_PLAYER_SEEK_BAR_THICKNESS_DP = "player_seek_bar_thickness_dp"
    private const val KEY_PLAYER_VOLUME_BAR_THICKNESS_DP = "player_volume_bar_thickness_dp"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
    private const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_CLASSIC_NAV_BAR = "classic_nav_bar"
    private const val KEY_HIDE_NAVIGATION_BAR_LABELS = "hide_navigation_bar_labels"
    private const val KEY_MAIN_NAVIGATION_TABS_POPUP = "main_navigation_tabs_popup"
    private const val KEY_VISIBLE_MAIN_NAVIGATION_TABS = "visible_main_navigation_tabs"
    private const val KEY_MAIN_NAVIGATION_TAB_ORDER = "main_navigation_tab_order"
    private const val KEY_MAIN_FOLDER_TAB_MIGRATED = "main_folder_tab_migrated"
    private const val KEY_STARTUP_TAB = "startup_tab"
    private const val KEY_SHOW_STATUS_BAR = "show_status_bar"
    private const val KEY_SHOW_PLAYER_STATUS_BAR = "show_player_status_bar"
    private const val KEY_SHOW_NAVIGATION_BAR = "show_navigation_bar"
    private const val KEY_MUSIC_ONLY_DEVICE_MODE = "music_only_device_mode"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_KEEP_SCREEN_ON_DURING_LYRICS = "keep_screen_on_during_lyrics"
    private const val KEY_SCREEN_ORIENTATION = "screen_orientation_mode"
    private const val KEY_FAVORITE_USES_STAR = "favorite_uses_star"
    private const val KEY_PLAYER_QUICK_ACTIONS = "player_quick_actions"
    private const val KEY_HIDE_PLAYER_QUICK_ACTION_CIRCLES = "hide_player_quick_action_circles"
    private const val KEY_HIDDEN_PLAYER_DETAILS_ACTIONS = "hidden_player_details_actions"
    private const val KEY_AUDIO_CUTTER_ENABLED = "audio_cutter_enabled"
    private const val KEY_ARTWORK_BACKDROP_BLUR_DP = "artwork_backdrop_blur_dp"
    private const val KEY_PERSISTENT_LOCAL_ARTWORK = "persistent_local_artwork"
    private const val KEY_LYRICS_BLUR = "lyrics_blur"
    private const val KEY_HIDE_LYRICS_STATUS_TEXT = "hide_lyrics_status_text"
    private const val KEY_HIDE_LYRICS_SAVED_MESSAGE = "hide_lyrics_saved_message"
    private const val KEY_HIDE_LYRICS_UNAVAILABLE_LABEL = "hide_lyrics_unavailable_label"
    private const val KEY_HIDE_LYRICS_GAP_NOTE = "hide_lyrics_gap_note"
    private const val KEY_SHOW_LYRICS_PREVIOUS_CONTROL = "show_lyrics_previous_control"
    private const val KEY_SHOW_LYRICS_PLAY_PAUSE_CONTROL = "show_lyrics_play_pause_control"
    private const val KEY_SHOW_LYRICS_NEXT_CONTROL = "show_lyrics_next_control"
    private const val KEY_LYRICS_TRANSPORT_CONTROL_SIZE = "lyrics_transport_control_size"
    private const val KEY_LYRICS_TRANSPORT_CONTROLS_CENTERED = "lyrics_transport_controls_centered"
    private const val KEY_LYRICS_FONT_SCALE = "lyrics_font_scale"
    private const val KEY_LYRICS_TEXT_ALIGNMENT = "lyrics_text_alignment"
    private const val KEY_ARTWORK_TAP_OPENS_LYRICS = "artwork_tap_opens_lyrics"
    private const val KEY_LYRICS_REPLACE_ARTWORK_ONLY = "lyrics_replace_artwork_only"
    private const val KEY_HIDE_PLAYER_HEADER_DURING_LYRICS = "hide_player_header_during_lyrics"
    private const val KEY_SHOW_PLAYER_LYRICS_STRIP = "show_player_lyrics_strip"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_RADIO_AREA_ID = "radio_area_id"
    private const val KEY_RADIO_TAB_MIGRATED = "radio_tab_migrated"
    private const val KEY_YOUTUBE_MUSIC_ENABLED = "youtube_music_enabled"
    private const val KEY_YOUTUBE_MUSIC_SEARCH_DEFAULT = "youtube_music_search_default"
    private const val KEY_INCLUDE_YOUTUBE_MUSIC_VIDEOS_IN_SEARCH = "include_youtube_music_videos_in_search"
    private const val KEY_HIDE_LOSSLESS_LABEL = "hide_lossless_label"
    private const val KEY_SHOW_TRACK_DURATION_INSTEAD_OF_REMAINING = "show_track_duration_instead_of_remaining"
    private const val KEY_POPUP_EXTERNAL_AUDIO_PLAYER = "popup_external_audio_player"
    private const val KEY_REPLAYGAIN_ENABLED = "replaygain_enabled"
    private const val KEY_REPLAYGAIN_ALBUM_MODE = "replaygain_album_mode"
    private const val KEY_REPLAYGAIN_PREAMP_DB = "replaygain_preamp_db"
    private const val KEY_REPLAYGAIN_PREVENT_CLIPPING = "replaygain_prevent_clipping"
    private const val KEY_VOLUME_BOOST_ENABLED = "volume_boost_enabled"
    private const val KEY_VOLUME_BOOST_DB = "volume_boost_db"
    private const val KEY_PILL_WIDGET_OPACITY = "pill_widget_opacity"
    private const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
    private const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
    private const val KEY_FAVORITE_BESIDE_TRACK_MENU = "favorite_beside_track_menu"
    private const val KEY_PREVENT_PLAY_AT_ZERO_VOLUME = "prevent_play_at_zero_volume"
    private const val KEY_PAUSE_PLAYBACK_AT_ZERO_VOLUME = "pause_playback_at_zero_volume"
    private const val KEY_VISIBLE_PLAYER_CONTROLS = "visible_player_controls"
    private const val KEY_PLAYER_CONTROL_ORDER = "player_control_order"
    private const val KEY_LIBRARY_ICON_FOR_PLAYLIST_CONTROL = "library_icon_for_playlist_control"
    private const val KEY_HIDE_PLAYER_ARTIST = "hide_player_artist"
    private const val KEY_HIDE_UNKNOWN_PLAYER_ARTIST = "hide_unknown_player_artist"
    private const val KEY_CENTER_PLAYER_TRACK_INFO = "center_player_track_info"
    private const val KEY_PLAYER_DETAILS_VERTICAL_MENU = "player_details_vertical_menu"
    private const val KEY_PRELOAD_ALBUM_ART_ON_STARTUP = "preload_album_art_on_startup"
    private const val KEY_ANIMATED_CANVAS = "animated_canvas"
    private const val KEY_CANVAS_OVER_CELLULAR = "canvas_over_cellular"
    private const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
    private const val KEY_KEEP_ORIGINAL_ARTWORK_ASPECT_RATIO = "keep_original_artwork_aspect_ratio"
    private const val KEY_KEEP_COLLECTION_ARTWORK_ASPECT_RATIO = "keep_collection_artwork_aspect_ratio"
    private const val KEY_LOCALIZE_LIBRARY_COLLECTION_COUNT = "localize_library_collection_count"
    private const val KEY_PRESERVE_ARTWORK_SHAPE_IN_COMPACT_TRANSITIONS = "preserve_artwork_shape_in_compact_transitions"
    private const val KEY_KEEP_ARTWORK_FULL_SIZE_PAUSED = "keep_artwork_full_size_when_paused"
    private const val KEY_DOUBLE_TAP_TO_SEEK = "double_tap_to_seek"
    private const val KEY_SEEK_INTERVAL_SECONDS = "seek_interval_seconds"
    private const val KEY_RECENT_PLAY_THRESHOLD_SECONDS = "recent_play_threshold_seconds"
    private const val KEY_SEEK_BUTTON_MODE = "seek_button_mode"
    private const val KEY_TRANSPORT_LEFT_ACTION = "transport_left_action"
    private const val KEY_TRANSPORT_RIGHT_ACTION = "transport_right_action"
    private const val KEY_HIDE_SEEK_BUTTON_LABELS = "hide_seek_button_labels"
    private const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
    private const val KEY_SYNCED_LYRICS = "synced_lyrics"
    private const val KEY_SHOW_LYRICS_FURIGANA = "show_lyrics_furigana"
    private const val KEY_AUTO_FIT_ONE_LINE_LYRICS = "auto_fit_one_line_lyrics"
    private const val KEY_LYRICS_SOURCES = "lyrics_sources"
    private const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
    private const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    private const val KEY_SHOW_LYRICS_LOGS = "show_lyrics_logs"
    private const val KEY_AUTO_EMBED_LYRICS = "auto_embed_lyrics"
    const val DEFAULT_LYRICS_EXTENSION_REPO_URL =
        "https://raw.githubusercontent.com/SamuelAdmand/MusicBeat-Lyrics-Extensions/main/registry.json"
    private const val KEY_LYRICS_EXTENSION_REPO_URL = "lyrics_extension_repo_url"
    private const val KEY_LYRICS_EXTENSIONS_ENABLED = "lyrics_extensions_enabled"
    private const val KEY_LYRICS_AUTO_UPDATE = "lyrics_auto_update"
    private const val KEY_LAST_LYRICS_EXTENSION_SYNC = "last_lyrics_extension_sync"
    private const val KEY_REPLAY_GENRES = "replay_genres"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
    private const val KEY_LOCAL_MUSIC_SORT = "local_music_sort"
    private const val KEY_DOWNLOADED_MUSIC_SORT = "downloaded_music_sort"
    private const val KEY_LOCAL_ARTIST_SORT = "local_artist_sort"
    private const val KEY_DOWNLOADED_ARTIST_SORT = "downloaded_artist_sort"
    private const val KEY_VISIBLE_LOCAL_LIBRARY_TABS = "visible_local_library_tabs"
    private const val KEY_ALBUM_ARTISTS_TAB_MIGRATED = "album_artists_tab_migrated"
    private const val KEY_LOCAL_LIBRARY_TAB_ORDER = "local_library_tab_order"
    private const val KEY_LOCAL_FOLDER_HIERARCHY = "local_folder_hierarchy"
    private const val KEY_INCLUDE_REMOTE_SOURCE_MEDIA_IN_LIBRARY = "include_remote_source_media_in_library"
    private const val KEY_LOCAL_MUSIC_FOLDER_PATH = "local_music_folder_path"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USERNAME = "webdav_username"
    private const val KEY_WEBDAV_PASSWORD = "webdav_password"
    private const val KEY_WEBDAV_ALLOW_HTTP = "webdav_allow_http"
    private const val KEY_SMB_HOST = "smb_host"
    private const val KEY_SMB_SHARE = "smb_share"
    private const val KEY_SMB_BASE_PATH = "smb_base_path"
    private const val KEY_SMB_USERNAME = "smb_username"
    private const val KEY_SMB_PASSWORD = "smb_password"
    private const val KEY_FULL_SCREEN_COLLECTION_ARTWORK = "full_screen_collection_artwork"
    private const val KEY_HIDE_COLLECTION_TOP_BAR = "hide_collection_top_bar"
    private const val KEY_HIDE_FLOATING_COLLECTION_CONTROLS = "hide_floating_collection_controls"
    private const val KEY_ARTISTS_OPEN_ALBUMS = "artists_open_albums"
    private const val KEY_SHUFFLE_ARTIST_ALBUMS = "shuffle_artist_albums"
    private const val KEY_ONLINE_ALBUM_ARTIST_ARTWORK = "online_album_artist_artwork"
    private const val KEY_LIBRARY_SORT = "library_sort"
    private const val KEY_DETAIL_SONG_SORTS = "detail_song_sorts"
    private const val KEY_LOCAL_MUSIC_VIEW_TYPE = "local_music_view_type"
    private const val KEY_LOCAL_SONGS_VIEW_TYPE = "local_songs_view_type"
    private const val KEY_LOCAL_ALBUMS_VIEW_TYPE = "local_albums_view_type"
    private const val KEY_LOCAL_ARTISTS_VIEW_TYPE = "local_artists_view_type"
    private const val KEY_DOWNLOADED_MUSIC_VIEW_TYPE = "downloaded_music_view_type"
    private const val KEY_DOWNLOADED_SONGS_VIEW_TYPE = "downloaded_songs_view_type"
    private const val KEY_DOWNLOADED_ALBUMS_VIEW_TYPE = "downloaded_albums_view_type"
    private const val KEY_DOWNLOADED_ARTISTS_VIEW_TYPE = "downloaded_artists_view_type"
    private const val KEY_LIBRARY_PLAYLISTS_VIEW_TYPE = "library_playlists_view_type"
    private const val KEY_LIBRARY_SONGS_VIEW_TYPE = "library_songs_view_type"
    private const val KEY_SHOW_PLAYLIST_SONG_ARTWORK = "show_playlist_song_artwork"
    private const val KEY_SHOW_COLLECTION_SONG_ARTWORK = "show_collection_song_artwork"
    private const val KEY_LOCAL_DRILLDOWN_SONGS_VIEW_TYPE = "local_drilldown_songs_view_type"
    private const val KEY_DOWNLOADED_DRILLDOWN_SONGS_VIEW_TYPE = "downloaded_drilldown_songs_view_type"
    private const val KEY_BLACKLISTED_FOLDERS = "blacklisted_folders"
    private const val KEY_PINNED_PLAYLISTS = "pinned_playlists"
    private const val KEY_ALL_FILES_PERMISSION_ASKED = "all_files_permission_asked"

    private const val KEY_LASTFM_ENABLED = "lastfm_enabled"
    private const val KEY_LASTFM_USERNAME = "lastfm_username"
    private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"
    private const val KEY_LASTFM_ENDPOINT = "lastfm_endpoint"
    private const val KEY_LASTFM_SCROBBLE_ENABLED = "lastfm_scrobble_enabled"
    private const val KEY_LASTFM_NOW_PLAYING = "lastfm_now_playing"
    private const val KEY_LASTFM_PRIMARY_ARTIST_ONLY = "lastfm_primary_artist_only"
    private const val KEY_SCROBBLE_MIN_DURATION = "scrobble_min_duration"
    private const val KEY_SCROBBLE_DELAY_PERCENT = "scrobble_delay_percent"
    private const val KEY_SCROBBLE_DELAY_SECONDS = "scrobble_delay_seconds"
    private const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
    private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
    private const val KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY = "listenbrainz_primary_artist_only"
    private const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"

    private const val KEY_DISCORD_USERNAME = "discord_username"
    private const val KEY_DISCORD_NAME = "discord_name"
    private const val KEY_DISCORD_AVATAR = "discord_avatar"
    private const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
    private const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
    private const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
    private const val KEY_DISCORD_STATUS = "discord_status"
    private const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
    private const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
    private const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
    private const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
    private const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
    private const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
    private const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}
