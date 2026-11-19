package bloombox.tui;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Real images via the kitty terminal graphics protocol (kitty, Ghostty, WezTerm).
 *
 * <p>Lanterna owns the text grid, so images are managed beside it: the renderer
 * calls {@link #show}/{@link #hide} while drawing a frame to record the desired
 * placement, and {@link #flush} runs after Lanterna's refresh to emit only the
 * changes. Escape sequences are wrapped in DECSC/DECRC (save/restore cursor) so
 * Lanterna's idea of the cursor position stays correct. Each image is transmitted
 * once (PNG, base64, 4 KiB chunks) and then re-placed by id.
 */
final class KittyImages {

    private static final int PLACEMENT = 1;

    private final boolean enabled;
    private final OutputStream out;
    private final Set<Integer> transmitted = new HashSet<>();

    // desired state for this frame
    private ArtService.Art want;
    private int wx, wy, ww, wh;
    // what the terminal currently shows
    private ArtService.Art shown;
    private int sx, sy, sw, sh;

    private KittyImages(boolean enabled, OutputStream out) {
        this.enabled = enabled;
        this.out = out;
    }

    /** @param mode "auto" | "kitty" | "blocks" | "off" */
    static KittyImages detect(String mode) {
        boolean on = switch (mode == null ? "auto" : mode) {
            case "kitty" -> true;
            case "auto" -> terminalSupportsKitty();
            default -> false;
        };
        return new KittyImages(on, new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false));
    }

    static boolean terminalSupportsKitty() {
        String term = env("TERM"), prog = env("TERM_PROGRAM");
        if (!env("TMUX").isEmpty() || term.startsWith("screen") || term.startsWith("tmux")) return false;
        return term.contains("kitty") || term.contains("ghostty") || !env("KITTY_WINDOW_ID").isEmpty()
                || prog.equals("ghostty") || prog.equals("wezterm") || !env("GHOSTTY_RESOURCES_DIR").isEmpty();
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v.toLowerCase(Locale.ROOT);
    }

    boolean enabled() {
        return enabled;
    }

    void show(ArtService.Art art, int x, int y, int w, int h) {
        want = art;
        wx = x;
        wy = y;
        ww = w;
        wh = h;
    }

    void hide() {
        want = null;
    }

    /** Applies the desired state. {@code full} = the screen was cleared (placements are gone). */
    void flush(boolean full) {
        if (!enabled) return;
        boolean same = !full && want == shown && (want == null || (wx == sx && wy == sy && ww == sw && wh == sh));
        if (same) return;
        StringBuilder sb = new StringBuilder();
        if (shown != null) sb.append(apc("a=d,d=i,i=" + shown.id() + ",q=2")); // delete placement, keep data
        if (want != null && want.png() != null) {
            if (transmitted.add(want.id())) transmit(sb, want);
            sb.append("\0337")                                       // save cursor
              .append("\033[").append(wy + 1).append(';').append(wx + 1).append('H')
              .append(apc("a=p,i=" + want.id() + ",p=" + PLACEMENT + ",c=" + ww + ",r=" + wh + ",C=1,z=-1,q=2"))
              .append("\0338");                                      // restore cursor
        }
        write(sb);
        shown = want;
        sx = wx;
        sy = wy;
        sw = ww;
        sh = wh;
    }

    private static void transmit(StringBuilder sb, ArtService.Art art) {
        String b64 = Base64.getEncoder().encodeToString(art.png());
        int chunk = 4096;
        for (int i = 0; i < b64.length(); i += chunk) {
            boolean first = i == 0, last = i + chunk >= b64.length();
            String keys = (first ? "a=t,f=100,i=" + art.id() + ",q=2," : "") + "m=" + (last ? 0 : 1);
            sb.append("\033_G").append(keys).append(';')
              .append(b64, i, Math.min(b64.length(), i + chunk)).append("\033\\");
        }
    }

    /** Removes every image we created (on exit or when switching modes). */
    void clearAll() {
        if (!enabled) return;
        write(new StringBuilder(apc("a=d,d=A,q=2")));
        transmitted.clear();
        shown = null;
    }

    private static String apc(String keys) {
        return "\033_G" + keys + "\033\\";
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
}
