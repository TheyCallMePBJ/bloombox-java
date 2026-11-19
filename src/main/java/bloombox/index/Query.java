package bloombox.index;

import bloombox.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed search query. Syntax (all parts are ANDed):
 * <pre>
 *   blue mon            words; each is a prefix of some word in any field
 *   artist:daft         restrict a word to a field (title/t, artist/a/ar, album/al, lyrics/l)
 *   "how does it feel"  exact phrase (substring after normalisation): lyrics via
 *                       Bloom screen + KMP, metadata via KMP
 *   l:"feel to treat"   phrase restricted to one field
 * </pre>
 * Unknown prefixes ("foo:bar") are treated as plain words. An unclosed quote runs to
 * the end of the input, so partially typed phrases still search. Input longer than
 * {@link #MAX_LENGTH} characters is truncated.
 */
public record Query(List<Term> terms) {

    public static final int MAX_LENGTH = 256;

    /**
     * @param field  null = any field
     * @param text   normalised text: a single token for words, the
     *               {@link Tokenizer#normalizeForMatch} form for phrases
     * @param phrase true for quoted phrases
     */
    public record Term(Field field, String text, boolean phrase) {}

    public Query {
        terms = List.copyOf(terms);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public static Query parse(String raw) {
        List<Term> terms = new ArrayList<>();
        if (raw == null) return new Query(terms);
        if (raw.length() > MAX_LENGTH) raw = raw.substring(0, MAX_LENGTH);
        int i = 0, n = raw.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(raw.charAt(i))) i++;
            if (i >= n) break;

            Field field = null;
            int colon = fieldPrefixEnd(raw, i);
            if (colon > 0) {
                Field f = Field.byName(raw.substring(i, colon));
                boolean hasValue = colon + 1 < n && !Character.isWhitespace(raw.charAt(colon + 1));
                if (f != null && hasValue) {
                    field = f;
                    i = colon + 1;
                }
            }

            if (raw.charAt(i) == '"') {
                int close = raw.indexOf('"', i + 1);
                int end = close < 0 ? n : close;
                String phrase = Tokenizer.normalizeForMatch(raw.substring(i + 1, end));
                if (!phrase.isEmpty()) terms.add(new Term(field, phrase, true));
                i = close < 0 ? n : close + 1;
            } else {
                int end = i;
                while (end < n && !Character.isWhitespace(raw.charAt(end)) && raw.charAt(end) != '"') end++;
                for (String tok : Tokenizer.tokens(raw.substring(i, end))) terms.add(new Term(field, tok, false));
                i = end;
            }
        }
        return new Query(terms);
    }

    /** Index of ':' if raw[i..] starts with letters followed by ':', else -1. */
    private static int fieldPrefixEnd(String raw, int i) {
        int j = i;
        while (j < raw.length() && Character.isLetter(raw.charAt(j)) && j - i < 8) j++;
        return j > i && j < raw.length() && raw.charAt(j) == ':' ? j : -1;
    }
}
