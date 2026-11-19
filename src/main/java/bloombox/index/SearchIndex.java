package bloombox.index;

import bloombox.core.OpStats;
import bloombox.core.prob.TrigramFilter;
import bloombox.core.text.Kmp;
import bloombox.core.text.Tokenizer;
import bloombox.core.trie.PrefixIndex;
import bloombox.core.trie.RadixTrie;
import bloombox.library.Library;
import bloombox.library.LyricLine;
import bloombox.library.Lyrics;
import bloombox.library.Track;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The search engine. Built once per library scan; immutable afterwards, so queries
 * may run concurrently from the UI thread.
 *
 * <h2>Structures</h2>
 * <ul>
 *   <li>One {@link PrefixIndex} (radix trie by default) per {@link Field}, mapping each
 *       distinct normalised word to the ids of tracks containing it (one posting per
 *       (word, track) pair).</li>
 *   <li>Per track with lyrics: its normalised lyric lines and a {@link TrigramFilter}.</li>
 * </ul>
 *
 * <h2>Query evaluation</h2>
 * Each {@link Query.Term} produces a candidate map {@code trackId → (score, fields)}:
 * <ul>
 *   <li><b>word</b>: prefix lookup in each relevant field trie, O(|w| + subtree).</li>
 *   <li><b>phrase</b>, lyrics: for each track, Bloom-screen the phrase's trigrams; only
 *       "possibly present" tracks have their lines scanned with a precompiled KMP matcher.
 *       Phrase, metadata fields: KMP over the normalised title/artist/album.</li>
 * </ul>
 * Candidate maps are intersected (AND), scores summed, and results ordered by score,
 * then library order (track id).
 */
public final class SearchIndex {

    public static final int EXACT_WORD_BONUS = 15;

    /** Query-time switches; {@code useBloom=false} is the baseline used in benchmarks. */
    public record Options(int limit, boolean useBloom) {
        public static final Options DEFAULT = new Options(500, true);

        public Options {
            if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
        }
    }

    /** Build-time statistics for the report and the UI. */
    public record BuildStats(int tracks, int tracksWithLyrics, Map<Field, Integer> distinctWords,
                             Map<Field, Integer> trieNodes, long postings, long bloomBits,
                             long buildMillis) {}

    public record Outcome(List<SearchResult> results, SearchReport report) {}

    private final Library library;
    private final EnumMap<Field, PrefixIndex<Integer>> tries = new EnumMap<>(Field.class);
    private final String[] normTitle, normArtist, normAlbum;
    private final String[][] normLines;          // null for tracks without lyrics
    private final TrigramFilter[] filters;       // null for tracks without lyrics
    private final int[] lyricTrackIds;
    private final BuildStats buildStats;

    private SearchIndex(Library library, Supplier<PrefixIndex<Integer>> trieFactory, double bloomFpp) {
        long t0 = System.nanoTime();
        this.library = library;
        int n = library.size();
        for (Field f : Field.values()) tries.put(f, trieFactory.get());
        normTitle = new String[n];
        normArtist = new String[n];
        normAlbum = new String[n];
        normLines = new String[n][];
        filters = new TrigramFilter[n];

        long postings = 0, bloomBits = 0;
        List<Integer> withLyrics = new ArrayList<>();
        for (Track t : library.tracks()) {
            int id = t.id();
            normTitle[id] = Tokenizer.normalizeForMatch(t.title());
            normArtist[id] = Tokenizer.normalizeForMatch(t.artist());
            normAlbum[id] = Tokenizer.normalizeForMatch(t.album());
            postings += indexWords(Field.TITLE, normTitle[id], id);
            postings += indexWords(Field.ARTIST, normArtist[id], id);
            postings += indexWords(Field.ALBUM, normAlbum[id], id);

            Lyrics ly = library.lyrics(id);
            if (!ly.isEmpty()) {
                String[] lines = new String[ly.lines().size()];
                Set<String> words = new LinkedHashSet<>();
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = Tokenizer.normalizeForMatch(ly.lines().get(i).text());
                    if (!lines[i].isEmpty()) words.addAll(List.of(lines[i].split(" ")));
                }
                normLines[id] = lines;
                for (String w : words) tries.get(Field.LYRICS).insert(w, id);
                postings += words.size();
                filters[id] = TrigramFilter.build(List.of(lines), bloomFpp);
                bloomBits += filters[id].bloom().bitSize();
                withLyrics.add(id);
            }
        }
        lyricTrackIds = withLyrics.stream().mapToInt(Integer::intValue).toArray();

