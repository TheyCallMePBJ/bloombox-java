package bloombox.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Live spectrum bars from <a href="https://github.com/karlstav/cava">cava</a>.
 * cava captures the system audio output (PipeWire/Pulse), so the bars follow
 * whatever is playing. We run it with a generated config in raw ASCII mode
 * ({@code v1;v2;…;vN;\n} per frame, values 0..1000) and keep the latest frame.
 */
final class CavaVisualizer implements AutoCloseable {

    static final int BARS = 48;
    static final int MAX = 1000;

    private final Path configFile;
    private final Runnable onFrame;
    private volatile Process process;
    private volatile int[] bars = new int[BARS];
    private Thread reader;

    CavaVisualizer(Path cacheDir, Runnable onFrame) {
        this.configFile = cacheDir.resolve("cava.conf");
        this.onFrame = onFrame;
    }

    void start() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, """
                    [general]
                    bars = %d
                    framerate = 30
                    autosens = 1
                    sleep_timer = 0

                    [output]
                    method = raw
                    raw_target = /dev/stdout
                    data_format = ascii
                    ascii_max_range = %d
                    bar_delimiter = 59
                    frame_delimiter = 10
                    channels = mono
                    mono_option = average

                    [smoothing]
                    noise_reduction = 70
                    """.formatted(BARS, MAX));
            process = new ProcessBuilder("cava", "-p", configFile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .start();
        } catch (IOException e) {
            process = null;
            return;
        }
        reader = new Thread(this::readLoop, "bloombox-cava");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        Process p = process;
        if (p == null) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = in.readLine()) != null) {
                int[] frame = parse(line);
                if (frame != null) {
                    bars = frame;
                    onFrame.run();
                }
            }
        } catch (IOException ignored) {
            // cava exited
        }
    }

    /** Parses one raw frame; returns null for malformed lines. Package-private for tests. */
    static int[] parse(String line) {
        String[] parts = line.split(";");
        int n = 0;
        int[] out = new int[parts.length];
        for (String s : parts) {
            if (s.isBlank()) continue;
            try {
                out[n++] = Math.max(0, Math.min(MAX, Integer.parseInt(s.strip())));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }

    int[] bars() {
        return bars;
    }

    boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    @Override
    public void close() {
        Process p = process;
        process = null;
        if (p != null) p.destroy();
    }
}
