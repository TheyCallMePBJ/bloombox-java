package bloombox.library;

import java.util.List;

/**
 * Summary of a library scan, shown in the UI and printed by {@code bloombox scan}.
 *
 * @param audioFiles       supported audio files found (after de-duplication)
 * @param symlinkDuplicates files skipped because they resolved to an already-seen real path
 * @param fromCache        tracks whose tags came from the cache (unchanged since last scan)
 * @param fromTags         tags read with jaudiotagger
 * @param fromFfprobe      tags read with ffprobe
 * @param fromFilename     no readable tags; metadata guessed from the file name
 * @param withLyrics       tracks with a parseable .lrc file
 * @param lyricErrors      .lrc files that exist but could not be read
 * @param likelyDuplicates tracks sharing artist + album + title with an earlier track
 * @param warnings         human-readable problems (truncated list)
 * @param elapsedMs        wall-clock scan time
 */
public record ScanReport(int audioFiles, int symlinkDuplicates, int fromCache, int fromTags,
                         int fromFfprobe, int fromFilename, int withLyrics, int lyricErrors,
                         int likelyDuplicates, List<String> warnings, long elapsedMs) {

    public static ScanReport empty() {
        return new ScanReport(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0);
    }

    public String summary() {
        return String.format(
                "%d tracks (%d cached, %d tags, %d ffprobe, %d filename-only), %d with lyrics%s%s%s in %d ms",
                audioFiles, fromCache, fromTags, fromFfprobe, fromFilename, withLyrics,
                lyricErrors > 0 ? ", " + lyricErrors + " unreadable .lrc" : "",
                symlinkDuplicates > 0 ? ", " + symlinkDuplicates + " symlink dupes skipped" : "",
                likelyDuplicates > 0 ? ", " + likelyDuplicates + " likely duplicates" : "",
                elapsedMs);
    }
}
