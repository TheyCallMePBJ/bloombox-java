package bloombox.library;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LrcParserTest {

    @Test
    void parsesBasicSyncedLines() {
        Lyrics l = LrcParser.parse("""
                [00:01.00]Hello
                [00:02.50]World
                """);
        assertTrue(l.synced());
        assertEquals(List.of(new LyricLine(1000, "Hello"), new LyricLine(2500, "World")), l.lines());
    }

    @Test
    void supportsAllFractionPrecisions() {
        Lyrics l = LrcParser.parse("""
                [00:01]a
                [00:01.5]b
                [00:01.45]c
                [00:01.456]d
                [00:01:45]e
                """);
        assertEquals(List.of(1000L, 1450L, 1450L, 1456L, 1500L),
                l.lines().stream().map(LyricLine::timeMs).toList());
    }

    @Test
    void expandsMultipleTimestampsAndSortsByTime() {
        Lyrics l = LrcParser.parse("""
                [00:10.00][00:30.00]Chorus
                [00:20.00]Verse
                """);
        assertEquals(List.of(
                new LyricLine(10_000, "Chorus"),
                new LyricLine(20_000, "Verse"),
                new LyricLine(30_000, "Chorus")), l.lines());
    }

    @Test
    void readsMetadataAndAppliesPositiveOffset() {
        Lyrics l = LrcParser.parse("""
                [ar:Daft Punk]
                [ti:Digital Love]
                [offset:+500]
                [00:01.00]line
                [00:00.20]early
                """);
        assertEquals("Daft Punk", l.metadata().get("ar"));
        assertEquals("Digital Love", l.metadata().get("ti"));
        // 1000 - 500 = 500; 200 - 500 clamps at 0
        assertEquals(List.of(new LyricLine(0, "early"), new LyricLine(500, "line")), l.lines());
    }

    @Test
    void negativeOffsetDelaysLyrics() {
        Lyrics l = LrcParser.parse("[offset:-250]\n[00:01.00]x");
        assertEquals(1250, l.lines().get(0).timeMs());
    }

    @Test
    void stripsEnhancedWordTags() {
        Lyrics l = LrcParser.parse("[00:05.00]<00:05.00>one <00:05.40>two  <00:05.90>three");
        assertEquals("one two three", l.lines().get(0).text());
    }

    @Test
    void keepsEmptyTimedLinesForInstrumentalBreaks() {
        Lyrics l = LrcParser.parse("[00:01.00]sing\n[00:05.00]\n[00:09.00]again");
        assertEquals(3, l.lines().size());
        assertEquals("", l.lines().get(1).text());
    }

    @Test
    void plainTextFileIsUnsynced() {
        Lyrics l = LrcParser.parse("first line\nsecond line\n");
        assertFalse(l.synced());
        assertEquals(2, l.lines().size());
        assertFalse(l.lines().get(0).isSynced());
        assertEquals(-1, l.indexAt(5000));
    }

    @Test
    void handlesBomCrlfAndBlankInput() {
        Lyrics l = LrcParser.parse("﻿[00:01.00]a\r\n[00:02.00]b\r\n");
        assertEquals(2, l.lines().size());
        assertTrue(LrcParser.parse("").isEmpty());
        assertTrue(LrcParser.parse("   \n\n").isEmpty());
        assertTrue(LrcParser.parse((String) null).isEmpty());
    }

    @Test
    void skipsMalformedTimestamps() {
        // seconds >= 60 is invalid; the line keeps the valid tag only
        Lyrics l = LrcParser.parse("[00:75.00][00:03.00]ok\n[xx:yy]junk tag line");
        assertEquals(List.of(new LyricLine(3000, "ok")), l.lines());
    }

    @Test
    void decodesUtf8AndFallsBackToLatin1() {
        String unicode = "[00:01.00]Café — 夜に駆ける";
        assertEquals(unicode, LrcParser.decode(unicode.getBytes(StandardCharsets.UTF_8)));
        byte[] latin1 = "[00:01.00]Café".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("[00:01.00]Café", LrcParser.decode(latin1));
    }

    @Test
    void indexAtFindsActiveLineByBinarySearch() {
        Lyrics l = LrcParser.parse("[00:01.00]a\n[00:03.00]b\n[00:05.00]c");
        assertEquals(-1, l.indexAt(0));     // before first line
        assertEquals(0, l.indexAt(1000));   // exactly on boundary
        assertEquals(0, l.indexAt(2999));
        assertEquals(1, l.indexAt(3000));
        assertEquals(2, l.indexAt(999_999)); // past the end
    }
}
