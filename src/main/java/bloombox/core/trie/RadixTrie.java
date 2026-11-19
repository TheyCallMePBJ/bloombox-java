package bloombox.core.trie;

import bloombox.core.OpStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Radix (compressed / Patricia-style) trie: chains of single-child, non-terminal
 * nodes are collapsed into one edge with a string label.
 *
 * <p>Invariants (checked by {@link #checkInvariants()} in tests):
 * <ol>
 *   <li>Every non-root node has a non-empty label.</li>
 *   <li>Sibling labels start with distinct characters; children are sorted by that character.</li>
 *   <li>Every non-root node that ends no key has at least two children
 *       (otherwise it would have been merged with its child).</li>
 * </ol>
 * Consequently a radix trie over K keys has at most 2K nodes, compared with up to
 * Σ|key| for the plain {@link Trie}. Time bounds are the same as the plain trie, but
 * descending one edge compares a whole label run instead of hopping node to node.
 */
public final class RadixTrie<T> implements PrefixIndex<T> {

    private static final char[] NO_KEYS = new char[0];
    private static final Node[] NO_KIDS = new Node[0];

    static final class Node {
        String label;
        char[] keys = NO_KEYS; // keys[i] == kids[i].label.charAt(0)
        Node[] kids = NO_KIDS;
        int size;
        List<Object> values;

        Node(String label) {
            this.label = label;
        }

        int find(char c) {
            return Arrays.binarySearch(keys, 0, size, c);
        }

        Node child(char c) {
            int i = find(c);
            return i >= 0 ? kids[i] : null;
        }

        void addChild(Node n) {
            char c = n.label.charAt(0);
            int i = find(c);
            if (i >= 0) throw new IllegalStateException("duplicate edge '" + c + "'");
            int at = -i - 1;
            if (size == keys.length) {
                int cap = Math.max(2, size * 2);
                keys = Arrays.copyOf(keys, cap);
                kids = Arrays.copyOf(kids, cap);
            }
            System.arraycopy(keys, at, keys, at + 1, size - at);
            System.arraycopy(kids, at, kids, at + 1, size - at);
            keys[at] = c;
            kids[at] = n;
            size++;
        }

        void replaceChild(char c, Node n) {
            kids[find(c)] = n;
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

    private final Node root = new Node("");
    private int keyCount;
    private int nodeCount = 1;

    @Override
    public void insert(String key, T value) {
        PrefixIndex.checkKey(key);
        Node n = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            Node child = n.child(c);
            if (child == null) {
                Node leaf = new Node(key.substring(i));
                n.addChild(leaf);
                nodeCount++;
                n = leaf;
                break;
            }
            String lab = child.label;
            int max = Math.min(lab.length(), key.length() - i);
            int j = 1; // first char already matched via the child index
            while (j < max && lab.charAt(j) == key.charAt(i + j)) j++;
            if (j == lab.length()) {
                n = child;
                i += j;
                continue;
            }
            // Split edge "lab" into "lab[0..j)" -> "lab[j..)"
            Node mid = new Node(lab.substring(0, j));
            child.label = lab.substring(j);
            mid.addChild(child);
            n.replaceChild(c, mid);
            nodeCount++;
            n = mid;
            i += j;
        }
        if (n.values == null) n.values = new ArrayList<>(1);
        if (n.values.isEmpty()) keyCount++;
        n.values.add(value);
    }

    @Override
    public boolean remove(String key, T value) {
        PrefixIndex.checkKey(key);
        List<Node> path = new ArrayList<>();
        path.add(root);
        Node n = root;
        int i = 0;
        while (i < key.length()) {
            Node child = n.child(key.charAt(i));
            if (child == null || !key.startsWith(child.label, i)) return false;
            i += child.label.length();
            n = child;
            path.add(n);
        }
        if (n == root || n.values == null || !n.values.remove(value)) return false;
        if (!n.values.isEmpty()) return true;
        n.values = null;
        keyCount--;

        Node parent = path.get(path.size() - 2);
        if (n.size == 0) {
            parent.removeChild(n.label.charAt(0));
            nodeCount--;
            if (parent != root && !parent.isTerminal() && parent.size == 1) mergeWithOnlyChild(parent);
        } else if (n.size == 1) {
            mergeWithOnlyChild(n);
        }
        return true;
    }

    /** Absorbs the single child of {@code x} into x (restores invariant 3). */
    private void mergeWithOnlyChild(Node x) {
        Node only = x.kids[0];
        x.label = x.label + only.label; // first char unchanged, so parent's index stays valid
        x.keys = only.keys;
        x.kids = only.kids;
        x.size = only.size;
        x.values = only.values;
        nodeCount--;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> get(String key) {
        if (key == null || key.isEmpty()) return List.of();
        Node n = root;
        int i = 0;
        while (i < key.length()) {
            Node child = n.child(key.charAt(i));
            if (child == null || !key.startsWith(child.label, i)) return List.of();
            i += child.label.length();
            n = child;
        }
        return n.values == null ? List.of() : (List<T>) List.copyOf(n.values);
    }

    /**
     * Finds the highest node whose whole subtree matches {@code prefix}. The prefix
     * may end part-way along that node's incoming edge. {@code pathOut} receives the
     * full string spelled from the root to the returned node.
     */
    private Node locatePrefix(String prefix, StringBuilder pathOut, OpStats stats) {
        Node n = root;
        int i = 0;
        while (i < prefix.length()) {
            if (stats != null) stats.nodesVisited++;
            Node child = n.child(prefix.charAt(i));
            if (child == null) return null;
            String lab = child.label;
            int m = Math.min(lab.length(), prefix.length() - i);
            for (int j = 0; j < m; j++) {
                if (stats != null) stats.charComparisons++;
                if (lab.charAt(j) != prefix.charAt(i + j)) return null;
            }
            pathOut.append(lab);
            i += m;
            n = child;
        }
        return n;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> valuesWithPrefix(String prefix, int limit, OpStats stats) {
        PrefixIndex.checkQuery(prefix, limit);
        List<T> out = new ArrayList<>();
        Node start = locatePrefix(prefix, new StringBuilder(), stats);
        if (start == null || limit == 0) return out;
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
        StringBuilder sb = new StringBuilder();
        Node start = locatePrefix(prefix, sb, stats);
        if (start == null || limit == 0) return out;
        collectKeys(start, sb, out, limit, stats);
        return out;
    }

    private boolean collectKeys(Node n, StringBuilder sb, List<String> out, int limit, OpStats stats) {
        if (stats != null) stats.nodesVisited++;
        if (n.isTerminal()) {
            out.add(sb.toString());
            if (out.size() >= limit) return true;
        }
        for (int i = 0; i < n.size; i++) {
            int len = sb.length();
            sb.append(n.kids[i].label);
            boolean full = collectKeys(n.kids[i], sb, out, limit, stats);
            sb.setLength(len);
            if (full) return true;
        }
        return false;
    }

    @Override
    public int keyCount() {
        return keyCount;
    }

    @Override
    public int nodeCount() {
        return nodeCount;
    }

    /** Verifies structural invariants and the node counter. Throws on violation. */
    void checkInvariants() {
        int seen = 0;
        ArrayList<Node> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            seen++;
            if (n != root) {
                if (n.label.isEmpty()) throw new IllegalStateException("empty label");
                if (!n.isTerminal() && n.size < 2)
                    throw new IllegalStateException("non-terminal node '" + n.label + "' with " + n.size + " children");
            }
            for (int i = 0; i < n.size; i++) {
                if (n.keys[i] != n.kids[i].label.charAt(0)) throw new IllegalStateException("index/label mismatch");
                if (i > 0 && n.keys[i - 1] >= n.keys[i]) throw new IllegalStateException("children not sorted");
                stack.add(n.kids[i]);
            }
        }
        if (seen != nodeCount) throw new IllegalStateException("nodeCount " + nodeCount + " != actual " + seen);
    }
}
