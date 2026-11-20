package bloombox.bench;

import bloombox.core.text.Tokenizer;
import bloombox.index.Query;
import bloombox.index.SearchIndex;
import bloombox.index.SearchReport;
import bloombox.index.SearchResult;
import bloombox.library.Library;
import bloombox.library.Lyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * E2: lyric phrase search. Bloom-screened KMP (per-track trigram Bloom filter, then KMP on
 * the survivors) vs KMP on every track vs a {@code String.contains} scan (the JDK's
 * vectorised substring search) over the same normalised lines.
 *
 * <p>Expected: for absent / rare phrases the Bloom screen rejects ~all tracks after a few
 * hash probes, so cost drops from O(total lyric text) to O(tracks × probes); for common
 * phrases most tracks pass and the screen is pure overhead. All three methods must return
 * the same set of tracks (checked).
 */
final class PhraseBench implements Experiment {

    @Override
    public String id() {
        return "e2_phrase";
    }

    @Override
    public String title() {
        return "E2 · Lyric phrase search: Bloom + KMP vs KMP vs String.contains";
    }

    @Override
    public void run(BenchContext ctx) throws IOException {
        int[] sizes = ctx.quick ? new int[]{200, 800, 3_200} : new int[]{500, 2_000, 8_000, 32_000};
        int nq = ctx.quick ? 40 : 150;
        List<Object[]> md = new ArrayList<>();
        try (BenchContext.Csv csv = ctx.csv(id(), "tracks", "lyric_tracks", "method", "phrase", "ns_per_query_median",
                "ns_min", "ns_max", "tracks_scanned_per_query", "char_cmps_per_query", "bloom_probes_per_query",
                "screen_false_pass_rate", "matches_per_query", "bloom_kib", "lyrics_text_kib", "results_agree")) {
            for (int n : sizes) {
                Library lib = ctx.library(n);
                SearchIndex idx = SearchIndex.build(lib);
                String[][] lines = normalisedLines(lib);
                long textBytes = 0;
                for (String[] ls : lines) if (ls != null) for (String l : ls) textBytes += l.length();
                Random rnd = new Random(ctx.seed + n);
                String[] vocab = SyntheticLibrary.vocabulary(SyntheticLibrary.Config.defaults(n, ctx.seed).vocabulary(), ctx.seed);
                List<String> present = new ArrayList<>(), absent = new ArrayList<>();
                while (present.size() < nq) {
                    String p = presentPhrase(lib, lines, rnd);
                    if (p != null) present.add(p);
                }
                for (int tries = 0; absent.size() < nq && tries < nq * 50; tries++) {
                    String p = vocab[100 + rnd.nextInt(4_900)] + " " + vocab[100 + rnd.nextInt(4_900)];
                    if (containsScan(lines, p).isEmpty()) absent.add(p);
                }
                double bloomKib = idx.buildStats().bloomBits() / 8.0 / 1024;
                for (String kind : new String[]{"present", "absent"}) {
                    List<String> qs = kind.equals("present") ? present : absent;
                    Query[] parsed = qs.stream().map(p -> Query.parse("lyrics:\"" + p + "\"")).toArray(Query[]::new);
                    // correctness: identical track sets from all three methods
                    boolean agree = true;
                    long[] scanned = new long[2], cmps = new long[2], probes = new long[2], fp = new long[2], neg = new long[2];
                    long matches = 0;
                    for (int i = 0; i < qs.size(); i++) {
                        Set<Integer> base = containsScan(lines, qs.get(i));
                        matches += base.size();
                        for (int b = 0; b < 2; b++) {
                            SearchIndex.Outcome o = idx.search(parsed[i], new SearchIndex.Options(Integer.MAX_VALUE, b == 0));
                            SearchReport r = o.report();
                            Set<Integer> got = new TreeSet<>();
                            for (SearchResult sr : o.results()) got.add(sr.track().id());
                            agree &= got.equals(base);
                            scanned[b] += r.bloomPassed();
                            cmps[b] += r.work().charComparisons;
                            probes[b] += r.work().bloomProbes;
                            fp[b] += r.falsePositives();
                            neg[b] += r.lyricTracks() - r.kmpConfirmed();
                        }
                    }
                    String[] methods = {"bloom+kmp", "kmp", "string.contains"};
                    for (int m = 0; m < 3; m++) {
                        final int mm = m;
                        BenchContext.Stats t = ctx.time(qs.size(), () -> {
                            long s = 0;
                            for (int i = 0; i < qs.size(); i++) {
                                s += mm == 2 ? containsScan(lines, qs.get(i)).size()
                                        : idx.search(parsed[i], new SearchIndex.Options(Integer.MAX_VALUE, mm == 0)).results().size();
                            }
                            BenchContext.sink += s;
                        });
                        int lyricTracks = idx.buildStats().tracksWithLyrics();
                        double perQ = qs.size();
                        double scan = m == 2 ? lyricTracks : scanned[m] / perQ;
                        double fpr = m == 0 && neg[0] > 0 ? fp[0] / (double) neg[0] : Double.NaN;
                        csv.row(n, lyricTracks, methods[m], kind, t.medianNs(), t.minNs(), t.maxNs(), scan,
                                m == 2 ? Double.NaN : cmps[m] / perQ, m == 2 ? Double.NaN : probes[m] / perQ, fpr,
                                matches / perQ, bloomKib, textBytes / 1024.0, agree);
                        md.add(new Object[]{n, kind, methods[m], t.medianNs() / 1000, scan,
                                m == 0 ? String.format(java.util.Locale.ROOT, "%.4f", fpr) : "–", agree ? "✓" : "✗"});
                        ctx.log("  %-15s tracks=%-6d %-7s %10.0f ns/query  scanned=%.0f", methods[m], n, kind, t.medianNs(), scan);
                    }
                }
            }
        }
        ctx.markdown("## " + title() + "\n");
        ctx.markdown("Phrases: *present* = 2–3 consecutive words from a random lyric line; *absent* = two mid-frequency "
                + "words that never co-occur (verified). Tracks scanned = tracks whose lyrics are actually searched. "
                + "Screen false-pass rate = tracks the trigram filter passed but KMP rejected ÷ tracks without the phrase. It includes "
                + "Bloom hash collisions (target 1%) *and* phrases whose trigrams all occur in the track but not together, so it can "
                + "exceed the per-trigram FPR; the pure Bloom FPR is measured in E4.\n");
        ctx.table(new String[]{"tracks", "phrase", "method", "µs/query (median)", "tracks scanned/query", "screen false-pass rate", "same results"}, md);
    }

