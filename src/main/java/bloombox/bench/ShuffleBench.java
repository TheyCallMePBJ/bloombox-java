package bloombox.bench;

import bloombox.player.PlayQueue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * E7: shuffle uniformity and cost. Fisher–Yates (the app's shuffle) vs two baselines:
 * the "naive swap" shuffle (swap each a[i] with a uniformly random a[j], j over the whole
 * array: n^n equally likely paths onto n! permutations, so it cannot be uniform) and
 * sort-by-random-key (uniform, but O(n log n)).
 *
 * <p>Uniformity: for n = 4, count each of the 24 permutations over many shuffles and run
 * Pearson's chi-square test against the uniform distribution (23 degrees of freedom).
 */
final class ShuffleBench implements Experiment {

    @Override
    public String id() {
        return "e7_shuffle";
    }

    @Override
    public String title() {
        return "E7 · Shuffle: Fisher–Yates vs naive swap vs sort-by-random-key";
    }

    interface Shuffler {
        void shuffle(int[] a, Random rnd);
    }

    static final Shuffler FISHER_YATES = PlayQueue::fisherYates;

    static final Shuffler NAIVE_SWAP = (a, rnd) -> {
        for (int i = 0; i < a.length; i++) {
            int j = rnd.nextInt(a.length);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    };

    static final Shuffler SORT_BY_KEY = (a, rnd) -> {
        long[] keyed = new long[a.length];
        for (int i = 0; i < a.length; i++) keyed[i] = ((long) rnd.nextInt() << 32) | (a[i] & 0xffffffffL);
        Arrays.sort(keyed); // random high bits order the elements; low bits carry the value
        for (int i = 0; i < a.length; i++) a[i] = (int) keyed[i];
    };

    @Override
    public void run(BenchContext ctx) throws IOException {
        int trials = ctx.quick ? 48_000 : 240_000;
        String[] names = {"fisher-yates", "naive-swap", "sort-by-key"};
        Shuffler[] impls = {FISHER_YATES, NAIVE_SWAP, SORT_BY_KEY};
        List<Object[]> md = new ArrayList<>();
        int replicates = ctx.quick ? 10 : 40;
        try (BenchContext.Csv csv = ctx.csv(id() + "_uniformity", "method", "trials", "chi_square", "df", "p_value",
                "min_cell_share", "max_cell_share", "expected_share", "replicates", "rejections_at_0.05", "median_chi_square");
             BenchContext.Csv reps = ctx.csv(id() + "_replicates", "method", "replicate", "chi_square", "p_value")) {
            for (int s = 0; s < impls.length; s++) {
                // replicate 0 is the headline test; replicates 1.. use independent seeds
                double[] chis = new double[replicates];
                long[] counts0 = null;
                int rejections = 0;
                for (int r = 0; r < replicates; r++) {
                    long[] counts = permutationCounts(impls[s], trials, new Random(ctx.seed + 7_919L * r));
                    chis[r] = chiSquare(counts, trials);
                    double pr = chiSquareSurvival(chis[r], 23);
                    if (pr < 0.05) rejections++;
                    if (r == 0) counts0 = counts;
                    reps.row(names[s], r, chis[r], pr);
                }
                long min = Arrays.stream(counts0).min().orElse(0), max = Arrays.stream(counts0).max().orElse(0);
                double chi = chis[0], p = chiSquareSurvival(chi, 23);
                double medChi = KMeansBench.percentile(chis, 0.5);
                csv.row(names[s], trials, chi, 23, p, min / (double) trials, max / (double) trials, 1 / 24.0, replicates,
                        rejections, medChi);
                md.add(new Object[]{names[s], chi, p < 1e-6 ? "< 1e-6" : String.format(java.util.Locale.ROOT, "%.3f", p),
                        medChi, rejections + "/" + replicates + " (" + Math.round(100.0 * rejections / replicates) + "%)",
                        min / (double) trials * 100, max / (double) trials * 100});
                ctx.log("  %-12s chi²=%10.1f p=%.4g  median chi² %.1f  rejected %d/%d", names[s], chi, p, medChi, rejections,
                        replicates);
            }
        }
        int[] sizes = ctx.quick ? new int[]{1_000, 10_000, 100_000} : new int[]{10_000, 100_000, 1_000_000};
        List<Object[]> md2 = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id() + "_time", "method", "n", "ns_per_shuffle_median", "ns_min", "ns_max", "ns_per_element")) {
            for (int n : sizes) {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = i;
                Random rnd = new Random(ctx.seed);
                for (int s : new int[]{0, 2}) { // timing: the two uniform methods
                    BenchContext.Stats t = ctx.time(1, () -> {
                        impls[s].shuffle(a, rnd);
                        BenchContext.sink += a[0];
                    });
                    csv.row(names[s], n, t.medianNs(), t.minNs(), t.maxNs(), t.medianNs() / n);
                    md2.add(new Object[]{n, names[s], t.medianNs() / 1000, t.medianNs() / n});
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown(trials + " shuffles of [0,1,2,3] per test; expected share of each of the 24 permutations = 4.167%. "
                + "Pearson chi-square test, 23 degrees of freedom (critical value 35.17 at α = 0.05; E[χ²] = 23 if uniform). "
                + "One test of a uniform shuffle still rejects 5% of the time by chance, so the test is repeated with "
                + replicates + " independent seeds: a uniform shuffle should be rejected in ≈ 5% of them, a biased one in all.\n");
        ctx.table(new String[]{"method", "χ² (seed 0)", "p-value (seed 0)", "median χ² over seeds", "rejected at α = 0.05",
                "rarest permutation %", "commonest permutation %"}, md);
        ctx.markdown("Cost of one shuffle (uniform methods only):\n");
        ctx.table(new String[]{"n", "method", "µs/shuffle (median)", "ns/element"}, md2);
    }

    static long[] permutationCounts(Shuffler sh, int trials, Random rnd) {
        long[] counts = new long[24];
        int[] a = new int[4];
        for (int t = 0; t < trials; t++) {
            for (int i = 0; i < 4; i++) a[i] = i;
            sh.shuffle(a, rnd);
            counts[permIndex(a)]++;
        }
        return counts;
    }

    static double chiSquare(long[] counts, long trials) {
        double expected = trials / (double) counts.length, chi = 0;
        for (long c : counts) chi += (c - expected) * (c - expected) / expected;
        return chi;
    }

    /** Lehmer-code index of a permutation of 0..3, in [0, 24). */
    static int permIndex(int[] a) {
        int idx = 0;
        for (int i = 0; i < a.length; i++) {
            int smaller = 0;
            for (int j = i + 1; j < a.length; j++) if (a[j] < a[i]) smaller++;
            idx = idx * (a.length - i) + smaller;
        }
        return idx;
    }

    /** P(X ≥ x) for X ~ χ²(df): the regularised upper incomplete gamma Q(df/2, x/2). */
    static double chiSquareSurvival(double x, int df) {
        if (x <= 0) return 1;
        return gammaQ(df / 2.0, x / 2.0);
    }

    // Regularised incomplete gamma (series for x < a+1, continued fraction otherwise; Numerical Recipes §6.2).
    private static double gammaQ(double a, double x) {
        if (x < a + 1) return 1 - gammaPSeries(a, x);
        double b = x + 1 - a, c = 1 / 1e-300, d = 1 / b, h = d;
        for (int i = 1; i < 500; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < 1e-300) d = 1e-300;
            c = b + an / c;
            if (Math.abs(c) < 1e-300) c = 1e-300;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-14) break;
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    private static double gammaPSeries(double a, double x) {
        double ap = a, sum = 1 / a, del = sum;
        for (int i = 0; i < 1000; i++) {
            ap++;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * 1e-14) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** Lanczos approximation of ln Γ(z), z > 0. */
    static double logGamma(double z) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double x = z, y = z, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double v : c) ser += v / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
