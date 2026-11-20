package bloombox.core.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrogramTest {

    static final int SR = 22_050;

    static short[] tone(double hz, double seconds, double amp) {
        short[] s = new short[(int) (seconds * SR)];
        for (int i = 0; i < s.length; i++) s[i] = (short) Math.round(amp * 32767 * Math.sin(2 * Math.PI * hz * i / SR));
        return s;
    }

    static int loudestBar(Spectrogram sp, int frame) {
        int best = 0;
        for (int b = 1; b < sp.bars(); b++) if (sp.level(frame, b) > sp.level(frame, best)) best = b;
        return best;
    }

    @Test
    void higherTonesLightHigherBars() {
        Spectrogram low = Spectrogram.of(tone(110, 1, 0.5), SR, 48);
        Spectrogram mid = Spectrogram.of(tone(1_000, 1, 0.5), SR, 48);
        Spectrogram high = Spectrogram.of(tone(6_000, 1, 0.5), SR, 48);
        int f = 15;
        assertTrue(loudestBar(low, f) < loudestBar(mid, f));
        assertTrue(loudestBar(mid, f) < loudestBar(high, f));
        assertEquals(255, mid.level(f, loudestBar(mid, f)), "the tone's band reaches full height");
        // log spacing: 50 Hz–10 kHz over 48 bars → 1 kHz sits ~27/48 of the way up
        assertEquals(27, loudestBar(mid, f), 2);
    }

    @Test
    void framesFollowTime() {
        Spectrogram sp = Spectrogram.of(tone(440, 2, 0.3), SR, 32);
        assertEquals(60, sp.frames(), 1, "30 frames per second");
        assertEquals(30.0, sp.fps());
        int[] out = new int[32];
        sp.sample(1.0, out, 1000);
        assertTrue(java.util.Arrays.stream(out).max().orElse(0) > 900);
        sp.sample(5.0, out, 1000);
        assertArrayEquals(new int[32], out, "past the end: silence");
        sp.sample(-1, out, 1000);
        assertArrayEquals(new int[32], out);
        sp.sample(Double.NaN, out, 1000);
        assertArrayEquals(new int[32], out);
    }

    @Test
    void toneThenSilenceThenTone() {
        short[] a = tone(2_000, 1, 0.5), pcm = new short[a.length * 3];
        System.arraycopy(a, 0, pcm, 0, a.length);
        System.arraycopy(a, 0, pcm, 2 * a.length, a.length);
        Spectrogram sp = Spectrogram.of(pcm, SR, 48);
        int bar = loudestBar(sp, 15);
        assertTrue(sp.level(15, bar) > 200);
        for (int b = 0; b < 48; b++) assertEquals(0, sp.level(45, b), "middle second is digital silence");
        assertTrue(sp.level(75, bar) > 200);
    }

    @Test
    void loudnessIsNormalisedPerTrack() {
        Spectrogram loud = Spectrogram.of(tone(800, 1, 0.9), SR, 48);
        Spectrogram quiet = Spectrogram.of(tone(800, 1, 0.01), SR, 48);
        int b = loudestBar(loud, 15);
        assertEquals(loud.level(15, b), quiet.level(15, b), 3, "−39 dB master still fills the bars");
    }

    @Test
    void streamingInAnyChunkSizeGivesTheSameResult() {
        short[] pcm = tone(330, 1.3, 0.4);
        for (int i = 0; i < pcm.length; i += 7) pcm[i] += (short) (i % 900); // add some texture
        Spectrogram whole = Spectrogram.of(pcm, SR, 24);
        Spectrogram.Builder b = new Spectrogram.Builder(SR, 24, Spectrogram.DEFAULT_FPS, Spectrogram.DEFAULT_WINDOW);
        int[] sizes = {1, 1000, 37, 4096, 3};
        for (int i = 0, k = 0; i < pcm.length; k++) {
            int n = Math.min(sizes[k % sizes.length], pcm.length - i);
            b.accept(pcm, i, n);
            i += n;
        }
        Spectrogram chunked = b.finish();
        assertEquals(whole.frames(), chunked.frames());
        for (int f = 0; f < whole.frames(); f++)
            for (int bar = 0; bar < 24; bar++) assertEquals(whole.level(f, bar), chunked.level(f, bar));
    }

    @Test
    void emptyAndSilentInputs() {
        Spectrogram empty = Spectrogram.of(new short[0], SR, 48);
        assertEquals(0, empty.frames());
        int[] out = {5, 5};
        empty.sample(0, out, 1000);
        assertArrayEquals(new int[2], out);
        Spectrogram silent = Spectrogram.of(new short[SR], SR, 48);
        for (int f = 0; f < silent.frames(); f++) for (int b = 0; b < 48; b++) assertEquals(0, silent.level(f, b));
        assertThrows(IllegalArgumentException.class, () -> new Spectrogram.Builder(4_000, 48, 30, 2048));
        assertThrows(IllegalArgumentException.class, () -> new Spectrogram.Builder(SR, 0, 30, 2048));
        assertThrows(IllegalArgumentException.class, () -> new Spectrogram.Builder(SR, 48, 30, 1000));
    }
}
