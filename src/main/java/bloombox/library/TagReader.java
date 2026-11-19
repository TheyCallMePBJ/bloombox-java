package bloombox.library;

import bloombox.util.Json;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads title / artist / album / track number / duration with a three-step fallback:
 * <ol>
 *   <li>jaudiotagger (FLAC, MP3, Ogg Vorbis): fast, in-process.</li>
 *   <li>{@code ffprobe} (covers Opus and files jaudiotagger rejects).</li>
 *   <li>Guess from the file name / parent directory.</li>
 * </ol>
 * Never throws for a bad file: the worst case is a filename-derived title.
 */
public final class TagReader {

    /** Which step produced the metadata, reported in the scan summary. */
    public enum Source { TAGS, FFPROBE, FILENAME }

    /** {@code lyrics} is embedded lyrics text (plain or LRC-formatted), or "" if none. */
    public record Tags(String title, String artist, String album, int trackNo, int discNo,
                       long durationMs, Source source, String lyrics) {
        public Tags {
            lyrics = lyrics == null ? "" : lyrics;
        }
    }

    // Strong reference so the level isn't lost when the logger is GC'd.
    private static final Logger JAT_LOG = Logger.getLogger("org.jaudiotagger");
    static {
        JAT_LOG.setLevel(Level.OFF);
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d{1,3})(?:\\s*[-._)]\\s*|\\s+)(.*)$");

    private final boolean ffprobeEnabled;

    public TagReader(boolean ffprobeEnabled) {
        this.ffprobeEnabled = ffprobeEnabled;
    }

    public Tags read(Path file) {
        Tags t = null;
        if (Track.formatOf(file) != Track.Format.OPUS) t = viaJaudiotagger(file);
        if (t == null && ffprobeEnabled) t = viaFfprobe(file);
        Tags guess = fromFilename(file);
        if (t == null) return guess;
        // fill gaps from the filename guess
        return new Tags(
                t.title.isBlank() ? guess.title : t.title,
                t.artist.isBlank() ? guess.artist : t.artist,
                t.album.isBlank() ? guess.album : t.album,
                t.trackNo != 0 ? t.trackNo : guess.trackNo,
                t.discNo,
                t.durationMs,
                t.source,
                t.lyrics);
    }

    private static Tags viaJaudiotagger(Path file) {
        try {
            AudioFile af = AudioFileIO.read(file.toFile());
            AudioHeader h = af.getAudioHeader();
            long dur = h == null ? 0 : Math.round(h.getPreciseTrackLength() * 1000);
            Tag tag = af.getTag();
            if (tag == null) return new Tags("", "", "", 0, 0, dur, Source.TAGS, "");
            return new Tags(
                    first(tag, FieldKey.TITLE),
                    first(tag, FieldKey.ARTIST).isBlank() ? first(tag, FieldKey.ALBUM_ARTIST) : first(tag, FieldKey.ARTIST),
                    first(tag, FieldKey.ALBUM),
                    leadingInt(first(tag, FieldKey.TRACK)),
                    leadingInt(first(tag, FieldKey.DISC_NO)),
                    dur,
                    Source.TAGS,
                    first(tag, FieldKey.LYRICS));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String first(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return v == null ? "" : v.strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Tags viaFfprobe(Path file) {
        Process p = null;
        try {
            p = new ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json",
                    "-show_entries", "format=duration:format_tags:stream_tags", file.toString())
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            Map<String, Object> root = (Map<String, Object>) Json.parse(out);
            Map<String, Object> format = (Map<String, Object>) root.getOrDefault("format", Map.of());
            Map<String, String> tags = new java.util.HashMap<>();
            mergeTags(tags, format.get("tags"));
            Object streams = root.get("streams");
            if (streams instanceof List<?> list) for (Object s : list) {
                if (s instanceof Map<?, ?> sm) mergeTags(tags, sm.get("tags"));
            }
            long dur = 0;
            Object d = format.get("duration");
            if (d != null) dur = Math.round(Double.parseDouble(d.toString()) * 1000);
            return new Tags(tags.getOrDefault("title", ""),
                    tags.getOrDefault("artist", tags.getOrDefault("album_artist", "")),
                    tags.getOrDefault("album", ""),
                    leadingInt(tags.getOrDefault("track", "")),
                    leadingInt(tags.getOrDefault("disc", "")),
                    dur, Source.FFPROBE, embeddedLyrics(tags));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** Lyrics as ffprobe names them: LYRICS, UNSYNCEDLYRICS, or language-suffixed "lyrics-eng". */
    static String embeddedLyrics(Map<String, String> tags) {
        for (String k : List.of("lyrics", "unsyncedlyrics", "unsynced lyrics")) {
            String v = tags.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey().startsWith("lyrics") && !e.getValue().isBlank()) return e.getValue();
        }
        return "";
    }

    private static void mergeTags(Map<String, String> into, Object tags) {
        if (!(tags instanceof Map<?, ?> m)) return;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            String v = String.valueOf(e.getValue()).strip();
            if (!v.isEmpty()) into.putIfAbsent(k, v);
        }
    }

    /**
     * Guesses metadata from {@code [NN - ][Artist - ]Title.ext} with the parent
     * directory as album. Public for tests.
     */
    public static Tags fromFilename(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').strip();
        int trackNo = 0;
        Matcher m = LEADING_NUMBER.matcher(base);
        if (m.matches() && !m.group(2).isBlank()) {
            trackNo = Integer.parseInt(m.group(1));
            base = m.group(2).strip();
        }
        String artist = "", title = base;
        int sep = base.indexOf(" - ");
        if (sep > 0) {
            artist = base.substring(0, sep).strip();
            title = base.substring(sep + 3).strip();
        }
        Path parent = file.getParent();
        String album = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        return new Tags(title, artist, album, trackNo, 0, 0, Source.FILENAME, "");
    }

    /** "3/12" → 3, "  07 " → 7, garbage → 0. */
    static int leadingInt(String s) {
        if (s == null) return 0;
        s = s.strip();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && i < 6) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
    }
}
