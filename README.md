# MusicBeatSP

### A local-first music player for Android
**Lossless audio · Synchronized lyrics · Customizable library and player · ReplayGain · Google Cast**

<br/>

[![Latest release](https://img.shields.io/github/v/release/Kdroidwin/MusicBeatSP?style=for-the-badge&color=6366f1&labelColor=0d1117)](https://github.com/Kdroidwin/MusicBeatSP/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=0d1117)](https://developer.android.com)
[![Hardware audio](https://img.shields.io/badge/Dolby%20Atmos-OnePlus%20%2F%20OPPO-FF0055?style=for-the-badge&labelColor=0d1117)](#oneplus--oppo-hardware-audio)
[![Forked from BitChord](https://img.shields.io/badge/Forked%20from-BitChord-007ACC?style=for-the-badge&logo=github&logoColor=white&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord)
[![License](https://img.shields.io/github/license/Kdroidwin/MusicBeatSP?style=for-the-badge&color=22c55e&labelColor=0d1117)](LICENSE)

<br/>

[**Features**](#features) · [**Download**](#download) · [**Build from source**](#build-from-source) · [**Credits**](#credits-and-attribution)

</div>

---

> **MusicBeatSP** is the MusicBeat fork under a new name and Android application ID. It builds on the open-source BitChord foundation and adds an extensive local music library, playlist, playback, lyrics, artwork, and customization feature set.

The standard Android package is `io.pockets.musicbestsp`. Because this differs from the previous MusicBeat package, Android installs MusicBeatSP as a separate app; app-private settings and data are not migrated automatically.

<p align="center">
  <img src="https://github.com/user-attachments/assets/eb91f149-c953-4822-8657-65c0e2f7cffb" width="180" alt="スクリーンショット 1">
  <img src="https://github.com/user-attachments/assets/b42ddc60-1b04-4a4c-aefe-f71c054f7d96" width="180" alt="スクリーンショット 2">
  <img src="https://github.com/user-attachments/assets/4efcc926-dcba-4c3f-b715-df88d307d97c" width="180" alt="スクリーンショット 3">
  <img src="https://github.com/user-attachments/assets/09efc84f-04dc-4e32-8340-32baeae1986d" width="180" alt="スクリーンショット 4">
  <img src="https://github.com/user-attachments/assets/cc14a50e-4b7a-4f8b-ab16-f4de9a063572" width="180" alt="スクリーンショット 5">
    <img src="https://github.com/user-attachments/assets/5b704239-5753-483b-ab56-91d31e802b50" width="180" alt="スクリーンショット 6">
</p>

</div>

## Features

### Audio playback and controls

- Play local high-resolution audio including FLAC, ALAC, WAV, AAC, MP3, OPUS, and OGG Vorbis.
- Use gapless playback and configurable crossfade, playback speed, and pitch controls.
- Adjust audio focus from level 0 to 4, control whether playback can start at zero volume, and apply ReplayGain metadata.
- Optionally pause playback when the volume reaches zero.
- Play CUE sheets as separate tracks when their referenced local audio is available.
- Choose bit-perfect output on supported Android 14+ USB DAC paths. Optional parametric EQ and reverb default to off; equalizer profiles can be saved per output device.
- Search the AutoEQ headphone catalog and apply a downloaded profile as an approximation for the built-in five-band EQ. Profile lookup requires an internet connection; it is a separate convenience from the parametric EQ.
- Use high-precision parametric EQ with up to 64 filters per channel, shared Global filters and independent Left/Right filters, per-channel preamp, and peak, shelf, low-pass, high-pass, band-pass, notch, and all-pass filter types.
- Save and select EQ presets, import or export supported EEL2 and Equalizer APO filter data, and retain EQ settings in app backup. Imported EEL files are parsed as data; scripts are not executed.
- Enable Automix for analyzed, beat-aware transitions, or use regular crossfade. Automix and crossfade are mutually exclusive; choose an Efficient, Balanced, or Performance analysis mode.
- Save and restore the position of tracks that are at least 15 minutes long, including when switching tracks or stopping playback.
- Open the playback queue with both upcoming tracks and recently played tracks.
- Optionally restart from the first queue item when playback reaches the end.
- Customize Android media controls with a star favorite, a selectable Shuffle/Playlists/Lyrics/Queue action, or hide the app's custom actions while keeping standard playback controls.
- Save named queue snapshots, switch between them, replace or remove them, and load their saved current track and position after a restart.
- Cast supported local tracks to Google Cast receivers on the local network.
- View audio codec, sample rate, bit depth, bitrate, channels, and buffer information in Stats for Nerds.

### Player customization

- Choose System, Light, Dark, AMOLED Black, Album Art, or a theme based on a locally selected image. Album Art colors the app background, surfaces, controls, and accents from the playing cover.
- Hide lyrics status messages, the saved-lyrics indicator, and audio-quality labels such as Lossless and Hi-Res.
- Show, hide, and reorder available player actions, including Shuffle, Repeat, Like, Playlist/Library, Search, and Queue. Hiding a control does not change its playback state; the Playlist control can use a Library icon.
- Choose and reorder player shortcuts beside the favorite button, including Lyrics, Add to Playlist, Speed and Pitch, Queue, Playlists, Search, Chromecast, and track details. The favorite button can also be reordered.
- Adjust the player background blur independently of the app-wide theme, up to 128 dp.
- Switch to the optional Walkman-style player with a centered cover and track information plus circular playback controls. Tune artwork and lyric transition animations independently from Reduce animation.
- Choose whether portrait and landscape cover art keeps its original proportions in the player, album and playlist pages, and compact-player transitions.
- Keep the floating tab bar or replace it with a popup navigation menu; customize tab order, visibility, labels, and the mini-player's swipe gestures.
- Optionally use full-bleed artwork in the player and keep the cover at full size while playback is paused.
- Use translucent frosted-glass surfaces for the queue manager, player details, and compact lyrics artwork area; each has its own setting.
- Match the local track details sheet backdrop to the selected track's artwork.
- Match player action accents and the mini-player surface to the current album art.
- Optionally show white, black-icon Play and Shuffle controls below album and playlist artwork for stronger contrast on dark album-art themes.
- Replace the main Settings gear with a three-dot overflow icon through a separate display option.
- On wide screens, optionally show synchronized lyrics beside the player. This replaces the old left-side library pane only in this mode; the existing split layout remains available, and artwork taps are disabled only for the side-by-side lyrics mode.
- Replace only the artwork area with lyrics while keeping playback controls, progress, volume, and player shortcuts visible.
- Choose whether the favorite button appears beside the More menu, keep artwork full-size while paused, and open lyrics by tapping the cover.
- Switch the player favorite glyph between a heart and a star.
- Show or hide the artist name, automatically hide “Unknown Artist,” and center the track information.
- Keep the screen awake, independently show or hide the Android status and navigation bars, choose portrait, landscape, or system orientation, and select the tab opened at startup.
- Select a pink, black, or green launcher icon.
- Use Japanese translations for the Settings screen and player More menu; additional app languages fall back to English where translations are not available.

### Library and playlists

- Browse Songs, Albums, Artists, Genres, Playlists, Folders, and other available library sections. Reorder tabs and hide individual tabs from navigation.
- Save YouTube Music albums and playlists as library shortcuts and reopen them from the Library tab. When signed out, the player’s heart/star remains usable as a device-local favorite; signed-in YouTube Music ratings continue to use the account.
- Add an Album Artists tab alongside Artists, choose to browse an artist's albums before its tracks, and reorder or hide available library tabs.
- Choose whether artist pages open to albums, use a folder hierarchy in search results, and hide the collection top bar on detail pages.
- Long-press to select multiple tracks and add them to a playlist in one action. Search folder results can act on all tracks in that folder.
- Sort folder search results by filename, including numbered names such as `1-01. example.flac`, while displaying the track title and artist from metadata.
- Import `.m3u` and `.m3u8` playlists through Android's file picker. Import playlists from Musicolet backup ZIP files; unavailable tracks are skipped so the rest can still be imported.
- Display folder names from the resolved local directory path, including folders on removable storage, with provider labels as a fallback.
- Connect self-hosted Jellyfin and Navidrome/OpenSubsonic libraries, or configure WebDAV and SMB locations for remote music files.
- Add compatible HTTP music-source endpoints and select them alongside built-in sources when searching. The app queries the configured endpoint and does not download or run add-on code; available catalogs, streams, and account access depend on that endpoint.
- Export playlists as M3U from the playlist menu.
- Open audio files from file managers such as MiXplorer. Optionally show a floating player over other apps for files opened this way.
- Choose a persistent app-private storage location for extracted local album artwork so it survives cache cleanup and app restarts, without requesting additional storage access.
- Drag to reorder playlists and reorder tracks within a playlist independently.
- Reorder tracks in a playlist by dragging, select tracks in bulk, and adapt selection action contrast to the current surface.
- Choose a custom image for a playlist cover, and use list or grid layouts with optional small album artwork in list view.
- Browse listening history and most-played tracks. Search settings by name and include settings, playlist data, and saved queue snapshots in app backup and restore.
- Rip CDs

### Lyrics and artwork

- Display the first lyric line for timed and plain lyrics while preserving synchronized scrolling and highlighting.
- Create synchronized lyrics with the audio-guided editor, which records line timestamps during playback. The optional furigana setting renders `漢字((かんじ))` readings above their kanji as ruby text instead of showing the marker parentheses.
- Hide generated lyric status text, unavailable and saved-lyrics labels, or instrumental gap notes. Set lyric size and alignment, and choose which playback controls appear on the lyrics screen.
- Configure double-tap seeking and optional skip buttons, including their intervals and whether seconds labels appear.
- Cache and prefetch local album artwork to speed up track changes; optionally load the local artwork library at startup.
- Use high-resolution local artwork in the player and customize playlist cover images.
- Use the optional audio cutter from local track details to export a trimmed AAC copy; the original file remains unchanged.

### Widgets and appearance

- Keep the two existing playback widgets and add a third square album-art widget with track title and playback controls.
- Expose playback state, track metadata, and artwork through Android's standard media session for external music widgets such as KWGT.
- Use the album-art color theme throughout the app, in addition to the System, Light, Dark, and AMOLED Black themes.
- Enable Offline Mode to block the app's network requests; local files and saved caches remain available. Online catalog, lyrics, casting, and connected services need their respective network or device connections.

### Supported Sources
- YouTube Music search and playback are opt-in and disabled by default; the app does not make YouTube Music requests while the feature is disabled or Offline Mode is active.
- radiko
- BitChord addon(Navidrome)
- webDAB
- SMB

### Lyrics and metadata tools

- Search multiple lyrics providers by title, artist, and album, with word-synchronized karaoke lyrics where available.
- Embed downloaded lyrics into supported audio tags and edit common metadata fields, including title, artist, album, track number, disc number, year, and genre.
- Use Android's storage framework and permission flows for local media access and metadata writes.

## OnePlus / OPPO hardware audio

OxygenOS and ColorOS devices can whitelist specific package IDs for system audio effects such as Dolby Atmos, Dirac Audio, and OReality Audio. The standard edition uses the MusicBeatSP package ID; the dedicated QQ Music and KuGou editions retain their vendor-whitelisted IDs for compatible devices.

| Edition | Android application ID | Intended use |
| :--- | :--- | :--- |
| Standard | `io.pockets.musicbestsp` | General Android devices |
| QQ Music compatibility | `com.tencent.qqmusic` | OnePlus, OPPO, and Realme system audio effects |
| KuGou compatibility | `com.kugou.android` | Alternative OnePlus / OPPO whitelist |

## Download

Check the [GitHub Releases page](https://github.com/Kdroidwin/MusicBeatSP/releases) for published builds. For a local release build, see [Build from source](#build-from-source).

| APK | Recommended devices |
| :--- | :--- |
| `arm64-v8a` | Most modern Android phones |
| `universal` | Devices when the CPU architecture is unknown |
| `armeabi-v7a` | Legacy 32-bit devices |
| `x86_64` | Emulators and x86 devices |

## Build from source

### Requirements

- JDK 17
- Android SDK Platform 37 and Android Build Tools 36.0.0
- CMake 3.22.1 or newer for the native TagLib components

### Commands

```bash
git clone https://github.com/Kdroidwin/MusicBeatSP.git
cd MusicBeatSP

# Standard release APK
./gradlew assembleStandardRelease

# Offline release APK without the INTERNET permission
./gradlew assembleNoInternetRelease

# OnePlus / OPPO compatibility editions
./gradlew assembleQqmusicRelease
./gradlew assembleKugouRelease

# Development build
./gradlew assembleDevDebug
```

APK files are written to `app/build/outputs/apk/<flavor>/<build-type>/`. Release signing is configured locally through `keystore.properties`; do not commit the keystore or its passwords.

YouTube Music PoToken support requires `YOUTUBE_PO_TOKEN_API_KEY` in `local.properties` or the build environment. Keep the key out of source control. Android client keys are included in the resulting APK, so restrict them to the intended API and client where possible.

## Linux desktop

The Linux edition is a dedicated offline fork of BitChord's Compose Desktop app, included under [`linux-offline/`](linux-offline/README.md). It scans and plays local files, reads embedded and sidecar artwork, keeps a persistent queue and playlists, and keeps BitChord's desktop player UI. Network catalogs, accounts, online lyrics fallback, Canvas, and Listen Together are disabled in this build. The AppImage build and local source-tree details are documented in [`linux-offline/README.md`](linux-offline/README.md).

## Credits and attribution

- **[BitChord](https://github.com/kushagrasinghx/BitChord)** — Created by [Kushagra Singh](https://github.com/kushagrasinghx). MusicBeatSP retains and builds on the project's UI and playback foundation.
- **[MusicBeat](https://github.com/SamuelAdmand/MusicBeat)** — Developed by [SamuelAdmand](https://github.com/SamuelAdmand). Forked from BitChord, MusicBeat builds on its UI and playback foundation while adding local music library features and enhanced audio support.
- **[Tinque](https://github.com/nanbeimerco/Tinque)** — Developed by [nanbeimerco](https://github.com/nanbeimerco). Forked from MusicBeatSP, Tinque builds on its foundation with independent left/right parametric EQ, channel swapping, folder browsing, and updated animations.
- **[Auris](https://github.com/OreoMuncher45/Auris)** — Developed by [OreoMuncher45](https://github.com/OreoMuncher45). A community fork of BitChord, Auris is a lossless-first Android music player for local music and YouTube Music, with a redesigned interface, offline downloads, lyrics, and optional lossless audio add-ons. 
- **[Booming Music](https://github.com/mardous/BoomingMusic)** — Reference for the fast-scroller design and tag-editor workflow.
- **[TagLib](https://github.com/kyant0/taglib)** — Audio metadata parsing and writing.
- **[Haze](https://github.com/chrisbanes/haze)** — Compose backdrop blur effects.
- **[AndroidX Media3](https://github.com/androidx/media)** — Android playback and media-session APIs.
- **Google Cast SDK** — Cast sender integration.
- **[Uyatame-CD-Ripping-Tool-UCRT](https://github.com/uyatame/Uyatame-CD-Ripping-Tool-UCRT)** — Android app to rip and play audio CDs with a USB optical drive (FLAC, AAC, MP3, MusicBrainz).
- **[Radikall](https://github.com/baudmusic/radikall)** — Android radio client for Japanese stations — listen to every Radiko station from anywhere in the world.



<div align="center">

**[MusicBeatSP](https://github.com/Kdroidwin/MusicBeatSP)** · Built for local music libraries and everyday listening.

</div>
