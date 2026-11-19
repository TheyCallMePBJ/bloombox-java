package bloombox.tui;

/** Selection + scroll offset for a vertical list of {@code size} rows shown in {@code viewport} rows. */
final class ListState {
    int selected;
    int offset;

    void clamp(int size, int viewport) {
        if (size <= 0) {
            selected = 0;
            offset = 0;
            return;
        }
        selected = Math.max(0, Math.min(selected, size - 1));
        if (viewport <= 0) return;
        if (selected < offset) offset = selected;
        if (selected >= offset + viewport) offset = selected - viewport + 1;
        offset = Math.max(0, Math.min(offset, Math.max(0, size - viewport)));
    }

    void move(int delta, int size) {
        selected = Math.max(0, Math.min(size - 1, selected + delta));
    }

    /** Scroll the view without moving the selection more than needed (mouse wheel). */
    void scroll(int delta, int size, int viewport) {
        offset = Math.max(0, Math.min(Math.max(0, size - viewport), offset + delta));
        if (selected < offset) selected = offset;
        if (selected >= offset + viewport) selected = offset + viewport - 1;
    }

    /** Selects {@code index} and puts it roughly in the middle of the view. */
    void center(int index, int size, int viewport) {
        selected = index;
        offset = Math.max(0, index - viewport / 2);
        clamp(size, viewport);
    }
}
