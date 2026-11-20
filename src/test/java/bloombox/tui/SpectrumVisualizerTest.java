package bloombox.tui;

import bloombox.core.signal.Spectrogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumVisualizerTest {

    private static final long MS = 1_000_000L;

    private static Spectrogram tone() {
        int sr = 22_050;
        short[] s = new short[sr * 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) (10_000 * Math.sin(2 * Math.PI * 1_000 * i / sr));
        return Spectrogram.of(s, sr, SpectrumVisualizer.BARS);
    }

    private static int max(int[] a) {
        return java.util.Arrays.stream(a).max().orElse(0);
    }

    @Test
    void barsRiseImmediatelyWhilePlaying() {
        SpectrumVisualizer v = new SpectrumVisualizer();
        assertTrue(v.settled());
        int[] bars = v.update(tone(), 1.0, true, 0);
        assertTrue(max(bars) > 900);
        assertFalse(v.settled());
    }

    @Test
    void pausingLetsBarsFallToTheFloorWithGravity() {
        SpectrumVisualizer v = new SpectrumVisualizer();
        Spectrogram sp = tone();
        v.update(sp, 1.0, true, 0);
        int prev = max(v.update(sp, 1.0, false, 33 * MS));
        assertTrue(prev > 0 && prev < 1000);
        int prevDrop = 1000 - prev;
        long t = 33 * MS;
        for (int i = 0; i < 5 && prev > 0; i++) {
            t += 33 * MS;
            int now = max(v.update(sp, 1.0, false, t));
            assertTrue(now < prev, "keeps falling");
            assertTrue(prev - now >= prevDrop || now == 0, "and accelerates");
            prevDrop = prev - now;
            prev = now;
        }
        for (int i = 0; i < 30; i++) v.update(sp, 1.0, false, t += 33 * MS);
        assertTrue(v.settled(), "on the floor within a second");
    }

    @Test
    void noSpectrumYetMeansFlatBars() {
        SpectrumVisualizer v = new SpectrumVisualizer();
        assertEquals(0, max(v.update(null, 3.0, true, 0)));
        assertTrue(v.settled());
    }

    @Test
    void positionIsExtrapolatedBetweenPlayerReportsButCapped() {
        // a spectrogram that is silent for 1 s, then a tone: bars appear once the clock passes 1 s
        int sr = 22_050;
        short[] s = new short[sr * 3];
        for (int i = sr; i < s.length; i++) s[i] = (short) (10_000 * Math.sin(2 * Math.PI * 500 * i / sr));
        Spectrogram sp = Spectrogram.of(s, sr, SpectrumVisualizer.BARS);
        SpectrumVisualizer v = new SpectrumVisualizer();
        assertEquals(0, max(v.update(sp, 0.80, true, 0)));
        assertTrue(max(v.update(sp, 0.80, true, 200 * MS)) > 500, "0.8 s + 0.2 s of wall clock ≈ 1.0 s");
        SpectrumVisualizer stalled = new SpectrumVisualizer();
        stalled.update(sp, 0.30, true, 0);
        assertEquals(0, max(stalled.update(sp, 0.30, true, 5_000 * MS)), "a stalled player can't run 5 s ahead");
    }
}
