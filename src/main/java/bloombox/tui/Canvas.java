package bloombox.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/** Thin drawing layer over Lanterna's TextGraphics with width-aware (CJK-safe) text. */
final class Canvas {

    final TextGraphics g;
    final int width, height;

    Canvas(TextGraphics g, int width, int height) {
        this.g = g;
        this.width = width;
        this.height = height;
    }

    void fill(int x, int y, int w, int h, TextColor bg) {
        if (w <= 0 || h <= 0) return;
        g.setBackgroundColor(bg);
        g.fillRectangle(new TerminalPosition(x, y), new TerminalSize(w, h), ' ');
    }

    /** Draws {@code s} clipped to {@code maxWidth} columns (ellipsis if cut). Returns columns used. */
    int text(int x, int y, String s, int maxWidth, TextColor fg, TextColor bg, SGR... sgr) {
        if (maxWidth <= 0 || s == null || s.isEmpty() || y < 0 || y >= height) return 0;
        String fitted = fit(s, maxWidth);
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        g.clearModifiers();
        if (sgr.length > 0) g.enableModifiers(sgr);
        g.putString(x, y, fitted);
        g.clearModifiers();
        return width(fitted);
    }

    /** Right-aligned text ending at column {@code right} (exclusive). */
    int textRight(int right, int y, String s, int maxWidth, TextColor fg, TextColor bg, SGR... sgr) {
        String fitted = fit(s, maxWidth);
        int w = width(fitted);
        return text(right - w, y, fitted, w, fg, bg, sgr);
    }

    void ch(int x, int y, char c, TextColor fg, TextColor bg) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        g.setCharacter(x, y, new TextCharacter(c, fg, bg));
    }

    void hline(int x, int y, int w, char c, TextColor fg, TextColor bg) {
        for (int i = 0; i < w; i++) ch(x + i, y, c, fg, bg);
    }

    /** Box border with an optional title. */
    void box(int x, int y, int w, int h, String title, TextColor border, TextColor bg) {
        fill(x, y, w, h, bg);
        hline(x + 1, y, w - 2, '─', border, bg);
        hline(x + 1, y + h - 1, w - 2, '─', border, bg);
        for (int j = 1; j < h - 1; j++) {
            ch(x, y + j, '│', border, bg);
            ch(x + w - 1, y + j, '│', border, bg);
        }
        ch(x, y, '╭', border, bg);
        ch(x + w - 1, y, '╮', border, bg);
        ch(x, y + h - 1, '╰', border, bg);
        ch(x + w - 1, y + h - 1, '╯', border, bg);
        if (title != null && !title.isEmpty()) text(x + 2, y, " " + title + " ", w - 4, Theme.ACCENT, bg, SGR.BOLD);
    }

    static int width(String s) {
        return TerminalTextUtils.getColumnWidth(s);
    }

    /** Truncates to at most {@code max} columns, adding "…" when something was cut. */
    static String fit(String s, int max) {
        if (max <= 0) return "";
        if (width(s) <= max) return s;
        if (max == 1) return "…";
        return TerminalTextUtils.fitString(s, max - 1) + "…";
    }

    /** Pads/truncates to exactly {@code w} columns. */
    static String pad(String s, int w) {
        String f = fit(s, w);
        int rem = w - width(f);
        return rem > 0 ? f + " ".repeat(rem) : f;
    }

    static String time(double seconds) {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) return "-:--";
        long s = (long) seconds;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }
}