        Map<Field, Integer> words = new EnumMap<>(Field.class), nodes = new EnumMap<>(Field.class);
        for (Field f : Field.values()) {
            words.put(f, tries.get(f).keyCount());
            nodes.put(f, tries.get(f).nodeCount());
        }
        buildStats = new BuildStats(n, lyricTrackIds.length, words, nodes, postings, bloomBits,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** Distinct tokens of an already-normalised string, each inserted once for this track. */
    private int indexWords(Field f, String normalized, int id) {
        if (normalized.isEmpty()) return 0;
        Set<String> distinct = new HashSet<>(List.of(normalized.split(" ")));
        for (String w : distinct) tries.get(f).insert(w, id);
        return distinct.size();
    }

    public static SearchIndex build(Library library) {
        return new SearchIndex(library, RadixTrie::new, TrigramFilter.DEFAULT_FPP);
    }

    /** For benchmarks: choose the prefix structure and the Bloom false-positive target. */
    public static SearchIndex build(Library library, Supplier<PrefixIndex<Integer>> trieFactory, double bloomFpp) {
        return new SearchIndex(library, trieFactory, bloomFpp);
    }

    public Library library() { return library; }
    public BuildStats buildStats() { return buildStats; }

    // ------------------------------------------------------------------ search

    private static final class Acc {
        int score;
        int mask;
        int phraseLine = -1;        // line found by KMP for a lyric phrase
        List<String> lyricPrefixes; // word terms that matched in lyrics
    }

    public Outcome search(String rawQuery) {
        return search(Query.parse(rawQuery), Options.DEFAULT);
    }

    public Outcome search(Query query, Options opts) {
        long t0 = System.nanoTime();
        OpStats work = new OpStats();
        PhraseStats ps = new PhraseStats();
        long[] trieCandidates = {0};

        Map<Integer, Acc> acc = null;
        for (Query.Term term : query.terms()) {
            Map<Integer, Acc> m = term.phrase()
                    ? evalPhrase(term, opts, work, ps)
                    : evalWord(term, work, trieCandidates);
            acc = acc == null ? m : intersect(acc, m);
            if (acc.isEmpty()) break; // AND can no longer succeed
        }

        List<SearchResult> results = new ArrayList<>();
        if (acc != null && !acc.isEmpty()) {
            List<Map.Entry<Integer, Acc>> ranked = new ArrayList<>(acc.entrySet());
            ranked.sort((a, b) -> a.getValue().score != b.getValue().score
                    ? Integer.compare(b.getValue().score, a.getValue().score)
                    : Integer.compare(a.getKey(), b.getKey()));
            int limit = Math.min(opts.limit(), ranked.size());
            for (int i = 0; i < limit; i++) results.add(toResult(ranked.get(i).getKey(), ranked.get(i).getValue()));
        }

        SearchReport report = new SearchReport(query, work, trieCandidates[0], ps.considered, ps.passed,
                ps.confirmed, ps.falsePositives, ps.screenable, opts.useBloom(),
                acc == null ? 0 : acc.size(), System.nanoTime() - t0);
        return new Outcome(results, report);
    }

    private Map<Integer, Acc> evalWord(Query.Term term, OpStats work, long[] trieCandidates) {
        Map<Integer, Acc> out = new HashMap<>();
        for (Field f : fieldsOf(term)) {
            PrefixIndex<Integer> trie = tries.get(f);
            List<Integer> ids = trie.valuesWithPrefix(term.text(), Integer.MAX_VALUE, work);
            if (ids.isEmpty()) continue;
            trieCandidates[0] += ids.size();
            Set<Integer> exact = new HashSet<>(trie.get(term.text()));
            for (int id : ids) {
                int s = f.weight + (exact.contains(id) ? EXACT_WORD_BONUS : 0);
                Acc a = out.computeIfAbsent(id, k -> new Acc());
                a.score = Math.max(a.score, s);
                a.mask |= f.bit;
                if (f == Field.LYRICS) {
                    if (a.lyricPrefixes == null) a.lyricPrefixes = new ArrayList<>(1);
                    if (!a.lyricPrefixes.contains(term.text())) a.lyricPrefixes.add(term.text());
                }
            }
        }
        return out;
    }

    private static final class PhraseStats {
        int considered, passed, confirmed, falsePositives;
        boolean screenable = true;
    }

    private Map<Integer, Acc> evalPhrase(Query.Term term, Options opts, OpStats work, PhraseStats ps) {
        Map<Integer, Acc> out = new HashMap<>();
        String phrase = term.text();
        Kmp kmp = Kmp.compile(phrase);
        for (Field f : fieldsOf(term)) {
            if (f == Field.LYRICS) {
                phraseInLyrics(kmp, opts.useBloom(), work, ps, (id, line) -> {
                    Acc a = out.computeIfAbsent(id, k -> new Acc());
                    a.score = Math.max(a.score, f.weight + EXACT_WORD_BONUS);
                    a.mask |= f.bit;
                    if (a.phraseLine < 0) a.phraseLine = line;
                });
            } else {
                String[] col = f == Field.TITLE ? normTitle : f == Field.ARTIST ? normArtist : normAlbum;
                for (int id = 0; id < col.length; id++) {
                    if (kmp.occursIn(col[id], work)) {
                        Acc a = out.computeIfAbsent(id, k -> new Acc());
                        a.score = Math.max(a.score, f.weight + EXACT_WORD_BONUS);
                        a.mask |= f.bit;
                    }
                }
            }
        }
        return out;
    }

    interface PhraseHit {
        void hit(int trackId, int lineIndex);
    }

    /**
     * Bloom-screened phrase search over lyrics. Every "possibly present" track is
     * verified line by line with KMP; a pass that KMP rejects is a false positive.
     */
    private void phraseInLyrics(Kmp kmp, boolean useBloom, OpStats work, PhraseStats ps, PhraseHit sink) {
        String phrase = kmp.pattern();
        boolean screen = useBloom && phrase.length() >= 3;
        if (useBloom && !screen) ps.screenable = false;
        for (int id : lyricTrackIds) {
            ps.considered++;
            if (screen && !filters[id].mightContain(phrase, work)) continue; // definitely absent
            ps.passed++;
            String[] lines = normLines[id];
            int found = -1;
            for (int li = 0; li < lines.length && found < 0; li++) {
                if (kmp.occursIn(lines[li], work)) found = li;
            }
            if (found >= 0) {
                ps.confirmed++;
                sink.hit(id, found);
            } else if (screen) {
                ps.falsePositives++;
            }
        }
    }

    private static Map<Integer, Acc> intersect(Map<Integer, Acc> acc, Map<Integer, Acc> m) {
        // iterate the smaller map for O(min(|acc|, |m|)) expected time
        Map<Integer, Acc> small = acc.size() <= m.size() ? acc : m;
        Map<Integer, Acc> large = small == acc ? m : acc;
        Map<Integer, Acc> out = new HashMap<>();
        for (Iterator<Map.Entry<Integer, Acc>> it = small.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Acc> e = it.next();
            Acc other = large.get(e.getKey());
            if (other == null) continue;
            Acc a = e.getValue();
            a.score += other.score;
            a.mask |= other.mask;
            if (a.phraseLine < 0) a.phraseLine = other.phraseLine;
            if (other.lyricPrefixes != null) {
                if (a.lyricPrefixes == null) a.lyricPrefixes = new ArrayList<>();
                a.lyricPrefixes.addAll(other.lyricPrefixes);
            }
            out.put(e.getKey(), a);
        }
        return out;
    }

    private static List<Field> fieldsOf(Query.Term term) {
        return term.field() == null ? List.of(Field.values()) : List.of(term.field());
    }

    private SearchResult toResult(int id, Acc a) {
        int line = a.phraseLine;
        if (line < 0 && a.lyricPrefixes != null) line = firstLineWithPrefix(id, a.lyricPrefixes);
        long time = -1;
        String text = null;
        if (line >= 0) {
            LyricLine l = library.lyrics(id).lines().get(line);
            time = l.isSynced() ? l.timeMs() : -1;
            text = l.text();
        }
        return new SearchResult(library.track(id), a.score, a.mask, line, time, text);
    }

    /** First lyric line of a track containing a word that starts with any of the prefixes. */
    private int firstLineWithPrefix(int id, List<String> prefixes) {
        String[] lines = normLines[id];
        if (lines == null) return -1;
        int best = -1, bestHits = 0;
        for (int i = 0; i < lines.length; i++) {
            int hits = 0;
            for (String p : prefixes) {
                if (lines[i].startsWith(p) || lines[i].contains(" " + p)) hits++;
            }
            if (hits > bestHits) {
                best = i;
                bestHits = hits;
                if (hits == prefixes.size()) break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- autocomplete

    /** Completions for the last word being typed, from one field's trie. */
    public List<String> suggest(Field field, String prefix, int limit) {
        String p = Tokenizer.normalize(prefix == null ? "" : prefix.strip());
        if (p.isEmpty()) return List.of();
        return tries.get(field).keysWithPrefix(p, limit, null);
    }
}
