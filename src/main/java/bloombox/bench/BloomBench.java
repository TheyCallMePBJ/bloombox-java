package bloombox.bench;

import bloombox.core.prob.BloomFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;

/**
 * E4: Bloom filter false-positive rate, measured vs theory, and memory / probe time vs an
 * exact {@code HashSet<Long>} baseline.
 *
 * <p>Theory: with m bits, k hashes and n keys, FPR ≈ (1 − e^(−kn/m))^k. Sizing
 * m = −n ln p / (ln 2)², k = (m/n) ln 2 targets FPR p using ≈ 1.44·log₂(1/p) bits per key,
 * independent of key size; a hash set stores every key (≈ 50+ bytes per boxed Long).
 */
final class BloomBench implements Experiment {

    @Override
    public String id() {
        return "e4_bloom";
    }

    @Override
    public String title() {
        return "E4 · Bloom filter: measured vs theoretical false-positive rate, vs HashSet";
    }

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{1_000, 10_000, 50_000} : new int[]{1_000, 10_000, 100_000, 1_000_000};
        double[] targets = {0.1, 0.01, 0.001};
        int probes = ctx.quick ? 100_000 : 1_000_000;
        List<Object[]> md = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id(), "n", "target_fpp", "m_bits", "k", "bits_per_key", "measured_fpp",
                "theoretical_fpp", "probe_ns_median", "hashset_probe_ns_median", "hashset_bytes_per_key", "bloom_bytes_per_key")) {
            for (int n : sizes) {
                SplittableRandom rnd = new SplittableRandom(ctx.seed + n);
                long[] keys = new long[n];
                HashSet<Long> exact = new HashSet<>(n * 2);
                for (int i = 0; i < n; i++) {
                    keys[i] = rnd.nextLong();
                    exact.add(keys[i]);
                }
                long[] queries = new long[probes]; // keys guaranteed absent
                for (int i = 0; i < probes; i++) {
                    long q;
                    do q = rnd.nextLong(); while (exact.contains(q));
                    queries[i] = q;
                }
                // exact baseline: memory (fresh set measured alone) and probe time
                long h0 = BenchContext.usedHeap();
                HashSet<Long> set = new HashSet<>();
                for (long k : keys) set.add(k);
                double setBytesPerKey = Math.max(0, BenchContext.usedHeap() - h0) / (double) n;
                BenchContext.Stats setT = ctx.time(probes, () -> {
                    long s = 0;
                    for (long q : queries) if (set.contains(q)) s++;
                    BenchContext.sink += s;
                });
                for (double p : targets) {
                    BloomFilter bf = BloomFilter.optimal(n, p);
                    for (long k : keys) bf.add(k);
                    for (long k : keys) if (!bf.mightContain(k)) throw new IllegalStateException("false negative");
                    long fp = 0;
                    for (long q : queries) if (bf.mightContain(q)) fp++;
                    double measured = fp / (double) probes;
                    double theory = BloomFilter.theoreticalFpp(bf.bitSize(), bf.hashCount(), n);
                    BenchContext.Stats t = ctx.time(probes, () -> {
                        long s = 0;
                        for (long q : queries) if (bf.mightContain(q)) s++;
                        BenchContext.sink += s;
                    });
                    double bitsPerKey = bf.bitSize() / (double) n;
                    csv.row(n, p, bf.bitSize(), bf.hashCount(), bitsPerKey, measured, theory, t.medianNs(), setT.medianNs(),
                            setBytesPerKey, bf.memoryBytes() / (double) n);
                    md.add(new Object[]{n, p, bf.hashCount(), bitsPerKey, String.format(java.util.Locale.ROOT, "%.5f", measured),
                            String.format(java.util.Locale.ROOT, "%.5f", theory), t.medianNs(), bf.memoryBytes() / (double) n,
                            setT.medianNs(), setBytesPerKey});
                    ctx.log("  n=%-8d p=%-6s measured=%.5f theory=%.5f  %.1f ns/probe", n, p, measured, theory, t.medianNs());
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("Random 64-bit keys; FPR measured over " + probes + " keys known to be absent. No false negatives were "
                + "observed (checked for every inserted key). HashSet bytes/key is the measured retained heap of a HashSet<Long>.\n");
        ctx.table(new String[]{"n", "target p", "k", "bits/key", "measured FPR", "theoretical FPR", "Bloom ns/probe",
                "Bloom bytes/key", "HashSet ns/probe", "HashSet bytes/key"}, md);
    }
}
