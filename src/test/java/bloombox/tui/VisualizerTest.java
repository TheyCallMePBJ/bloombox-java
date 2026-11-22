package bloombox.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class VisualizerTest {

    private static TerminalScreen screen(int w, int h) throws IOException {
        TerminalScreen s = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(w, h)));
        s.startScreen();
        return s;
    }

    private static char at(TerminalScreen s, int x, int y) {
        return s.getBackCharacter(x, y).getCharacterString().charAt(0);
    }

    /** Fills the canvas with 'x' (standing in for art / lyrics underneath). */
    private static Canvas underlay(TerminalScreen s, int w, int h) {
        Canvas cv = new Canvas(s.newTextGraphics(), w, h);
        for (int y = 0; y < h; y++) cv.text(0, y, "x".repeat(w), w, Theme.TEXT, Theme.PANEL);
        return cv;
    }

    @Test
    void overlayLeavesEverythingAboveTheBarsUntouched() throws IOException {
        int w = 8, h = 4;
        TerminalScreen s = screen(w, h);
        Canvas cv = underlay(s, w, h);
        int[] bars = new int[4];
        bars[0] = SpectrumVisualizer.MAX / 2; // first bar: half height = bottom two rows
        Visualizer.draw(cv, bars, 0, 0, w, h, true, true);
        // 4 bars over 8 columns: bar 0 is columns 0-1, no gap; rows 2-3 covered, rows 0-1 show the underlay
        for (int x = 0; x < 2; x++) {
            assertEquals('x', at(s, x, 0));
            assertEquals('x', at(s, x, 1));
            assertEquals('█', at(s, x, 2), "no gap: both columns of the bar are filled");
            assertEquals('█', at(s, x, 3));
        }
        // zero bars: nothing drawn at all, not even a floor line
        for (int x = 2; x < w; x++) for (int y = 0; y < h; y++) assertEquals('x', at(s, x, y), "x=" + x + " y=" + y);
    }

    @Test
    void normalModePaintsTheWholeBoxWithAFloorLine() throws IOException {
        int w = 8, h = 3;
        TerminalScreen s = screen(w, h);
        Canvas cv = underlay(s, w, h);
        Visualizer.draw(cv, new int[4], 0, 0, w, h, true);
        for (int x = 0; x < w; x++) {
            assertEquals(' ', at(s, x, 0));
            assertEquals('▁', at(s, x, h - 1), "floor line");
        }
    }
}
