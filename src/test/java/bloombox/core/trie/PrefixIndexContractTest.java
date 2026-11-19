package bloombox.core.trie;

import bloombox.core.OpStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural contract shared by every trie implementation. Concrete subclasses
 * supply the implementation under test.
 */
abstract class PrefixIndexContractTest {

    abstract PrefixIndex<Integer> create();

    /** Hook for implementation-specific structural checks after each mutation. */
    void verify(PrefixIndex<Integer> idx) {}

    private PrefixIndex<Integer> withWords(String... words) {
        PrefixIndex<Integer> t = create();
        for (int i = 0; i < words.length; i++) t.insert(words[i], i);
        verify(t);
        return t;
    }

    @Test
    void exactLookupDistinguishesKeysFromPrefixes() {
        PrefixIndex<Integer> t = withWords("tea", "team", "ten");
        assertEquals(List.of(0), t.get("tea"));
        assertEquals(List.of(1), t.get("team"));
        assertEquals(List.of(), t.get("te"));      // prefix only, not a key
        assertEquals(List.of(), t.get("teams"));   // longer than any key
        assertEquals(List.of(), t.get("x"));
        assertTrue(t.containsKey("ten"));
        assertFalse(t.containsKey("t"));
    }

    @Test
    void prefixQueryReturnsKeysInLexicographicOrder() {
        PrefixIndex<Integer> t = withWords("team", "tea", "ten", "to", "inn", "te");
        assertEquals(List.of("te", "tea", "team", "ten"), t.keysWithPrefix("te"));
        assertEquals(List.of(5, 1, 0, 2), t.valuesWithPrefix("te"));
        assertEquals(List.of("inn", "te", "tea", "team", "ten", "to"), t.keysWithPrefix(""));
    }

    @Test
    void prefixEndingInsideAnEdgeStillMatches() {
        // In a radix trie "blue" and "blues" share an edge; "bl" ends mid-label.
        PrefixIndex<Integer> t = withWords("blue", "blues", "black");
        assertEquals(List.of("black", "blue", "blues"), t.keysWithPrefix("bl"));
        assertEquals(List.of("blue", "blues"), t.keysWithPrefix("blu"));
        assertEquals(List.of(), t.keysWithPrefix("blx"));
        assertEquals(List.of(), t.keysWithPrefix("bluesy"));
    }

    @Test
    void duplicateKeysKeepAllValuesInInsertionOrder() {
        PrefixIndex<Integer> t = create();
        t.insert("love", 7);
        t.insert("love", 3);
        t.insert("love", 7);
        assertEquals(List.of(7, 3, 7), t.get("love"));
        assertEquals(1, t.keyCount());
    }

    @Test
    void limitIsRespectedAndZeroReturnsNothing() {
        PrefixIndex<Integer> t = withWords("a", "ab", "abc", "abd", "abe");
        assertEquals(List.of("a", "ab"), t.keysWithPrefix("a", 2, null));
        assertEquals(3, t.valuesWithPrefix("ab", 3, null).size());
        assertTrue(t.valuesWithPrefix("a", 0, null).isEmpty());
        assertTrue(t.keysWithPrefix("a", 0, null).isEmpty());
    }

    @Test
    void removePrunesAndRestoresOriginalShape() {
        PrefixIndex<Integer> t = withWords("tea", "ten");
        int nodesBefore = t.nodeCount();
        t.insert("team", 99);
        t.insert("teammate", 98);
        verify(t);
        assertTrue(t.remove("teammate", 98));
        verify(t);
        assertTrue(t.remove("team", 99));
        verify(t);
        assertEquals(nodesBefore, t.nodeCount());
        assertEquals(List.of("tea", "ten"), t.keysWithPrefix(""));
        assertFalse(t.remove("team", 99), "already removed");
        assertFalse(t.remove("tea", 12345), "wrong value");
        assertFalse(t.remove("zzz", 0), "absent key");
    }

    @Test
    void removingInnerKeyKeepsDescendants() {
        PrefixIndex<Integer> t = withWords("in", "inn", "into");
        assertTrue(t.remove("in", 0));
        verify(t);
        assertEquals(List.of("inn", "into"), t.keysWithPrefix("i"));
        assertEquals(2, t.keyCount());
    }

    @Test
    void handlesUnicodeKeys() {
        PrefixIndex<Integer> t = withWords("夜に駆ける", "夜空", "beyonce", "दिल", "दिलबर", "🎵song", "🎶tune");
        assertEquals(List.of("夜に駆ける", "夜空"), t.keysWithPrefix("夜"));
        assertEquals(List.of("दिल", "दिलबर"), t.keysWithPrefix("दि"));
        assertEquals(List.of(5), t.get("🎵song"));
        assertEquals(List.of("🎶tune"), t.keysWithPrefix("🎶"));
    }

    @Test
    void emptyIndexAndInvalidInput() {
        PrefixIndex<Integer> t = create();
        assertTrue(t.keysWithPrefix("").isEmpty());
        assertTrue(t.get("anything").isEmpty());
        assertEquals(0, t.keyCount());
        assertThrows(IllegalArgumentException.class, () -> t.insert("", 1));
        assertThrows(IllegalArgumentException.class, () -> t.insert(null, 1));
        assertThrows(IllegalArgumentException.class, () -> t.valuesWithPrefix(null, 5, null));
        assertThrows(IllegalArgumentException.class, () -> t.valuesWithPrefix("a", -1, null));
        assertTrue(t.get("").isEmpty());
    }

