# MusicBeatSP for Linux

MusicBeatSP for Linux is a local-only fork of the BitChord Compose Desktop application supplied in `BitChord-main.zip`. It builds the Linux desktop module directly; it does not wrap the older JavaFX/mpv prototype in `linux-desktop/`.

## What is included

- Local music folder scanning, metadata, playback, persistent queue, history, and local playlists.
- Artwork from images beside the audio files and embedded cover art. Embedded covers are extracted on demand with FFmpeg and cached under the MusicBeatSP cache directory.
- The BitChord Compose Desktop player and library UI, with MusicBeatSP controls for hiding the player artist, unknown artist, quality label, and generated lyric status; centering track information; changing lyric size/alignment; choosing Dark / AMOLED black / artwork colors; and showing, hiding, and reordering each bottom mini-player control.
- Local-only playback and artwork handling. Missing local files fail as local-file errors instead of triggering an online source lookup. Network catalogs, sign-in, scrobbling, Canvas, online lyrics fallback, and Listen Together are disabled.

This offline fork does not currently include the Android edition's Chromecast receiver flow or every Android-only screen and setting.

## Build

Requires JDK 17, CMake, and a Linux x86-64 host. Gradle downloads dependencies on the first build if they are not already cached.

```sh
./gradlew :desktopApp:run
```

Create the bundled application directory and AppImage:

```sh
./gradlew :desktopApp:createDistributable -Pbitchord.version=2.1.0
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/MusicBeatSP \
  desktopApp/build/compose/binaries/main/app/MusicBeatSP.AppImage \
  /path/to/appimagetool
```

The app keeps its preferences under the operating system's Java preferences store and its artwork cache under the user's local cache directory.

## Upstream

The desktop source is derived from [BitChord](https://github.com/kushagrasinghx/BitChord). MusicBeatSP-specific changes in this directory are maintained separately from the Android Gradle project.
