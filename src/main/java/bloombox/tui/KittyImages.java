package bloombox.tui;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Real images through the kitty graphics protocol using <b>Unicode placeholders</b>
 * (supported by kitty and Ghostty, also inside tmux).
 *
 * <p>The image is transmitted once out-of-band (PNG, base64, 4 KiB chunks) and given a
 * <em>virtual</em> placement of c×r cells. It then appears wherever the grid contains the
 * placeholder character U+10EEEE whose foreground colour encodes the image id and whose
 * combining diacritics encode the cell's row and column inside the image. Because these
 * are ordinary text cells, Lanterna diffs/redraws them like any other text, overlays
 * cover them naturally, and tmux keeps them in the right pane position.
 *
 * <p>Inside tmux the APC sequences are wrapped in tmux's DCS passthrough, which requires
 * {@code allow-passthrough}; we enable it for our pane only and restore it on exit.
 */
final class KittyImages {

    private static final String PLACEHOLDER = new String(Character.toChars(0x10EEEE));
    private static final int PLACEMENT_ID = 1;

    private final boolean enabled;
    private final boolean tmux;
    private final String reason;
    private final Runnable restoreTmux;
    private final OutputStream out = new FileOutputStream(FileDescriptor.out);
    private final Set<Integer> transmitted = new HashSet<>();
    private final Map<Integer, Long> placedSize = new HashMap<>();

    private KittyImages(boolean enabled, boolean tmux, String reason, Runnable restoreTmux) {
        this.enabled = enabled;
        this.tmux = tmux;
        this.reason = reason;
        this.restoreTmux = restoreTmux;
    }

    /** @param mode "auto" | "kitty" | "blocks" | "off" */
    static KittyImages detect(String mode) {
        boolean inTmux = !env("TMUX").isEmpty();
        if ("blocks".equals(mode) || "off".equals(mode)) return new KittyImages(false, inTmux, mode, () -> { });
        String outerTerm = inTmux ? tmuxOuterTerm() : env("TERM");
        boolean capable = outerTerm.contains("kitty") || outerTerm.contains("ghostty")
                || (!inTmux && (!env("KITTY_WINDOW_ID").isEmpty() || env("TERM_PROGRAM").equals("ghostty")));
        if (!capable && !"kitty".equals(mode)) {
            return new KittyImages(false, inTmux, "terminal '" + outerTerm + "' has no placeholder support", () -> { });
        }
        if (inTmux) {
            Runnable restore = enableTmuxPassthrough();
            if (restore == null) {
                return new KittyImages(false, true, "tmux passthrough unavailable", () -> { });
            }
            return new KittyImages(true, true, "kitty graphics via tmux passthrough", restore);
        }
        return new KittyImages(true, false, "kitty graphics", () -> { });
    }

    boolean enabled() {
        return enabled;
    }

    String reason() {
        return reason;
    }

