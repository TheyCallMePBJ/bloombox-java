package bloombox.core.trie;

import bloombox.core.OpStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Classic (uncompressed) trie: one node per character.
 *
 * <p>Children are kept in a sorted {@code char[]} with a parallel node array, so a
 * child lookup is a binary search over at most |Σ| entries and a DFS yields keys in
 * lexicographic order without extra sorting. This uses far less memory than a
 * {@code HashMap<Character,Node>} per node.
 *
 * <p>Complexity (L = key length, σ = max children per node, S = nodes in the prefix's subtree):
 * <ul>
 *   <li>insert / get / remove: O(L log σ)</li>
 *   <li>valuesWithPrefix: O(P log σ + S) to locate the prefix and walk its subtree</li>
 *   <li>space: O(total characters of all keys) nodes in the worst case</li>
 * </ul>
 */
public final class Trie<T> implements PrefixIndex<T> {

    private static final char[] NO_KEYS = new char[0];
    private static final Node[] NO_KIDS = new Node[0];

    static final class Node {
        char[] keys = NO_KEYS;
        Node[] kids = NO_KIDS;
        int size;
        List<Object> values; // null when this node ends no key

        int find(char c) {
            return Arrays.binarySearch(keys, 0, size, c);
        }

        Node child(char c) {
            int i = find(c);
            return i >= 0 ? kids[i] : null;
        }

        Node getOrAdd(char c) {
            int i = find(c);
            if (i >= 0) return kids[i];
            int at = -i - 1;
            if (size == keys.length) {
                int cap = Math.max(2, size * 2);
                keys = Arrays.copyOf(keys, cap);
                kids = Arrays.copyOf(kids, cap);
            }
            System.arraycopy(keys, at, keys, at + 1, size - at);
            System.arraycopy(kids, at, kids, at + 1, size - at);
            Node n = new Node();
            keys[at] = c;
            kids[at] = n;
            size++;
            return n;
        }

        void removeChild(char c) {
            int i = find(c);
            if (i < 0) return;
            System.arraycopy(keys, i + 1, keys, i, size - i - 1);
            System.arraycopy(kids, i + 1, kids, i, size - i - 1);
            size--;
            kids[size] = null;
        }

        boolean isTerminal() {
            return values != null && !values.isEmpty();
        }
    }

    private final Node root = new Node();
    private int keyCount;
    private int nodeCount = 1;

    @Override
    public void insert(String key, T value) {
        PrefixIndex.checkKey(key);
        Node n = root;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            Node next = n.child(c);
            if (next == null) {
                next = n.getOrAdd(c);
                nodeCount++;
            }
            n = next;
        }
        if (n.values == null) n.values = new ArrayList<>(1);
        if (n.values.isEmpty()) keyCount++;
        n.values.add(value);
    }

    @Override
    public boolean remove(String key, T value) {
        PrefixIndex.checkKey(key);
        Node[] path = new Node[key.length() + 1];
        path[0] = root;
        Node n = root;
        for (int i = 0; i < key.length(); i++) {
            n = n.child(key.charAt(i));
            if (n == null) return false;
            path[i + 1] = n;
        }
        if (n.values == null || !n.values.remove(value)) return false;
        if (n.values.isEmpty()) {
            n.values = null;
            keyCount--;
        }
        // prune now-useless nodes bottom-up
        for (int i = key.length(); i > 0; i--) {
            Node cur = path[i];
            if (cur.size > 0 || cur.isTerminal()) break;
            path[i - 1].removeChild(key.charAt(i - 1));
            nodeCount--;
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> get(String key) {
        if (key == null || key.isEmpty()) return List.of();
        Node n = locate(key, null);
        return n == null || n.values == null ? List.of() : (List<T>) List.copyOf(n.values);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> valuesWithPrefix(String prefix, int limit, OpStats stats) {
        PrefixIndex.checkQuery(prefix, limit);
        List<T> out = new ArrayList<>();
        Node start = locate(prefix, stats);
        if (start == null || limit == 0) return out;
        // iterative pre-order DFS; children pushed in reverse to pop in ascending order
        ArrayList<Node> stack = new ArrayList<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            if (stats != null) stats.nodesVisited++;
            if (n.values != null) {
                for (Object v : n.values) {
                    out.add((T) v);
                    if (out.size() >= limit) return out;
                }
            }
            for (int i = n.size - 1; i >= 0; i--) stack.add(n.kids[i]);
        }
        return out;
    }

    @Override
    public List<String> keysWithPrefix(String prefix, int limit, OpStats stats) {
        PrefixIndex.checkQuery(prefix, limit);
        List<String> out = new ArrayList<>();
        Node start = locate(prefix, stats);
        if (start == null || limit == 0) return out;
        collectKeys(start, new StringBuilder(prefix), out, limit, stats);
        return out;
    }

    private boolean collectKeys(Node n, StringBuilder sb, List<String> out, int limit, OpStats stats) {
        if (stats != null) stats.nodesVisited++;
        if (n.isTerminal()) {
            out.add(sb.toString());
            if (out.size() >= limit) return true;
        }
        for (int i = 0; i < n.size; i++) {
            sb.append(n.keys[i]);
            boolean full = collectKeys(n.kids[i], sb, out, limit, stats);
            sb.setLength(sb.length() - 1);
            if (full) return true;
        }
        return false;
    }

    /** Node reached by following {@code s} from the root, or null. */
    private Node locate(String s, OpStats stats) {
        Node n = root;
        for (int i = 0; i < s.length() && n != null; i++) {
            if (stats != null) stats.nodesVisited++;
            n = n.child(s.charAt(i));
        }
        return n;
    }

    @Override
    public int keyCount() {
        return keyCount;
    }

    @Override
    public int nodeCount() {
        return nodeCount;
    }

    /** Recounts nodes by traversal; used by tests to verify the maintained counter. */
    int recountNodes() {
        int c = 0;
        ArrayList<Node> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            c++;
            for (int i = 0; i < n.size; i++) stack.add(n.kids[i]);
        }
        return c;
    }
}
