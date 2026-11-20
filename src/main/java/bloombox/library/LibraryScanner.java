package bloombox.library;

import bloombox.core.text.Tokenizer;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a music directory, reads tags and lyrics (both cached by size + mtime).
 *
 * <p>Edge cases handled: missing / non-directory root, unreadable subdirectories,
 * symlink loops, two paths resolving to the same file, hidden files and folders,
 * unsupported extensions, broken tags, unreadable lyrics.
 */
public final class LibraryScanner {

    /** Progress callback: (files processed, total files). Called from worker threads. */
    public interface Progress {
        void update(int done, int total);
    }

    private static final int MAX_WARNINGS = 50;

    private final TagReader tagReader;
    private final TagCache cache;
    private final LyricsCache lyricsCache;

    public LibraryScanner(TagReader tagReader, TagCache cache) {
        this(tagReader, cache, null);
    }

    public LibraryScanner(TagReader tagReader, TagCache cache, LyricsCache lyricsCache) {
        this.tagReader = tagReader;
        this.cache = cache == null ? TagCache.emptyCache() : cache;
        this.lyricsCache = lyricsCache == null ? LyricsCache.emptyCache() : lyricsCache;
    }

    public LyricsCache lyricsCache() {
        return lyricsCache;
    }

    /** Size + mtime (and identity) of a file as reported by the directory walk. */
    private record Stat(long size, long mtime) {}

    public TagCache cache() {
        return cache;
    }

