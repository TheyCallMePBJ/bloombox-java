package bloombox.library;

import java.nio.file.Path;
import java.util.List;

/**
 * A scanned music library. Track ids are dense: {@code tracks().get(id).id() == id},
 * and {@code lyrics(id)} is aligned with the same ids.
 */
public final class Library {

    private final Path root;
    private final List<Track> tracks;
    private final Lyrics[] lyrics;
    private final ScanReport report;

    Library(Path root, List<Track> tracks, Lyrics[] lyrics, ScanReport report) {
        if (tracks.size() != lyrics.length) throw new IllegalArgumentException("tracks/lyrics size mismatch");
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id() != i) throw new IllegalArgumentException("track ids must be dense 0..n-1");
        }
        this.root = root;
        this.tracks = List.copyOf(tracks);
        this.lyrics = lyrics.clone();
        this.report = report;
    }

    /** Builds a library directly from in-memory data (tests, benchmarks). Ids are reassigned. */
    public static Library of(Path root, List<Track> unsorted, List<Lyrics> lyricsInSameOrder) {
        Integer[] order = new Integer[unsorted.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Track.LIBRARY_ORDER.compare(unsorted.get(a), unsorted.get(b)));
        Track[] t = new Track[order.length];
        Lyrics[] l = new Lyrics[order.length];
        for (int id = 0; id < order.length; id++) {
            t[id] = unsorted.get(order[id]).withId(id);
            Lyrics ly = lyricsInSameOrder.get(order[id]);
            l[id] = ly == null ? Lyrics.EMPTY : ly;
        }
        return new Library(root, List.of(t), l, ScanReport.empty());
    }

    public static Library empty() {
        return new Library(null, List.of(), new Lyrics[0], ScanReport.empty());
    }

    public Path root() { return root; }
    public List<Track> tracks() { return tracks; }
    public Track track(int id) { return tracks.get(id); }
    public Lyrics lyrics(int id) { return lyrics[id]; }
    public int size() { return tracks.size(); }
    public boolean isEmpty() { return tracks.isEmpty(); }
    public ScanReport report() { return report; }

    Library withReport(ScanReport r) {
        return new Library(root, tracks, lyrics, r);
    }
}
