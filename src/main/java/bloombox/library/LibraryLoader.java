package bloombox.library;

import bloombox.config.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scans the configured music directory using the on-disk tag cache, then saves the cache. */
public final class LibraryLoader {

    private LibraryLoader() {}

    public static Path tagCacheFile() {
        return Settings.cacheHome().resolve("tags.bin");
    }

    public static Library load(Path musicDir, LibraryScanner.Progress progress) throws IOException {
        TagCache cache = TagCache.load(tagCacheFile());
        LibraryScanner scanner = new LibraryScanner(new TagReader(onPath("ffprobe")), cache);
        Library lib = scanner.scan(musicDir, progress);
        try {
            cache.save(tagCacheFile());
        } catch (IOException e) {
            System.err.println("bloombox: could not write tag cache: " + e.getMessage());
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
