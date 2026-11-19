package bloombox.library;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * On-disk cache of tag-reading results keyed by absolute path, validated by file
 * size + modification time. Tag reading dominates scan time, so an unchanged
 * library rescans almost instantly. The search structures themselves are rebuilt
 * in memory on every start (cheap, and always consistent with the code).
 *
 * <p>Format: magic, version, count, then per entry
 * {@code path, size, mtime, title, artist, album, trackNo, discNo, durationMs, source}.
 * Any read error discards the whole cache (it is only an optimisation).
 */
public final class TagCache {

    private static final int MAGIC = 0x42425843; // "BBXC"
    private static final int VERSION = 1;

    record Entry(long size, long mtime, TagReader.Tags tags) {}

    private final Map<String, Entry> entries;

    private TagCache(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public static TagCache emptyCache() {
        return new TagCache(new HashMap<>());
    }

    public static TagCache load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return emptyCache();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return emptyCache();
            int n = in.readInt();
            if (n < 0) return emptyCache();
            Map<String, Entry> m = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                String path = in.readUTF();
                long size = in.readLong();
                long mtime = in.readLong();
                TagReader.Tags t = new TagReader.Tags(in.readUTF(), in.readUTF(), in.readUTF(),
                        in.readInt(), in.readInt(), in.readLong(),
                        TagReader.Source.values()[in.readByte()]);
                m.put(path, new Entry(size, mtime, t));
            }
            return new TagCache(m);
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
                TagReader.Tags t = e.getValue().tags();
                out.writeUTF(e.getKey());
                out.writeLong(e.getValue().size());
                out.writeLong(e.getValue().mtime());
                out.writeUTF(clip(t.title()));
                out.writeUTF(clip(t.artist()));
                out.writeUTF(clip(t.album()));
                out.writeInt(t.trackNo());
                out.writeInt(t.discNo());
                out.writeLong(t.durationMs());
                out.writeByte(t.source().ordinal());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Cached tags if the file is unchanged, else null. */
    public TagReader.Tags lookup(Path file, long size, long mtime) {
        Entry e;
        synchronized (entries) {
            e = entries.get(file.toString());
        }
        return e != null && e.size() == size && e.mtime() == mtime ? e.tags() : null;
    }

    public void put(Path file, long size, long mtime, TagReader.Tags tags) {
        synchronized (entries) {
            entries.put(file.toString(), new Entry(size, mtime, tags));
        }
    }

    /** Drops entries for files not in {@code keep} (deleted/moved files). */
    public void retainOnly(java.util.Set<String> keep) {
        synchronized (entries) {
            entries.keySet().retainAll(keep);
        }
    }

    public int size() {
        return entries.size();
    }

    /** writeUTF is limited to 65535 encoded bytes; tags that long are junk anyway. */
    private static String clip(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
