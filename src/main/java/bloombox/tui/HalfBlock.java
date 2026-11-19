package bloombox.tui;

import com.googlecode.lanterna.TextColor;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Renders an image with "▀" glyphs: each cell shows two vertically stacked pixels
 * (foreground = top, background = bottom) in 24-bit colour. Works in any truecolor
 * terminal; used when the kitty graphics protocol is unavailable.
 */
final class HalfBlock {
    private HalfBlock() {}

    private record Key(int w, int h) {}

    // scaled copies per source image and cell size, so we don't rescale every frame
    private static final Map<BufferedImage, Map<Key, BufferedImage>> SCALED = new WeakHashMap<>();

    static void draw(Canvas cv, BufferedImage img, int x, int y, int w, int h) {
        if (img == null || w <= 0 || h <= 0) return;
        // fit preserving aspect: a cell is ~1 px wide and 2 px tall in this mapping
        double s = Math.min((double) w / img.getWidth(), (double) (h * 2) / img.getHeight());
        int pw = Math.max(1, (int) Math.round(img.getWidth() * s));
        int ph = Math.max(2, (int) Math.round(img.getHeight() * s));
        BufferedImage px = SCALED.computeIfAbsent(img, k -> new WeakHashMap<>())
                .computeIfAbsent(new Key(pw, ph), k -> exact(img, pw, ph));
        int ox = x + (w - px.getWidth()) / 2;
        int oy = y + (h - (px.getHeight() + 1) / 2) / 2;
        for (int row = 0; row * 2 < px.getHeight(); row++) {
            for (int col = 0; col < px.getWidth(); col++) {
                TextColor top = color(px.getRGB(col, row * 2));
                TextColor bottom = row * 2 + 1 < px.getHeight() ? color(px.getRGB(col, row * 2 + 1)) : Theme.PANEL;
                cv.ch(ox + col, oy + row, '▀', top, bottom);
            }
        }
    }

    private static BufferedImage exact(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static TextColor color(int rgb) {
        return new TextColor.RGB((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }
}
