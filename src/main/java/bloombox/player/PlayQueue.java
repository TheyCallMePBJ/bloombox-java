package bloombox.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The play queue: an ordered list of track ids, a cursor, and shuffle / repeat modes.
 * Pure logic, no I/O, so it is fully unit-testable.
 *
 * <h2>Shuffle</h2>
 * Shuffling builds a permutation {@code order} of queue positions with the
 * Fisher–Yates algorithm: for i from n−1 down to 1, swap a[i] with a[j] where j is
 * uniform in [0, i]. Every one of the n! permutations is equally likely and it runs
 * in O(n) time with O(n) extra space. The currently playing track is moved to the
 * front so turning shuffle on never interrupts playback. When repeat-all wraps
 * around, a fresh permutation is drawn so each cycle is a new order.
 *
 * <p>Not thread-safe; {@link PlaybackController} serialises access.
 */
public final class PlayQueue {

    public enum Repeat {
        OFF, ALL, ONE;

        public Repeat next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final Random random;
    private final List<Integer> tracks = new ArrayList<>(); // queue in user order
    private int[] order = new int[0];  // play order: permutation of 0..n-1 (identity if not shuffled)
    private int cursor = -1;
    private int version;           // index into order; -1 = nothing selected
    private boolean shuffle;
    private Repeat repeat = Repeat.OFF;

    public PlayQueue(Random random) {
        this.random = random;
    }

    public PlayQueue() {
        this(new Random());
    }

    // ---- content ----------------------------------------------------------------

    /** Replaces the queue and selects position {@code start} (in the given list). */
    public void set(List<Integer> trackIds, int start) {
        if (trackIds.isEmpty()) {
            clear();
            return;
        }
        if (start < 0 || start >= trackIds.size())
            throw new IndexOutOfBoundsException("start " + start + " not in queue of " + trackIds.size());
        tracks.clear();
        tracks.addAll(trackIds);
        rebuildOrder(start);
        version++;
    }

    public void clear() {
        tracks.clear();
        order = new int[0];
        cursor = -1;
        version++;
    }

    /** Appends to the end of the queue (and of the play order). */
    public void append(int trackId) {
        tracks.add(trackId);
        order = java.util.Arrays.copyOf(order, order.length + 1);
        order[order.length - 1] = tracks.size() - 1;
        if (cursor < 0) cursor = 0;
        version++;
    }

    public int size() {
        return tracks.size();
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /** Track ids in user (unshuffled) order. */
    public List<Integer> tracks() {
        return List.copyOf(tracks);
    }

    /** Track ids in the order they will actually play. */
    public List<Integer> playOrder() {
        List<Integer> out = new ArrayList<>(order.length);
        for (int i : order) out.add(tracks.get(i));
        return out;
    }

    // ---- cursor -----------------------------------------------------------------

    /** Currently selected track id, or -1. */
    public int current() {
        return cursor < 0 ? -1 : tracks.get(order[cursor]);
    }

    /** Index of the current track in the play order ({@link #playOrder()}), or -1. */
    public int cursor() {
        return cursor;
    }

    /** Selects the entry at {@code index} of the play order and returns its track id. */
    public int jumpToOrderIndex(int index) {
        if (index < 0 || index >= order.length) throw new IndexOutOfBoundsException();
        cursor = index;
        return current();
    }

    /** Changes whenever the contents or the play order change (not when the cursor moves). */
    public int version() {
        return version;
    }

    /** Position of the current track in user order, or -1. */
    public int currentPosition() {
        return cursor < 0 ? -1 : order[cursor];
    }

    /** Selects the track at {@code position} in user order. */
    public int jumpTo(int position) {
        if (position < 0 || position >= tracks.size()) throw new IndexOutOfBoundsException();
        for (int i = 0; i < order.length; i++) {
            if (order[i] == position) {
                cursor = i;
                break;
            }
        }
        return current();
    }

    /**
     * Advances and returns the new current track, or -1 when playback should stop.
     *
     * @param userInitiated true for the Next button (repeat-one then still advances),
     *                      false when a track ended naturally
     */
    public int next(boolean userInitiated) {
        if (cursor < 0) return -1;
        if (repeat == Repeat.ONE && !userInitiated) return current();
        if (cursor + 1 < order.length) {
            cursor++;
        } else if (repeat != Repeat.OFF) {
            if (shuffle && order.length > 2) {
                int last = order[cursor];
                reshuffleAll();
                if (order[0] == last) swap(order, 0, 1 + random.nextInt(order.length - 1)); // no back-to-back repeat
            }
            cursor = 0;
        } else {
            return -1;
        }
        return current();
    }

    /**
     * The track that will play after the current one ends naturally, without moving the
     * cursor; -1 if playback will stop or the next pick isn't known yet (a shuffled queue
     * that reshuffles when it wraps).
     */
    public int peekNext() {
        if (cursor < 0) return -1;
        if (repeat == Repeat.ONE) return current();
        if (cursor + 1 < order.length) return tracks.get(order[cursor + 1]);
        if (repeat == Repeat.ALL && !(shuffle && order.length > 2)) return tracks.get(order[0]);
        return -1;
    }

    /** Steps back; at the start, wraps only when repeat is on. Returns the new current id. */
    public int previous() {
        if (cursor < 0) return -1;
        if (cursor > 0) cursor--;
        else if (repeat != Repeat.OFF) cursor = order.length - 1;
        return current();
    }

    public boolean hasNext() {
        return cursor >= 0 && (cursor + 1 < order.length || repeat != Repeat.OFF);
    }

    public boolean hasPrevious() {
        return cursor >= 0 && (cursor > 0 || repeat != Repeat.OFF);
    }

    // ---- modes ------------------------------------------------------------------

    public boolean shuffle() {
        return shuffle;
    }

    public void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        rebuildOrder(currentPosition());
        version++;
    }

    public Repeat repeat() {
        return repeat;
    }

    public void setRepeat(Repeat r) {
        repeat = r;
    }

    // ---- internals --------------------------------------------------------------

    /** Rebuilds the play order keeping {@code keepPosition} (user order) as current. */
    private void rebuildOrder(int keepPosition) {
        int n = tracks.size();
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        if (n == 0) {
            cursor = -1;
            return;
        }
        if (!shuffle) {
            cursor = Math.max(0, keepPosition);
            return;
        }
        fisherYates(order, random);
        if (keepPosition >= 0) {
            for (int i = 0; i < n; i++) {
                if (order[i] == keepPosition) {
                    swap(order, 0, i);
                    break;
                }
            }
        }
        cursor = 0;
    }

    private void reshuffleAll() {
        for (int i = 0; i < order.length; i++) order[i] = i;
        fisherYates(order, random);
        version++;
    }

    /** In-place uniform random permutation (Durstenfeld's Fisher–Yates). Package-private for tests. */
    public static void fisherYates(int[] a, Random rnd) {
        for (int i = a.length - 1; i > 0; i--) {
            swap(a, i, rnd.nextInt(i + 1));
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
