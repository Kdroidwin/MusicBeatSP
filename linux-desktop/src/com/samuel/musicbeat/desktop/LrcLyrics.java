package com.samuel.musicbeat.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads adjacent local LRC files; it never requests lyrics over the network. */
final class LrcLyrics {
    record Line(double seconds, String text) { }
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final long MAX_LRC_BYTES = 1024 * 1024;

    private LrcLyrics() { }

    static List<Line> read(Path audioFile) {
        String filename = audioFile.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path lyrics = audioFile.resolveSibling((dot > 0 ? filename.substring(0, dot) : filename) + ".lrc");
        try {
            if (!Files.isRegularFile(lyrics) || Files.size(lyrics) > MAX_LRC_BYTES) return List.of();
            List<Line> result = new ArrayList<>();
            for (String line : Files.readAllLines(lyrics, StandardCharsets.UTF_8)) {
                Matcher matcher = TIMESTAMP.matcher(line);
                List<Double> times = new ArrayList<>();
                int end = 0;
                while (matcher.find()) {
                    double minutes = Double.parseDouble(matcher.group(1));
                    double seconds = Double.parseDouble(matcher.group(2));
                    String fraction = matcher.group(3);
                    double sub = fraction == null ? 0 : Double.parseDouble("0." + fraction);
                    times.add(minutes * 60 + seconds + sub);
                    end = matcher.end();
                }
                if (times.isEmpty()) continue;
                String text = line.substring(end).strip();
                for (double time : times) result.add(new Line(time, text));
            }
            result.sort(Comparator.comparingDouble(Line::seconds));
            return List.copyOf(result);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    static String at(List<Line> lines, double seconds) {
        String current = "";
        for (Line line : lines) {
            if (line.seconds() > seconds) break;
            if (!line.text().isBlank()) current = line.text();
        }
        return current;
    }
}
