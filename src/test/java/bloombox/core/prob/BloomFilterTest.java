package bloombox.core.prob;

import bloombox.core.OpStats;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    void neverReturnsFalseNegatives() {
        BloomFilter b = BloomFilter.optimal(50_000, 0.01);
        Random rnd = new Random(1);
        long[] keys = new long[50_000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = rnd.nextLong();
            b.add(keys[i]);
        }
        for (long k : keys) assertTrue(b.mightContain(k));
    }

    @Test
    void observedFalsePositiveRateIsCloseToTheory() {
        int n = 20_000;
        double target = 0.01;
        BloomFilter b = BloomFilter.optimal(n, target);
        Random rnd = new Random(2);
        Set<Long> inserted = new HashSet<>();
        while (inserted.size() < n) {
            long k = rnd.nextLong();
            if (inserted.add(k)) b.add(k);
        }
        int trials = 200_000, fp = 0;
        for (int i = 0; i < trials; i++) {
            long q = rnd.nextLong();
            if (!inserted.contains(q) && b.mightContain(q)) fp++;
        }
        double observed = (double) fp / trials;
        double theory = b.expectedFpp();
        assertEquals(target, theory, 0.002, "sizing formula should hit the target");
        assertTrue(observed < theory * 1.5 && observed > theory * 0.5,
                "observed " + observed + " vs theoretical " + theory);
    }

    @Test
    void optimalSizingFollowsFormulas() {
        BloomFilter b = BloomFilter.optimal(1000, 0.01);
        // m = -n ln p / (ln2)^2 ≈ 9586 bits, k = m/n ln2 ≈ 6.64 → 7
        assertEquals(9586, b.bitSize(), 1);
        assertEquals(7, b.hashCount());
        BloomFilter tighter = BloomFilter.optimal(1000, 0.001);
        assertTrue(tighter.bitSize() > b.bitSize());
        assertTrue(tighter.hashCount() > b.hashCount());
    }

    @Test
    void emptyFilterContainsNothing() {
        BloomFilter b = BloomFilter.optimal(0, 0.01);
        assertEquals(64, b.bitSize());
        assertFalse(b.mightContain(123L));
        assertFalse(b.mightContain("anything"));
        assertEquals(0.0, b.fillRatio());
        assertEquals(0.0, b.expectedFpp());
    }

    @Test
    void stringKeysAndTinyFilterBoundary() {
        BloomFilter b = new BloomFilter(1, 1); // every add sets the only bit
        assertFalse(b.mightContain("x"));
        b.add("x");
        assertTrue(b.mightContain("x"));
        assertTrue(b.mightContain("y"), "1-bit filter is all false positives once set");
        assertEquals(1.0, b.fillRatio());

        BloomFilter words = BloomFilter.optimal(10, 0.001);
        for (String w : List.of("blue", "monday", "digital", "love")) words.add(w);
        for (String w : List.of("blue", "monday", "digital", "love")) assertTrue(words.mightContain(w));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 31));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(-1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, 1));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimal(10, Double.NaN));
    }

    @Test
    void fppGrowsWhenOverfilledAndProbesAreCounted() {
        BloomFilter b = BloomFilter.optimal(100, 0.01);
        Random rnd = new Random(5);
        for (int i = 0; i < 100; i++) b.add(rnd.nextLong());
        double atCapacity = b.expectedFpp();
        for (int i = 0; i < 900; i++) b.add(rnd.nextLong());
        assertTrue(b.expectedFpp() > 10 * atCapacity);
        OpStats s = new OpStats();
        b.mightContain(1L, s);
        b.mightContain(2L, s);
        assertEquals(2, s.bloomProbes);
    }
}
