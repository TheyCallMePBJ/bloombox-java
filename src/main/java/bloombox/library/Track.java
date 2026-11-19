package bloombox.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * Immutable metadata for one audio file in the library.
 *
 * @param id         dense integer id (index into {@link Library#tracks()}); used in posting lists.
 *                   -1 until the track is placed in a {@link Library}.
 * @param path       absolute path to the audio file
 * @param title      track title (falls back to the file name)
 * @param artist     artist, or "" if unknown
 * @param album      album, or "" if unknown
 * @param trackNo    track number within the disc, or 0 if unknown
 * @param discNo     disc number, or 0 if unknown
 * @param durationMs duration in milliseconds, or 0 if unknown
 * @param lyricsPath where the lyrics came from: a sibling .lrc/.txt file, the audio file itself
 *                   (lyrics embedded in its tags), or null if the track has no lyrics
 */
public record Track(int id, Path path, String title, String artist, String album,
                    int trackNo, int discNo, long durationMs, Path lyricsPath) {

    public enum Format { FLAC, MP3, OGG, OPUS }

    /** Library order: artist, album, disc, track number, title, path. */
    public static final Comparator<Track> LIBRARY_ORDER = Comparator
            .comparing((Track t) -> t.artist().toLowerCase(Locale.ROOT))
            .thenComparing(t -> t.album().toLowerCase(Locale.ROOT))
            .thenComparingInt(Track::discNo)
            .thenComparingInt(Track::trackNo)
            .thenComparing(t -> t.title().toLowerCase(Locale.ROOT))
            .thenComparing(Track::path);

    public Track {
        title = title == null ? "" : title.strip();
        artist = artist == null ? "" : artist.strip();
        album = album == null ? "" : album.strip();
    }

    public Track withId(int newId) {
        return new Track(newId, path, title, artist, album, trackNo, discNo, durationMs, lyricsPath);
    }

    public boolean hasLyrics() {
        return lyricsPath != null;
    }

    /** Detects the audio format from the file extension, or null if unsupported. */
    public static Format formatOf(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (name.substring(dot + 1)) {
            case "flac" -> Format.FLAC;
            case "mp3" -> Format.MP3;
            case "ogg", "oga" -> Format.OGG;
            case "opus" -> Format.OPUS;
            default -> null;
        };
    }

    /** The .lrc file that would accompany {@code audio}: same directory, same base name. */
    public static Path lrcSiblingOf(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return audio.resolveSibling(base + ".lrc");
    }

    /**
     * Lyrics file next to {@code audio}: synced {@code .lrc} preferred, then plain-text
     * {@code .txt} (as written by e.g. LRCGET when no synced lyrics exist). Either case
     * of extension. Returns null if neither exists.
     */
    public static Path findLyricsFile(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        for (String ext : new String[]{".lrc", ".LRC", ".txt", ".TXT"}) {
            Path p = audio.resolveSibling(base + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
