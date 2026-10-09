# MusicBeatSP for Linux · 2.1.0

MusicBeatSP for Linux is an offline-first desktop player for local audio files. It is a Linux implementation of MusicBeatSP's local playback workflow, not the online BitChord desktop client. It does not sign in, contact an online catalog, stream music, or request account access.

## Features

- Scan a local music folder and search title, artist, album, or file path. Metadata is read with `ffprobe`; file names remain the fallback when tags are absent.
- Play, pause, skip forward and back, and continue through the selected song list or queue with `mpv`.
- Keep a persistent playback queue and named local playlists. Playlist and queue order can be adjusted with Move Up / Move Down.
- Import M3U and M3U8 files, including `#EXTM3U`, `#EXTINF`, absolute paths, `file:` URIs, and paths relative to the playlist. Missing, unreadable, unsupported, and duplicate entries are skipped; the imported playlist reports added and skipped counts.
- Display adjacent `.lrc` lyrics in sync with the local player when the audio track has a matching lyrics file.
- Store playlists and queue state under `~/.local/share/musicbeatsp/`. Imported audio references stay as paths; the original audio files are not copied or modified.

## Runtime requirements

- Linux Mint 22.x / Ubuntu 24.04 compatible desktop, x86_64
- `mpv` for local playback
- `ffprobe` from FFmpeg for embedded tags and duration; without it, titles fall back to file names

Install the media tools on Linux Mint:

```sh
sudo apt install mpv ffmpeg
```

## Build the AppImage

Requires JDK 17 and `appimagetool` (or the official AppImage build of `appimagetool`). Run from the repository root:

```sh
./linux-desktop/build-appimage.sh
```

The script creates a self-contained Java runtime with `jpackage` and writes:

```text
linux-desktop/dist/MusicBeatSP-Linux-x86_64-2.1.0.AppImage
```

`mpv` and `ffprobe` are host dependencies and are not bundled. The AppImage itself contains no user music, credentials, or personal playlist data.
