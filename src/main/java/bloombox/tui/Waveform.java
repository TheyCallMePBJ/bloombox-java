package bloombox.tui;

import com.googlecode.lanterna.TextColor;

/**
 * The seek bar. Styles:
 * <ul>
 *   <li>{@code wave} (default): a fine mirrored waveform drawn with braille dots: each cell
 *       holds 2 sample columns × 4 dot rows; over two rows that gives 4 dots above and 4 below
 *       a centre line, which stays visible through silence.</li>
 *   <li>{@code line}: a solid progress bar centred in the two rows, with a taller playhead.</li>
 * </ul>
 */
final class Waveform {
    private Waveform() {}

    static final String[] STYLES = {"wave", "line"};

    /** A known style name; anything else (e.g. the removed "bars" in an old config) → "wave". */
    static String style(String s) {
        return java.util.Arrays.asList(STYLES).contains(s) ? s : "wave";
    }

    private static final char[] LOWER = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    /** Braille dot bits for (column, row): rows 0..3 top to bottom. */
    private static final int[][] DOT = {{0x01, 0x02, 0x04, 0x40}, {0x08, 0x10, 0x20, 0x80}};

    static void draw(Canvas cv, float[] env, int x, int y, int w, int h, double played, boolean active, String style) {
        if (w <= 0) return;
        switch (style(style)) {
            case "line" -> line(cv, x, y, w, h, played, active);
            default -> braille(cv, env, x, y, w, h, played, active);
        }
    }

    private static TextColor colour(int c, int head, boolean active) {
        if (!active) return Theme.BORDER;
        if (c == head) return Theme.TEXT;
        return c < head ? Theme.ACCENT : Theme.mix(Theme.BORDER, Theme.ACCENT_DIM, 0.45);
    }

    /** Envelope resampled to {@code n} points (bucket mean) with a light 3-tap smoothing. */
    static double[] resample(float[] env, int n) {
        double[] out = new double[n];
        if (env == null || env.length == 0) return out;
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) i * env.length / n);
            int to = Math.max(from + 1, (int) ((long) (i + 1) * env.length / n));
            double sum = 0;
            int cnt = 0;
            for (int j = from; j < to && j < env.length; j++) {
                sum += env[j];
                cnt++;
            }
            out[i] = cnt == 0 ? 0 : sum / cnt;
        }
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double a = out[Math.max(0, i - 1)], b = out[i], c = out[Math.min(n - 1, i + 1)];
            smooth[i] = 0.25 * a + 0.5 * b + 0.25 * c;
        }
        return smooth;
    }

    private static void braille(Canvas cv, float[] env, int x, int y, int w, int h, double played, boolean active) {
        double[] v = resample(env, w * 2);
        int head = (int) Math.round(played * (w - 1));
        for (int c = 0; c < w; c++) {
            int top = 0, bottom = 0;
            for (int sub = 0; sub < 2; sub++) {
                int k = env == null ? 1 : Math.max(1, Math.min(4, (int) Math.round(v[c * 2 + sub] * 4)));
                for (int d = 0; d < k; d++) {
                    top |= DOT[sub][3 - d];   // grows upward from the bottom of the top cell
                    bottom |= DOT[sub][d];    // grows downward from the top of the bottom cell
                }
            }
            TextColor col = colour(c, head, active);
            if (h > 1) {
                cv.ch(x + c, y, (char) (0x2800 + top), col, Theme.BAR);
                cv.ch(x + c, y + 1, (char) (0x2800 + bottom), col, Theme.BAR);
            } else {
                cv.ch(x + c, y, (char) (0x2800 + (top | bottom)), col, Theme.BAR);
            }
        }
    }

    /**
     * A solid progress bar centred on the boundary between the two rows: the top row's lower
     * part and the bottom row's upper part are filled (in eighths of a cell), so the bar sits
     * exactly mid-way and can be thicker than one glyph stroke. The playhead is a taller knob.
     */
    private static void line(Canvas cv, int x, int y, int w, int h, double played, boolean active) {
        int head = (int) Math.round(played * (w - 1));
        if (h < 2) { // not enough room: single-row fallback
            for (int c = 0; c < w; c++) {
                char g = active && c == head ? '●' : c < head && active ? '━' : '─';
                cv.ch(x + c, y, g, colour(c, head, active), Theme.BAR);
            }
            return;
        }
        final int bar = 3, knob = 7; // eighths of a cell above and below the centre line
        for (int c = 0; c < w; c++) {
            boolean isHead = active && c == head;
            int t = isHead ? knob : bar;
            TextColor col = colour(c, head, active);
            cv.ch(x + c, y, LOWER[t], col, Theme.BAR);
            cv.ch(x + c, y + 1, LOWER[8 - t], Theme.BAR, col); // inverted: upper t/8 shows col
        }
    }
}
