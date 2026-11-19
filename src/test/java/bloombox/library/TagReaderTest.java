package bloombox.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TagReaderTest {

    @Test
    void filenameWithNumberArtistAndTitle() {
        TagReader.Tags t = TagReader.fromFilename(Path.of("/music/Discovery/03 - Daft Punk - Digital Love.flac"));
        assertEquals("Digital Love", t.title());
        assertEquals("Daft Punk", t.artist());
        assertEquals("Discovery", t.album());
        assertEquals(3, t.trackNo());
        assertEquals(TagReader.Source.FILENAME, t.source());
    }

    @Test
    void filenameVariants() {
        assertEquals("Reckoner", TagReader.fromFilename(Path.of("/m/In Rainbows/07. Reckoner.mp3")).title());
        assertEquals(7, TagReader.fromFilename(Path.of("/m/x/07. Reckoner.mp3")).trackNo());
        assertEquals("Song Name", TagReader.fromFilename(Path.of("/m/x/Song_Name.ogg")).title());
        // a title that is only a number must not be eaten as a track number
        TagReader.Tags num = TagReader.fromFilename(Path.of("/m/x/1979.flac"));
        assertEquals("1979", num.title());
        assertEquals(0, num.trackNo());
    }

    @Test
    void leadingIntParsing() {
        assertEquals(3, TagReader.leadingInt("3/12"));
        assertEquals(7, TagReader.leadingInt(" 07 "));
        assertEquals(0, TagReader.leadingInt("abc"));
        assertEquals(0, TagReader.leadingInt(""));
        assertEquals(0, TagReader.leadingInt(null));
    }

    @Test
    void unreadableFileFallsBackWithoutThrowing(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("Artist - Broken.flac");
        java.nio.file.Files.write(f, new byte[]{0, 1, 2});
        TagReader.Tags t = new TagReader(false).read(f);
        assertEquals("Broken", t.title());
        assertEquals("Artist", t.artist());
        assertEquals(TagReader.Source.FILENAME, t.source());
    }

    @Test
    void missingFileFallsBackToo() {
        TagReader.Tags t = new TagReader(false).read(Path.of("/definitely/not/here/Title.mp3"));
        assertEquals("Title", t.title());
    }

    @Test
    void embeddedLyricsKeyVariants() {
        assertEquals("a", TagReader.embeddedLyrics(java.util.Map.of("lyrics", "a")));
        assertEquals("b", TagReader.embeddedLyrics(java.util.Map.of("unsyncedlyrics", "b")));
        assertEquals("c", TagReader.embeddedLyrics(java.util.Map.of("lyrics-eng", "c")));
        assertEquals("", TagReader.embeddedLyrics(java.util.Map.of("title", "x", "lyrics", "  ")));
    }
}
