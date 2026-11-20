package bloombox.bench;

import bloombox.core.OpStats;
import bloombox.core.trie.LinearPrefixIndex;
import bloombox.core.trie.PrefixIndex;
import bloombox.core.trie.RadixTrie;
import bloombox.core.trie.Trie;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * E1: prefix lookup in Trie and RadixTrie vs the linear-scan baseline, for growing key counts.
 *
 * <p>Expected: trie query cost O(m + r) (prefix length m, r = size of the matching subtree),
 * independent of the number of keys K; linear scan O(K) per query. Exact lookups: O(m) vs
 * O(K). The radix trie stores at most 2K nodes (vs up to Σ|key| for the plain trie).
 */
final class PrefixBench implements Experiment {

    @Override
    public String id() {
        return "e1_prefix";
    }

    @Override
    public String title() {
        return "E1 · Prefix index: Trie vs Radix trie vs linear scan";
    }

    private record Impl(String name, Supplier<PrefixIndex<Integer>> make) {}

    private static final List<Impl> IMPLS = List.of(
            new Impl("trie", Trie::new),
            new Impl("radix", RadixTrie::new),
            new Impl("linear", LinearPrefixIndex::new));

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{1_000, 4_000, 16_000} : new int[]{1_000, 10_000, 100_000, 1_000_000};
        int queries = ctx.quick ? 200 : 1_000;
        List<Object[]> md = new ArrayList<>();
        prime(ctx);
        try (BenchContext.Csv csv = ctx.csv(id(), "structure", "keys", "query", "ns_per_query_median", "ns_min", "ns_max",
                "results_per_query", "nodes_visited_per_query", "char_cmps_per_query", "build_ms", "nodes", "heap_bytes_per_key")) {
            for (int k : sizes) {
                String[] keys = SyntheticLibrary.vocabulary(k, ctx.seed + k);
                Random rnd = new Random(ctx.seed);
                // query classes: prefixes of real keys (2 and 4 chars), exact keys, and misses
                String[][] work = {
                        sample(keys, rnd, queries, 2), sample(keys, rnd, queries, 4),
                        sample(keys, rnd, queries, Integer.MAX_VALUE), misses(rnd, queries)};
                String[] classes = {"prefix-2", "prefix-4", "exact", "miss"};
                for (Impl impl : IMPLS) {
                    long h0 = BenchContext.usedHeap();
                    long t0 = System.nanoTime();
                    PrefixIndex<Integer> idx = impl.make().get();
                    for (int i = 0; i < keys.length; i++) idx.insert(keys[i], i);
                    double buildMs = (System.nanoTime() - t0) / 1e6;
                    long heap = Math.max(0, BenchContext.usedHeap() - h0);
                    for (int c = 0; c < classes.length; c++) {
                        String[] qs = work[c];
                        boolean exact = classes[c].equals("exact");
                        // linear scan on a million keys is ~1 ms per query: fewer queries keep the run short
                        int nq = impl.name().equals("linear") && k >= 100_000 ? Math.max(20, qs.length / 20) : qs.length;
                        OpStats st = new OpStats();
                        long results = 0;
                        for (int i = 0; i < nq; i++) {
                            results += exact ? idx.get(qs[i]).size() : idx.valuesWithPrefix(qs[i], Integer.MAX_VALUE, st).size();
                        }
                        BenchContext.Stats t = ctx.time(nq, () -> {
                            long s = 0;
                            for (int i = 0; i < nq; i++) {
                                s += exact ? idx.get(qs[i]).size() : idx.valuesWithPrefix(qs[i], Integer.MAX_VALUE, null).size();
                            }
                            BenchContext.sink += s;
                        });
                        // get() takes no OpStats: operation counts only exist for prefix queries
                        double nodesPerQ = exact ? Double.NaN : st.nodesVisited / (double) nq;
                        double cmpsPerQ = exact ? Double.NaN : st.charComparisons / (double) nq;
                        csv.row(impl.name(), k, classes[c], t.medianNs(), t.minNs(), t.maxNs(), results / (double) nq,
                                nodesPerQ, cmpsPerQ, buildMs, idx.nodeCount(), heap / (double) k);
                        if (classes[c].equals("prefix-4") || classes[c].equals("miss")) {
                            md.add(new Object[]{k, impl.name(), classes[c], t.medianNs() / 1000, st.nodesVisited / (double) nq,
                                    st.charComparisons / (double) nq, idx.nodeCount(), heap / (double) k});
                        }
                        ctx.log("  %-6s K=%-8d %-9s %10.0f ns/query", impl.name(), k, classes[c], t.medianNs());
                    }
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("Prefix queries return *all* matches (as the search index uses them). Keys: pseudo-words. "
                + "Expected: trie/radix cost depends on prefix length and output size, not on K; linear scan grows with K. "
                + "Counters: the trie counts nodes (child lookup is a binary search over a sorted char array); the radix trie "
                + "counts nodes and edge-label characters compared; the linear scan counts characters compared.\n");
        ctx.table(new String[]{"keys K", "structure", "query", "µs/query (median)", "nodes visited/query",
                "char cmps/query", "nodes", "heap bytes/key"}, md);
    }

    /** Unrecorded pass over every structure so the first measured configuration is not penalised by JIT compilation. */
    private static void prime(BenchContext ctx) {
        String[] keys = SyntheticLibrary.vocabulary(5_000, ctx.seed);
        Random rnd = new Random(ctx.seed);
        String[] qs = sample(keys, rnd, 500, 2);
        for (Impl impl : IMPLS) {
            PrefixIndex<Integer> idx = impl.make().get();
            for (int i = 0; i < keys.length; i++) idx.insert(keys[i], i);
            ctx.time(qs.length, () -> {
                long s = 0;
                for (String q : qs) s += idx.valuesWithPrefix(q, Integer.MAX_VALUE, null).size() + idx.get(q).size();
                BenchContext.sink += s;
            });
        }
    }

    /** Prefixes of length {@code len} (whole key if shorter / MAX_VALUE) of uniformly chosen keys. */
    private static String[] sample(String[] keys, Random rnd, int n, int len) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            String k = keys[rnd.nextInt(keys.length)];
            out[i] = k.substring(0, Math.min(len, k.length()));
        }
        return out;
    }

    /** Strings that cannot be keys (digits never occur in the vocabulary). */
    private static String[] misses(Random rnd, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "q" + rnd.nextInt(10) + "x" + (char) ('a' + rnd.nextInt(26));
        return out;
    }
}
