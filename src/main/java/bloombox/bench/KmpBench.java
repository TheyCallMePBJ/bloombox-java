package bloombox.bench;

import bloombox.core.OpStats;
import bloombox.core.text.Kmp;
import bloombox.core.text.NaiveSearch;
import bloombox.library.Library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * E5: KMP vs naive substring search (and the JDK's String.indexOf for reference).
 *
 * <p>Expected: KMP makes at most 2n character comparisons on any input; naive search makes
 * up to n·m on adversarial input (text "aaa…", pattern "aa…ab") but ≈ n on natural text,
 * where mismatches come early. The JDK's indexOf uses SIMD intrinsics, so it is the fastest
 * in wall time on natural text despite being a naive algorithm — reported for honesty.
 */
final class KmpBench implements Experiment {

    @Override
    public String id() {
        return "e5_kmp";
    }

    @Override
    public String title() {
        return "E5 · Substring search: KMP vs naive (vs JDK indexOf)";
    }

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{10_000, 40_000, 160_000} : new int[]{10_000, 100_000, 1_000_000};
        int m = 64;
        String natural = naturalText(ctx, sizes[sizes.length - 1]);
        String absentNatural = "zq unmatched phrase that is not in the generated lyrics at all xx".substring(0, m);
        List<Object[]> md = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id(), "text", "n", "m", "method", "char_cmps", "cmps_per_char", "ns_per_search_median",
                "ns_min", "ns_max")) {
            for (String kind : new String[]{"adversarial", "natural"}) {
                for (int n : sizes) {
                    String text = kind.equals("adversarial") ? "a".repeat(n) : natural.substring(0, n);
                    String pat = kind.equals("adversarial") ? "a".repeat(m - 1) + "b" : absentNatural;
                    Kmp kmp = Kmp.compile(pat);
                    OpStats ks = new OpStats(), ns = new OpStats();
                    if (kmp.indexIn(text, 0, ks) != -1 || NaiveSearch.indexOf(text, pat, ns) != -1 || text.contains(pat)) {
                        throw new IllegalStateException("pattern unexpectedly present");
                    }
                    BenchContext.Stats tk = ctx.time(1, () -> BenchContext.sink += kmp.indexIn(text, 0, null));
                    BenchContext.Stats tn = ctx.time(1, () -> BenchContext.sink += NaiveSearch.indexOf(text, pat, null));
                    BenchContext.Stats tj = ctx.time(1, () -> BenchContext.sink += text.indexOf(pat));
                    csv.row(kind, n, m, "kmp", ks.charComparisons, ks.charComparisons / (double) n, tk.medianNs(), tk.minNs(), tk.maxNs());
                    csv.row(kind, n, m, "naive", ns.charComparisons, ns.charComparisons / (double) n, tn.medianNs(), tn.minNs(), tn.maxNs());
                    csv.row(kind, n, m, "jdk-indexOf", Double.NaN, Double.NaN, tj.medianNs(), tj.minNs(), tj.maxNs());
                    md.add(new Object[]{kind, n, ks.charComparisons / (double) n, ns.charComparisons / (double) n,
                            tk.medianNs() / 1000, tn.medianNs() / 1000, tj.medianNs() / 1000});
                    ctx.log("  %-11s n=%-8d kmp %.2f cmp/char %8.0f µs | naive %.2f cmp/char %8.0f µs | jdk %8.0f µs", kind, n,
                            ks.charComparisons / (double) n, tk.medianNs() / 1000, ns.charComparisons / (double) n,
                            tn.medianNs() / 1000, tj.medianNs() / 1000);
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("Pattern length m = " + m + ", always absent (full scan). Adversarial: text a^n, pattern a^(m−1)b. "
                + "Natural: concatenated synthetic lyrics.\n");
        ctx.table(new String[]{"text", "n", "KMP cmps/char", "naive cmps/char", "KMP µs", "naive µs", "JDK indexOf µs"}, md);
    }

    private static String naturalText(BenchContext ctx, int n) {
        Library lib = ctx.library(ctx.quick ? 800 : 2_000);
        StringBuilder sb = new StringBuilder(n + 100);
        String[][] lines = PhraseBench.normalisedLines(lib);
        outer:
        while (true) {
            for (String[] ls : lines) {
                if (ls == null) continue;
                for (String l : ls) {
                    sb.append(l).append(' ');
                    if (sb.length() >= n) break outer;
                }
            }
        }
        return sb.substring(0, n);
    }
}
