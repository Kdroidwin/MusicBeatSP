<div align="center">

<br/>

<img src="Logo.png" alt="MusicBeatSP app icon" width="160" />

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

> **MusicBeatSP 2.0.1** is the MusicBeat fork under a new name and Android application ID. It builds on the open-source BitChord foundation and adds an extensive local music library, playlist, playback, lyrics, artwork, and customization feature set.

The standard Android package is `io.pockets.musicbestsp`. Because this differs from the previous MusicBeat package, Android installs MusicBeatSP as a separate app; app-private settings and data are not migrated automatically.

<div align="center">
<img src="Banner.png" alt="MusicBeatSP banner" width="100%" />
</div>

## Features

### Audio playback and controls

- Play local high-resolution audio including FLAC, ALAC, WAV, AAC, MP3, OPUS, and OGG Vorbis.
- Use gapless playback and configurable crossfade, playback speed, and pitch controls.
- Adjust audio focus from level 0 to 4, control whether playback can start at zero volume, and apply ReplayGain metadata.
- Save and restore the position of tracks that are at least 15 minutes long, including when switching tracks or stopping playback.
- Open the playback queue with both upcoming tracks and recently played tracks.
- Save named queue snapshots, switch between them, replace or remove them, and load their saved current track and position after a restart.
- Cast supported local tracks to Google Cast receivers on the local network.
- View audio codec, sample rate, bit depth, bitrate, channels, and buffer information in Stats for Nerds.

### Player customization

- Choose System, Light, Dark, AMOLED Black, or Album Art theme. Album Art colors the app background, surfaces, controls, and accents from the playing cover.
- Hide lyrics status messages, the saved-lyrics indicator, and audio-quality labels such as Lossless and Hi-Res.
- Show, hide, and reorder available player actions, including Shuffle, Repeat, Like, Playlist/Library, Search, and Queue. Hiding a control does not change its playback state; the Playlist control can use a Library icon.
- Configure up to six ordered shortcuts beside the player title, including Lyrics, Add to Playlist, Speed and Pitch, Queue, Playlists, and Search. All shortcuts can be turned off.
- Choose whether the favorite button appears beside the More menu, keep artwork full-size while paused, and open lyrics by tapping the cover.
- Switch the player favorite glyph between a heart and a star.
- Show or hide the artist name, automatically hide “Unknown Artist,” and center the track information.
- Keep the screen awake, independently show or hide the Android status and navigation bars, choose portrait, landscape, or system orientation, and select the tab opened at startup.
- Use Japanese translations for the Settings screen and player More menu; additional app languages fall back to English where translations are not available.

### Library and playlists

- Browse Songs, Albums, Artists, Genres, Playlists, Folders, and other available library sections. Reorder tabs and hide individual tabs from navigation.
- Long-press to select multiple tracks and add them to a playlist in one action. Search folder results can act on all tracks in that folder.
- Sort folder search results by filename, including numbered names such as `1-01. example.flac`, while displaying the track title and artist from metadata.
- Import `.m3u` and `.m3u8` playlists through Android's file picker. Import playlists from Musicolet backup ZIP files; unavailable tracks are skipped so the rest can still be imported.
- Choose a persistent app-private storage location for extracted local album artwork so it survives cache cleanup and app restarts, without requesting additional storage access.
- Drag to reorder playlists and reorder tracks within a playlist independently.
- Choose a custom image for a playlist cover, and use list or grid layouts with optional small album artwork in list view.
- Browse listening history and most-played tracks. App backup and restore includes settings, playlist data, and saved queue snapshots.

### Lyrics and artwork

- Display the first lyric line for timed and plain lyrics while preserving synchronized scrolling and highlighting.
- Hide generated lyric status text without hiding the lyrics themselves, and optionally hide the saved-to-device label.
- Cache and prefetch local album artwork to speed up track changes; optionally load the local artwork library at startup.
- Use high-resolution local artwork in the player and customize playlist cover images.

### Widgets and appearance

- Keep the two existing playback widgets and add a third square album-art widget with track title and playback controls.
- Use the album-art color theme throughout the app, in addition to the System, Light, Dark, and AMOLED Black themes.
- Enable Offline Mode to block the app's network requests; local files and saved caches remain available. Online catalog, lyrics, casting, and connected services need their respective network or device connections.

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

# OnePlus / OPPO compatibility editions
./gradlew assembleQqmusicRelease
./gradlew assembleKugouRelease

# Development build
./gradlew assembleDevDebug
```

APK files are written to `app/build/outputs/apk/<flavor>/<build-type>/`. Release signing is configured locally through `keystore.properties`; do not commit the keystore or its passwords.

## Linux preview

The repository also contains an early native Linux desktop preview for local music playback. It supports a local folder scan, metadata display, search, and basic playback controls. See [`linux-desktop/README.md`](linux-desktop/README.md) for requirements and build instructions.

## Credits and attribution

- **[BitChord](https://github.com/kushagrasinghx/BitChord)** — Created by [Kushagra Singh](https://github.com/kushagrasinghx). MusicBeatSP retains and builds on the project's UI and playback foundation.
- **[Booming Music](https://github.com/mardous/BoomingMusic)** — Reference for the fast-scroller design and tag-editor workflow.
- **[TagLib](https://github.com/kyant0/taglib)** — Audio metadata parsing and writing.
- **[Haze](https://github.com/chrisbanes/haze)** — Compose backdrop blur effects.
- **[AndroidX Media3](https://github.com/androidx/media)** — Android playback and media-session APIs.
- **Google Cast SDK** — Cast sender integration.

<div align="center">

**[MusicBeatSP](https://github.com/Kdroidwin/MusicBeatSP)** · Built for local music libraries and everyday listening.

</div>
