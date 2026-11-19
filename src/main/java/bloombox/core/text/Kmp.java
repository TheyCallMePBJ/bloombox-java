package bloombox.core.text;

import bloombox.core.OpStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Knuth–Morris–Pratt exact string matching.
 *
 * <p>The pattern is preprocessed once into its failure function
 * {@code fail[i]} = length of the longest proper prefix of {@code pattern[0..i]} that is
 * also a suffix of it. A compiled matcher is then reused across many lyric lines.
 *
 * <p>Complexity: O(m) time/space to compile, O(n) time per text with at most
 * 2n character comparisons, independent of the alphabet. The text pointer never
 * moves backwards, unlike the naive O(n·m) scan.
 */
public final class Kmp {

    private final String pattern;
    private final int[] fail;

    private Kmp(String pattern) {
        this.pattern = pattern;
        this.fail = failureFunction(pattern);
    }

    /** @throws IllegalArgumentException if the pattern is null or empty */
    public static Kmp compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("KMP pattern must be non-empty");
        }
        return new Kmp(pattern);
    }

    public String pattern() {
        return pattern;
    }

    /** Failure (prefix) function of {@code p}. Package-private for tests. */
    static int[] failureFunction(String p) {
        int m = p.length();
        int[] f = new int[m];
        int k = 0; // length of current matched border
        for (int i = 1; i < m; i++) {
            while (k > 0 && p.charAt(i) != p.charAt(k)) k = f[k - 1];
            if (p.charAt(i) == p.charAt(k)) k++;
            f[i] = k;
        }
        return f;
    }

    /** First occurrence at or after {@code from}, or -1. */
    public int indexIn(String text, int from, OpStats stats) {
        int n = text.length(), m = pattern.length();
        if (from < 0) from = 0;
        if (n - from < m) return -1;
        long cmps = 0;
        int q = 0; // number of pattern chars matched
        for (int i = from; i < n; i++) {
            while (q > 0 && text.charAt(i) != pattern.charAt(q)) {
                cmps++;
                q = fail[q - 1];
            }
            cmps++;
            if (text.charAt(i) == pattern.charAt(q)) q++;
            if (q == m) {
                if (stats != null) stats.charComparisons += cmps;
                return i - m + 1;
            }
        }
        if (stats != null) stats.charComparisons += cmps;
        return -1;
    }

    public int indexIn(String text) {
        return indexIn(text, 0, null);
    }

    public boolean occursIn(String text, OpStats stats) {
        return indexIn(text, 0, stats) >= 0;
    }

    /** All (possibly overlapping) occurrence start indices, ascending. */
    public List<Integer> findAll(String text, OpStats stats) {
        List<Integer> out = new ArrayList<>();
        int n = text.length(), m = pattern.length();
        long cmps = 0;
        int q = 0;
        for (int i = 0; i < n; i++) {
            while (q > 0 && text.charAt(i) != pattern.charAt(q)) {
                cmps++;
                q = fail[q - 1];
            }
            cmps++;
            if (text.charAt(i) == pattern.charAt(q)) q++;
            if (q == m) {
                out.add(i - m + 1);
                q = fail[q - 1]; // continue, allowing overlaps
            }
        }
        if (stats != null) stats.charComparisons += cmps;
        return out;
    }

    public List<Integer> findAll(String text) {
        return findAll(text, null);
    }
}
