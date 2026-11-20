package bloombox.core.color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Picks theme colours from album art with <b>k-means clustering in CIELAB</b>, seeded with
 * <b>k-means++</b> (a randomized seeding: each next centre is drawn with probability
 * proportional to its squared distance from the nearest centre chosen so far, which gives an
 * O(log k)-competitive start in expectation instead of plain k-means' arbitrary one).
 *
 * <p>Lloyd iterations: assign every pixel to its nearest centre, move each centre to the
 * mean of its pixels, repeat until no assignment changes (or {@link #MAX_ITERATIONS}).
 * Cost: O(n·k·iterations) for n sampled pixels.
 *
 * <p>Then the clusters are scored by colourfulness (chroma) × coverage (√share), with a
 * penalty for very dark or very light colours. The accent is the best <em>colourful</em>
 * cluster (chroma ≥ {@link #VIVID_CHROMA}, ≥ 1% of the cover), so a small coloured detail
 * on a mostly grey cover still wins; failing that, the strongest muted tint
 * (chroma ≥ {@link #MUTED_CHROMA}); failing that (greyscale art) a monochrome palette from
 * the lightest grey. So every non-empty image yields a palette. The secondary accent is the
 * best cluster whose hue differs by at least {@link #MIN_HUE_GAP}°.
 *
 * <p>Reproducibility: the same pixels and seed always give the same palette.
 */
public final class PaletteExtractor {
    private PaletteExtractor() {}

    public static final int DEFAULT_K = 6;
    public static final int MAX_ITERATIONS = 30;
    public static final double MIN_HUE_GAP = 45;
    /** At or above this chroma a colour reads as clearly colourful. */
    public static final double VIVID_CHROMA = 15;
    /** Below this chroma a colour is plain grey (hue is noise); between the two it is a muted tint. */
    public static final double MUTED_CHROMA = 3;
    static final double MIN_ACCENT_SHARE = 0.01;

    /** How much colour the cover offered. */
    public enum Kind {
        /** a clearly colourful area exists */
        VIVID,
        /** only faint tints: accent is the strongest one */
        MUTED,
        /** plain greyscale: accent is the lightest grey, no hue */
        MONO,
        /** no pixels */
        NONE
    }

    /** One cluster: centroid colour and the fraction of pixels in it. */
    public record Swatch(int rgb, double[] lab, double share) {
        public double chroma() {
            return Lab.chroma(lab);
        }

        public double hue() {
            return Lab.hue(lab);
        }
    }

    /** Result of {@link #cluster}: swatches by share (largest first) plus the work done. */
    public record Clustering(List<Swatch> swatches, int iterations, long distanceEvaluations) {}

    /**
     * @param accent     main accent (raw cluster colour, not yet adjusted for readability)
     * @param secondary  secondary accent, or -1 if no cluster is far enough in hue
     * @param baseHue    hue of the dominant colourful area (for tinting backgrounds)
     * @param baseChroma that area's chroma (0 for grey covers)
     * @param kind       how colourful the art is; see {@link Kind}
     */
    public record Palette(int accent, int secondary, double baseHue, double baseChroma, Kind kind,
                          Clustering clustering) {}

    /** Clusters 0xRRGGBB pixels into at most {@code k} colours. */
    public static Clustering cluster(int[] rgbPixels, int k, long seed) {
        if (rgbPixels == null) throw new IllegalArgumentException("pixels must not be null");
        if (k < 1 || k > 64) throw new IllegalArgumentException("k must be in 1..64, got " + k);
        int n = rgbPixels.length;
        if (n == 0) return new Clustering(List.of(), 0, 0);
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) pts[i] = Lab.fromRgb(rgbPixels[i]);
        long evals = 0;

        // ---- k-means++ seeding
        Random rnd = new Random(seed);
        List<double[]> centres = new ArrayList<>();
        centres.add(pts[rnd.nextInt(n)].clone());
        double[] d2 = new double[n];
        for (int i = 0; i < n; i++) d2[i] = Lab.distanceSq(pts[i], centres.get(0));
        evals += n;
        while (centres.size() < k) {
            double total = 0;
            for (double v : d2) total += v;
            if (total <= 1e-9) break; // fewer distinct colours than k
            double r = rnd.nextDouble() * total;
            int pick = n - 1;
            for (int i = 0; i < n; i++) {
                r -= d2[i];
                if (r <= 0) {
                    pick = i;
                    break;
                }
            }
            double[] c = pts[pick].clone();
            centres.add(c);
            for (int i = 0; i < n; i++) d2[i] = Math.min(d2[i], Lab.distanceSq(pts[i], c));
            evals += n;
        }

        // ---- Lloyd iterations
        int kk = centres.size();
        int[] assign = new int[n];
        java.util.Arrays.fill(assign, -1);
        int iter = 0;
        boolean changed = true;
        while (changed && iter < MAX_ITERATIONS) {
            iter++;
            changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                double bestD = Double.MAX_VALUE;
                for (int c = 0; c < kk; c++) {
                    double d = Lab.distanceSq(pts[i], centres.get(c));
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                evals += kk;
                if (assign[i] != best) {
                    assign[i] = best;
                    changed = true;
                }
            }
            double[][] sum = new double[kk][3];
            int[] cnt = new int[kk];
            for (int i = 0; i < n; i++) {
                int c = assign[i];
                cnt[c]++;
                sum[c][0] += pts[i][0];
                sum[c][1] += pts[i][1];
                sum[c][2] += pts[i][2];
            }
            for (int c = 0; c < kk; c++) {
                if (cnt[c] > 0) centres.set(c, new double[]{sum[c][0] / cnt[c], sum[c][1] / cnt[c], sum[c][2] / cnt[c]});
            }
        }

        int[] cnt = new int[kk];
        for (int a : assign) cnt[a]++;
        List<Swatch> out = new ArrayList<>();
        for (int c = 0; c < kk; c++) {
            if (cnt[c] == 0) continue; // empty cluster: dropped
            double[] lab = centres.get(c);
            out.add(new Swatch(Lab.toRgb(lab[0], lab[1], lab[2]), lab, cnt[c] / (double) n));
        }
        out.sort(Comparator.comparingDouble(Swatch::share).reversed());
        return new Clustering(List.copyOf(out), iter, evals);
    }

    public static Palette extract(int[] rgbPixels, long seed) {
        return fromClustering(cluster(rgbPixels, DEFAULT_K, seed));
    }

    public static Palette fromClustering(Clustering cl) {
        if (cl.swatches().isEmpty()) return new Palette(-1, -1, 0, 0, Kind.NONE, cl);
        // 1) accent from colourful clusters only, even small ones: a red patch on a grey cover
        //    is exactly the colour that should theme it
        Swatch accent = best(cl, null, VIVID_CHROMA, MIN_ACCENT_SHARE);
        Kind kind = Kind.VIVID;
        // 2) no colourful area: the strongest tint the cover has, however faint
        if (accent == null) {
            accent = best(cl, null, MUTED_CHROMA, MIN_ACCENT_SHARE);
            kind = Kind.MUTED;
        }
        // 3) plain greyscale: monochrome, built from the lightest sizeable cluster
        if (accent == null) {
            Swatch light = cl.swatches().get(0);
            for (Swatch s : cl.swatches()) {
                if (s.share() >= MIN_ACCENT_SHARE && s.lab()[0] > light.lab()[0]) light = s;
            }
            return new Palette(light.rgb(), -1, 0, 0, Kind.MONO, cl);
        }
        // secondary: best other cluster with a clearly different hue (muted ones allowed)
        Swatch second = best(cl, accent, MUTED_CHROMA, MIN_ACCENT_SHARE);
        // background tint: the largest area that has any colour at all
        Swatch base = accent;
        for (Swatch s : cl.swatches()) {
            if (s.chroma() >= MUTED_CHROMA) {
                base = s;
                break;
            }
        }
        return new Palette(accent.rgb(), second == null ? -1 : second.rgb(), base.hue(), base.chroma(), kind, cl);
    }

    /** Highest-scoring swatch with chroma ≥ minChroma and share ≥ minShare (hue-separated from {@code avoid}). */
    private static Swatch best(Clustering cl, Swatch avoid, double minChroma, double minShare) {
        Swatch best = null;
        double bestScore = -1;
        for (Swatch s : cl.swatches()) {
            if (s == avoid || s.share() < minShare || s.chroma() < minChroma) continue;
            if (avoid != null && Lab.hueDistance(s.hue(), avoid.hue()) < MIN_HUE_GAP) continue;
            double sc = score(s);
            if (sc > bestScore) {
                bestScore = sc;
                best = s;
            }
        }
        return best;
    }

    /** chroma × √share, penalising colours far from mid lightness. */
    static double score(Swatch s) {
        double l = s.lab()[0];
        double lightness = Math.max(0.15, 1 - Math.abs(l - 60) / 60);
        return s.chroma() * Math.sqrt(s.share()) * lightness;
    }

    /**
     * Moves a colour into a readable band on a dark background: lightness clamped to
     * [minL, maxL], chroma to [minC, maxC], hue kept. Result is in sRGB gamut.
     */
    public static int readable(int rgb, double minL, double maxL, double minC, double maxC) {
        double[] lab = Lab.fromRgb(rgb);
        double l = Math.max(minL, Math.min(maxL, lab[0]));
        double c = Math.max(minC, Math.min(maxC, Lab.chroma(lab)));
        return Lab.fromLch(l, c, Lab.hue(lab));
    }
}
