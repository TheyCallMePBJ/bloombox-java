package bloombox.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** Single-line editable text with a cursor (code-point aware). */
final class TextInput {

    private final StringBuilder buf = new StringBuilder();
    private int cursor; // index into buf (UTF-16), always on a code-point boundary

    String text() {
        return buf.toString();
    }

    int cursor() {
        return cursor;
    }

    void set(String s) {
        buf.setLength(0);
        buf.append(s == null ? "" : s);
        cursor = buf.length();
    }

    void clear() {
        set("");
    }

    boolean isEmpty() {
        return buf.length() == 0;
    }

    /** Applies an editing key. Returns true if the text changed. */
    boolean handle(KeyStroke k) {
        KeyType t = k.getKeyType();
        if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
            buf.insert(cursor, k.getCharacter().charValue());
            cursor++;
            return true;
        }
        if (t == KeyType.Character && k.isCtrlDown()) {
            switch (Character.toLowerCase(k.getCharacter())) {
                case 'u' -> { // delete to start
                    if (cursor == 0) return false;
                    buf.delete(0, cursor);
                    cursor = 0;
                    return true;
                }
                case 'w' -> { // delete previous word
                    if (cursor == 0) return false;
                    int i = cursor;
                    while (i > 0 && buf.charAt(i - 1) == ' ') i--;
                    while (i > 0 && buf.charAt(i - 1) != ' ') i--;
                    buf.delete(i, cursor);
                    cursor = i;
                    return true;
                }
                case 'a' -> { cursor = 0; return false; }
                case 'e' -> { cursor = buf.length(); return false; }
                default -> { return false; }
            }
        }
        switch (t) {
            case Backspace -> {
                if (cursor == 0) return false;
                int prev = buf.offsetByCodePoints(cursor, -1);
                buf.delete(prev, cursor);
                cursor = prev;
                return true;
            }
            case Delete -> {
                if (cursor >= buf.length()) return false;
                buf.delete(cursor, buf.offsetByCodePoints(cursor, 1));
                return true;
            }
            case ArrowLeft -> { if (cursor > 0) cursor = buf.offsetByCodePoints(cursor, -1); }
            case ArrowRight -> { if (cursor < buf.length()) cursor = buf.offsetByCodePoints(cursor, 1); }
            case Home -> cursor = 0;
            case End -> cursor = buf.length();
            default -> { }
        }
        return false;
    }
}
