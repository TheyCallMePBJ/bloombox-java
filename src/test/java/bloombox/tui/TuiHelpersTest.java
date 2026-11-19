package bloombox.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiHelpersTest {

    private static KeyStroke ch(char c) { return new KeyStroke(c, false, false); }
    private static KeyStroke ctrl(char c) { return new KeyStroke(c, true, false); }
    private static KeyStroke key(KeyType t) { return new KeyStroke(t); }

    @Test
    void textInputEditsAtCursorIncludingSurrogatePairs() {
        TextInput in = new TextInput();
        for (char c : "blue".toCharArray()) in.handle(ch(c));
        in.handle(key(KeyType.Home));
        in.handle(ch('x'));
        assertEquals("xblue", in.text());
        in.handle(key(KeyType.End));
        in.set("a🎵");
        assertTrue(in.handle(key(KeyType.Backspace)));
        assertEquals("a", in.text(), "backspace removes a whole emoji, not half a surrogate pair");
        in.set("daft punk digital");
        assertTrue(in.handle(ctrl('w')));
        assertEquals("daft punk ", in.text());
        assertTrue(in.handle(ctrl('u')));
        assertEquals("", in.text());
        assertFalse(in.handle(key(KeyType.Backspace)), "nothing to delete");
    }

    @Test
    void listStateKeepsSelectionVisibleAndInRange() {
        ListState ls = new ListState();
        ls.move(50, 100);
        ls.clamp(100, 10);
        assertEquals(50, ls.selected);
        assertEquals(41, ls.offset); // selection is the last visible row
        ls.move(-1000, 100);
        ls.clamp(100, 10);
        assertEquals(0, ls.selected);
        assertEquals(0, ls.offset);
        ls.selected = 99;
        ls.clamp(5, 10); // list shrank (new search results)
        assertEquals(4, ls.selected);
        ls.clamp(0, 10);
        assertEquals(0, ls.selected);
        ls.center(60, 100, 10);
        assertEquals(55, ls.offset);
        ls.scroll(3, 100, 10);
        assertEquals(58, ls.offset);
        assertTrue(ls.selected >= 58 && ls.selected < 68);
    }

    @Test
    void canvasFitIsWidthAwareForCjk() {
        assertEquals("hello", Canvas.fit("hello", 5));
        assertEquals("hel…", Canvas.fit("hello", 4));
        assertEquals(4, Canvas.width("夜に"));
        assertTrue(Canvas.width(Canvas.fit("夜に駆ける", 5)) <= 5);
        assertEquals("", Canvas.fit("x", 0));
        assertEquals("ab   ", Canvas.pad("ab", 5));
    }

    @Test
    void timeFormatting() {
        assertEquals("0:00", Canvas.time(0));
        assertEquals("3:05", Canvas.time(185.9));
        assertEquals("1:01:01", Canvas.time(3661));
        assertEquals("-:--", Canvas.time(Double.NaN));
        assertEquals("-:--", Canvas.time(-1));
    }

    @Test
    void waveformEnvelopeIsNormalisedAndBucketed() {
        short[] s = new short[8000];
        for (int i = 0; i < s.length; i++) s[i] = (short) (i < 4000 ? 1000 : 8000);
        float[] env = WaveformService.envelope(s, s.length);
        assertEquals(WaveformService.BUCKETS, env.length);
        assertEquals(1.0f, env[env.length - 1], 1e-6);
        assertTrue(env[0] < env[env.length - 1]);
        for (float v : env) assertTrue(v >= 0 && v <= 1);
        assertEquals(3, WaveformService.envelope(new short[]{0, 0, 0}, 3).length, "fewer samples than buckets");
        for (float v : WaveformService.envelope(new short[10], 10)) assertEquals(0f, v, "silence stays flat");
    }

    @Test
    void cavaFrameParsing() {
        assertArrayEquals(new int[]{0, 500, 1000}, CavaVisualizer.parse("0;500;1000;"));
        assertArrayEquals(new int[]{1000, 0}, CavaVisualizer.parse("4000;-3;"), "values are clamped");
        assertNull(CavaVisualizer.parse(""));
        assertNull(CavaVisualizer.parse("1;x;3;"));
    }

    @Test
    void wrapBreaksOnWordsWithinWidth() {
        var rows = Canvas.wrap("the quick brown fox jumps over the lazy dog", 10);
        assertEquals(java.util.List.of("the quick", "brown fox", "jumps over", "the lazy", "dog"), rows);
        for (String r : rows) assertTrue(Canvas.width(r) <= 10);
    }

    @Test
    void wrapEdgeCases() {
        assertEquals(java.util.List.of(""), Canvas.wrap("   ", 10), "blank input gives one empty row");
        assertEquals(java.util.List.of("abcd", "efgh", "ij"), Canvas.wrap("abcdefghij", 4), "long words are hard-split");
        assertEquals(java.util.List.of("日本", "語"), Canvas.wrap("日本語", 4), "double-width characters count as 2");
        assertTrue(Canvas.wrap("x", 0).isEmpty());
    }

    @Test
    void waveformResampleKeepsShapeAndRange() {
        float[] env = new float[1024];
        for (int i = 512; i < 1024; i++) env[i] = 1f;
        double[] v = Waveform.resample(env, 8);
        assertEquals(8, v.length);
        assertEquals(0.0, v[0], 1e-9);
        assertEquals(1.0, v[7], 1e-9);
        for (double d : v) assertTrue(d >= 0 && d <= 1);
        assertArrayEquals(new double[4], Waveform.resample(null, 4), "no envelope yet → flat");
    }

    @Test
    void kittyDiacriticTableIsComplete() {
        assertEquals(297, KittyDiacritics.CODEPOINTS.length);
        assertEquals(0x0305, KittyDiacritics.CODEPOINTS[0]);
        assertEquals(0x030D, KittyDiacritics.CODEPOINTS[1]);
        for (int cp : KittyDiacritics.CODEPOINTS) {
            assertEquals(Character.NON_SPACING_MARK, Character.getType(cp), Integer.toHexString(cp));
        }
    }
}
