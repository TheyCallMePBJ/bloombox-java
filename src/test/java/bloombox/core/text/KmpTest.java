package bloombox.core.text;

import bloombox.core.OpStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class KmpTest {

    @Test
    void failureFunctionMatchesTextbookExamples() {
        assertArrayEquals(new int[]{0, 0, 1, 2, 3, 0, 1}, Kmp.failureFunction("ababaca"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, Kmp.failureFunction("aaaa"));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Kmp.failureFunction("abcd"));
        assertArrayEquals(new int[]{0, 1, 0, 1, 2, 2, 3}, Kmp.failureFunction("aabaaab"));
    }

    @Test
    void findsFirstAndAllOccurrences() {
        Kmp k = Kmp.compile("love");
        assertEquals(4, k.indexIn("all love is love"));
        assertEquals(List.of(4, 12), k.findAll("all love is love"));
        assertEquals(12, k.indexIn("all love is love", 5, null));
    }

    @Test
    void reportsOverlappingMatches() {
        assertEquals(List.of(0, 1, 2), Kmp.compile("aaa").findAll("aaaaa"));
        assertEquals(List.of(0, 2, 4), Kmp.compile("abab").findAll("abababab"));
    }

    @Test
    void boundaryCases() {
        Kmp k = Kmp.compile("abc");
        assertEquals(-1, k.indexIn(""));                 // empty text
        assertEquals(-1, k.indexIn("ab"));               // text shorter than pattern
        assertEquals(0, k.indexIn("abc"));               // text == pattern
        assertEquals(-1, k.indexIn("abc", 1, null));     // from beyond last possible start
        assertEquals(List.of(), k.findAll("xyz"));       // no match
        assertEquals(List.of(0), Kmp.compile("x").findAll("x"));
        assertThrows(IllegalArgumentException.class, () -> Kmp.compile(""));
        assertThrows(IllegalArgumentException.class, () -> Kmp.compile(null));
    }

    @Test
    void worksOnNonLatinText() {
        assertEquals(List.of(2), Kmp.compile("駆ける").findAll("夜に駆ける"));
        assertEquals(0, Kmp.compile("दिल").indexIn("दिल से"));
    }

    @Test
    void comparisonsNeverExceedTwiceTextLength() {
        // Worst case for naive search: many near-matches.
        String text = "a".repeat(10_000) + "b";
        OpStats s = new OpStats();
        assertEquals(10_000 - 99, Kmp.compile("a".repeat(99) + "b").indexIn(text, 0, s));
        assertTrue(s.charComparisons <= 2L * text.length(), "cmps=" + s.charComparisons);
    }

    @Test
    void randomTextsAgreeWithNaiveSearch() {
        Random rnd = new Random(11);
        for (int trial = 0; trial < 2000; trial++) {
            String text = random(rnd, rnd.nextInt(60));
            String pat = random(rnd, 1 + rnd.nextInt(4));
            List<Integer> expected = new ArrayList<>();
            for (int i = text.indexOf(pat); i >= 0; i = text.indexOf(pat, i + 1)) expected.add(i);
            assertEquals(expected, Kmp.compile(pat).findAll(text), "'" + pat + "' in '" + text + "'");
        }
    }

    private static String random(Random rnd, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(3)));
        return sb.toString();
    }
}
