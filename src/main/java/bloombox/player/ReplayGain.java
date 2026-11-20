package bloombox.player;

import java.util.List;
import java.util.Locale;

/**
 * ReplayGain settings: which gain tag to apply and a pre-amp on top of it.
 *
 * <p>mpv does the actual work: it reads REPLAYGAIN_TRACK_GAIN / _ALBUM_GAIN (and the peak
 * tags, to avoid clipping) from the file, and multiplies the signal by
 * 10^((gain + preamp) / 20). In album mode a file without album gain falls back to its track
 * gain. The pre-amp only affects files that carry ReplayGain tags; untagged files play at
 * their normal level.
 *
 * @param mode     off, track or album
 * @param preampDb extra gain in dB, clamped to [{@link #MIN_PREAMP_DB}, {@link #MAX_PREAMP_DB}]
 */
public record ReplayGain(Mode mode, double preampDb) {

    public static final double MIN_PREAMP_DB = -12, MAX_PREAMP_DB = 12;
    public static final ReplayGain DISABLED = new ReplayGain(Mode.OFF, 0);

    public enum Mode {
        OFF("no"), TRACK("track"), ALBUM("album");

        /** The value of mpv's {@code replaygain} option. */
        public final String mpvValue;

        Mode(String mpvValue) {
            this.mpvValue = mpvValue;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Lenient parse of a settings value; unknown or missing → OFF. */
        public static Mode parse(String s) {
            if (s == null) return OFF;
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "track" -> TRACK;
                case "album" -> ALBUM;
                default -> OFF;
            };
        }
    }

    public ReplayGain {
        if (mode == null) mode = Mode.OFF;
        if (Double.isNaN(preampDb)) preampDb = 0;
        preampDb = Math.max(MIN_PREAMP_DB, Math.min(MAX_PREAMP_DB, preampDb));
    }

    public ReplayGain withMode(Mode m) {
        return new ReplayGain(m, preampDb);
    }

    public ReplayGain withPreamp(double db) {
        return new ReplayGain(mode, db);
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    /** mpv command-line options. {@code Double.toString} is locale-independent ("1.5", never "1,5"). */
    public List<String> mpvArgs() {
        return List.of("--replaygain=" + mode.mpvValue, "--replaygain-preamp=" + preampDb);
    }

    /** e.g. "+3 dB", "-1.5 dB", "0 dB". */
    public static String formatDb(double db) {
        String n = db == Math.rint(db) ? Long.toString((long) Math.abs(db)) : String.format(Locale.ROOT, "%.1f", Math.abs(db));
        return (db > 0 ? "+" : db < 0 ? "-" : "") + n + " dB";
    }
}