    static String[][] normalisedLines(Library lib) {
        String[][] out = new String[lib.size()][];
        for (int id = 0; id < lib.size(); id++) {
            Lyrics ly = lib.lyrics(id);
            if (ly.isEmpty()) continue;
            out[id] = new String[ly.lines().size()];
            for (int i = 0; i < out[id].length; i++) out[id][i] = Tokenizer.normalizeForMatch(ly.lines().get(i).text());
        }
        return out;
    }

    /** Baseline: tracks with a line containing the (normalised) phrase, via String.contains. */
    static Set<Integer> containsScan(String[][] lines, String phrase) {
        String p = Tokenizer.normalizeForMatch(phrase);
        Set<Integer> hits = new TreeSet<>();
        for (int id = 0; id < lines.length; id++) {
            String[] ls = lines[id];
            if (ls == null) continue;
            for (String l : ls) {
                if (l.contains(p)) {
                    hits.add(id);
                    break;
                }
            }
        }
        return hits;
    }

    private static String presentPhrase(Library lib, String[][] lines, Random rnd) {
        int id = rnd.nextInt(lib.size());
        if (lines[id] == null) return null;
        String[] words = lines[id][rnd.nextInt(lines[id].length)].split(" ");
        int len = Math.min(words.length, 2 + rnd.nextInt(2));
        int start = rnd.nextInt(words.length - len + 1);
        return String.join(" ", java.util.Arrays.copyOfRange(words, start, start + len));
    }
}
