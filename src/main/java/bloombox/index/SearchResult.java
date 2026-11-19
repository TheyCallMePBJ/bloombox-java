package bloombox.index;

import bloombox.library.Track;

/**
 * One ranked hit.
 *
 * @param track       the matching track
 * @param score       relevance (sum over query terms of the best field weight, plus exact-word bonus)
 * @param fieldMask   OR of {@link Field#bit} for every field that matched some term
 * @param lyricLine   index of the lyric line that matched, or -1
 * @param lyricTimeMs start time of that line (for "play from here"), or -1 if unknown / unsynced
 * @param lyricText   that line's original text, or null
 */
public record SearchResult(Track track, int score, int fieldMask, int lyricLine, long lyricTimeMs, String lyricText) {

    public boolean matched(Field f) {
        return (fieldMask & f.bit) != 0;
    }
}
