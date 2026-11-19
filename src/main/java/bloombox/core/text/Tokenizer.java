package bloombox.core.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text normalisation shared by indexing and querying. Both sides MUST go through
 * the same functions, otherwise the tries and Bloom filters would disagree with
 * the query and produce false negatives.
 *
 * <ul>
 *   <li>Lower-cases with {@link Locale#ROOT}.</li>
 *   <li>Strips accents from Latin / Greek / Cyrillic letters only ("Beyoncé" → "beyonce").
 *       Combining marks in other scripts carry meaning (Devanagari vowel signs, Japanese
 *       dakuten) and are kept.</li>
 *   <li>Deletes apostrophes so "don't" and "dont" are the same word.</li>
 *   <li>Treats every other non-letter/digit as a separator.</li>
 * </ul>
 */
public final class Tokenizer {

    private Tokenizer() {}

    /** Lower-case + selective accent folding; punctuation is preserved. */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(d.length());
        Character.UnicodeScript lastBase = null;
        for (int i = 0; i < d.length(); ) {
            int cp = d.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean mark = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            if (mark) {
                if (lastBase != null && foldsAccents(lastBase)) continue;
            } else {
                lastBase = Character.UnicodeScript.of(cp);
            }
            out.appendCodePoint(cp);
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Normalised words, in order. Empty list for null/blank input. */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        String n = normalize(s);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (isApostrophe(cp)) continue;
            if (isWordChar(cp)) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * Canonical form for substring / phrase matching: the tokens joined by single
     * spaces. "Don't  STOP me-now!" → "dont stop me now". KMP and the trigram Bloom
     * filters operate on this form.
     */
    public static String normalizeForMatch(String s) {
        return String.join(" ", tokens(s));
    }

    private static boolean isWordChar(int cp) {
        if (Character.isLetterOrDigit(cp)) return true;
        int t = Character.getType(cp);
        // keep combining marks that survived normalize() (e.g. Devanagari matras)
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
                || t == Character.ENCLOSING_MARK;
    }

    private static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’' || cp == 'ʼ' || cp == '`';
    }

    private static boolean foldsAccents(Character.UnicodeScript script) {
        return script == Character.UnicodeScript.LATIN
                || script == Character.UnicodeScript.GREEK
                || script == Character.UnicodeScript.CYRILLIC;
    }
}
