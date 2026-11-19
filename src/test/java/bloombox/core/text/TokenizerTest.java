package bloombox.core.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenizerTest {

    @Test
    void lowercasesAndFoldsLatinAccents() {
        assertEquals(List.of("beyonce", "sigur", "ros", "motorhead"),
                Tokenizer.tokens("Beyoncé, Sigur Rós & MOTÖRHEAD"));
    }

    @Test
    void apostrophesJoinWordsOtherPunctuationSplits() {
        assertEquals(List.of("dont", "stop", "me", "now"), Tokenizer.tokens("Don't stop me-now!"));
        assertEquals(List.of("dont"), Tokenizer.tokens("Don’t")); // typographic apostrophe
    }

    @Test
    void keepsMeaningfulMarksInOtherScripts() {
        // Devanagari vowel signs and Japanese voiced kana must survive.
        assertEquals(List.of("दिल", "से"), Tokenizer.tokens("दिल से"));
        assertEquals(List.of("夜に駆ける"), Tokenizer.tokens("夜に駆ける"));
        assertEquals(List.of("がんばれ"), Tokenizer.tokens("がんばれ"));
    }

    @Test
    void digitsAreWordCharacters() {
        assertEquals(List.of("blink", "182", "1979"), Tokenizer.tokens("blink-182 — 1979"));
    }

    @Test
    void emptyAndNullInput() {
        assertEquals(List.of(), Tokenizer.tokens(null));
        assertEquals(List.of(), Tokenizer.tokens(""));
        assertEquals(List.of(), Tokenizer.tokens("  ...!!  "));
        assertEquals("", Tokenizer.normalizeForMatch(null));
    }

    @Test
    void normalizeForMatchCanonicalisesWhitespaceAndPunctuation() {
        assertEquals("dont stop me now", Tokenizer.normalizeForMatch("  Don't   STOP, me — now!! "));
        // query and text normalised the same way compare equal
        assertEquals(Tokenizer.normalizeForMatch("cafe del mar"), Tokenizer.normalizeForMatch("Café  del Mar"));
    }
}
