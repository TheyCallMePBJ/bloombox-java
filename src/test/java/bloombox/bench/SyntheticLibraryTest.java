package bloombox.bench;

import bloombox.library.Library;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticLibraryTest {

    @Test
    void sameSeedSameLibrary() {
        Library a = SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(300, 7));
        Library b = SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(300, 7));
        assertEquals(a.tracks(), b.tracks());
        for (int i = 0; i < a.size(); i++) assertEquals(a.lyrics(i), b.lyrics(i));
        Library c = SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(300, 8));
        assertNotEquals(a.tracks(), c.tracks(), "different seed, different data");
    }

    @Test
    void sizesAndLyricsFractionAsConfigured() {
        Library lib = SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(2_000, 1));
        assertEquals(2_000, lib.size());
        long withLyrics = lib.tracks().stream().filter(t -> !lib.lyrics(t.id()).isEmpty()).count();
        assertEquals(0.6, withLyrics / 2000.0, 0.05);
        for (int i = 0; i < lib.size(); i++) {
            var ly = lib.lyrics(i);
            if (ly.isEmpty()) continue;
            assertTrue(ly.synced());
            assertTrue(ly.lines().size() >= 20 && ly.lines().size() <= 40);
        }
        assertEquals(0, SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(0, 1)).size(), "empty library allowed");
    }

    @Test
    void vocabularyIsDistinctShortestFirst() {
        String[] v = SyntheticLibrary.vocabulary(50_000, 3);
        assertEquals(50_000, new HashSet<>(Arrays.asList(v)).size());
        for (int i = 1; i < v.length; i++) assertTrue(v[i - 1].length() <= v[i].length());
        for (String w : v) assertTrue(w.matches("[a-z]+"), w);
    }

    @Test
    void zipfFavoursLowRanks() {
        SyntheticLibrary.Zipf z = new SyntheticLibrary.Zipf(1_000, 1.07);
        int[] counts = new int[1_000];
        Random r = new Random(5);
        for (int i = 0; i < 200_000; i++) counts[z.sample(r)]++;
        assertTrue(counts[0] > counts[1] && counts[1] > counts[9] && counts[9] > counts[99]);
        // rank 1 vs rank 2 frequency ratio ≈ 2^1.07 ≈ 2.1
        assertEquals(Math.pow(2, 1.07), counts[0] / (double) counts[1], 0.15);
    }

    @Test
    void invalidConfigsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Config(-1, 0.5, 1, 2, 1, 2, 100, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Config(10, 1.5, 1, 2, 1, 2, 100, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Config(10, 0.5, 5, 2, 1, 2, 100, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Config(10, 0.5, 1, 2, 1, 2, 5, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Config(10, 0.5, 1, 2, 1, 2, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticLibrary.Zipf(0, 1));
    }
}
