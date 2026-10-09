package com.samuel.musicbeat.desktop;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Local-only M3U8 storage for user playlists and the playback queue. */
final class PlaylistStore {
    private static final long MAX_PLAYLIST_BYTES = 8L * 1024 * 1024;
    private static final int MAX_PLAYLIST_LINES = 100_000;
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav",
            ".aiff", ".aif", ".wma", ".ape", ".alac", ".wv"
    };

    record Playlist(String id, String name, List<Track> tracks) { }
    record Imported(String suggestedName, List<Track> tracks, int skipped) { }

    private final Path root = Path.of(System.getProperty("user.home"), ".local", "share", "musicbeatsp");
    private final Path playlistsDirectory = root.resolve("playlists");
    private final Path queueFile = root.resolve("queue.m3u8");

    List<Playlist> loadPlaylists() throws IOException {
        Files.createDirectories(playlistsDirectory);
        List<Playlist> result = new ArrayList<>();
        try (var files = Files.list(playlistsDirectory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".m3u8"))
                    .sorted().toList()) {
                try {
                    Parsed parsed = parse(file);
                    result.add(new Playlist(file.getFileName().toString(), parsed.name(), parsed.tracks()));
                } catch (IOException ignored) {
                    // A damaged saved list does not prevent the other lists from loading.
                }
            }
        }
        return List.copyOf(result);
    }

    Imported importFile(Path source) throws IOException {
        Parsed parsed = parse(source);
        String base = source.getFileName() == null ? "Imported playlist" : source.getFileName().toString();
        base = base.replaceFirst("(?i)\\.(m3u8?|pls)$", "");
        return new Imported(parsed.name().isBlank() ? base : parsed.name(), parsed.tracks(), parsed.skipped());
    }

    Playlist saveNew(String name, List<Track> tracks) throws IOException {
        Files.createDirectories(playlistsDirectory);
        String cleanedName = cleanName(name);
        String id = UUID.randomUUID().toString();
        writeM3u(playlistsDirectory.resolve(id + ".m3u8"), cleanedName, tracks);
        return new Playlist(id + ".m3u8", cleanedName, List.copyOf(tracks));
    }

    Playlist replace(Playlist playlist, List<Track> tracks) throws IOException {
        String name = cleanName(playlist.name());
        writeM3u(playlistsDirectory.resolve(playlist.id()), name, tracks);
        return new Playlist(playlist.id(), name, List.copyOf(tracks));
    }

    void delete(Playlist playlist) throws IOException {
        Files.deleteIfExists(playlistsDirectory.resolve(playlist.id()).normalize());
    }

    List<Track> loadQueue() {
        if (!Files.isRegularFile(queueFile)) return List.of();
        try {
            return parse(queueFile).tracks();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    void saveQueue(List<Track> tracks) throws IOException {
        if (tracks.isEmpty()) {
            Files.deleteIfExists(queueFile);
        } else {
            writeM3u(queueFile, "Playback queue", tracks);
        }
    }

    private Parsed parse(Path source) throws IOException {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("The selected playlist file cannot be read.");
        }
        if (Files.size(source) > MAX_PLAYLIST_BYTES) {
            throw new IOException("The playlist is larger than the 8 MB safety limit.");
        }
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        if (lines.size() > MAX_PLAYLIST_LINES) {
            throw new IOException("The playlist contains too many lines.");
        }
        Path parent = source.toAbsolutePath().normalize().getParent();
        String name = "";
        String extTitle = null;
        String extArtist = null;
        int skipped = 0;
        Set<Path> seen = new HashSet<>();
        List<Track> tracks = new ArrayList<>();
        for (String original : lines) {
            String line = original.strip();
            if (line.startsWith("\uFEFF")) line = line.substring(1).strip();
            if (line.isEmpty()) continue;
            if (line.regionMatches(true, 0, "#PLAYLIST:", 0, 10)) {
                name = line.substring(10).strip();
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                String metadata = line.substring(8);
                int comma = metadata.indexOf(',');
                String label = comma >= 0 ? metadata.substring(comma + 1).strip() : "";
                int separator = label.indexOf(" - ");
                if (separator > 0) {
                    extArtist = label.substring(0, separator).strip();
                    extTitle = label.substring(separator + 3).strip();
                } else {
                    extTitle = label.isBlank() ? null : label;
                    extArtist = null;
                }
                continue;
            }
            if (line.startsWith("#")) continue;

            try {
                Path trackPath = resolveTrack(source, parent, line);
                if (!isAudioFile(trackPath) || !Files.isRegularFile(trackPath) || !Files.isReadable(trackPath)) {
                    skipped++;
                } else if (!seen.add(trackPath)) {
                    skipped++;
                } else {
                    tracks.add(Track.read(trackPath, extTitle, extArtist));
                }
            } catch (RuntimeException | IOException ignored) {
                skipped++;
            } finally {
                extTitle = null;
                extArtist = null;
            }
        }
        if (lines.isEmpty()) throw new IOException("The selected playlist is empty.");
        return new Parsed(name, List.copyOf(tracks), skipped);
    }

    private static Path resolveTrack(Path source, Path parent, String value) throws IOException {
        String target = value.strip();
        if (target.length() >= 2 && target.startsWith("\"") && target.endsWith("\"")) {
            target = target.substring(1, target.length() - 1);
        }
        if (target.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return Path.of(URI.create(target)).toAbsolutePath().normalize();
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid file URI", error);
            }
        }
        if (target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IOException("Only local audio files are supported in offline playlists.");
        }
        Path path = Path.of(target);
        return (path.isAbsolute() ? path : parent.resolve(path)).toAbsolutePath().normalize();
    }

    private static boolean isAudioFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) if (filename.endsWith(extension)) return true;
        return false;
    }

    private static void writeM3u(Path destination, String name, List<Track> tracks) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".playlist-", ".tmp");
        try {
            StringBuilder body = new StringBuilder("#EXTM3U\n#PLAYLIST:")
                    .append(cleanLine(name)).append('\n');
            for (Track track : tracks) {
                body.append("#EXTINF:-1,")
                        .append(cleanLine(track.artist())).append(" - ")
                        .append(cleanLine(track.title())).append('\n')
                        .append(track.path().toAbsolutePath().normalize()).append('\n');
            }
            Files.writeString(temporary, body.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").strip();
        return value.isBlank() ? "New playlist" : value;
    }

    private static String cleanLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private record Parsed(String name, List<Track> tracks, int skipped) { }
}