    /**
     * Draws {@code art} fitted (aspect-preserving, centred) into the w×h cell box by
     * writing placeholder cells into the canvas. Transmits / re-places as needed.
     */
    void draw(Canvas cv, ArtService.Art art, int x, int y, int w, int h) {
        if (!enabled || art.png() == null || w <= 0 || h <= 0) return;
        // a terminal cell is roughly twice as tall as it is wide
        double aspect = art.image().getHeight() / (double) art.image().getWidth();
        int cols = w, rows = (int) Math.round(w * aspect / 2.0);
        if (rows > h) {
            rows = h;
            cols = (int) Math.round(h * 2.0 / aspect);
        }
        cols = Math.max(1, Math.min(cols, Math.min(w, KittyDiacritics.CODEPOINTS.length)));
        rows = Math.max(1, Math.min(rows, Math.min(h, KittyDiacritics.CODEPOINTS.length)));
        ensurePlaced(art, cols, rows);

        int id = art.id();
        TextColor fg = new TextColor.RGB((id >> 16) & 0xff, (id >> 8) & 0xff, id & 0xff);
        int ox = x + (w - cols) / 2, oy = y + (h - rows) / 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String cell = PLACEHOLDER
                        + new String(Character.toChars(KittyDiacritics.CODEPOINTS[r]))
                        + new String(Character.toChars(KittyDiacritics.CODEPOINTS[c]));
                cv.g.setCharacter(ox + c, oy + r, new Placeholder(cell, fg, Theme.PANEL));
            }
        }
    }

    private void ensurePlaced(ArtService.Art art, int cols, int rows) {
        StringBuilder sb = new StringBuilder();
        if (transmitted.add(art.id())) {
            String b64 = Base64.getEncoder().encodeToString(art.png());
            int chunk = 4096;
            for (int i = 0; i < b64.length(); i += chunk) {
                boolean first = i == 0, last = i + chunk >= b64.length();
                String keys = (first ? "a=t,f=100,i=" + art.id() + ",q=2," : "") + "m=" + (last ? 0 : 1);
                sb.append(wrap("\033_G" + keys + ";" + b64.substring(i, Math.min(b64.length(), i + chunk)) + "\033\\"));
            }
        }
        long size = ((long) cols << 32) | rows;
        Long prev = placedSize.put(art.id(), size);
        if (prev == null || prev != size) {
            sb.append(wrap("\033_Ga=p,U=1,i=" + art.id() + ",p=" + PLACEMENT_ID
                    + ",c=" + cols + ",r=" + rows + ",q=2\033\\"));
        }
        write(sb);
    }

    /** Removes our images from the terminal and restores tmux settings (on exit / mode switch). */
    void close() {
        if (enabled) write(new StringBuilder(wrap("\033_Ga=d,d=A,q=2\033\\")));
        transmitted.clear();
        placedSize.clear();
        restoreTmux.run();
    }

    private String wrap(String seq) {
        return tmux ? "\033Ptmux;" + seq.replace("\033", "\033\033") + "\033\\" : seq;
    }

    private void write(CharSequence s) {
        if (s.length() == 0) return;
        try {
            out.write(s.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // terminal gone
        }
    }

    /** A grid cell holding one placeholder grapheme. Lanterna would treat it as an emoji (2 columns); it is 1. */
    static final class Placeholder extends TextCharacter {
        Placeholder(String grapheme, TextColor fg, TextColor bg) {
            super(TextCharacter.fromString(grapheme, fg, bg)[0]);
        }

        @Override
        public boolean isDoubleWidth() {
            return false;
        }
    }

    // ---- environment / tmux helpers ---------------------------------------------

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v.toLowerCase(Locale.ROOT);
    }

    /** TERM of the terminal the user actually sees (tmux's client terminal when inside tmux), lowercase. */
    static String outerTerminal() {
        if (env("TMUX").isEmpty()) {
            return env("TERM_PROGRAM").equals("ghostty") ? "xterm-ghostty" : env("TERM");
        }
        return tmuxOuterTerm();
    }

    private static String tmuxOuterTerm() {
        String t = run("tmux", "display-message", "-p", "#{client_termname}");
        return t == null ? "" : t.toLowerCase(Locale.ROOT);
    }

    /** Ensures allow-passthrough for our pane. Returns a restore action, or null if impossible. */
    private static Runnable enableTmuxPassthrough() {
        String pane = System.getenv("TMUX_PANE");
        if (pane == null || pane.isBlank()) return null;
        String effective = run("tmux", "show-options", "-Apv", "-t", pane, "allow-passthrough");
        if (effective != null && (effective.startsWith("on") || effective.startsWith("all"))) return () -> { };
        String paneLocal = run("tmux", "show-options", "-pv", "-t", pane, "allow-passthrough");
        if (run("tmux", "set-option", "-p", "-t", pane, "allow-passthrough", "on") == null) return null;
        return () -> {
            if (paneLocal == null || paneLocal.isEmpty()) run("tmux", "set-option", "-pu", "-t", pane, "allow-passthrough");
            else run("tmux", "set-option", "-p", "-t", pane, "allow-passthrough", paneLocal);
        };
    }

    /** Runs a command; returns trimmed stdout, or null on failure. */
    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return outText;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
