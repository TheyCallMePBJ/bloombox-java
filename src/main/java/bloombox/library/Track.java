package bloombox.library;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Immutable metadata for one audio file in the library.
 *
 * @param id         dense integer id (index into the library's track list); used in posting lists
 * @param path       absolute path to the audio file
 * @param title      track title (falls back to the file name)
 * @param artist     artist, or "" if unknown
 * @param album      album, or "" if unknown
 * @param durationMs duration in milliseconds, or 0 if unknown
 * @param lrcPath    sibling .lrc file, or null if none exists
 */
public record Track(int id, Path path, String title, String artist, String album,
                    long durationMs, Path lrcPath) {

    public enum Format { FLAC, MP3, OGG, OPUS }

    public Track {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
    }

    public boolean hasLyrics() {
        return lrcPath != null;
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
}
