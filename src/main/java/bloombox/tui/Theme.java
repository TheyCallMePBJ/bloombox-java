package bloombox.tui;

import bloombox.core.color.Lab;
import bloombox.core.color.PaletteExtractor;
import com.googlecode.lanterna.TextColor;

import java.awt.image.BufferedImage;

/**
 * Dark, low-contrast palette with one accent, in the spirit of Tauon's default theme.
 *
 * <p>The surface and accent colours are not constants: with {@code ui.theme=cover} they follow
 * the playing track's album art ({@link Scheme#fromCover}) and cross-fade when it changes
 * ({@link #transitionTo} / {@link #tick}). Text greys and status colours stay fixed so that
 * readability never depends on the cover. Only the UI thread reads or writes these fields.
 */
final class Theme {
    private Theme() {}

    // surfaces (tinted towards the cover's dominant hue)
    static TextColor BG, PANEL, STRIPE, BAR, SELECT, BORDER;
    // accents (taken from the cover)
    static TextColor ACCENT, ACCENT_DIM, ACCENT2;

    // fixed
    static final TextColor TEXT = rgb(0xd8, 0xd8, 0xde);
    static final TextColor DIM = rgb(0x80, 0x80, 0x8c);
    static final TextColor FAINT = rgb(0x55, 0x55, 0x60);
    static final TextColor GOOD = rgb(0x9f, 0xd8, 0xa8);
    static final TextColor WARN = rgb(0xf0, 0xc0, 0x7a);
    static final TextColor ERROR = rgb(0xf0, 0x80, 0x80);

    /** One full set of themeable colours, as 0xRRGGBB. */
    record Scheme(int bg, int panel, int stripe, int bar, int select, int border,
                  int accent, int accentDim, int accent2, String description) {

        static final Scheme DEFAULT = new Scheme(0x161619, 0x1c1c20, 0x1f1f24, 0x121214, 0x332a3a, 0x3a3a42,
                0xec9fc2 /* bloom pink */, 0x8a5d73, 0x9fc8ec, "default colours");

        /** Samples the cover, clusters it (k-means++ in Lab) and derives a readable scheme. */
        static Scheme fromCover(BufferedImage img) {
            int[] px = sample(img, 64);
            PaletteExtractor.Palette p = PaletteExtractor.extract(px, java.util.Arrays.hashCode(px));
            String work = "k-means++: " + p.clustering().swatches().size() + " clusters, "
                    + p.clustering().iterations() + " iterations";
            if (!p.vivid()) return withDescription(DEFAULT, "grey cover, default colours · " + work);
            return fromPalette(p, work);
        }

        static Scheme fromPalette(PaletteExtractor.Palette p, String description) {
            int accent = PaletteExtractor.readable(p.accent(), 70, 82, 32, 62);
            int accent2 = p.secondary() >= 0
                    ? PaletteExtractor.readable(p.secondary(), 74, 84, 24, 50)
                    : Lab.fromLch(80, 34, (Lab.hue(Lab.fromRgb(accent)) + 150) % 360); // split-complement
            double hue = p.baseHue();
            double c = Math.min(p.baseChroma(), 40) / 40.0; // how much tint the cover can carry
            Scheme d = DEFAULT;
            int panel = tint(d.panel(), hue, 3 + 5 * c);
            return new Scheme(
                    tint(d.bg(), hue, 2.5 + 4 * c),
                    panel,
                    tint(d.stripe(), hue, 3.5 + 5 * c),
                    tint(d.bar(), hue, 2 + 3.5 * c),
                    mixRgb(panel, accent, 0.16),
                    tint(d.border(), hue, 5 + 6 * c),
                    accent,
                    mixRgb(panel, accent, 0.55),
                    accent2,
                    description);
        }

        private static Scheme withDescription(Scheme s, String d) {
            return new Scheme(s.bg, s.panel, s.stripe, s.bar, s.select, s.border, s.accent, s.accentDim, s.accent2, d);
        }

        int[] values() {
            return new int[]{bg, panel, stripe, bar, select, border, accent, accentDim, accent2};
        }
    }

    // ---- transitions

    private static final long FADE_MS = 450;
    private static int[] from = Scheme.DEFAULT.values(), shown = from;
    private static Scheme target = Scheme.DEFAULT;
    private static long fadeStart = 0;

    static {
        apply(shown);
    }

    static Scheme current() {
        return target;
    }

    /** Starts a cross-fade from whatever is on screen now to {@code next}. */
    static void transitionTo(Scheme next, long nowMs) {
        if (next.equals(target)) return;
        from = shown;
        target = next;
        fadeStart = nowMs;
    }

    /** Advances the fade; returns true while it is still running (caller should keep redrawing). */
    static boolean tick(long nowMs) {
        int[] to = target.values();
        if (shown == to || java.util.Arrays.equals(shown, to)) return false;
        double t = Math.min(1, (nowMs - fadeStart) / (double) FADE_MS);
        double e = t * t * (3 - 2 * t); // smoothstep
        int[] v = new int[to.length];
        for (int i = 0; i < v.length; i++) v[i] = mixRgb(from[i], to[i], e);
        shown = t >= 1 ? to : v;
        apply(shown);
        return t < 1;
    }

    private static void apply(int[] v) {
        BG = rgb(v[0]);
        PANEL = rgb(v[1]);
        STRIPE = rgb(v[2]);
        BAR = rgb(v[3]);
        SELECT = rgb(v[4]);
        BORDER = rgb(v[5]);
        ACCENT = rgb(v[6]);
        ACCENT_DIM = rgb(v[7]);
        ACCENT2 = rgb(v[8]);
    }

    // ---- helpers

    /** Keeps the colour's lightness, gives it {@code chroma} at {@code hue} (LCh). */
    static int tint(int rgb, double hue, double chroma) {
        return Lab.fromLch(Lab.fromRgb(rgb)[0], chroma, hue);
    }

    /** Evenly spaced pixel sample (at most max×max), as 0xRRGGBB; transparent pixels skipped. */
    static int[] sample(BufferedImage img, int max) {
        int w = img.getWidth(), h = img.getHeight();
        int sx = Math.max(1, (w + max - 1) / max), sy = Math.max(1, (h + max - 1) / max);
        int[] out = new int[((w + sx - 1) / sx) * ((h + sy - 1) / sy)];
        int n = 0;
        for (int y = sy / 2; y < h; y += sy) {
            for (int x = sx / 2; x < w; x += sx) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 128) continue;
                out[n++] = argb & 0xffffff;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static TextColor rgb(int r, int g, int b) {
        return new TextColor.RGB(r, g, b);
    }

    static TextColor rgb(int v) {
        return new TextColor.RGB((v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff);
    }

    static int mixRgb(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * t);
        int g = (int) Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * t);
        int bl = (int) Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Linear blend between two RGB colours, t in [0,1]. */
    static TextColor mix(TextColor a, TextColor b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return rgb(r, g, bl);
    }
}
