package com.samuel.musicbeat.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only mpv control over its JSON IPC socket. */
final class MpvIpcPlayer {
    record State(double seconds, boolean paused) { }

    private static final Pattern PROPERTY_NAME = Pattern.compile("\\\"name\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern NUMBER_DATA = Pattern.compile("\\\"data\\\":(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern BOOLEAN_DATA = Pattern.compile("\\\"data\\\":(true|false)");

    private final Consumer<String> onError;
    private final Consumer<State> onState;
    private final Runnable onEnded;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "musicbeatsp-mpv-commands");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Process process;
    private volatile Path socket;
    private volatile SocketChannel watcherChannel;
    private volatile Thread watcher;
    private volatile boolean closed;

    MpvIpcPlayer(Consumer<String> onError, Consumer<State> onState, Runnable onEnded) {
        this.onError = onError;
        this.onState = onState;
        this.onEnded = onEnded;
    }

    void play(Path track) {
        commands.submit(() -> {
            try {
                startIfNeeded();
                send("[\"loadfile\",\"" + jsonEscape(track.toAbsolutePath().toString()) + "\",\"replace\"]");
            } catch (Exception error) {
                report(error, "Could not start audio playback");
            }
        });
    }

    void togglePause() { commands.submit(() -> runCommand("[\"cycle\",\"pause\"]")); }

    private void runCommand(String command) {
        try {
            startIfNeeded();
            send(command);
        } catch (Exception error) {
            report(error, "Could not control audio playback");
        }
    }

    private void startIfNeeded() throws IOException, InterruptedException {
        Process active = process;
        if (active != null && active.isAlive() && socket != null && Files.exists(socket)) {
            startWatcherIfNeeded(active, socket);
            return;
        }
        closeWatcher();
        if (active != null && active.isAlive()) active.destroyForcibly();
        if (!commandExists("mpv")) {
            throw new IOException("MusicBeatSP uses mpv for local audio. Install it with: sudo apt install mpv");
        }
        Path endpoint = Path.of("/tmp", "mbsp-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        Process launched = new ProcessBuilder("mpv", "--no-video", "--idle=yes", "--force-window=no",
                "--no-terminal", "--really-quiet", "--input-ipc-server=" + endpoint)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        process = launched;
        socket = endpoint;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (launched.isAlive() && !Files.exists(endpoint) && System.nanoTime() < deadline) Thread.sleep(40);
        if (!launched.isAlive() || !Files.exists(endpoint)) {
            launched.destroyForcibly();
            process = null;
            socket = null;
            throw new IOException("mpv did not start. Check that the system audio output is available.");
        }
        startWatcherIfNeeded(launched, endpoint);
    }

    private void startWatcherIfNeeded(Process active, Path endpoint) {
        Thread current = watcher;
        if (current != null && current.isAlive()) return;
        Thread thread = new Thread(() -> watch(active, endpoint), "musicbeatsp-mpv-state");
        thread.setDaemon(true);
        watcher = thread;
        thread.start();
    }

    private void watch(Process active, Path endpoint) {
        while (!closed && active.isAlive() && endpoint.equals(socket)) {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(endpoint));
                watcherChannel = channel;
                send(channel, "[\"observe_property\",1,\"time-pos\"]");
                send(channel, "[\"observe_property\",2,\"pause\"]");
                BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
                String line;
                double seconds = 0;
                boolean paused = false;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.contains("\"event\":\"end-file\"") && line.contains("\"reason\":\"eof\"")) {
                        onEnded.run();
                    } else if (line.contains("\"event\":\"property-change\"")) {
                        Matcher name = PROPERTY_NAME.matcher(line);
                        if (!name.find()) continue;
                        if ("time-pos".equals(name.group(1))) {
                            Matcher value = NUMBER_DATA.matcher(line);
                            if (value.find()) {
                                seconds = Double.parseDouble(value.group(1));
                                onState.accept(new State(Math.max(0, seconds), paused));
                            }
                        } else if ("pause".equals(name.group(1))) {
                            Matcher value = BOOLEAN_DATA.matcher(line);
                            if (value.find()) {
                                paused = Boolean.parseBoolean(value.group(1));
                                onState.accept(new State(Math.max(0, seconds), paused));
                            }
                        }
                    }
                }
            } catch (IOException error) {
                if (!closed && active.isAlive() && endpoint.equals(socket)) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            } finally {
                watcherChannel = null;
            }
        }
    }

    private void send(String command) throws IOException {
        Path endpoint = socket;
        if (endpoint == null) throw new IOException("mpv is not running.");
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(endpoint));
            send(channel, command);
        }
    }

    private static void send(SocketChannel channel, String command) throws IOException {
        byte[] message = ("{\"command\":" + command + "}\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(message);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private void closeWatcher() {
        SocketChannel channel = watcherChannel;
        watcherChannel = null;
        if (channel != null) try { channel.close(); } catch (IOException ignored) { }
        Thread thread = watcher;
        watcher = null;
        if (thread != null) thread.interrupt();
    }

    private void report(Exception error, String fallback) {
        onError.accept(error.getMessage() == null || error.getMessage().isBlank() ? fallback : error.getMessage());
    }

    private static boolean commandExists(String command) {
        try {
            Process check = new ProcessBuilder("sh", "-c", "command -v " + command)
                    .redirectErrorStream(true).start();
            return check.waitFor(2, TimeUnit.SECONDS) && check.exitValue() == 0;
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    void close() {
        closed = true;
        closeWatcher();
        commands.submit(() -> {
            if (process != null && process.isAlive() && socket != null && Files.exists(socket)) {
                try { send("[\"quit\"]"); }
                catch (IOException ignored) { process.destroy(); }
            }
            if (socket != null) try { Files.deleteIfExists(socket); } catch (IOException ignored) { }
        });
        commands.shutdown();
    }
}
