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
        float[] env = TrackAnalysis.envelope(s, s.length);
        assertEquals(TrackAnalysis.BUCKETS, env.length);
        assertEquals(1.0f, env[env.length - 1], 1e-6);
        assertTrue(env[0] < env[env.length - 1]);
        for (float v : env) assertTrue(v >= 0 && v <= 1);
        assertEquals(3, TrackAnalysis.envelope(new short[]{0, 0, 0}, 3).length, "fewer samples than buckets");
        for (float v : TrackAnalysis.envelope(new short[10], 10)) assertEquals(0f, v, "silence stays flat");
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

    @Test
    void themeFadesToTargetAndStops() {
        Theme.Scheme orig = Theme.current();
        try {
            Theme.Scheme green = new Theme.Scheme(0x101510, 0x141a14, 0x181f18, 0x0f120f, 0x203020, 0x304030,
                    0x80e080, 0x406040, 0x80c0ff, "test");
            Theme.transitionTo(green, 1000);
            assertTrue(Theme.tick(1100), "mid-fade");
            assertNotEquals(0x80e080, rgbOf(Theme.ACCENT));
            assertFalse(Theme.tick(5000), "fade finished");
            assertEquals(0x80e080, rgbOf(Theme.ACCENT));
            assertEquals(0x141a14, rgbOf(Theme.PANEL));
            assertFalse(Theme.tick(6000), "idle when nothing changes");
        } finally {
            Theme.transitionTo(orig, 0);
            Theme.tick(Long.MAX_VALUE / 2);
        }
    }

    @Test
    void coverSchemeKeepsSurfacesDarkAndAccentReadable() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) img.setRGB(x, y, x < 28 ? 0x0b2a55 : 0xf2c14e);
        Theme.Scheme s = Theme.Scheme.fromCover(img);
        double bgL = bloombox.core.color.Lab.fromRgb(s.bg())[0], accL = bloombox.core.color.Lab.fromRgb(s.accent())[0];
        assertTrue(bgL < 15, "background stays dark: L=" + bgL);
        assertTrue(accL >= 69, "accent readable: L=" + accL);
        assertNotEquals(Theme.Scheme.DEFAULT.accent(), s.accent());
        assertTrue(s.description().startsWith("k-means++"));
    }

    private static int rgbOf(com.googlecode.lanterna.TextColor c) {
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    @Test
    void parsesOsc4RepliesWithAnyChannelWidth() {
        String reply = "\033]4;1;rgb:cdcd/acac/acac\033\\\033]4;4;rgb:b4/bc/c4\007\033]4;5;rgb:f/0/8\033\\\033[?62;4c";
        var m = TerminalPalette.parseOsc4(reply);
        assertEquals(0xcdacac, m.get(1));
        assertEquals(0xb4bcc4, m.get(4));
        assertEquals(0xff0088, m.get(5), "1-digit channels scale to 8 bits");
        assertEquals(3, m.size());
        assertTrue(TerminalPalette.parseOsc4("\033[?62;4c").isEmpty(), "terminal answered only DA1");
    }

    @Test
    void parsesGhosttyShowConfigPalette() {
        String cfg = "theme = compline\nbackground = #1a1d21\npalette = 0=#22262b\npalette = 1=#cdacac\n"
                + "palette = 4=#b4bcc4\npalette = 200=#123456\npalette = bogus\n";
        var m = TerminalPalette.parseGhostty(cfg);
        assertEquals(java.util.Map.of(0, 0x22262b, 1, 0xcdacac, 4, 0xb4bcc4), m, "only slots 0-15, malformed lines skipped");
    }

    @Test
    void terminalSchemePicksMostColourfulSlotNotFixedMagenta() {
        // Ghostty "compline": slot 5 (magenta) is beige; the rose in slot 1 is the colourful one
        var compline = new java.util.HashMap<Integer, Integer>();
        int[] c = {0, 0xcdacac, 0xb8c4b8, 0xd4ccb4, 0xb4bcc4, 0xccc4b4, 0xb4c0c8};
        for (int i = 1; i <= 6; i++) { compline.put(i, c[i]); compline.put(i + 8, c[i]); }
        Theme.Scheme s = Theme.Scheme.fromTerminal(compline, "ghostty palette");
        assertEquals(0xcdacac, s.accent(), "user colour kept as-is");
        assertNotEquals(-1, s.accent2());
        assertTrue(bloombox.core.color.Lab.hueDistance(
                bloombox.core.color.Lab.hue(bloombox.core.color.Lab.fromRgb(s.accent())),
                bloombox.core.color.Lab.hue(bloombox.core.color.Lab.fromRgb(s.accent2()))) >= 45);
        assertEquals(Theme.Scheme.DEFAULT.bg(), s.bg(), "surfaces unchanged");
        assertEquals(Theme.Scheme.DEFAULT, Theme.Scheme.fromTerminal(java.util.Map.of(), "x"), "no palette → built-in");
        var grey = new java.util.HashMap<Integer, Integer>();
        for (int slot : TerminalPalette.ACCENT_SLOTS) grey.put(slot, 0x808080);
        assertEquals(Theme.Scheme.DEFAULT, Theme.Scheme.fromTerminal(grey, "x"), "all-grey palette → built-in");
    }

    @Test
    void greyscaleCoverGetsMonochromeSchemeNotFallback() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) img.setRGB(x, y, ((x + y) & 4) == 0 ? 0xeeeeee : 0x333333);
        Theme.Scheme s = Theme.Scheme.fromCover(img);
        assertTrue(s.description().startsWith("monochrome"), s.description());
        double[] acc = bloombox.core.color.Lab.fromRgb(s.accent());
        assertTrue(bloombox.core.color.Lab.chroma(acc) < 1.5, "silver accent");
        assertTrue(acc[0] >= 79);
        assertNotEquals(Theme.Scheme.DEFAULT.accent(), s.accent());
    }

    @Test
    void seekBarStyleFallsBackForRemovedOrUnknownNames() {
        assertEquals("wave", Waveform.style("bars"), "removed style in an old config");
        assertEquals("wave", Waveform.style(null));
        assertEquals("line", Waveform.style("line"));
        assertArrayEquals(new String[]{"wave", "line"}, Waveform.STYLES);
    }

    @Test
    void transparentBackgroundOnlyReplacesPlainSurfaces() {
        boolean before = Canvas.transparentBackground;
        try {
            Canvas.transparentBackground = false;
            assertEquals(Theme.BG, Canvas.bg(Theme.BG), "theme mode: solid");
            Canvas.transparentBackground = true;
            assertEquals(com.googlecode.lanterna.TextColor.ANSI.DEFAULT, Canvas.bg(Theme.BG));
            assertEquals(com.googlecode.lanterna.TextColor.ANSI.DEFAULT, Canvas.bg(Theme.PANEL));
            assertEquals(com.googlecode.lanterna.TextColor.ANSI.DEFAULT, Canvas.bg(Theme.STRIPE));
            assertEquals(Theme.SELECT, Canvas.bg(Theme.SELECT), "selection stays solid");
            assertEquals(Theme.BAR, Canvas.bg(Theme.BAR), "top and bottom bars stay solid");
            assertEquals(Theme.ACCENT, Canvas.bg(Theme.ACCENT));
            assertNull(Canvas.bg(null));
        } finally {
            Canvas.transparentBackground = before;
        }
    }
}