    @Test
    void statsCountWork() {
        PrefixIndex<Integer> t = withWords("alpha", "alps", "beta");
        OpStats s = new OpStats();
        t.valuesWithPrefix("al", Integer.MAX_VALUE, s);
        assertTrue(s.nodesVisited > 0);
        OpStats miss = new OpStats();
        t.valuesWithPrefix("zz", Integer.MAX_VALUE, miss);
        assertTrue(miss.nodesVisited <= 1, "a miss at the root should stop immediately");
    }

    /** Randomised differential test against a TreeMap reference model. */
    @Test
    void randomOperationsMatchReferenceModel() {
        Random rnd = new Random(42);
        PrefixIndex<Integer> t = create();
        TreeMap<String, List<Integer>> ref = new TreeMap<>();
        String alphabet = "abcde";
        for (int op = 0; op < 20_000; op++) {
            String key = randomWord(rnd, alphabet, 1 + rnd.nextInt(6));
            int value = rnd.nextInt(50);
            if (rnd.nextInt(3) > 0) {
                t.insert(key, value);
                ref.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            } else {
                List<Integer> vs = ref.get(key);
                boolean expected = vs != null && vs.remove((Integer) value);
                if (vs != null && vs.isEmpty()) ref.remove(key);
                assertEquals(expected, t.remove(key, value), "remove " + key + "/" + value);
            }
            if (op % 1000 == 0) verify(t);
        }
        verify(t);
        assertEquals(ref.size(), t.keyCount());
        for (int q = 0; q < 300; q++) {
            String p = randomWord(rnd, alphabet, rnd.nextInt(4));
            List<String> expectedKeys = new ArrayList<>();
            List<Integer> expectedValues = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> e : ref.tailMap(p, true).entrySet()) {
                if (!e.getKey().startsWith(p)) break;
                expectedKeys.add(e.getKey());
                expectedValues.addAll(e.getValue());
            }
            assertEquals(expectedKeys, t.keysWithPrefix(p), "keys for '" + p + "'");
            assertEquals(expectedValues, t.valuesWithPrefix(p), "values for '" + p + "'");
        }
    }

    static String randomWord(Random rnd, String alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}

class TrieTest extends PrefixIndexContractTest {
    @Override
    PrefixIndex<Integer> create() {
        return new Trie<>();
    }

    @Override
    void verify(PrefixIndex<Integer> idx) {
        assertEquals(((Trie<Integer>) idx).recountNodes(), idx.nodeCount(), "node counter drifted");
    }

    @Test
    void nodeCountIsOnePerDistinctPrefix() {
        Trie<Integer> t = new Trie<>();
        t.insert("tea", 0);
        t.insert("ten", 1);
        // root, t, e, a, n
        assertEquals(5, t.nodeCount());
    }
}

class RadixTrieTest extends PrefixIndexContractTest {
    @Override
    PrefixIndex<Integer> create() {
        return new RadixTrie<>();
    }

    @Override
    void verify(PrefixIndex<Integer> idx) {
        ((RadixTrie<Integer>) idx).checkInvariants();
    }

    @Test
    void compressesChainsAndSplitsOnDivergence() {
        RadixTrie<Integer> t = new RadixTrie<>();
        t.insert("romane", 0);
        assertEquals(2, t.nodeCount()); // root + "romane"
        t.insert("romanus", 1);         // split: "roman" -> {"e","us"}
        assertEquals(4, t.nodeCount());
        t.insert("rubens", 2);          // split: "r" -> {"oman"..., "ubens"}
        t.checkInvariants();
        assertEquals(List.of("romane", "romanus", "rubens"), t.keysWithPrefix("r"));
    }

    @Test
    void usesNoMoreThanTwiceKeysNodesAndFewerThanPlainTrie() {
        Random rnd = new Random(7);
        RadixTrie<Integer> radix = new RadixTrie<>();
        Trie<Integer> plain = new Trie<>();
        for (int i = 0; i < 5000; i++) {
            String w = randomWord(rnd, "abcdefghijklmnopqrstuvwxyz", 3 + rnd.nextInt(8));
            radix.insert(w, i);
            plain.insert(w, i);
        }
        radix.checkInvariants();
        assertTrue(radix.nodeCount() <= 2 * radix.keyCount() + 1);
        assertTrue(radix.nodeCount() < plain.nodeCount());
    }
}

class LinearPrefixIndexTest {
    @Test
    void agreesWithTrieOnSameData() {
        Random rnd = new Random(3);
        LinearPrefixIndex<Integer> lin = new LinearPrefixIndex<>();
        Trie<Integer> trie = new Trie<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            String w = PrefixIndexContractTest.randomWord(rnd, "abcd", 1 + rnd.nextInt(5));
            lin.insert(w, i);
            trie.insert(w, i);
            seen.merge(w, 1, Integer::sum);
        }
        assertEquals(seen.size(), lin.keyCount());
        for (String p : List.of("", "a", "ab", "dcb", "zz")) {
            List<Integer> a = new ArrayList<>(lin.valuesWithPrefix(p));
            List<Integer> b = new ArrayList<>(trie.valuesWithPrefix(p));
            a.sort(null);
            b.sort(null);
            assertEquals(b, a, "prefix '" + p + "'");
        }
    }
}
