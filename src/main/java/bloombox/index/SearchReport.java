package bloombox.index;

import bloombox.core.OpStats;

import java.util.List;

/**
 * What the algorithms did for one query: drives the in-app "Algorithm" panel and
 * the benchmark harness.
 *
 * @param query             the parsed query
 * @param work              trie nodes visited, KMP/scan char comparisons, Bloom probes
 * @param trieCandidates    postings returned by trie prefix lookups (before intersection)
 * @param lyricTracks       tracks with lyrics considered by phrase search
 * @param bloomPassed       of those, how many the trigram Bloom filter let through
 * @param kmpConfirmed      tracks where KMP found the phrase
 * @param falsePositives    Bloom passes that KMP rejected (should be ≈ fpp, see report)
 * @param phraseScreenable  false when a phrase was shorter than 3 chars (no trigrams to screen)
 * @param bloomEnabled      false in baseline mode (KMP over every track)
 * @param results           number of results after AND-intersection
 * @param elapsedNanos      total query time
 */
public record SearchReport(Query query, OpStats work, long trieCandidates, int lyricTracks,
                           int bloomPassed, int kmpConfirmed, int falsePositives,
                           boolean phraseScreenable, boolean bloomEnabled, int results,
                           long elapsedNanos) {

    public boolean hadPhrase() {
        return query.terms().stream().anyMatch(Query.Term::phrase);
    }

    /** Lines for display, e.g. in the Algorithm panel. */
    public List<String> describe() {
        List<String> out = new java.util.ArrayList<>();
        out.add(String.format("%d results in %.3f ms", results, elapsedNanos / 1e6));
        out.add(String.format("radix trie: %d nodes visited, %d postings", work.nodesVisited, trieCandidates));
        if (hadPhrase()) {
            if (!bloomEnabled) {
                out.add(String.format("phrase: KMP over all %d lyric tracks (Bloom disabled)", lyricTracks));
            } else if (!phraseScreenable) {
                out.add("phrase < 3 chars: not screenable, KMP over all " + lyricTracks + " tracks");
            } else {
                double skipped = lyricTracks == 0 ? 0 : 100.0 * (lyricTracks - bloomPassed) / lyricTracks;
                out.add(String.format("bloom: %d/%d tracks possibly match (%.1f%% skipped), %d probes",
                        bloomPassed, lyricTracks, skipped, work.bloomProbes));
                out.add(String.format("kmp: %d confirmed, %d false positives", kmpConfirmed, falsePositives));
            }
        }
        out.add(String.format("char comparisons: %d", work.charComparisons));
        return out;
    }
}
