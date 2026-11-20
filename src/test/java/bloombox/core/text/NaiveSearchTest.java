package bloombox.core.text;

import bloombox.core.OpStats;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NaiveSearchTest {

    @Test
    void findsFirstOccurrence() {
        assertEquals(2, NaiveSearch.indexOf("abcabc", "cab", null));
        assertEquals(0, NaiveSearch.indexOf("abc", "abc", null));
        assertEquals(-1, NaiveSearch.indexOf("abc", "abd", null));
    }

    @Test
    void edgeCases() {
        assertEquals(-1, NaiveSearch.indexOf("", "a", null), "empty text");
        assertEquals(-1, NaiveSearch.indexOf("ab", "abc", null), "pattern longer than text");
        assertThrows(IllegalArgumentException.class, () -> NaiveSearch.indexOf("abc", "", null));
        assertThrows(IllegalArgumentException.class, () -> NaiveSearch.indexOf("abc", null, null));
    }

    @Test
    void worstCaseIsQuadraticWhileKmpIsLinear() {
        String text = "a".repeat(1_000), pat = "a".repeat(9) + "b";
        OpStats naive = new OpStats(), kmp = new OpStats();
        assertEquals(-1, NaiveSearch.indexOf(text, pat, naive));
        assertEquals(-1, Kmp.compile(pat).indexIn(text, 0, kmp));
        assertEquals(991L * 10, naive.charComparisons, "(n−m+1)·m comparisons");
        assertTrue(kmp.charComparisons <= 2L * text.length());
    }

    @Test
    void agreesWithStringIndexOfOnRandomInputs() {
        Random r = new Random(9);
        for (int t = 0; t < 2_000; t++) {
            String text = randomAb(r, r.nextInt(40)), pat = randomAb(r, 1 + r.nextInt(5));
            assertEquals(text.indexOf(pat), NaiveSearch.indexOf(text, pat, null), text + " / " + pat);
            assertEquals(text.indexOf(pat), Kmp.compile(pat).indexIn(text), "KMP agrees too");
        }
    }

    @Test
    void countsComparisonsEvenOnSuccess() {
        OpStats st = new OpStats();
        NaiveSearch.indexOf("xxab", "ab", st);
        assertEquals(4, st.charComparisons, "x≠a, x≠a, a=a, b=b");
    }

    private static String randomAb(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append((char) ('a' + r.nextInt(2)));
        return sb.toString();
    }
}
