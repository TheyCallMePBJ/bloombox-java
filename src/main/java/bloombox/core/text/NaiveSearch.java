package bloombox.core.text;

import bloombox.core.OpStats;

/**
 * Brute-force substring search: try every shift, compare left to right until a mismatch.
 * Worst case O(n·m) comparisons (e.g. text "aaaa…a", pattern "aa…ab"), against KMP's
 * O(n + m). Kept as the baseline for {@link Kmp}; counts comparisons the same way.
 */
public final class NaiveSearch {
    private NaiveSearch() {}

    /** First occurrence of {@code pattern} in {@code text}, or -1. */
    public static int indexOf(String text, String pattern, OpStats stats) {
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("pattern must be non-empty");
        int n = text.length(), m = pattern.length();
        long cmps = 0;
        try {
            for (int s = 0; s + m <= n; s++) {
                int j = 0;
                while (j < m) {
                    cmps++;
                    if (text.charAt(s + j) != pattern.charAt(j)) break;
                    j++;
                }
                if (j == m) return s;
            }
            return -1;
        } finally {
            if (stats != null) stats.charComparisons += cmps;
        }
    }
}
