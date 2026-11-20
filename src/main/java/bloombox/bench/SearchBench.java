package bloombox.bench;

import bloombox.core.prob.TrigramFilter;
import bloombox.core.trie.LinearPrefixIndex;
import bloombox.core.trie.PrefixIndex;
import bloombox.core.trie.RadixTrie;
import bloombox.core.trie.Trie;
import bloombox.index.SearchIndex;
import bloombox.library.Library;
import bloombox.library.Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * E3: end-to-end search-as-you-type over the whole index (title, artist, album and lyric
 * words), with the index built on RadixTrie (the app's choice), Trie, or the linear baseline.
 *
 * <p>Workload: for random title words, every keystroke prefix (1..5 chars) is a query, plus
 * two-term queries "artist-word title-prefix" (ANDed). Also reports index build time and
 * approximate heap use per structure.
 */
final class SearchBench implements Experiment {

    @Override
    public String id() {
        return "e3_search";
    }

    @Override
    public String title() {
        return "E3 · Search-as-you-type over the full index";
    }

    private record Impl(String name, Supplier<PrefixIndex<Integer>> make) {}

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{200, 800, 3_200} : new int[]{500, 2_000, 8_000, 32_000};
        List<Impl> impls = List.of(new Impl("radix", RadixTrie::new), new Impl("trie", Trie::new),
                new Impl("linear", LinearPrefixIndex::new));
        List<Object[]> md = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id(), "tracks", "structure", "query", "ns_per_query_median", "ns_min", "ns_max",
                "results_per_query", "build_ms", "heap_mib", "trie_nodes", "postings")) {
            for (int n : sizes) {
                Library lib = ctx.library(n);
                Random rnd = new Random(ctx.seed + 31L * n);
                List<String> typed = new ArrayList<>(), twoTerm = new ArrayList<>();
                int words = ctx.quick ? 30 : 60;
                for (int i = 0; i < words; i++) {
                    Track t = lib.track(rnd.nextInt(lib.size()));
                    String w = t.title().split(" ")[0].toLowerCase(java.util.Locale.ROOT);
                    for (int len = 1; len <= Math.min(5, w.length()); len++) typed.add(w.substring(0, len));
                    String a = t.artist().split(" ")[0].toLowerCase(java.util.Locale.ROOT);
                    twoTerm.add(a + " " + w.substring(0, Math.min(3, w.length())));
                }
                for (Impl impl : impls) {
                    long h0 = BenchContext.usedHeap();
                    long t0 = System.nanoTime();
                    SearchIndex idx = SearchIndex.build(lib, impl.make(), TrigramFilter.DEFAULT_FPP);
                    double buildMs = (System.nanoTime() - t0) / 1e6;
                    double heapMib = Math.max(0, BenchContext.usedHeap() - h0) / (1024.0 * 1024);
                    long nodes = idx.buildStats().trieNodes().values().stream().mapToLong(Integer::longValue).sum();
                    for (String kind : new String[]{"typed-prefix", "two-term"}) {
                        List<String> qs0 = kind.equals("typed-prefix") ? typed : twoTerm;
                        List<String> qs = impl.name().equals("linear") && n >= 8_000 ? qs0.subList(0, Math.max(10, qs0.size() / 6)) : qs0;
                        long results = 0;
                        for (String q : qs) results += idx.search(q).results().size();
                        BenchContext.Stats t = ctx.time(qs.size(), () -> {
                            long s = 0;
                            for (String q : qs) s += idx.search(q).results().size();
                            BenchContext.sink += s;
                        });
                        csv.row(n, impl.name(), kind, t.medianNs(), t.minNs(), t.maxNs(), results / (double) qs.size(), buildMs,
                                heapMib, nodes, idx.buildStats().postings());
                        if (kind.equals("typed-prefix")) {
                            md.add(new Object[]{n, impl.name(), t.medianNs() / 1000, buildMs, heapMib, nodes});
                        }
                        ctx.log("  %-6s tracks=%-6d %-12s %10.0f ns/query  build %.0f ms", impl.name(), n, kind, t.medianNs(), buildMs);
                    }
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("Each keystroke prefix (1–5 chars) of a title word is one query over all fields; results limited to 500 "
                + "(the app default). Heap = approximate retained size of the whole index (tries + Bloom filters + normalised text).\n");
        ctx.table(new String[]{"tracks", "structure", "µs/keystroke query (median)", "build ms", "index heap MiB", "trie nodes"}, md);
    }
}
