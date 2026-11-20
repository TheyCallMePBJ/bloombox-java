package bloombox.tui;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the terminal's ANSI colour palette, used as the fallback accent when no album cover
 * colours apply. Must run <em>before</em> Lanterna takes over the terminal.
 *
 * <ol>
 *   <li>OSC 4 query ({@code ESC ] 4 ; n ; ? ST}) answered by kitty, Ghostty, foot, WezTerm,
 *       xterm, …; followed by a DA1 query that every terminal answers, used as an end
 *       marker so we never wait for a timeout on terminals that ignore OSC 4.</li>
 *   <li>tmux does not forward OSC 4, so inside tmux with Ghostty as the outer terminal, the
 *       palette is read from {@code ghostty +show-config}.</li>
 * </ol>
 */
final class TerminalPalette {
    private TerminalPalette() {}

    /** The ANSI colours worth considering for an accent: normal and bright red..cyan. */
    static final int[] ACCENT_SLOTS = {1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14};

    private static final Pattern OSC4_REPLY = Pattern.compile(
            "\\]4;(\\d+);rgb:([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})");
    private static final Pattern GHOSTTY_LINE = Pattern.compile("^palette\\s*=\\s*(\\d+)\\s*=\\s*#([0-9a-fA-F]{6})\\s*$");

    /** Palette index → 0xRRGGBB plus where it came from; empty map if unknown. */
    record Result(Map<Integer, Integer> colours, String source) {}

    static Result detect() {
        Map<Integer, Integer> osc = queryOsc(ACCENT_SLOTS, 400);
        if (!osc.isEmpty()) return new Result(osc, "terminal palette");
        if (KittyImages.outerTerminal().contains("ghostty")) {
            Map<Integer, Integer> g = ghosttyConfig();
            if (!g.isEmpty()) return new Result(g, "ghostty palette");
        }
        return new Result(Map.of(), "");
    }

    // ---- OSC 4

    static Map<Integer, Integer> queryOsc(int[] slots, long timeoutMs) {
        String saved = stty("-g");
        if (saved == null) return Map.of(); // not a tty
        StringBuilder reply = new StringBuilder();
        try {
            if (stty("raw", "-echo", "min", "0", "time", "1") == null) return Map.of();
            StringBuilder q = new StringBuilder();
            for (int s : slots) q.append("\033]4;").append(s).append(";?\033\\");
            q.append("\033[c"); // DA1: the end marker
            @SuppressWarnings("resource") // wraps stdout: must not be closed
            FileOutputStream out = new FileOutputStream(FileDescriptor.out);
            out.write(q.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = System.in;
            byte[] buf = new byte[512];
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline && !hasDa1(reply)) {
                int n = in.read(buf); // returns after ≤100 ms (VTIME=1) even without data
                if (n > 0) reply.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            }
        } catch (IOException e) {
            return Map.of();
        } finally {
            stty(saved);
        }
        return parseOsc4(reply.toString());
    }

    private static boolean hasDa1(CharSequence s) {
        return Pattern.compile("\033\\[\\?[0-9;]*c").matcher(s).find();
    }

    /** Parses all OSC 4 replies; each channel is 1–4 hex digits, scaled to 8 bits. */
    static Map<Integer, Integer> parseOsc4(String s) {
        Map<Integer, Integer> out = new TreeMap<>();
        Matcher m = OSC4_REPLY.matcher(s);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            out.put(idx, (channel(m.group(2)) << 16) | (channel(m.group(3)) << 8) | channel(m.group(4)));
        }
        return out;
    }

    private static int channel(String hex) {
        int max = (1 << (4 * hex.length())) - 1;
        return (int) Math.round(Integer.parseInt(hex, 16) * 255.0 / max);
    }

    // ---- ghostty

    static Map<Integer, Integer> ghosttyConfig() {
        String out = run(3, "ghostty", "+show-config");
        return out == null ? Map.of() : parseGhostty(out);
    }

    static Map<Integer, Integer> parseGhostty(String config) {
        Map<Integer, Integer> out = new TreeMap<>();
        for (String line : config.split("\n")) {
            Matcher m = GHOSTTY_LINE.matcher(line.strip());
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx < 16) out.put(idx, Integer.parseInt(m.group(2), 16));
            }
        }
        return out;
    }

    // ---- helpers

    /** Runs stty on the controlling terminal; returns its output, or null on failure. */
    private static String stty(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "stty";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectInput(new File("/dev/tty"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0 ? o : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String run(int timeoutSec, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? o : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