    public Library scan(Path root, Progress progress) throws IOException {
        long t0 = System.nanoTime();
        if (root == null) throw new IllegalArgumentException("no music directory configured");
        if (!Files.exists(root)) throw new IllegalArgumentException("music directory does not exist: " + root);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("not a directory: " + root);
        root = root.toAbsolutePath().normalize();
        final Path start = root;

        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        int[] symlinkDupes = {0};
        // Everything below is taken from the walk's own attributes: on a network share every
        // extra stat / realpath / exists check is a round trip per file.
        Map<Object, Path> identityToPath = new LinkedHashMap<>(); // file key (dev+inode) → first path
        Map<Path, Stat> stats = new java.util.HashMap<>();         // every regular file seen

        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Path name = dir.getFileName();
                        if (!dir.equals(start) && name != null && name.toString().startsWith("."))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String n = file.getFileName().toString();
                        if (n.startsWith(".")) return FileVisitResult.CONTINUE;
                        stats.put(file, new Stat(attrs.size(), attrs.lastModifiedTime().toMillis()));
                        if (Track.formatOf(file) == null) return FileVisitResult.CONTINUE;
                        Object identity = attrs.fileKey();
                        if (identity == null) { // filesystem without inode keys: fall back to realpath
                            try {
                                identity = file.toRealPath();
                            } catch (IOException e) {
                                warn(warnings, "cannot resolve " + file + ": " + e.getMessage());
                                identity = file;
                            }
                        }
                        if (identityToPath.putIfAbsent(identity, file) != null) symlinkDupes[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        if (exc instanceof FileSystemLoopException) warn(warnings, "symlink loop at " + file);
                        else warn(warnings, "cannot read " + file + ": " + exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });

        List<Path> files = new ArrayList<>(identityToPath.values());
        int total = files.size();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger fromCache = new AtomicInteger(), fromTags = new AtomicInteger(),
                fromFfprobe = new AtomicInteger(), fromName = new AtomicInteger(),
                withLyrics = new AtomicInteger(), lyricErrors = new AtomicInteger();

        record Scanned(Track track, Lyrics lyrics) {}
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "bloombox-scan");
            t.setDaemon(true);
            return t;
        });
        List<Future<Scanned>> futures = new ArrayList<>(total);
        for (Path f : files) {
            futures.add(pool.submit(() -> {
                try {
                    Stat st = stats.get(f);
                    long size = st.size(), mtime = st.mtime();
                    TagReader.Tags tags = cache.lookup(f, size, mtime);
                    if (tags != null) {
                        fromCache.incrementAndGet();
                    } else {
                        tags = tagReader.read(f);
                        cache.put(f, size, mtime, tags);
                        switch (tags.source()) {
                            case TAGS -> fromTags.incrementAndGet();
                            case FFPROBE -> fromFfprobe.incrementAndGet();
                            case FILENAME -> {
                                fromName.incrementAndGet();
                                warn(warnings, "no readable tags, used file name: " + f);
                            }
                        }
                    }
                    // lyrics source order: .lrc → .txt → lyrics embedded in the audio file's tags
                    Path lrc = lyricsSibling(f, stats);
                    Lyrics lyrics = Lyrics.EMPTY;
                    if (lrc != null) {
                        Stat ls = stats.get(lrc);
                        Lyrics cached = lyricsCache.lookup(lrc, ls.size(), ls.mtime());
                        if (cached != null) {
                            lyrics = cached;
                        } else {
                            try {
                                lyrics = LrcParser.parse(lrc);
                                lyricsCache.put(lrc, ls.size(), ls.mtime(), lyrics);
                            } catch (IOException e) {
                                lyricErrors.incrementAndGet();
                                warn(warnings, "unreadable lyrics " + lrc + ": " + e.getMessage());
                            }
                        }
                    }
                    if (lyrics.isEmpty() && !tags.lyrics().isBlank()) {
                        lyrics = LrcParser.parse(tags.lyrics());
                        lrc = f;
                    }
                    if (lyrics.isEmpty()) lrc = null;
                    else withLyrics.incrementAndGet();
                    Track t = new Track(-1, f, tags.title(), tags.artist(), tags.album(),
                            tags.trackNo(), tags.discNo(), tags.durationMs(), lrc);
                    return new Scanned(t, lyrics);
                } catch (RuntimeException e) {
                    warn(warnings, "skipped " + f + ": " + e);
                    return null;
                } finally {
                    if (progress != null) progress.update(done.incrementAndGet(), total);
                }
            }));
        }
        pool.shutdown();

        List<Track> tracks = new ArrayList<>(total);
        List<Lyrics> lyrics = new ArrayList<>(total);
        for (Future<Scanned> fu : futures) {
            try {
                Scanned s = fu.get();
                if (s != null) {
                    tracks.add(s.track());
                    lyrics.add(s.lyrics());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                throw new IOException("scan interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                warn(warnings, "scan task failed: " + e.getCause());
            }
        }

        Set<String> keep = new HashSet<>(), keepLyrics = new HashSet<>();
        for (Track t : tracks) {
            keep.add(t.path().toString());
            if (t.lyricsPath() != null) keepLyrics.add(t.lyricsPath().toString());
        }
        cache.retainOnly(keep);
        lyricsCache.retainOnly(keepLyrics);

        Library lib = Library.of(root, tracks, lyrics);
        int likelyDupes = countLikelyDuplicates(lib.tracks());
        ScanReport report = new ScanReport(lib.size(), symlinkDupes[0], fromCache.get(), fromTags.get(),
                fromFfprobe.get(), fromName.get(), withLyrics.get(), lyricErrors.get(), likelyDupes,
                List.copyOf(warnings), (System.nanoTime() - t0) / 1_000_000);
        return lib.withReport(report);
    }

    /** Sibling lyrics file seen by the walk: .lrc preferred over .txt (like {@link Track#findLyricsFile}). */
    private static Path lyricsSibling(Path audio, Map<Path, Stat> seen) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        for (String ext : new String[]{".lrc", ".LRC", ".txt", ".TXT"}) {
            Path p = audio.resolveSibling(base + ext);
            if (seen.containsKey(p)) return p;
        }
        return null;
    }

    /** Same normalised artist + album + title as another track (e.g. a FLAC and an MP3 copy). */
    static int countLikelyDuplicates(List<Track> tracks) {
        Set<String> seen = new HashSet<>();
        int dupes = 0;
        for (Track t : tracks) {
            String key = Tokenizer.normalizeForMatch(t.artist()) + '\u0000'
                    + Tokenizer.normalizeForMatch(t.album()) + '\u0000'
                    + Tokenizer.normalizeForMatch(t.title());
            if (!seen.add(key)) dupes++;
        }
        return dupes;
    }

    private static void warn(List<String> warnings, String msg) {
        synchronized (warnings) {
            if (warnings.size() < MAX_WARNINGS) warnings.add(msg);
        }
    }
}
