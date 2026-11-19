package bloombox.core.trie;

import bloombox.core.OpStats;

import java.util.List;

/**
 * A multimap from string keys to values that supports prefix queries.
 * Implemented by {@link Trie}, {@link RadixTrie} and the {@link LinearPrefixIndex}
 * baseline so that all three can be tested and benchmarked against one contract.
 *
 * <p>Contract:
 * <ul>
 *   <li>Keys are non-empty strings; one key may map to many values (duplicates allowed).</li>
 *   <li>Trie implementations return prefix results in lexicographic key order
 *       (by UTF-16 code unit), and a key's values in insertion order.</li>
 *   <li>{@code limit} caps the number of returned items; {@link Integer#MAX_VALUE} = all.</li>
 *   <li>The empty prefix matches every key.</li>
 * </ul>
 */
public interface PrefixIndex<T> {

    /** Adds {@code value} under {@code key}. */
    void insert(String key, T value);

    /** Removes one occurrence of {@code value} under {@code key}; returns whether it was present. */
    boolean remove(String key, T value);

    /** Values stored under exactly {@code key} (empty list if none). */
    List<T> get(String key);

    default boolean containsKey(String key) {
        return !get(key).isEmpty();
    }

    /** Values of every key starting with {@code prefix}, up to {@code limit}. */
    List<T> valuesWithPrefix(String prefix, int limit, OpStats stats);

    /** Distinct keys starting with {@code prefix}, up to {@code limit} (autocomplete). */
    List<String> keysWithPrefix(String prefix, int limit, OpStats stats);

    default List<T> valuesWithPrefix(String prefix) {
        return valuesWithPrefix(prefix, Integer.MAX_VALUE, null);
    }

    default List<String> keysWithPrefix(String prefix) {
        return keysWithPrefix(prefix, Integer.MAX_VALUE, null);
    }

    /** Number of distinct keys with at least one value. */
    int keyCount();

    /** Structural size: number of nodes (or entries, for the linear baseline). */
    int nodeCount();

    static void checkKey(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("key must be non-empty");
    }

    static void checkQuery(String prefix, int limit) {
        if (prefix == null) throw new IllegalArgumentException("prefix must not be null");
        if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
    }
}
