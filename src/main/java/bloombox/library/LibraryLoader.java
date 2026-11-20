package bloombox.library;

import bloombox.config.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the library: from the snapshot saved by the last scan when possible (no folder
 * access at all), otherwise by scanning with the tag and lyrics caches.
 */
public final class LibraryLoader {

    private LibraryLoader() {}

    public static Path tagCacheFile() {
        return Settings.cacheHome().resolve("tags.bin");
    }

    public static Path lyricsCacheFile() {
        return Settings.cacheHome().resolve("lyrics.bin");
    }

    public static Path snapshotFile() {
        return Settings.cacheHome().resolve("library.bin");
    }

    /** The library saved by the last scan of {@code musicDir}, or null (never touches the folder). */
    public static LibrarySnapshot.Loaded loadSnapshot(Path musicDir) {
        return LibrarySnapshot.load(snapshotFile(), musicDir);
    }

    /** Snapshot if there is one for this folder, otherwise a full scan. */
    public static Library load(Path musicDir, LibraryScanner.Progress progress) throws IOException {
        LibrarySnapshot.Loaded snap = loadSnapshot(musicDir);
        return snap != null ? snap.library() : scan(musicDir, progress);
    }

    /** Walks the folder (tags and lyrics from their caches where unchanged), then saves caches + snapshot. */
    public static Library scan(Path musicDir, LibraryScanner.Progress progress) throws IOException {
        TagCache cache = TagCache.load(tagCacheFile());
        LyricsCache lyrics = LyricsCache.load(lyricsCacheFile());
        LibraryScanner scanner = new LibraryScanner(new TagReader(onPath("ffprobe")), cache, lyrics);
        Library lib = scanner.scan(musicDir, progress);
        try {
            cache.save(tagCacheFile());
            lyrics.save(lyricsCacheFile());
            LibrarySnapshot.save(lib, snapshotFile());
        } catch (IOException e) {
            System.err.println("bloombox: could not write library cache: " + e.getMessage());
        }
        return lib;
    }

    /** True if an executable called {@code name} is on $PATH. */
    public static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return true;
        }
        return false;
    }
}
