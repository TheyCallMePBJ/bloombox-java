package bloombox.tui;

import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lanterna's UnixTerminal writes strings one {@code char} at a time, which splits characters
 * outside the BMP (surrogate pairs) and turns them into "?". The kitty image placeholder
 * U+10EEEE is such a character, so strings that contain surrogates are encoded whole.
 */
final class Utf8UnixTerminal extends UnixTerminal {

    Utf8UnixTerminal(InputStream in, OutputStream out, UnixLikeTerminal.CtrlCBehaviour ctrlC) throws IOException {
        super(in, out, StandardCharsets.UTF_8, ctrlC);
    }

    @Override
    public void putString(String s) throws IOException {
        if (hasSurrogate(s)) writeToTerminal(s.getBytes(StandardCharsets.UTF_8));
        else super.putString(s);
    }

    static boolean hasSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isSurrogate(s.charAt(i))) return true;
        return false;
    }
}
