package bloombox.bench;

import bloombox.core.color.PaletteExtractor;
import bloombox.core.color.PaletteExtractor.Seeding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * E6: k-means++ seeding vs uniform random seeding, on synthetic "album covers" made of six
 * colour regions of unequal size plus noise, at growing pixel counts.
 *
 * <p>Expected: k-means++ is O(log k)-competitive in expectation (Arthur &amp; Vassilvitskii,
 * 2007), so its final inertia is close to the best found and rarely bad; random seeding often
 * puts two centres in one region and misses a small one (a small coloured detail — exactly
 * the accent colour we want), giving much higher inertia in the bad cases. Both are
 * O(n·k·iterations); seeding adds O(n·k).
 */
final class KMeansBench implements Experiment {

    @Override
    public String id() {
        return "e6_kmeans";
    }

    @Override
    public String title() {
        return "E6 · Palette clustering: k-means++ vs random seeding";
    }

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{256, 1_024, 4_096} : new int[]{256, 1_024, 4_096, 16_384};
        int trials = ctx.quick ? 8 : 30;
        int k = PaletteExtractor.DEFAULT_K;
        // JIT warm-up so the first trial's timing is not interpreted code
        for (int i = 0; i < 20; i++) PaletteExtractor.cluster(image(4_096, new Random(i)), k, i, Seeding.KMEANS_PP);
        List<Object[]> md = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id(), "pixels", "trial", "seeding", "inertia", "excess_over_best", "iterations",
                "clusters_found", "distance_evals", "time_us")) {
            for (int n : sizes) {
                double[][] excess = new double[2][trials], iters = new double[2][trials], micros = new double[2][trials];
                int[] degenerate = new int[2];
                for (int t = 0; t < trials; t++) {
                    int[] px = image(n, new Random(ctx.seed * 1_000_003L + n * 31L + t));
                    PaletteExtractor.Clustering[] res = new PaletteExtractor.Clustering[2];
                    long[] ns = new long[2];
                    for (int s = 0; s < 2; s++) {
                        long t0 = System.nanoTime();
                        res[s] = PaletteExtractor.cluster(px, k, ctx.seed + t, s == 0 ? Seeding.KMEANS_PP : Seeding.RANDOM);
                        ns[s] = System.nanoTime() - t0;
                    }
                    double best = Math.min(res[0].inertia(), res[1].inertia());
                    for (int s = 0; s < 2; s++) {
                        double ex = best > 0 ? res[s].inertia() / best - 1 : 0;
                        excess[s][t] = ex;
                        iters[s][t] = res[s].iterations();
                        micros[s][t] = ns[s] / 1000.0;
                        if (res[s].swatches().size() < k) degenerate[s]++;
                        csv.row(n, t, s == 0 ? "kmeans++" : "random", res[s].inertia(), ex, res[s].iterations(),
                                res[s].swatches().size(), res[s].distanceEvaluations(), ns[s] / 1000.0);
                    }
                }
                for (int s = 0; s < 2; s++) {
                    String name = s == 0 ? "k-means++" : "random";
                    md.add(new Object[]{n, name, mean(excess[s]) * 100, percentile(excess[s], 0.9) * 100, mean(iters[s]),
                            degenerate[s] + "/" + trials, percentile(micros[s], 0.5)});
                    ctx.log("  pixels=%-6d %-9s mean excess %6.1f%%  p90 %6.1f%%  iters %.1f  %.0f µs", n, name,
                            mean(excess[s]) * 100, percentile(excess[s], 0.9) * 100, mean(iters[s]), percentile(micros[s], 0.5));
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("k = " + k + "; " + trials + " synthetic covers per size (6 colour regions, shares 2–40%, Gaussian noise). "
                + "Excess = inertia ÷ best inertia found for that image − 1 (0% = as good as the better method). "
                + "Lost clusters = runs that ended with an empty cluster.\n");
        ctx.table(new String[]{"pixels", "seeding", "mean excess %", "p90 excess %", "mean iterations", "lost clusters",
                "median µs"}, md);
    }

    /** Six colour regions with unequal shares (one small "detail" region) and per-channel Gaussian noise. */
    static int[] image(int n, Random rnd) {
        int regions = 6;
        int[] centre = new int[regions];
        double[] share = {0.40, 0.25, 0.15, 0.10, 0.08, 0.02};
        for (int r = 0; r < regions; r++) centre[r] = rnd.nextInt(0x1000000);
        int[] px = new int[n];
        int i = 0;
        for (int r = 0; r < regions; r++) {
            int count = r == regions - 1 ? n - i : (int) Math.round(share[r] * n);
            for (int j = 0; j < count && i < n; j++) px[i++] = noisy(centre[r], rnd);
        }
        return px;
    }

    private static int noisy(int rgb, Random rnd) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int v = (int) Math.round(((rgb >> shift) & 0xff) + rnd.nextGaussian() * 10);
            out |= Math.max(0, Math.min(255, v)) << shift;
        }
        return out;
    }

    static double mean(double[] a) {
        return Arrays.stream(a).average().orElse(Double.NaN);
    }

    static double percentile(double[] a, double q) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.floor(q * (s.length - 1) + 0.5))];
    }
}
