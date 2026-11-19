package bloombox.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlayQueueTest {

    private static PlayQueue queue(int n, int start) {
        PlayQueue q = new PlayQueue(new Random(1));
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(100 + i);
        q.set(ids, start);
        return q;
    }

    @Test
    void sequentialPlaybackStopsAtEndWithoutRepeat() {
        PlayQueue q = queue(3, 0);
        assertEquals(100, q.current());
        assertEquals(101, q.next(false));
        assertEquals(102, q.next(false));
        assertFalse(q.hasNext());
        assertEquals(-1, q.next(false));
        assertEquals(102, q.current(), "cursor stays on the last track");
    }

    @Test
    void repeatAllWrapsAndRepeatOneOnlyHoldsForNaturalEnd() {
        PlayQueue q = queue(2, 1);
        q.setRepeat(PlayQueue.Repeat.ALL);
        assertEquals(100, q.next(false));
        assertEquals(101, q.previous());
        assertEquals(100, q.previous());
        assertEquals(101, q.previous(), "previous wraps when repeating");

        q.setRepeat(PlayQueue.Repeat.ONE);
        assertEquals(101, q.next(false), "track ended: play it again");
        assertEquals(100, q.next(true), "user pressed next: move on");
    }

    @Test
    void previousAtStartWithoutRepeatStays() {
        PlayQueue q = queue(3, 0);
        assertFalse(q.hasPrevious());
        assertEquals(100, q.previous());
    }

    @Test
    void shuffleKeepsCurrentTrackAndIsAPermutation() {
        PlayQueue q = queue(50, 17);
        q.setShuffle(true);
        assertEquals(117, q.current(), "turning shuffle on must not change the playing track");
        assertEquals(117, q.playOrder().get(0));
        assertEquals(50, new HashSet<>(q.playOrder()).size());
        assertNotEquals(q.tracks(), q.playOrder());

        for (int i = 0; i < 10; i++) q.next(true);
        int playing = q.current();
        q.setShuffle(false);
        assertEquals(playing, q.current(), "turning shuffle off keeps the playing track");
        assertEquals(playing - 100, q.currentPosition());
        if (playing < 149) assertEquals(playing + 1, q.next(false), "continues in user order from there");
    }

    @Test
    void repeatAllWithShuffleReshufflesWithoutImmediateRepeat() {
        PlayQueue q = queue(5, 0);
        q.setShuffle(true);
        q.setRepeat(PlayQueue.Repeat.ALL);
        for (int cycle = 0; cycle < 50; cycle++) {
            for (int i = 0; i < 4; i++) q.next(false);
            int last = q.current();
            int first = q.next(false);
            assertNotEquals(last, first, "same track twice in a row across the wrap");
            assertEquals(5, new HashSet<>(q.playOrder()).size());
        }
    }

    @Test
    void fisherYatesIsUniform() {
        // 3 elements → 6 permutations, each expected 1/6 of trials
        int trials = 60_000;
        Map<String, Integer> counts = new HashMap<>();
        Random rnd = new Random(123);
        for (int t = 0; t < trials; t++) {
            int[] a = {0, 1, 2};
            PlayQueue.fisherYates(a, rnd);
            counts.merge(Arrays.toString(a), 1, Integer::sum);
        }
        assertEquals(6, counts.size());
        // chi-square with 5 degrees of freedom; 20.5 is the 0.1% critical value
        double expected = trials / 6.0, chi2 = 0;
        for (int c : counts.values()) chi2 += (c - expected) * (c - expected) / expected;
        assertTrue(chi2 < 20.5, "chi2=" + chi2 + " counts=" + counts);
    }

    @Test
    void emptyQueueAndBoundaryInput() {
        PlayQueue q = new PlayQueue(new Random(0));
        assertEquals(-1, q.current());
        assertEquals(-1, q.next(true));
        assertEquals(-1, q.previous());
        assertFalse(q.hasNext());
        q.setShuffle(true); // must not throw on empty queue
        q.set(List.of(), 0);
        assertTrue(q.isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> q.set(List.of(1, 2), 2));
        q.append(7);
        assertEquals(7, q.current(), "appending to an empty queue selects it");
        q.set(List.of(9), 0);
        assertEquals(-1, q.next(false), "single track, no repeat");
    }

    @Test
    void jumpToSelectsByUserPosition() {
        PlayQueue q = queue(10, 0);
        q.setShuffle(true);
        assertEquals(104, q.jumpTo(4));
        assertEquals(4, q.currentPosition());
        assertThrows(IndexOutOfBoundsException.class, () -> q.jumpTo(10));
    }
}
