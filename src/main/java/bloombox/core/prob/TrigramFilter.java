package bloombox.core.prob;

import bloombox.core.OpStats;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-track screening filter for substring search over lyrics.
 *
 * <p>Every character trigram of every (normalised) lyric line is inserted into a
 * {@link BloomFilter}. If a query string Q occurs inside some line, then every
 * trigram of Q is also a trigram of that line, so it must be in the filter.
 * Contrapositive: if <em>any</em> trigram of Q is reported absent, the track
 * <em>definitely</em> does not contain Q and can be skipped without reading its
 * lyrics. A positive answer is only "possibly present" and is confirmed by KMP.
 *
 * <p>Queries shorter than 3 characters have no trigrams and cannot be screened;
 * {@link #mightContain} conservatively returns true for them.
 */
public final class TrigramFilter {

    public static final double DEFAULT_FPP = 0.01;

    private final BloomFilter bloom;
    private final int distinctTrigrams;

    private TrigramFilter(BloomFilter bloom, int distinctTrigrams) {
        this.bloom = bloom;
        this.distinctTrigrams = distinctTrigrams;
    }

    /**
     * @param normalizedLines lyric lines already passed through
     *                        {@link bloombox.core.text.Tokenizer#normalizeForMatch}
     * @param fpp             target false-positive rate per probe
     */
    public static TrigramFilter build(Collection<String> normalizedLines, double fpp) {
        Set<Long> grams = new HashSet<>();
        for (String line : normalizedLines) {
            for (int i = 0; i + 3 <= line.length(); i++) {
                grams.add(pack(line.charAt(i), line.charAt(i + 1), line.charAt(i + 2)));
            }
        }
        BloomFilter b = BloomFilter.optimal(grams.size(), fpp);
        for (long g : grams) b.add(g);
        return new TrigramFilter(b, grams.size());
    }

    /**
     * False ⇒ the query certainly does not occur in any line of this track.
     * True ⇒ it possibly does (verify exactly).
     */
    public boolean mightContain(String normalizedQuery, OpStats stats) {
        if (normalizedQuery.length() < 3) return true;
        for (int i = 0; i + 3 <= normalizedQuery.length(); i++) {
            long g = pack(normalizedQuery.charAt(i), normalizedQuery.charAt(i + 1), normalizedQuery.charAt(i + 2));
            if (!bloom.mightContain(g, stats)) return false;
        }
        return true;
    }

    /** Three UTF-16 code units packed into the low 48 bits of a long. */
    static long pack(char a, char b, char c) {
        return ((long) a << 32) | ((long) b << 16) | c;
    }

    public BloomFilter bloom() {
        return bloom;
    }

    public int distinctTrigrams() {
        return distinctTrigrams;
    }
}
