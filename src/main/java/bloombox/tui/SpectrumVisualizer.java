package bloombox.tui;

import bloombox.core.signal.Spectrogram;

import java.util.Arrays;

/**
 * Plays a track's precomputed {@link Spectrogram} back in step with the player.
 *
 * <p>mpv reports {@code time-pos} only every so often, so between reports the position is
 * extrapolated from the wall clock (capped, so a stalled player can't run ahead). Bars jump
 * up immediately and fall back with gravity, like cava; when playback pauses or stops they
 * fall to the floor.
 */
final class SpectrumVisualizer {

    static final int BARS = TrackAnalysis.BARS;
    static final int MAX = 1000;
    /** Fall acceleration in bar units/s²: a full-height bar drops in ≈ 0.5 s. */
    static final double GRAVITY = 8_000;
    private static final double MAX_EXTRAPOLATION_S = 0.25;

    private final double[] shown = new double[BARS], velocity = new double[BARS];
    private final int[] target = new int[BARS], out = new int[BARS];
    private long lastNanos = Long.MIN_VALUE, posNanos;
    private double lastPos = Double.NaN;

    /**
     * Advances the animation to {@code nowNanos} and returns bar heights 0..{@link #MAX}
     * (the returned array is reused).
     */
    int[] update(Spectrogram spectrum, double position, boolean playing, long nowNanos) {
        double dt = lastNanos == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(0.1, (nowNanos - lastNanos) / 1e9));
        lastNanos = nowNanos;
        if (position != lastPos) {
            lastPos = position;
            posNanos = nowNanos;
        }
        if (spectrum != null && playing) {
            double est = lastPos + Math.min(MAX_EXTRAPOLATION_S, (nowNanos - posNanos) / 1e9);
            spectrum.sample(est, target, MAX);
        } else {
            Arrays.fill(target, 0);
        }
        for (int i = 0; i < BARS; i++) {
            if (target[i] >= shown[i]) {
                shown[i] = target[i];
                velocity[i] = 0;
            } else {
                velocity[i] += GRAVITY * dt;
                shown[i] = Math.max(target[i], shown[i] - velocity[i] * dt);
            }
            out[i] = (int) Math.round(shown[i]);
        }
        return out;
    }

    /** True once every bar is at rest on the floor (nothing left to animate). */
    boolean settled() {
        for (double v : shown) if (v > 0.5) return false;
        return true;
    }
}
