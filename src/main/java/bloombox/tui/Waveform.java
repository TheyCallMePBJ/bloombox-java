package bloombox.tui;

import com.googlecode.lanterna.TextColor;

/**
 * Two-row mirrored waveform seek bar. Each column has 8 sub-levels per half:
 * the top row grows upward with lower-block glyphs; the bottom row grows downward
 * by drawing the complementary lower block in the background colour over a
 * coloured cell (terminals have no "upper 3/8 block" glyphs).
 */
final class Waveform {
    private Waveform() {}

    private static final char[] LOWER = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    static void draw(Canvas cv, float[] env, int x, int y, int w, int h, double played, boolean active) {
        if (w <= 0) return;
        int head = (int) Math.round(played * (w - 1));
        for (int c = 0; c < w; c++) {
            double v;
            if (env == null || env.length == 0) {
                v = active ? 0.12 : 0.06;
            } else {
                int from = (int) ((long) c * env.length / w);
                int to = Math.max(from + 1, (int) ((long) (c + 1) * env.length / w));
                float m = 0;
                for (int i = from; i < to && i < env.length; i++) m = Math.max(m, env[i]);
                v = m;
            }
            int level = Math.max(1, (int) Math.round(v * 8)); // 1..8 per half
            TextColor col = !active ? Theme.BORDER
                    : c == head ? Theme.TEXT
                    : c < head ? Theme.ACCENT
                    : Theme.mix(Theme.BORDER, Theme.ACCENT_DIM, 0.35);
            // top half: grows up from the middle
            cv.ch(x + c, y, LOWER[level], col, Theme.BAR);
            // bottom half: coloured cell with the uncovered lower part painted back in BAR colour
            if (h > 1) cv.ch(x + c, y + 1, LOWER[8 - level], Theme.BAR, col);
        }
    }
}
