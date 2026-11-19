package bloombox.core.prob;

import bloombox.core.text.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TrigramFilterTest {

    private static List<String> lines(String... raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) out.add(Tokenizer.normalizeForMatch(r));
        return out;
    }

    @Test
    void everySubstringOfEveryLinePassesTheScreen() {
        List<String> ls = lines("How does it feel", "to treat me like you do?", "Blue Monday");
        TrigramFilter f = TrigramFilter.build(ls, 0.01);
        for (String line : ls)
            for (int i = 0; i < line.length(); i++)
                for (int j = i + 1; j <= line.length(); j++)
                    assertTrue(f.mightContain(line.substring(i, j), null), line.substring(i, j));
    }

    @Test
    void rejectsQueriesWithAnAbsentTrigram() {
        TrigramFilter f = TrigramFilter.build(lines("how does it feel"), 0.0001);
        assertFalse(f.mightContain("zebra", null));
        assertFalse(f.mightContain("feel how", null), "words exist but this sequence does not");
    }

    @Test
    void shortQueriesCannotBeScreened() {
        TrigramFilter f = TrigramFilter.build(lines("abc"), 0.01);
        assertTrue(f.mightContain("zz", null));
        assertTrue(f.mightContain("", null));
    }

    @Test
    void trackWithoutLyricsRejectsEveryScreenableQuery() {
        TrigramFilter f = TrigramFilter.build(List.of(), 0.01);
        assertEquals(0, f.distinctTrigrams());
        assertFalse(f.mightContain("love", null));
    }

    @Test
    void phrasesSpanningTwoLinesAreNotFalselyPromised() {
        // "feel to" crosses a line boundary, so trigram "l t" never occurs.
        TrigramFilter f = TrigramFilter.build(lines("how does it feel", "to treat me"), 0.0001);
        assertFalse(f.mightContain("feel to", null));
    }

    @Test
    void randomisedNoFalseNegativesWithUnicode() {
        Random rnd = new Random(9);
        String alphabet = "abc夜に駆けるदिल ";
        for (int t = 0; t < 200; t++) {
            List<String> ls = new ArrayList<>();
            for (int l = 0; l < 5; l++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < 30; c++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                ls.add(sb.toString());
            }
            TrigramFilter f = TrigramFilter.build(ls, 0.05);
            String src = ls.get(rnd.nextInt(ls.size()));
            int i = rnd.nextInt(src.length() - 5);
            assertTrue(f.mightContain(src.substring(i, i + 5), null));
        }
    }
}
