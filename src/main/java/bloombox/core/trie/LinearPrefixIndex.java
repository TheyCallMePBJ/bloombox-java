package bloombox.core.trie;

import bloombox.core.OpStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baseline for benchmarks: an unindexed list of (key, value) entries.
 * Every prefix query scans all N entries and compares up to |prefix| characters
 * each, i.e. O(N·P), versus O(P + subtree) for the tries.
 *
 * <p>Results come back in insertion order, not key order.
 */
public final class LinearPrefixIndex<T> implements PrefixIndex<T> {

    private record Entry<T>(String key, T value) {}

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Map<String, Integer> keyMultiplicity = new HashMap<>();

    @Override
    public void insert(String key, T value) {
        PrefixIndex.checkKey(key);
        entries.add(new Entry<>(key, value));
        keyMultiplicity.merge(key, 1, Integer::sum);
    }

    @Override
    public boolean remove(String key, T value) {
        PrefixIndex.checkKey(key);
        for (int i = 0; i < entries.size(); i++) {
            Entry<T> e = entries.get(i);
            if (e.key.equals(key) && java.util.Objects.equals(e.value, value)) {
                entries.remove(i);
                keyMultiplicity.computeIfPresent(key, (k, c) -> c == 1 ? null : c - 1);
                return true;
            }
        }
        return false;
    }

    @Override
    public List<T> get(String key) {
        List<T> out = new ArrayList<>();
        if (key == null || key.isEmpty()) return out;
        for (Entry<T> e : entries) if (e.key.equals(key)) out.add(e.value);
        return out;
    }

    @Override
    public List<T> valuesWithPrefix(String prefix, int limit, OpStats stats) {
        PrefixIndex.checkQuery(prefix, limit);
        List<T> out = new ArrayList<>();
        if (limit == 0) return out;
        for (Entry<T> e : entries) {
            if (startsWith(e.key, prefix, stats)) {
                out.add(e.value);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    @Override
    public List<String> keysWithPrefix(String prefix, int limit, OpStats stats) {
        PrefixIndex.checkQuery(prefix, limit);
        Set<String> out = new LinkedHashSet<>();
        if (limit == 0) return new ArrayList<>();
        for (Entry<T> e : entries) {
            if (startsWith(e.key, prefix, stats)) {
                out.add(e.key);
                if (out.size() >= limit) break;
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean startsWith(String s, String p, OpStats stats) {
        if (p.length() > s.length()) return false;
        for (int i = 0; i < p.length(); i++) {
            if (stats != null) stats.charComparisons++;
            if (s.charAt(i) != p.charAt(i)) return false;
        }
        return true;
    }

    @Override
    public int keyCount() {
        return keyMultiplicity.size();
    }

    @Override
    public int nodeCount() {
        return entries.size();
    }
}
