package bloombox.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {

    private static Query.Term w(Field f, String t) { return new Query.Term(f, t, false); }
    private static Query.Term p(Field f, String t) { return new Query.Term(f, t, true); }

    @Test
    void plainWordsAreNormalisedTokens() {
        assertEquals(List.of(w(null, "blue"), w(null, "monday")), Query.parse("  Blue   MONDAY ").terms());
        assertEquals(List.of(w(null, "beyonce")), Query.parse("Beyoncé").terms());
        assertEquals(List.of(w(null, "blink"), w(null, "182")), Query.parse("blink-182").terms());
    }

    @Test
    void fieldPrefixesAndAliases() {
        assertEquals(List.of(w(Field.ARTIST, "daft"), w(Field.TITLE, "digi"), w(Field.ALBUM, "disc"), w(Field.LYRICS, "love")),
                Query.parse("artist:daft t:digi al:disc l:love").terms());
    }

    @Test
    void quotedPhrasesWithAndWithoutField() {
        assertEquals(List.of(p(null, "how does it feel"), w(null, "order")),
                Query.parse("\"How does it FEEL?\" order").terms());
        assertEquals(List.of(p(Field.ARTIST, "daft punk")), Query.parse("a:\"Daft Punk\"").terms());
    }

    @Test
    void unclosedQuoteRunsToEnd() {
        assertEquals(List.of(p(null, "how does it")), Query.parse("\"how does it").terms());
    }

    @Test
    void unknownOrEmptyFieldPrefixFallsBackToWords() {
        assertEquals(List.of(w(null, "foo"), w(null, "bar")), Query.parse("foo:bar").terms());
        assertEquals(List.of(w(null, "artist")), Query.parse("artist: ").terms());
    }

    @Test
    void emptyPunctuationAndOverlongInput() {
        assertTrue(Query.parse(null).isEmpty());
        assertTrue(Query.parse("").isEmpty());
        assertTrue(Query.parse("!!! ... \"\"").isEmpty());
        Query q = Query.parse("a".repeat(10_000));
        assertEquals(Query.MAX_LENGTH, q.terms().get(0).text().length());
    }
}
