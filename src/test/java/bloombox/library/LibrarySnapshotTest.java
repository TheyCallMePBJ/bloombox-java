package bloombox.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LibrarySnapshotTest {

    @TempDir
    Path dir;

    private Library sample(Path root) {
        Track a = new Track(-1, root.resolve("A/01 x.flac"), "Blackbird", "Alter Bridge", "Blackbird", 8, 1, 478_000,
                root.resolve("A/01 x.lrc"));
        Track b = new Track(-1, root.resolve("B/02 y.ogg"), "日本語", "Kumo", "", 0, 0, 0, null);
        Lyrics la = new Lyrics(List.of(new LyricLine(1000, "first"), new LyricLine(2500, "")), true, Map.of("ar", "Alter Bridge"));
        return Library.of(root, List.of(a, b), List.of(la, Lyrics.EMPTY))
                .withReport(new ScanReport(2, 0, 1, 1, 0, 0, 1, 0, 0, List.of("warn one"), 1234));
    }

    @Test
    void roundTripsTracksLyricsAndReport() throws IOException {
        Path root = dir.resolve("music");
        Library lib = sample(root);
        Path f = dir.resolve("library.bin");
        LibrarySnapshot.save(lib, f);
        LibrarySnapshot.Loaded loaded = LibrarySnapshot.load(f, root);
        assertNotNull(loaded);
        Library back = loaded.library();
        assertEquals(lib.tracks(), back.tracks());
        for (int i = 0; i < lib.size(); i++) assertEquals(lib.lyrics(i), back.lyrics(i));
        assertEquals(lib.report(), back.report());
        assertTrue(System.currentTimeMillis() - loaded.scannedAtMs() < 60_000);
    }

    @Test
    void loadingNeverTouchesTheMusicFolder() throws IOException {
        Path root = dir.resolve("gone"); // never created: a snapshot must load without it
        Path f = dir.resolve("library.bin");
        LibrarySnapshot.save(sample(root), f);
        assertFalse(Files.exists(root));
        assertEquals(2, LibrarySnapshot.load(f, root).library().size());
    }

    @Test
    void rejectedForOtherFolderMissingOrCorruptFile() throws IOException {
        Path root = dir.resolve("music");
        Path f = dir.resolve("library.bin");
        assertNull(LibrarySnapshot.load(f, root), "missing");
        LibrarySnapshot.save(sample(root), f);
        assertNull(LibrarySnapshot.load(f, dir.resolve("other")), "different music folder");
        byte[] b = Files.readAllBytes(f);
        Files.write(f, java.util.Arrays.copyOf(b, b.length / 2));
        assertNull(LibrarySnapshot.load(f, root), "truncated");
        Files.write(f, new byte[]{1, 2, 3});
        assertNull(LibrarySnapshot.load(f, root), "garbage");
    }

    @Test
    void emptyLibraryRoundTrips() throws IOException {
        Path root = dir.resolve("empty");
        Path f = dir.resolve("library.bin");
        LibrarySnapshot.save(Library.of(root, List.of(), List.of()), f);
        assertTrue(LibrarySnapshot.load(f, root).library().isEmpty());
    }

    @Test
    void lyricsCacheReusesUnchangedAndRereadsChangedFiles() throws IOException {
        Path music = dir.resolve("m");
        Files.createDirectories(music);
        Files.write(music.resolve("Artist - Song.flac"), new byte[]{1, 2, 3});
        Path lrc = music.resolve("Artist - Song.lrc");
        Files.writeString(lrc, "[00:01.00]version one");
        LyricsCache cache = LyricsCache.emptyCache();
        LibraryScanner sc = new LibraryScanner(new TagReader(false), null, cache);
        assertEquals("version one", sc.scan(music, null).lyrics(0).lines().get(0).text());
        assertEquals(1, cache.size());

        // a cached entry with a matching stamp is used instead of reading the file
        long size = Files.size(lrc), mtime = Files.getLastModifiedTime(lrc).toMillis();
        cache.put(lrc, size, mtime, LrcParser.parse("[00:01.00]from cache"));
        assertEquals("from cache", sc.scan(music, null).lyrics(0).lines().get(0).text());

        // the file changes (size differs) → re-read
        Files.writeString(lrc, "[00:01.00]version two, longer");
        assertEquals("version two, longer", sc.scan(music, null).lyrics(0).lines().get(0).text());

        // persisted and reloaded
        Path f = dir.resolve("lyrics.bin");
        cache.save(f);
        LyricsCache reloaded = LyricsCache.load(f);
        assertEquals(1, reloaded.size());
        assertNotNull(reloaded.lookup(lrc, Files.size(lrc), Files.getLastModifiedTime(lrc).toMillis()));
        assertEquals(0, LyricsCache.load(dir.resolve("missing.bin")).size());
    }
}
