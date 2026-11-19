package bloombox.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryScannerTest {

    @TempDir
    Path dir;

    /** Junk bytes: jaudiotagger fails, so metadata comes from the file name. */
    private Path junk(String rel) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{1, 2, 3, 4});
        return p;
    }

    private static LibraryScanner scanner(TagCache cache) {
        return new LibraryScanner(new TagReader(false), cache);
    }

    @Test
    void findsSupportedFilesAttachesLyricsAndSkipsHidden() throws IOException {
        junk("Discovery/01 - Daft Punk - One More Time.flac");
        junk("Discovery/02 - Daft Punk - Aerodynamic.MP3");
        junk("Other/Song.ogg");
        junk("Other/voice.opus");
        junk("Other/cover.jpg");
        junk("Other/notes.txt");
        junk(".hidden/secret.flac");
        junk("Other/.dotfile.flac");
        Files.writeString(dir.resolve("Discovery/01 - Daft Punk - One More Time.lrc"), "[00:01.00]One more time");

        Library lib = scanner(null).scan(dir, null);
        assertEquals(4, lib.size());
        assertEquals(4, lib.report().fromFilename());
        Track omt = lib.tracks().stream().filter(t -> t.title().equals("One More Time")).findFirst().orElseThrow();
        assertEquals("Daft Punk", omt.artist());
        assertEquals("Discovery", omt.album());
        assertEquals(1, omt.trackNo());
        assertTrue(omt.hasLyrics());
        assertEquals("One more time", lib.lyrics(omt.id()).lines().get(0).text());
        assertEquals(1, lib.report().withLyrics());
        for (int i = 0; i < lib.size(); i++) assertEquals(i, lib.track(i).id());
    }

    @Test
    void plainTextLyricsFileIsUsedWhenNoLrcExists() throws IOException {
        junk("A/Artist - Plain.flac");
        Files.writeString(dir.resolve("A/Artist - Plain.txt"), "first verse line\nsecond verse line\n");
        junk("A/Artist - Both.flac");
        Files.writeString(dir.resolve("A/Artist - Both.lrc"), "[00:02.00]synced wins");
        Files.writeString(dir.resolve("A/Artist - Both.txt"), "plain loses");

        Library lib = scanner(null).scan(dir, null);
        Track plain = lib.tracks().stream().filter(t -> t.title().equals("Plain")).findFirst().orElseThrow();
        Track both = lib.tracks().stream().filter(t -> t.title().equals("Both")).findFirst().orElseThrow();
        assertFalse(lib.lyrics(plain.id()).synced());
        assertEquals(2, lib.lyrics(plain.id()).lines().size());
        assertTrue(plain.lyricsPath().toString().endsWith(".txt"));
        assertTrue(lib.lyrics(both.id()).synced());
        assertEquals("synced wins", lib.lyrics(both.id()).lines().get(0).text());
        assertEquals(2, lib.report().withLyrics());
    }

    @Test
    void symlinkToSameFileIsCountedOnce() throws IOException {
        Path real = junk("a/x.flac");
        Files.createDirectories(dir.resolve("b"));
        Files.createSymbolicLink(dir.resolve("b/x-link.flac"), real);
        Files.createSymbolicLink(dir.resolve("b/loop"), dir); // directory loop must not hang
        Library lib = scanner(null).scan(dir, null);
        assertEquals(1, lib.size());
        assertEquals(1, lib.report().symlinkDuplicates());
    }

    @Test
    void secondScanUsesCacheAndDetectsChanges() throws IOException {
        Path f = junk("Artist - Title.flac");
        TagCache cache = TagCache.emptyCache();
        assertEquals(0, scanner(cache).scan(dir, null).report().fromCache());
        assertEquals(1, scanner(cache).scan(dir, null).report().fromCache());
        Files.write(f, new byte[]{9, 9, 9, 9, 9}); // size changes → re-read
        assertEquals(0, scanner(cache).scan(dir, null).report().fromCache());
    }

    @Test
    void cacheRoundTripsThroughDiskAndSurvivesCorruption() throws IOException {
        junk("Artist - Title.flac");
        TagCache cache = TagCache.emptyCache();
        scanner(cache).scan(dir, null);
        Path file = dir.resolve("cache/tags.bin");
        cache.save(file);
        TagCache loaded = TagCache.load(file);
        assertEquals(1, loaded.size());
        assertEquals(1, scanner(loaded).scan(dir, null).report().fromCache());
        Files.write(file, new byte[]{0x42, 0x42, 0x58, 0x43, 0, 0, 0, 1, 0x7f}); // truncated
        assertEquals(0, TagCache.load(file).size());
        assertEquals(0, TagCache.load(dir.resolve("missing.bin")).size());
    }

    @Test
    void invalidRootsAreRejectedAndEmptyDirIsEmptyLibrary() throws IOException {
        LibraryScanner s = scanner(null);
        assertThrows(IllegalArgumentException.class, () -> s.scan(dir.resolve("nope"), null));
        Path file = junk("file.flac");
        assertThrows(IllegalArgumentException.class, () -> s.scan(file, null));
        assertThrows(IllegalArgumentException.class, () -> s.scan(null, null));
        Path empty = Files.createDirectories(dir.resolve("empty"));
        assertTrue(s.scan(empty, null).isEmpty());
    }

    @Test
    void reportsLikelyDuplicates() {
        Track a = new Track(-1, Path.of("/a.flac"), "Song", "Artist", "Album", 1, 0, 0, null);
        Track b = new Track(-1, Path.of("/b.mp3"), "song", "ARTIST", "Album", 1, 0, 0, null);
        Track c = new Track(-1, Path.of("/c.mp3"), "Other", "Artist", "Album", 2, 0, 0, null);
        assertEquals(1, LibraryScanner.countLikelyDuplicates(List.of(a, b, c)));
    }
}
