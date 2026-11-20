package bloombox.library;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static bloombox.library.LyricsCache.checkCount;
import static bloombox.library.LyricsCache.readString;
import static bloombox.library.LyricsCache.writeString;

/**
 * The whole scanned library (tracks, parsed lyrics, last scan report) saved after each scan,
 * so start-up does not have to touch the music folder at all; important when it lives on a
 * network share, where even an all-cached scan costs a round trip per file.
 *
 * <p>The snapshot records the root it was scanned from; it is ignored if the configured music
 * folder differs, if the format version changed, or if it is corrupt (then a scan runs).
 */
public final class LibrarySnapshot {
    private LibrarySnapshot() {}

    private static final int MAGIC = 0x42424c53; // "BBLS"
    private static final int VERSION = 1;

    public static void save(Library lib, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeString(out, lib.root() == null ? "" : lib.root().toString());
            out.writeLong(System.currentTimeMillis());
            ScanReport r = lib.report();
            out.writeInt(r.audioFiles());
            out.writeInt(r.symlinkDuplicates());
            out.writeInt(r.fromCache());
            out.writeInt(r.fromTags());
            out.writeInt(r.fromFfprobe());
            out.writeInt(r.fromFilename());
            out.writeInt(r.withLyrics());
            out.writeInt(r.lyricErrors());
            out.writeInt(r.likelyDuplicates());
            out.writeLong(r.elapsedMs());
            out.writeInt(r.warnings().size());
            for (String w : r.warnings()) writeString(out, w);
            out.writeInt(lib.size());
            for (Track t : lib.tracks()) {
                writeString(out, t.path().toString());
                writeString(out, t.title());
                writeString(out, t.artist());
                writeString(out, t.album());
                out.writeInt(t.trackNo());
                out.writeInt(t.discNo());
                out.writeLong(t.durationMs());
                writeString(out, t.lyricsPath() == null ? "" : t.lyricsPath().toString());
                LyricsCache.writeLyrics(out, lib.lyrics(t.id()));
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A loaded snapshot and when the scan behind it happened. */
    public record Loaded(Library library, long scannedAtMs) {}

    /** The snapshot for {@code expectedRoot}, or null if missing, stale-format, for another folder, or corrupt. */
    public static Loaded load(Path file, Path expectedRoot) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            String root = readString(in);
            Path want = expectedRoot.toAbsolutePath().normalize();
            if (!root.equals(want.toString())) return null;
            long scannedAt = in.readLong();
            int[] counts = new int[9];
            for (int i = 0; i < counts.length; i++) counts[i] = in.readInt();
            long elapsed = in.readLong();
            int nw = checkCount(in.readInt());
            List<String> warnings = new ArrayList<>(nw);
            for (int i = 0; i < nw; i++) warnings.add(readString(in));
            ScanReport report = new ScanReport(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                    counts[6], counts[7], counts[8], List.copyOf(warnings), elapsed);
            int n = checkCount(in.readInt());
            List<Track> tracks = new ArrayList<>(n);
            Lyrics[] lyrics = new Lyrics[n];
            for (int id = 0; id < n; id++) {
                Path path = Path.of(readString(in));
                String title = readString(in), artist = readString(in), album = readString(in);
                int trackNo = in.readInt(), discNo = in.readInt();
                long dur = in.readLong();
                String lp = readString(in);
                tracks.add(new Track(id, path, title, artist, album, trackNo, discNo, dur, lp.isEmpty() ? null : Path.of(lp)));
                lyrics[id] = LyricsCache.readLyrics(in);
            }
            return new Loaded(new Library(want, tracks, lyrics, report), scannedAt);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
