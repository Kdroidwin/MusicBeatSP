package com.samuel.musicbeat.desktop;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Small, local-only control bridge to mpv over its documented JSON IPC socket. */
final class MpvIpcPlayer {
    private final Consumer<String> onError;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "musicbeat-mpv-commands");
        thread.setDaemon(true);
        return thread;
    });
    private Process process;
    private Path socket;

    MpvIpcPlayer(Consumer<String> onError) { this.onError = onError; }

    void play(Path track) {
        commands.submit(() -> {
            try {
                startIfNeeded();
                send("[\"loadfile\",\"" + jsonEscape(track.toAbsolutePath().toString()) + "\",\"replace\"]");
            } catch (Exception error) {
                onError.accept(error.getMessage() == null ? "Could not start audio playback" : error.getMessage());
            }
        });
    }

    void togglePause() { commands.submit(() -> runCommand("[\"cycle\",\"pause\"]")); }

    private void runCommand(String command) {
        try {
            startIfNeeded();
            send(command);
        } catch (Exception error) {
            onError.accept(error.getMessage() == null ? "Could not control audio playback" : error.getMessage());
        }
    }

    private void startIfNeeded() throws IOException, InterruptedException {
        if (process != null && process.isAlive() && socket != null && Files.exists(socket)) return;
        if (process != null && process.isAlive()) process.destroyForcibly();
        if (!commandExists("mpv")) {
            throw new IOException("This Linux preview uses mpv for audio. Install it with: sudo apt install mpv");
        }
        socket = Path.of("/tmp", "mb-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        process = new ProcessBuilder("mpv", "--no-video", "--idle=yes", "--force-window=no",
                "--no-terminal", "--really-quiet", "--input-ipc-server=" + socket)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (process.isAlive() && !Files.exists(socket) && System.nanoTime() < deadline) Thread.sleep(40);
        if (!process.isAlive() || !Files.exists(socket)) {
            process.destroyForcibly();
            throw new IOException("mpv did not start. Check that the system audio output is available.");
        }
    }

    private void send(String command) throws IOException {
        byte[] message = ("{\"command\":" + command + "}\n").getBytes(StandardCharsets.UTF_8);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer buffer = ByteBuffer.wrap(message);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
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
        commands.submit(() -> {
            if (process != null && process.isAlive() && socket != null && Files.exists(socket)) {
                try { send("[\"quit\"]"); }
                catch (IOException ignored) { process.destroy(); }
            }
            if (socket != null) {
                try { Files.deleteIfExists(socket); } catch (IOException ignored) { }
            }
        });
        commands.shutdown();
    }
}
