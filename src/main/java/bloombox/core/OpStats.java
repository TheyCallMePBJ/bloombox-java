package bloombox.core;

/**
 * Work counters filled in by the core algorithms. Used for the in-app
 * "Algorithm" panel and by the benchmarks to compare against baselines.
 *
 * <p>Not thread-safe: use one instance per query. Every algorithm accepts
 * {@code null} to mean "don't count".
 */
public final class OpStats {
    /** Trie / radix-trie nodes touched (descent + subtree collection). */
    public long nodesVisited;
    /** Character comparisons (KMP, radix edge labels, linear scans). */
    public long charComparisons;
    /** Bloom-filter membership probes (one per {@code mightContain} call). */
    public long bloomProbes;

    public void reset() {
        nodesVisited = charComparisons = bloomProbes = 0;
    }

    public void add(OpStats other) {
        if (other == null) return;
        nodesVisited += other.nodesVisited;
        charComparisons += other.charComparisons;
        bloomProbes += other.bloomProbes;
    }

    @Override
    public String toString() {
        return "nodes=" + nodesVisited + " cmps=" + charComparisons + " probes=" + bloomProbes;
    }
}
