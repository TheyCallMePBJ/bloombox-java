package bloombox.player;

import bloombox.util.Json;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Audio playback by driving an {@code mpv --idle} child process over its JSON IPC
 * unix socket. mpv handles decoding (FLAC/MP3/OGG/Opus), output and seeking;
 * bloombox owns the queue and only ever loads one file at a time.
 *
 * <p>Thread-safety: commands may be sent from any thread; events are delivered on
 * a single reader thread.
 */
public final class MpvPlayer implements AudioBackend, Closeable {

    /** Callbacks from mpv. All methods are invoked on the IPC reader thread. */
    public interface Listener {
        default void onPosition(double seconds) {}
        default void onDuration(double seconds) {}
        default void onPause(boolean paused) {}
        default void onVolume(double volume) {}
        /** Track finished. {@code naturalEnd} is false when stopped/replaced/errored. */
        default void onEndFile(boolean naturalEnd, String reason) {}
        default void onFileLoaded() {}
        /** Playback resumed after a seek or a file load; time-pos is accurate again. */
        default void onPlaybackRestart() {}
        default void onDisconnected() {}
    }

    private final Process process;
    private final SocketChannel channel;
    private final Path socketPath;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Thread reader;
    private final Object writeLock = new Object();

    private volatile double position;
    private volatile double duration;
    private volatile boolean paused = true;
    private volatile boolean idle = true;
    private volatile double volume;

    private MpvPlayer(Process process, SocketChannel channel, Path socketPath) {
        this.process = process;
        this.channel = channel;
        this.socketPath = socketPath;
        this.reader = new Thread(this::readLoop, "mpv-ipc");
        this.reader.setDaemon(true);
    }

    /**
     * Starts mpv and connects to it.
     *
     * @throws IOException if mpv is not installed or the socket never appears
     */
    public static MpvPlayer start(int initialVolume) throws IOException {
        Path runtime = runtimeDir();
        Files.createDirectories(runtime);
        Path sock = runtime.resolve("mpv-" + ProcessHandle.current().pid() + ".sock");
        Files.deleteIfExists(sock);

        Process p;
        try {
            p = new ProcessBuilder(
                    "mpv", "--idle=yes", "--no-video", "--no-terminal", "--no-config",
                    "--audio-display=no", "--gapless-audio=weak",
                    "--volume=" + initialVolume, "--volume-max=130",
                    "--input-ipc-server=" + sock)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new IOException("could not start mpv — is it installed and on PATH?", e);
        }

        SocketChannel ch = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!p.isAlive()) throw new IOException("mpv exited immediately (code " + p.exitValue() + ")");
            if (Files.exists(sock)) {
                try {
                    ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                    ch.connect(UnixDomainSocketAddress.of(sock));
                    break;
                } catch (IOException retry) {
                    if (ch != null) ch.close();
                    ch = null;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (ch == null) {
            p.destroyForcibly();
            throw new IOException("timed out connecting to mpv IPC socket " + sock);
        }

        MpvPlayer player = new MpvPlayer(p, ch, sock);
        player.volume = initialVolume;
        player.reader.start();
        player.observe(1, "time-pos");
        player.observe(2, "duration");
        player.observe(3, "pause");
        player.observe(4, "volume");
        player.observe(5, "idle-active");
        return player;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // ---- commands -------------------------------------------------------------

    public void load(Path file) {
        command("loadfile", file.toAbsolutePath().toString(), "replace");
        setPaused(false);
    }

    public void stop() {
        command("stop");
    }

    public void setPaused(boolean p) {
        command("set_property", "pause", p);
    }

    public void togglePause() {
        command("cycle", "pause");
    }

    public void seekAbsolute(double seconds) {
        command("seek", Math.max(0, seconds), "absolute");
    }

    public void seekRelative(double seconds) {
        command("seek", seconds, "relative");
    }

    public void setVolume(double v) {
        command("set_property", "volume", Math.max(0, Math.min(130, v)));
    }

    // ---- state ----------------------------------------------------------------

    public double position() { return position; }
    public double duration() { return duration; }
    public boolean isPaused() { return paused; }
    public boolean isIdle() { return idle; }
    public double volume() { return volume; }
    public boolean isAlive() { return process.isAlive() && channel.isOpen(); }

    // ---- plumbing -------------------------------------------------------------

    private void observe(int id, String property) {
        command("observe_property", id, property);
    }

    private void command(Object... args) {
        String msg = Json.write(Map.of("command", List.of(args))) + "\n";
        ByteBuffer buf = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
        synchronized (writeLock) {
            try {
                while (buf.hasRemaining()) channel.write(buf);
            } catch (IOException e) {
                // reader thread will notice the disconnect and notify listeners
            }
        }
    }

    private void readLoop() {
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        try {
            while (channel.read(buf) >= 0) {
                buf.flip();
                while (buf.hasRemaining()) {
                    byte b = buf.get();
                    if (b == '\n') {
                        handle(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(b);
                    }
                }
                buf.clear();
            }
        } catch (IOException ignored) {
            // closed
        }
        for (Listener l : listeners) l.onDisconnected();
    }

    @SuppressWarnings("unchecked")
    private void handle(String raw) {
        if (raw.isBlank()) return;
        Map<String, Object> msg;
        try {
            msg = (Map<String, Object>) Json.parse(raw);
        } catch (RuntimeException e) {
            return; // never let one bad message kill the reader
        }
        Object event = msg.get("event");
        if (event == null) return; // command reply; we don't currently wait on these

        switch ((String) event) {
            case "property-change" -> onProperty((String) msg.get("name"), msg.get("data"));
            case "end-file" -> {
                String reason = String.valueOf(msg.get("reason"));
                boolean natural = "eof".equals(reason);
                for (Listener l : listeners) l.onEndFile(natural, reason);
            }
            case "file-loaded" -> {
                for (Listener l : listeners) l.onFileLoaded();
            }
            case "playback-restart" -> {
                for (Listener l : listeners) l.onPlaybackRestart();
            }
            default -> { }
        }
    }

    private void onProperty(String name, Object data) {
        if (name == null) return;
        switch (name) {
            case "time-pos" -> {
                position = data instanceof Number n ? n.doubleValue() : 0;
                for (Listener l : listeners) l.onPosition(position);
            }
            case "duration" -> {
                duration = data instanceof Number n ? n.doubleValue() : 0;
                for (Listener l : listeners) l.onDuration(duration);
            }
            case "pause" -> {
                paused = Boolean.TRUE.equals(data);
                for (Listener l : listeners) l.onPause(paused);
            }
            case "volume" -> {
                volume = data instanceof Number n ? n.doubleValue() : volume;
                for (Listener l : listeners) l.onVolume(volume);
            }
            case "idle-active" -> idle = Boolean.TRUE.equals(data);
            default -> { }
        }
    }

    @Override
    public void close() {
        try {
            command("quit");
            process.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(1),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { channel.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(socketPath); } catch (IOException ignored) { }
        }
    }

    private static Path runtimeDir() {
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("bloombox");
    }
}
