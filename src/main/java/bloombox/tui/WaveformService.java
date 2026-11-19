package bloombox.tui;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Computes a whole-track amplitude envelope for the waveform seek bar by decoding
 * the file with {@code ffmpeg} to 4 kHz mono 16-bit PCM in a background thread.
 * Results are kept in a small LRU cache. Without ffmpeg the bar simply stays flat.
 */
final class WaveformService implements AutoCloseable {

    static final int BUCKETS = 1024;
    private static final int SAMPLE_RATE = 4000;
    private static final int CACHE = 32;

    private final Runnable onReady;
    private final Map<Path, float[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, float[]> e) {
            return size() > CACHE;
        }
    });
    private final Set<Path> pending = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bloombox-waveform");
        t.setDaemon(true);
        return t;
    });
    private volatile Process current;

    WaveformService(Runnable onReady) {
        this.onReady = onReady;
    }

    float[] peaks(Path p) {
        return cache.get(p);
    }

    void request(Path p) {
        if (cache.containsKey(p) || !pending.add(p)) return;
        worker.submit(() -> {
            try {
                float[] env = decode(p);
                if (env != null) {
                    cache.put(p, env);
                    onReady.run();
                }
            } finally {
                pending.remove(p);
            }
        });
    }

    private float[] decode(Path p) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("ffmpeg", "-v", "error", "-nostdin", "-i", p.toString(),
                    "-ac", "1", "-ar", Integer.toString(SAMPLE_RATE), "-f", "s16le", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            current = proc;
            byte[] pcm;
            try (InputStream in = new BufferedInputStream(proc.getInputStream(), 1 << 16)) {
                pcm = in.readAllBytes(); // 4 kHz mono s16: ~0.5 MB per minute
            }
            int n = pcm.length / 2;
            short[] samples = new short[n];
            for (int i = 0; i < n; i++) samples[i] = (short) ((pcm[2 * i + 1] << 8) | (pcm[2 * i] & 0xff));
            proc.waitFor();
            return n == 0 ? null : envelope(samples, n);
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
