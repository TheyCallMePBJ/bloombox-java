package bloombox.library;

import java.util.List;
import java.util.Map;

/**
 * Parsed lyrics for a single track.
 *
 * <p>For synced lyrics, {@link #lines()} is sorted by time ascending, which lets
 * {@link #indexAt(long)} find the active line by binary search in O(log n).
 */
public record Lyrics(List<LyricLine> lines, boolean synced, Map<String, String> metadata) {

    public static final Lyrics EMPTY = new Lyrics(List.of(), false, Map.of());

    public Lyrics {
        lines = List.copyOf(lines);
        metadata = Map.copyOf(metadata);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Index of the line that is active at {@code positionMs}: the last line whose
     * start time is &lt;= positionMs. Returns -1 before the first line, or when the
     * lyrics are not synced.
     */
    public int indexAt(long positionMs) {
        if (!synced || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
