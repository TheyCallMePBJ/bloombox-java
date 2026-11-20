package bloombox.core.signal;

import java.util.Arrays;

/**
 * Bar-graph spectrogram of a whole track: for each frame (FPS per second) the energy in
 * {@code bars} logarithmically spaced frequency bands, as levels 0–255.
 *
 * <p>Built in one streaming pass ({@link Builder}): each frame is a Hann-windowed FFT of the
 * samples centred on that instant (short-time Fourier transform). Band power is the mean
 * |X[k]|² over the band's bins, in dB, with a +3 dB/octave tilt so a typical music spectrum
 * (which falls off towards the treble) fills the bars evenly. Levels are then normalised per
 * track: the 99.5th-percentile band level maps to full height and anything 45 dB below it
 * to zero, so quiet and loud masters both use the whole bar height.
 *
 * <p>Cost: one O(N log N) FFT per frame, so O(T·FPS·N log N) for a track of T seconds;
 * memory O(T·FPS·bars) bytes (≈ 430 KB for 5 minutes) plus an O(N) sample window.
 */
public final class Spectrogram {

    public static final double DEFAULT_FPS = 30;
    public static final int DEFAULT_WINDOW = 2048;
    static final double TILT_DB_PER_OCTAVE = 3;
    static final double RANGE_DB = 45;
    static final double REFERENCE_PERCENTILE = 0.995;

    private final int bars, frames;
    private final double fps;
    private final byte[] levels;

    private Spectrogram(int bars, int frames, double fps, byte[] levels) {
        this.bars = bars;
        this.frames = frames;
        this.fps = fps;
        this.levels = levels;
    }

    public int bars() {
        return bars;
    }

    public int frames() {
        return frames;
    }

    public double fps() {
        return fps;
    }

    /** Level 0–255 of {@code bar} in {@code frame}. */
    public int level(int frame, int bar) {
        return levels[frame * bars + bar] & 0xff;
    }

    /**
     * Bar heights at {@code seconds} into the track, scaled to 0..{@code max} and linearly
     * interpolated between the two nearest frames; zeros outside the track.
     */
    public void sample(double seconds, int[] out, int max) {
        int nb = Math.min(out.length, bars);
        double f = seconds * fps;
        if (!(f >= 0) || f > frames - 1 || frames == 0) {
            Arrays.fill(out, 0);
            return;
        }
        int f0 = (int) f, f1 = Math.min(frames - 1, f0 + 1);
        double t = f - f0;
        for (int b = 0; b < nb; b++) {
            double v = level(f0, b) * (1 - t) + level(f1, b) * t;
            out[b] = (int) Math.round(v / 255.0 * max);
        }
        for (int b = nb; b < out.length; b++) out[b] = 0;
    }

    /** Convenience for whole buffers (tests, benchmarks). */
    public static Spectrogram of(short[] pcm, int sampleRate, int bars) {
        Builder b = new Builder(sampleRate, bars, DEFAULT_FPS, DEFAULT_WINDOW);
        b.accept(pcm, 0, pcm.length);
        return b.finish();
    }

    /** Streaming construction: feed mono 16-bit samples in any chunk sizes, then {@link #finish()}. */
    public static final class Builder {
        private final int sampleRate, bars, window;
        private final double fps;
        private final Fft fft;
        private final double[] hann, re, im;
        private final int[] bandStart; // band b = bins [bandStart[b], bandStart[b+1])
        private final double[] tiltDb;
        private final double norm;

        private float[] buf;       // samples from absolute index bufStart onwards
        private int bufLen;
        private long bufStart, total;
        private int nextFrame;
        private byte[] raw = new byte[1 << 14]; // quantised dB, 0.5 dB steps
        private int rawLen;

