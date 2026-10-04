# MusicBeat Linux preview

This is the first native Linux increment, intended for Linux Mint 22.x on x86_64. It provides a local music folder picker, recursive library scan, metadata-based title/artist/album display, search, and basic play/pause/previous/next controls. It is a separate desktop target; the Android app remains unchanged.

## Runtime requirements

- Linux Mint 22.x / Ubuntu 24.04 compatible desktop, x86_64
- `mpv` for audio playback
- `ffprobe` (from FFmpeg) for tags and durations; without it the library falls back to cleaned file names

On Linux Mint, install the media tools with:

```sh
sudo apt install mpv ffmpeg
```

## Build

Requires JDK 17 and `appimagetool` (or the official AppImage of `appimagetool`). Run:

```sh
./linux-desktop/build-appimage.sh
```

The result is written to `linux-desktop/dist/MusicBeat-Linux-x86_64.AppImage`. The build script first creates a self-contained Java runtime image with `jpackage`, then wraps it as an AppImage. Audio playback and metadata probing use the host's `mpv` and `ffprobe` in this preview.

## Current scope

This is an incremental native Linux port, not a repackaged Android APK. This first increment is local-library playback. Android-specific widgets, foreground playback service, Android Auto, Cast sender integration, account flows, online catalog, lyrics, downloads, playlists, and settings have not yet been ported. Those need Linux-specific implementations before they can be included.
