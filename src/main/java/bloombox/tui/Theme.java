package bloombox.tui;

import com.googlecode.lanterna.TextColor;

/** Dark, low-contrast palette with one accent, in the spirit of Tauon's default theme. */
final class Theme {
    private Theme() {}

    static final TextColor BG = rgb(0x16, 0x16, 0x19);
    static final TextColor PANEL = rgb(0x1c, 0x1c, 0x20);
    static final TextColor STRIPE = rgb(0x1f, 0x1f, 0x24);   // alternate-album shading
    static final TextColor BAR = rgb(0x12, 0x12, 0x14);      // top/bottom bars
    static final TextColor SELECT = rgb(0x33, 0x2a, 0x3a);
    static final TextColor BORDER = rgb(0x3a, 0x3a, 0x42);

    static final TextColor TEXT = rgb(0xd8, 0xd8, 0xde);
    static final TextColor DIM = rgb(0x80, 0x80, 0x8c);
    static final TextColor FAINT = rgb(0x55, 0x55, 0x60);
    static final TextColor ACCENT = rgb(0xec, 0x9f, 0xc2);   // bloom pink
    static final TextColor ACCENT_DIM = rgb(0x8a, 0x5d, 0x73);
    static final TextColor ACCENT2 = rgb(0x9f, 0xc8, 0xec);  // secondary (lyric hits, info)
    static final TextColor GOOD = rgb(0x9f, 0xd8, 0xa8);
    static final TextColor WARN = rgb(0xf0, 0xc0, 0x7a);
    static final TextColor ERROR = rgb(0xf0, 0x80, 0x80);

    static TextColor rgb(int r, int g, int b) {
        return new TextColor.RGB(r, g, b);
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
