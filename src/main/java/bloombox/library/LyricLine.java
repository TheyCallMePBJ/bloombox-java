package bloombox.library;

/**
 * One displayable lyric line.
 *
 * @param timeMs start time in milliseconds, or {@link #UNSYNCED} for plain-text lyrics
 * @param text   line text with all LRC tags removed (may be empty for instrumental breaks)
 */
public record LyricLine(long timeMs, String text) {
    public static final long UNSYNCED = -1;

    public boolean isSynced() {
        return timeMs != UNSYNCED;
    }
}
