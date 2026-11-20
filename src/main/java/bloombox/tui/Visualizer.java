package bloombox.tui;

import com.googlecode.lanterna.TextColor;

/** Draws spectrum bars with 1/8-cell vertical resolution and a vertical colour gradient. */
final class Visualizer {
    private Visualizer() {}

    private static final char[] LOWER = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    static void draw(Canvas cv, int[] bars, int x, int y, int w, int h, boolean playing) {
        if (w <= 0 || h <= 0) return;
        int n = bars == null ? 0 : bars.length;
        int levels = h * 8;
        for (int c = 0; c < w; c++) {
            int v = 0;
            if (n > 0) {
                // spread n bars over w columns, leaving a 1-column gap between bars when there is room
                int from = (int) ((long) c * n / w), to = Math.max(from + 1, (int) ((long) (c + 1) * n / w));
                for (int i = from; i < to && i < n; i++) v = Math.max(v, bars[i]);
                boolean gap = w >= n * 2 && ((long) (c + 1) * n / w) != ((long) c * n / w) && c % 2 == 1;
                if (gap) v = 0;
            }
            int fill = (int) Math.round(v / (double) SpectrumVisualizer.MAX * levels);
            for (int r = 0; r < h; r++) {
                int rowFromBottom = h - 1 - r;
                int cellFill = Math.max(0, Math.min(8, fill - rowFromBottom * 8));
                double t = (rowFromBottom + 0.5) / h;
                TextColor col = playing ? Theme.mix(Theme.ACCENT_DIM, Theme.ACCENT, t)
                        : Theme.mix(Theme.BORDER, Theme.FAINT, t);
                char ch = cellFill == 0 && rowFromBottom == 0 ? '▁' : LOWER[cellFill];
                cv.ch(x + c, y + r, ch, cellFill == 0 && rowFromBottom == 0 ? Theme.STRIPE : col, Theme.PANEL);
            }
        }
    }
}
