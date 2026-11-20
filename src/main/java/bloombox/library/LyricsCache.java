package bloombox.library;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed lyrics keyed by lyrics-file path, validated by size + mtime (like {@link TagCache}).
 * On a network share reading every .lrc file is a round trip each; with this cache a rescan
 * reads only the lyrics files that changed. Also holds the (de)serialisation of
 * {@link Lyrics} shared with {@link LibrarySnapshot}. Any read error discards the cache.
 */
public final class LyricsCache {

    private static final int MAGIC = 0x42424c59; // "BBLY"
    private static final int VERSION = 1;

    record Entry(long size, long mtime, Lyrics lyrics) {}

    private final Map<String, Entry> entries;

    private LyricsCache(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public static LyricsCache emptyCache() {
        return new LyricsCache(new ConcurrentHashMap<>());
    }

    /** Cached lyrics if the file still has this size and mtime, else null. */
    public Lyrics lookup(Path file, long size, long mtime) {
        Entry e = entries.get(file.toString());
        return e != null && e.size() == size && e.mtime() == mtime ? e.lyrics() : null;
    }

    public void put(Path file, long size, long mtime, Lyrics lyrics) {
        entries.put(file.toString(), new Entry(size, mtime, lyrics));
    }

    public void retainOnly(Set<String> paths) {
        entries.keySet().retainAll(paths);
    }

    public int size() {
        return entries.size();
    }

    public static LyricsCache load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return emptyCache();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return emptyCache();
            int n = in.readInt();
            if (n < 0) return emptyCache();
            Map<String, Entry> m = new ConcurrentHashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                String path = readString(in);
                long size = in.readLong(), mtime = in.readLong();
                m.put(path, new Entry(size, mtime, readLyrics(in)));
            }
            return new LyricsCache(m);
        } catch (IOException | RuntimeException e) {
            return emptyCache();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                writeString(out, e.getKey());
                out.writeLong(e.getValue().size());
                out.writeLong(e.getValue().mtime());
                writeLyrics(out, e.getValue().lyrics());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- shared binary helpers

    static void writeLyrics(DataOutputStream out, Lyrics l) throws IOException {
        out.writeBoolean(l.synced());
        out.writeInt(l.lines().size());
        for (LyricLine line : l.lines()) {
            out.writeLong(line.timeMs());
            writeString(out, line.text());
        }
        out.writeInt(l.metadata().size());
        for (Map.Entry<String, String> e : l.metadata().entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue());
        }
    }

    static Lyrics readLyrics(DataInputStream in) throws IOException {
        boolean synced = in.readBoolean();
        int n = checkCount(in.readInt());
        List<LyricLine> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(new LyricLine(in.readLong(), readString(in)));
        Map<String, String> meta = readMeta(in, checkCount(in.readInt()));
        return lines.isEmpty() && meta.isEmpty() && !synced ? Lyrics.EMPTY : new Lyrics(lines, synced, meta);
    }

    private static Map<String, String> readMeta(DataInputStream in, int n) throws IOException {
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) meta.put(readString(in), readString(in));
        return meta;
    }

    static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > (8 << 20)) throw new IOException("corrupt string length " + n);
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    static int checkCount(int n) throws IOException {
        if (n < 0 || n > 10_000_000) throw new IOException("corrupt count " + n);
        return n;
    }
}