        public Builder(int sampleRate, int bars, double fps, int window) {
            if (sampleRate < 8_000) throw new IllegalArgumentException("sample rate too low: " + sampleRate);
            if (bars < 1 || bars > 256) throw new IllegalArgumentException("bars must be 1..256: " + bars);
            if (!(fps > 0) || fps > sampleRate) throw new IllegalArgumentException("bad fps: " + fps);
            this.sampleRate = sampleRate;
            this.bars = bars;
            this.fps = fps;
            this.window = window;
            this.fft = new Fft(window);
            if (window < 2 * bars) throw new IllegalArgumentException("window too small for " + bars + " bars");
            hann = new double[window];
            double sum = 0;
            for (int i = 0; i < window; i++) {
                hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / window);
                sum += hann[i];
            }
            // full-scale sine (amplitude 1) → peak |X| = sum/2 → 0 dB
            norm = (sum / 2) * (sum / 2);
            re = new double[window];
            im = new double[window];
            double lo = 50, hi = Math.min(10_000, sampleRate * 0.45);
            int maxBin = window / 2;
            bandStart = new int[bars + 1];
            tiltDb = new double[bars];
            for (int b = 0; b <= bars; b++) {
                double hz = lo * Math.pow(hi / lo, b / (double) bars);
                int bin = (int) Math.round(hz * window / sampleRate);
                bandStart[b] = Math.max(b == 0 ? 1 : bandStart[b - 1] + 1, bin);
            }
            if (bandStart[bars] > maxBin) throw new IllegalArgumentException("window too small for the band layout");
            for (int b = 0; b < bars; b++) {
                double fLo = bandStart[b] * (double) sampleRate / window, fHi = bandStart[b + 1] * (double) sampleRate / window;
                tiltDb[b] = TILT_DB_PER_OCTAVE * Math.log(Math.sqrt(fLo * fHi) / 1000) / Math.log(2);
            }
            buf = new float[window * 2];
        }

        public void accept(short[] s, int off, int len) {
            if (bufLen + len > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, bufLen + len));
            for (int i = 0; i < len; i++) buf[bufLen + i] = s[off + i] / 32768f;
            bufLen += len;
            total += len;
            while (centre(nextFrame) + window / 2 <= total) frame(nextFrame++);
            compact();
        }

        public Spectrogram finish() {
            while (centre(nextFrame) < total) frame(nextFrame++);
            int frames = nextFrame;
            // per-track normalisation from a histogram of the quantised levels (digital silence = 0 is ignored)
            long[] hist = new long[256];
            long counted = 0;
            for (int i = 0; i < rawLen; i++) {
                int q = raw[i] & 0xff;
                if (q > 0) {
                    hist[q]++;
                    counted++;
                }
            }
            byte[] levels = new byte[frames * bars];
            if (counted > 0) {
                long want = (long) Math.ceil(counted * REFERENCE_PERCENTILE), seen = 0;
                int ref = 255;
                for (int q = 1; q < 256; q++) {
                    seen += hist[q];
                    if (seen >= want) {
                        ref = q;
                        break;
                    }
                }
                double floor = ref - RANGE_DB * 2;
                for (int i = 0; i < frames * bars; i++) {
                    int q = raw[i] & 0xff;
                    double v = q == 0 ? 0 : (q - floor) / (ref - floor);
                    levels[i] = (byte) Math.round(255 * Math.max(0, Math.min(1, v)));
                }
            }
            return new Spectrogram(bars, frames, fps, levels);
        }

        private long centre(int f) {
            return Math.round(f * (double) sampleRate / fps);
        }

        private void frame(int f) {
            long from = centre(f) - window / 2;
            boolean silent = true;
            for (int i = 0; i < window; i++) {
                long abs = from + i;
                double x = abs < bufStart || abs >= total ? 0 : buf[(int) (abs - bufStart)];
                if (x != 0) silent = false;
                re[i] = x * hann[i];
                im[i] = 0;
            }
            if (rawLen + bars > raw.length) raw = Arrays.copyOf(raw, raw.length * 2);
            if (silent) {
                Arrays.fill(raw, rawLen, rawLen + bars, (byte) 0);
            } else {
                fft.transform(re, im);
                for (int b = 0; b < bars; b++) {
                    double p = 0;
                    for (int k = bandStart[b]; k < bandStart[b + 1]; k++) p += re[k] * re[k] + im[k] * im[k];
                    p /= (bandStart[b + 1] - bandStart[b]) * norm;
                    double db = 10 * Math.log10(p + 1e-20) + tiltDb[b];
                    // 0.5 dB steps over −127 … 0 dB (+ headroom); 0 is reserved for silence
                    raw[rawLen + b] = (byte) Math.max(1, Math.min(255, Math.round((db + 115) * 2)));
                }
            }
            rawLen += bars;
        }

        /** Drops samples no future frame needs. */
        private void compact() {
            long keepFrom = Math.max(bufStart, centre(nextFrame) - window / 2);
            int drop = (int) Math.min(bufLen, keepFrom - bufStart);
            if (drop <= 0) return;
            System.arraycopy(buf, drop, buf, 0, bufLen - drop);
            bufLen -= drop;
            bufStart += drop;
        }
    }
}
