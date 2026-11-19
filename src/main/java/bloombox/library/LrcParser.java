package bloombox.library;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for .lrc lyric files.
 *
 * <p>Supported:
 * <ul>
 *   <li>Line timestamps {@code [mm:ss]}, {@code [mm:ss.x]}, {@code [mm:ss.xx]}, {@code [mm:ss.xxx]}
 *       and {@code [mm:ss:xx]}; several timestamps may prefix one line (repeated chorus).</li>
 *   <li>ID tags such as {@code [ar:Artist]}, {@code [ti:Title]}, {@code [al:Album]}.</li>
 *   <li>{@code [offset:+/-ms]} — positive values make lyrics appear earlier.</li>
 *   <li>Enhanced/word-level tags {@code <mm:ss.xx>} (stripped from the text).</li>
 *   <li>Files without any timestamps are treated as plain, unsynced lyrics.</li>
 *   <li>UTF-8 (with or without BOM), falling back to ISO-8859-1 for legacy files.</li>
 * </ul>
 * Malformed lines are skipped rather than failing the whole file.
 */
public final class LrcParser {

    /** A time tag at the start of the remaining text, e.g. [01:23.45]. */
    private static final Pattern TIME_TAG =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** An ID tag occupying a whole line, e.g. [ar:Daft Punk]. */
    private static final Pattern ID_TAG =
            Pattern.compile("^\\[([A-Za-z#]+):(.*)]$");
    /** Word-level timing tags inside enhanced LRC lines. */
    private static final Pattern WORD_TAG =
            Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private LrcParser() {}

    public static Lyrics parse(Path file) throws IOException {
        return parse(decode(Files.readAllBytes(file)));
    }

    public static Lyrics parse(String content) {
        if (content == null || content.isBlank()) return Lyrics.EMPTY;
        if (content.charAt(0) == '﻿') content = content.substring(1);

        Map<String, String> meta = new HashMap<>();
        List<LyricLine> timed = new ArrayList<>();
        List<String> plain = new ArrayList<>();

        for (String raw : content.split("\\r\\n|\\r|\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;

            Matcher tm = TIME_TAG.matcher(line);
            if (tm.find()) {
                List<Long> times = new ArrayList<>();
                String rest = line;
                do {
                    Long t = toMillis(tm.group(1), tm.group(2), tm.group(3));
                    if (t != null) times.add(t);
                    rest = rest.substring(tm.end());
                    tm = TIME_TAG.matcher(rest);
                } while (tm.find());
                String text = cleanText(rest);
                for (long t : times) timed.add(new LyricLine(t, text));
                continue;
            }

            Matcher id = ID_TAG.matcher(line);
            if (id.matches()) {
                meta.put(id.group(1).toLowerCase(Locale.ROOT), id.group(2).strip());
                continue;
            }

            plain.add(cleanText(line));
        }

        if (timed.isEmpty()) {
            if (plain.isEmpty()) return new Lyrics(List.of(), false, meta);
            List<LyricLine> lines = new ArrayList<>(plain.size());
            for (String p : plain) lines.add(new LyricLine(LyricLine.UNSYNCED, p));
            return new Lyrics(lines, false, meta);
        }

        long offset = parseOffset(meta.get("offset"));
        List<LyricLine> lines = new ArrayList<>(timed.size());
        for (LyricLine l : timed) {
            lines.add(new LyricLine(Math.max(0, l.timeMs() - offset), l.text()));
        }
        // Stable sort keeps file order for lines that share a timestamp.
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return new Lyrics(lines, true, meta);
    }

    private static Long toMillis(String mm, String ss, String frac) {
        int minutes = Integer.parseInt(mm);
        int seconds = Integer.parseInt(ss);
        if (seconds >= 60) return null;
        int ms = 0;
        if (frac != null) {
            // ".5" = 500 ms, ".45" = 450 ms, ".456" = 456 ms
            ms = switch (frac.length()) {
                case 1 -> Integer.parseInt(frac) * 100;
                case 2 -> Integer.parseInt(frac) * 10;
                default -> Integer.parseInt(frac);
            };
        }
        return (minutes * 60L + seconds) * 1000L + ms;
    }

    private static long parseOffset(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value.strip().replace("+", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String cleanText(String s) {
        return WORD_TAG.matcher(s).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    /** Strict UTF-8 decode, falling back to Latin-1 for legacy-encoded files. */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
