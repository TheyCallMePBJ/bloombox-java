package bloombox.index;

import bloombox.core.trie.Trie;
import bloombox.library.Library;
import bloombox.library.LrcParser;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {

    static SearchIndex idx;

    static Track t(String artist, String album, String title) {
        return new Track(-1, Path.of("/m", artist, album, title + ".flac"), title, artist, album, 0, 0, 200_000, null);
    }

    @BeforeAll
    static void build() {
        List<Track> tracks = List.of(
                t("New Order", "Power, Corruption & Lies", "Blue Monday"),
                t("Daft Punk", "Discovery", "Digital Love"),
                t("Daft Punk", "Discovery", "Something About Us"),
                t("Radiohead", "In Rainbows", "Reckoner"),
                t("Beyoncé", "Lemonade", "Formation"),
                t("Kenshi Yonezu", "Stray Sheep", "Lemon"),
                t("YOASOBI", "The Book", "夜に駆ける"));
        List<Lyrics> lyrics = List.of(
                LrcParser.parse("[00:10.00]How does it feel\n[00:15.00]To treat me like you do?"),
                LrcParser.parse("[00:20.00]Last night I had a dream about you\n[00:25.00]In this dream I'm dancing right beside you"),
                LrcParser.parse("[00:30.00]It might not be the right time\n[00:35.00]Something about us"),
                LrcParser.parse("Reckoner, you can't take it with you"), // unsynced
                Lyrics.EMPTY,
                Lyrics.EMPTY,
                LrcParser.parse("[00:05.00]沈むように溶けてゆくように"));
        idx = SearchIndex.build(Library.of(Path.of("/m"), new ArrayList<>(tracks), new ArrayList<>(lyrics)));
    }

    static List<String> titles(String q) {
        return idx.search(q).results().stream().map(r -> r.track().title()).toList();
    }

    @Test
    void prefixMatchesTitleArtistAndAlbum() {
        assertEquals(List.of("Blue Monday"), titles("blue mon"));
        assertEquals(List.of("Digital Love", "Something About Us"), titles("daft"));
        assertEquals(List.of("Reckoner"), titles("rainb"));
        assertEquals(List.of("Formation"), titles("beyonce"));
    }

    @Test
    void termsAreAndedAcrossFields() {
        assertEquals(List.of("Digital Love"), titles("daft digi"));
        assertEquals(List.of(), titles("daft reckoner"));
    }

    @Test
    void fieldRestrictionAndTitleOutranksLyrics() {
        // "lemon" is a title (Lemon) and an album prefix (Lemonade).
        assertEquals(List.of("Lemon", "Formation"), titles("lemon"));
        assertEquals(List.of("Formation"), titles("album:lemon"));
        // "something": title of one track and a lyric of the same track; "about" also in lyrics of another
        assertEquals("Something About Us", titles("about").get(0));
        assertEquals(List.of("Digital Love"), titles("l:dream"));
    }

    @Test
    void lyricWordHitReportsLineAndTimestamp() {
        SearchResult r = idx.search("l:treat").results().get(0);
        assertEquals("Blue Monday", r.track().title());
        assertEquals(1, r.lyricLine());
        assertEquals(15_000, r.lyricTimeMs());
        assertTrue(r.matched(Field.LYRICS));
    }

    @Test
    void phraseSearchUsesBloomThenKmp() {
        SearchIndex.Outcome o = idx.search("\"does it feel to\""); // spans two lines → no match
        assertTrue(o.results().isEmpty());
        o = idx.search("\"how does it feel\"");
        assertEquals(List.of("Blue Monday"), o.results().stream().map(r -> r.track().title()).toList());
        assertEquals(10_000, o.results().get(0).lyricTimeMs());
        SearchReport rep = o.report();
        assertEquals(5, rep.lyricTracks());
        assertEquals(1, rep.kmpConfirmed());
        assertTrue(rep.bloomPassed() >= 1 && rep.bloomPassed() <= rep.lyricTracks());
        assertEquals(rep.bloomPassed() - rep.kmpConfirmed(), rep.falsePositives());
    }

    @Test
    void unsyncedLyricsHaveNoTimestampAndShortPhraseIsUnscreenable() {
        SearchResult r = idx.search("\"take it with\"").results().get(0);
        assertEquals("Reckoner", r.track().title());
        assertEquals(-1, r.lyricTimeMs());
        SearchReport rep = idx.search("l:\"us\"").report();
        assertFalse(rep.phraseScreenable());
        assertEquals(rep.lyricTracks(), rep.bloomPassed());
    }

    @Test
    void unicodeTitlesAndLyrics() {
        assertEquals(List.of("夜に駆ける"), titles("夜に"));
        assertEquals(List.of("夜に駆ける"), titles("\"溶けてゆく\""));
    }

    @Test
    void emptyAndNoMatchQueries() {
        assertTrue(idx.search("").results().isEmpty());
        assertTrue(idx.search("   !!! ").results().isEmpty());
        assertTrue(idx.search("zzzzqqq").results().isEmpty());
        assertEquals(0, idx.search("zzzzqqq").report().results());
    }

    @Test
    void limitAndAutocomplete() {
        assertEquals(1, idx.search(Query.parse("daft"), new SearchIndex.Options(1, true)).results().size());
        assertEquals(List.of("daft"), idx.suggest(Field.ARTIST, "Da", 5));
        assertEquals(List.of("dancing", "do", "does", "dream"), idx.suggest(Field.LYRICS, "d", 10));
    }

    /** Bloom screening must never change results, only the work done. */
    @Test
    void bloomAndBaselineReturnIdenticalResultsOnRandomLibrary() {
        Random rnd = new Random(21);
        String[] vocab = "love night dream feel blue light heart fire run away home time stay go".split(" ");
        List<Track> tracks = new ArrayList<>();
        List<Lyrics> lyrics = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            tracks.add(t("artist" + (i % 37), "album" + (i % 53), "song " + i));
            StringBuilder lrc = new StringBuilder();
            for (int l = 0; l < 8; l++) {
                lrc.append(String.format("[00:%02d.00]", l * 5));
                for (int w = 0; w < 5; w++) lrc.append(vocab[rnd.nextInt(vocab.length)]).append(' ');
                lrc.append('\n');
            }
            lyrics.add(LrcParser.parse(lrc.toString()));
        }
        Library lib = Library.of(Path.of("/r"), tracks, lyrics);
        SearchIndex radix = SearchIndex.build(lib);
        SearchIndex plain = SearchIndex.build(lib, Trie::new, 0.05);
        for (int q = 0; q < 100; q++) {
            String phrase = vocab[rnd.nextInt(vocab.length)] + " " + vocab[rnd.nextInt(vocab.length)];
            Query query = Query.parse("\"" + phrase + "\"");
            var withBloom = radix.search(query, new SearchIndex.Options(10_000, true));
            var baseline = radix.search(query, new SearchIndex.Options(10_000, false));
            var plainTrie = plain.search(query, new SearchIndex.Options(10_000, true));
            assertEquals(ids(baseline), ids(withBloom), phrase);
            assertEquals(ids(baseline), ids(plainTrie), phrase);
            assertTrue(withBloom.report().bloomPassed() <= baseline.report().bloomPassed());
        }
    }

    private static List<Integer> ids(SearchIndex.Outcome o) {
        return o.results().stream().map(r -> r.track().id()).sorted().toList();
    }
}
