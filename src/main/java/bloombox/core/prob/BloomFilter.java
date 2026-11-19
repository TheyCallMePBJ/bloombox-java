package bloombox.core.prob;

import bloombox.core.OpStats;

/**
 * Bloom filter over 64-bit keys (strings are hashed to 64 bits first).
 *
 * <p>A bit array of m bits and k index functions. {@code add} sets k bits;
 * {@code mightContain} checks them. A "false" answer is always correct (no false
 * negatives); a "true" answer means only <em>possibly present</em> and must be
 * confirmed by an exact check (here: KMP over the lyric text).
 *
 * <p>Index functions use Kirsch–Mitzenmacher double hashing,
 * {@code g_i(x) = h1(x) + i·h2(x) mod m}, which keeps the asymptotic false-positive
 * rate of k independent hashes while computing only two.
 *
 * <p>After n distinct insertions the false-positive probability is
 * {@code p ≈ (1 − e^(−kn/m))^k}, minimised at {@code k = (m/n)·ln 2}; for a target p
 * the required size is {@code m = −n·ln p / (ln 2)²} (see {@link #optimal}).
 *
 * <p>Time: O(k) per operation. Space: m bits.
 */
public final class BloomFilter {

    private static final long SEED2 = 0x9E3779B97F4A7C15L;

    private final long[] words;
    private final int m;
    private final int k;
    private int inserted;

    /**
     * @param mBits number of bits (≥ 1)
     * @param k     number of index functions (1..30)
     */
    public BloomFilter(int mBits, int k) {
        if (mBits < 1) throw new IllegalArgumentException("m must be >= 1");
        if (k < 1 || k > 30) throw new IllegalArgumentException("k must be in 1..30");
        this.m = mBits;
        this.k = k;
        this.words = new long[(mBits + 63) >>> 6];
    }

    /**
     * Filter sized for {@code expectedN} distinct items at false-positive rate {@code fpp}.
     * {@code expectedN == 0} yields a minimal (64-bit) filter.
     */
    public static BloomFilter optimal(int expectedN, double fpp) {
        if (expectedN < 0) throw new IllegalArgumentException("expectedN must be >= 0");
        if (!(fpp > 0 && fpp < 1)) throw new IllegalArgumentException("fpp must be in (0,1)");
        int n = Math.max(1, expectedN);
        double ln2 = Math.log(2);
        long m = (long) Math.ceil(-n * Math.log(fpp) / (ln2 * ln2));
        m = Math.max(64, Math.min(m, Integer.MAX_VALUE - 63));
        int k = (int) Math.max(1, Math.min(30, Math.round((double) m / n * ln2)));
        return new BloomFilter((int) m, k);
    }

    // ---- core operations ------------------------------------------------------

    public void add(long key) {
        long h1 = mix64(key);
        long h2 = mix64(key ^ SEED2) | 1; // odd => all k indices differ when m is not tiny
        for (int i = 0; i < k; i++) {
            int bit = (int) Math.floorMod(h1 + i * h2, (long) m);
            words[bit >>> 6] |= 1L << bit;
        }
        inserted++;
    }

    public boolean mightContain(long key, OpStats stats) {
        if (stats != null) stats.bloomProbes++;
        long h1 = mix64(key);
        long h2 = mix64(key ^ SEED2) | 1;
        for (int i = 0; i < k; i++) {
            int bit = (int) Math.floorMod(h1 + i * h2, (long) m);
            if ((words[bit >>> 6] & (1L << bit)) == 0) return false;
        }
        return true;
    }

    public boolean mightContain(long key) {
        return mightContain(key, null);
    }

    public void add(CharSequence s) {
        add(hashString(s));
    }

    public boolean mightContain(CharSequence s) {
        return mightContain(hashString(s), null);
    }

    // ---- introspection --------------------------------------------------------

    public int bitSize() { return m; }
    public int hashCount() { return k; }
    /** Number of add() calls (counts duplicates). */
    public int insertedCount() { return inserted; }

    /** Theoretical false-positive rate for the current number of insertions. */
    public double expectedFpp() {
        return theoreticalFpp(m, k, inserted);
    }

    public static double theoreticalFpp(long m, int k, long n) {
        return Math.pow(1 - Math.exp(-(double) k * n / m), k);
    }

    /** Fraction of bits set; FPP is approximately fillRatio^k. */
    public double fillRatio() {
        long set = 0;
        for (long w : words) set += Long.bitCount(w);
        return (double) set / m;
    }

    public long memoryBytes() {
        return (long) words.length * Long.BYTES;
    }

    // ---- hashing (own implementations; no library hash used) ------------------

    /** MurmurHash3 64-bit finaliser: an invertible avalanche mix. */
    static long mix64(long z) {
        z ^= z >>> 33;
        z *= 0xFF51AFD7ED558CCDL;
        z ^= z >>> 33;
        z *= 0xC4CEB9FE1A85EC53L;
        z ^= z >>> 33;
        return z;
    }

    /** FNV-1a over UTF-16 code units, then avalanche-mixed. */
    static long hashString(CharSequence s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            h ^= (c & 0xFF);
            h *= 0x100000001B3L;
            h ^= (c >>> 8);
            h *= 0x100000001B3L;
        }
        return mix64(h ^ s.length());
    }
}
