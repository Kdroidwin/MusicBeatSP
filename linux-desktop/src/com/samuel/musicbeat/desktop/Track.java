package com.samuel.musicbeat.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

record Track(Path path, String title, String artist, String album, String duration) {
    static Track read(Path path) {
        return read(path, null, null);
    }

    static Track read(Path path, String fallbackTitle, String fallbackArtist) {
        Map<String, String> tags = probe(path);
        String title = tags.getOrDefault("title", nonBlank(fallbackTitle, friendlyFileName(path)));
        String artist = tags.getOrDefault("artist", nonBlank(fallbackArtist, "Unknown artist"));
        String album = tags.getOrDefault("album", "—");
        return new Track(path, title.isBlank() ? friendlyFileName(path) : title,
                artist.isBlank() ? "Unknown artist" : artist,
                album.isBlank() ? "—" : album,
                formatDuration(tags.get("duration")));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Map<String, String> probe(Path path) {
        Map<String, String> tags = new HashMap<>();
        Process process = null;
        try {
            process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                    "format=duration:format_tags=title,artist,album,album_artist,track",
                    "-of", "default=noprint_wrappers=1", path.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return tags;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
                if (key.startsWith("tag:")) key = key.substring(4);
                String value = unquote(line.substring(separator + 1).strip());
                if (!value.isBlank()) tags.putIfAbsent(key, value);
            }
        } catch (IOException | InterruptedException ignored) {
            if (process != null) process.destroyForcibly();
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return tags;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\n", " ").replace("\\\\", "\\").strip();
    }

    private static String friendlyFileName(Path path) {
        String name = path.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
        return name.replaceFirst("^\\s*\\d+(?:[-.]\\d+)*[\\s._-]+", "")
                .replace('_', ' ').strip();
    }

    private static String formatDuration(String secondsText) {
        if (secondsText == null) return "—";
        try {
            long seconds = Math.max(0, (long) Double.parseDouble(secondsText));
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        } catch (NumberFormatException ignored) {
            return "—";
        }
    }
}
