package bloombox.bench;

import bloombox.library.Library;
import bloombox.library.LyricLine;
import bloombox.library.Lyrics;
import bloombox.library.Track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic synthetic music libraries for benchmarks: same config + seed → identical library.
 *
 * <p>Words are pseudo-words built from syllables; the most frequent ones are the shortest,
 * and word frequencies follow a Zipf law (frequency ∝ 1/rank^s, s ≈ 1.07, as in natural
 * language), so tries see realistic shared prefixes and lyrics have realistic repetition.
 * Tracks are grouped into albums (~10 tracks) by artists (~3 albums each); a configurable
 * fraction of tracks has synced lyrics.
 */
public final class SyntheticLibrary {
    private SyntheticLibrary() {}

    /**
     * @param tracks         number of tracks (≥ 0)
     * @param lyricsFraction fraction of tracks with lyrics, [0, 1]
     * @param minLines       lyric lines per track, inclusive range
     * @param minWords       words per lyric line, inclusive range
     * @param vocabulary     distinct words available for titles and lyrics
     * @param zipfS          Zipf exponent (> 0)
     */
    public record Config(int tracks, double lyricsFraction, int minLines, int maxLines,
                         int minWords, int maxWords, int vocabulary, double zipfS, long seed) {
        public Config {
            if (tracks < 0) throw new IllegalArgumentException("tracks must be >= 0");
            if (lyricsFraction < 0 || lyricsFraction > 1) throw new IllegalArgumentException("lyricsFraction must be in [0,1]");
            if (minLines < 1 || maxLines < minLines) throw new IllegalArgumentException("bad line range");
            if (minWords < 1 || maxWords < minWords) throw new IllegalArgumentException("bad word range");
            if (vocabulary < 10) throw new IllegalArgumentException("vocabulary must be >= 10");
            if (!(zipfS > 0)) throw new IllegalArgumentException("zipfS must be > 0");
        }

        /** 60% of tracks with 20–40 lines of 4–9 words, 20 000-word vocabulary. */
        public static Config defaults(int tracks, long seed) {
            return new Config(tracks, 0.6, 20, 40, 4, 9, 20_000, 1.07, seed);
        }
    }

    private static final String[] ONSETS = {"", "b", "br", "ch", "d", "dr", "f", "g", "gl", "h", "j", "k", "l", "m",
            "n", "p", "pl", "r", "s", "sh", "sk", "st", "t", "th", "tr", "v", "w", "z"};
    private static final String[] VOWELS = {"a", "e", "i", "o", "u", "ai", "ou", "ee"};
    private static final String[] CODAS = {"", "", "", "n", "r", "s", "t", "l", "nd", "ck"};

    /** {@code size} distinct lowercase pseudo-words, shortest first (rank 0 = most frequent). */
    public static String[] vocabulary(int size, long seed) {
        if (size < 0) throw new IllegalArgumentException("size must be >= 0");
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        Set<String> words = new LinkedHashSet<>();
        int boost = 0, misses = 0; // short syllable combinations run out for big vocabularies:
        while (words.size() < size) { // after 100 consecutive duplicates, words get one syllable longer
            int syllables = 1 + boost + (int) Math.min(3, Math.abs(rnd.nextGaussian()) * 1.3);
            StringBuilder w = new StringBuilder();
            for (int i = 0; i < syllables; i++) {
                w.append(ONSETS[rnd.nextInt(ONSETS.length)]).append(VOWELS[rnd.nextInt(VOWELS.length)]);
                if (i == syllables - 1 || rnd.nextInt(4) == 0) w.append(CODAS[rnd.nextInt(CODAS.length)]);
            }
            if (words.add(w.toString())) misses = 0;
            else if (++misses >= 100) {
                boost++;
                misses = 0;
            }
        }
        String[] out = words.toArray(new String[0]);
        Arrays.sort(out, Comparator.comparingInt(String::length)); // stable: short words get low ranks
        return out;
    }

    /** Samples ranks 0..n-1 with P(r) ∝ 1/(r+1)^s, by binary search over the CDF: O(log n) per sample. */
    public static final class Zipf {
        private final double[] cdf;

        public Zipf(int n, double s) {
            if (n < 1) throw new IllegalArgumentException("n must be >= 1");
            cdf = new double[n];
            double sum = 0;
            for (int r = 0; r < n; r++) {
                sum += 1.0 / Math.pow(r + 1, s);
                cdf[r] = sum;
            }
            for (int r = 0; r < n; r++) cdf[r] /= sum;
        }

        public int sample(Random rnd) {
            int i = Arrays.binarySearch(cdf, rnd.nextDouble());
            return Math.min(cdf.length - 1, i >= 0 ? i : -i - 1);
        }
    }

    public static Library generate(Config c) {
        Random rnd = new Random(c.seed());
        String[] vocab = vocabulary(c.vocabulary(), c.seed());
        Zipf zipf = new Zipf(vocab.length, c.zipfS());
        String[] names = vocabulary(Math.max(10, c.tracks() / 4 + 10), c.seed() + 1);

        List<Track> tracks = new ArrayList<>(c.tracks());
        List<Lyrics> lyrics = new ArrayList<>(c.tracks());
        Path root = Path.of("/synthetic");
        int made = 0, artistNo = 0;
        while (made < c.tracks()) {
            String artist = cap(names[(artistNo * 2) % names.length])
                    + (rnd.nextBoolean() ? " " + cap(names[(artistNo * 2 + 1) % names.length]) : "");
            artistNo++;
            int albums = 1 + rnd.nextInt(5);
            for (int a = 0; a < albums && made < c.tracks(); a++) {
                String album = phrase(vocab, zipf, rnd, 1 + rnd.nextInt(3), true);
                int count = 6 + rnd.nextInt(10);
                for (int n = 1; n <= count && made < c.tracks(); n++, made++) {
                    String title = phrase(vocab, zipf, rnd, 1 + rnd.nextInt(4), true);
                    Path file = root.resolve(artist).resolve(album).resolve(String.format(Locale.ROOT, "%02d %s.flac", n, title));
                    Lyrics ly = Lyrics.EMPTY;
                    Path lrc = null;
                    if (rnd.nextDouble() < c.lyricsFraction()) {
                        int lines = c.minLines() + rnd.nextInt(c.maxLines() - c.minLines() + 1);
                        List<LyricLine> ll = new ArrayList<>(lines);
                        for (int i = 0; i < lines; i++) {
                            int words = c.minWords() + rnd.nextInt(c.maxWords() - c.minWords() + 1);
                            ll.add(new LyricLine(i * 4_000L, phrase(vocab, zipf, rnd, words, false)));
                        }
                        ly = new Lyrics(ll, true, Map.of());
                        lrc = file.resolveSibling(String.format(Locale.ROOT, "%02d %s.lrc", n, title));
                    }
                    tracks.add(new Track(-1, file, title, artist, album, n, 1, 180_000 + rnd.nextInt(120_000), lrc));
                    lyrics.add(ly);
                }
            }
        }
        return Library.of(root, tracks, lyrics);
    }

    private static String phrase(String[] vocab, Zipf zipf, Random rnd, int words, boolean capitalise) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append(' ');
            String w = vocab[zipf.sample(rnd)];
            sb.append(capitalise ? cap(w) : w);
        }
        return sb.toString();
    }

    private static String cap(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
