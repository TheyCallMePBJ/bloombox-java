package bloombox.tui;

import bloombox.core.signal.Spectrogram;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decodes each played track once with {@code ffmpeg} (mono 16-bit PCM at 22.05 kHz, streamed)
 * in a background thread and derives both
 * <ul>
 *   <li>the amplitude envelope for the waveform seek bar, and</li>
 *   <li>the {@link Spectrogram} the spectrum visualizer plays back in sync with mpv —
 *       so the bars show this track only, never other applications' audio.</li>
 * </ul>
 * Results are kept in a small LRU cache. Without ffmpeg both simply stay empty.
 */
final class TrackAnalysis implements AutoCloseable {

    static final int BUCKETS = 1024;
    static final int SAMPLE_RATE = 22_050;
    static final int BARS = 48;
    /** The envelope only needs loudness: keep every 5th sample (≈ 4.4 kHz) for it. */
    private static final int ENVELOPE_DECIMATION = 5;
    private static final int CACHE = 16;

    record Result(float[] peaks, Spectrogram spectrum) {}

    private final Runnable onReady;
    private final Map<Path, Result> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Result> e) {
            return size() > CACHE;
        }
    });
    private final Set<Path> pending = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bloombox-analysis");
        t.setDaemon(true);
        return t;
    });
    private volatile Process current;

    TrackAnalysis(Runnable onReady) {
        this.onReady = onReady;
    }

    float[] peaks(Path p) {
        Result r = p == null ? null : cache.get(p);
        return r == null ? null : r.peaks();
    }

    Spectrogram spectrum(Path p) {
        Result r = p == null ? null : cache.get(p);
        return r == null ? null : r.spectrum();
    }

    void request(Path p) {
        if (p == null || cache.containsKey(p) || !pending.add(p)) return;
        worker.submit(() -> {
            try {
                Result r = decode(p);
                if (r != null) {
                    cache.put(p, r);
                    onReady.run();
                }
            } finally {
                pending.remove(p);
            }
        });
    }

    private Result decode(Path p) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("ffmpeg", "-v", "error", "-nostdin", "-i", p.toString(), "-vn",
                    "-ac", "1", "-ar", Integer.toString(SAMPLE_RATE), "-f", "s16le", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            current = proc;
            Spectrogram.Builder spec = new Spectrogram.Builder(SAMPLE_RATE, BARS, Spectrogram.DEFAULT_FPS,
                    Spectrogram.DEFAULT_WINDOW);
            short[] env = new short[1 << 16];
            int envLen = 0;
            long index = 0;
            byte[] bytes = new byte[1 << 16];
            short[] chunk = new short[bytes.length / 2];
            int carry = -1; // odd byte left over from the previous read
            try (InputStream in = new BufferedInputStream(proc.getInputStream(), 1 << 16)) {
                int r;
                while ((r = in.read(bytes, carry >= 0 ? 1 : 0, bytes.length - (carry >= 0 ? 1 : 0))) > 0) {
                    int len = r;
                    if (carry >= 0) {
                        bytes[0] = (byte) carry;
                        len++;
                    }
                    int n = len / 2;
                    for (int i = 0; i < n; i++) chunk[i] = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xff));
                    carry = (len & 1) == 1 ? bytes[len - 1] & 0xff : -1;
                    spec.accept(chunk, 0, n);
                    for (int i = 0; i < n; i++, index++) {
                        if (index % ENVELOPE_DECIMATION != 0) continue;
                        if (envLen == env.length) env = Arrays.copyOf(env, env.length * 2);
                        env[envLen++] = chunk[i];
                    }
                    if (Thread.currentThread().isInterrupted()) return null;
                }
            }
            proc.waitFor();
            if (index == 0) return null;
            return new Result(envelope(env, envLen), spec.finish());
        } catch (IOException e) {
            return null; // ffmpeg missing or unreadable file
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            current = null;
        }
    }

    /**
     * RMS per bucket, normalised to the loudest bucket and gamma-lifted so quiet
     * passages stay visible. Package-private for tests.
     */
    static float[] envelope(short[] s, int n) {
        int buckets = Math.min(BUCKETS, n);
        float[] env = new float[buckets];
        float max = 0;
        for (int b = 0; b < buckets; b++) {
            int from = (int) ((long) b * n / buckets), to = (int) ((long) (b + 1) * n / buckets);
            double sum = 0;
            for (int i = from; i < to; i++) sum += (double) s[i] * s[i];
            env[b] = (float) Math.sqrt(sum / Math.max(1, to - from));
            max = Math.max(max, env[b]);
        }
        for (int b = 0; b < buckets; b++) env[b] = max == 0 ? 0 : (float) Math.pow(env[b] / max, 0.7);
        return env;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        Process p = current;
        if (p != null) p.destroyForcibly();
    }
}
