package day03;

import day02.AeroMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class AeroLRU {
    private final int capacity;
    private int size;
    private final AeroMap map;
    private LRUNode head;
    private LRUNode tail;

    public AeroLRU(int capacity) {
        this.capacity = capacity;
        this.size = 0;
        this.map = new AeroMap();
    }

    public Object get(String key) {
        LRUNode node = (LRUNode) map.get(key);
        if (node == null) {
            return null;
        }
        moveToHead(node);
        return node.val;
    }

    /**
     * Inserts/updates a key. When inserting a brand-new key into a full
     * cache, evicts the least-recently-used entry that satisfies
     * isEvictable (skipping past any that don't, e.g. a still-live hold).
     * Returns false without inserting if the cache is full and no entry
     * satisfies isEvictable - callers decide how to handle that (e.g.
     * reject the write rather than silently corrupting a live entry).
     */
    public boolean put(String key, Object val, Predicate<Object> isEvictable) {
        LRUNode existingNode = (LRUNode) map.get(key);
        if (existingNode != null) {
            existingNode.val = val;
            moveToHead(existingNode);
            return true;
        }

        if (size >= capacity && evictFirstMatching(isEvictable) == null) {
            return false;
        }

        LRUNode newNode = new LRUNode(key, val);
        addToHead(newNode);
        map.put(key, newNode);
        size++;
        return true;
    }

    public void remove(String key) {
        LRUNode node = (LRUNode) map.get(key);
        if (node != null) {
            removeNode(node);
            map.put(key, null);
            size--;
        }
    }

    private void addToHead(LRUNode node) {
        node.next = head;
        node.prev = null;

        if (head != null) {
            head.prev = node;
        }
        head = node;

        if (tail == null) {
            tail = head;
        }
    }

    private void removeNode(LRUNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
    }

    private void moveToHead(LRUNode node) {
        removeNode(node);
        addToHead(node);
    }

    /**
     * Scans from the least-recently-used end and evicts the first node
     * whose value satisfies isEvictable, returning its key/value - or null
     * if nothing in the whole list matches (e.g. every entry is a live,
     * non-expired hold). A live entry is skipped rather than evicted, so
     * it is never silently dropped to make room for something else.
     */
    public Map.Entry<String, Object> evictFirstMatching(Predicate<Object> isEvictable) {
        LRUNode curr = tail;
        while (curr != null) {
            if (isEvictable.test(curr.val)) {
                String key = curr.key;
                Object val = curr.val;
                removeNode(curr);
                map.put(key, null);
                size--;
                return Map.entry(key, val);
            }
            curr = curr.prev;
        }
        return null;
    }

    public int getSize() {
        return size;
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Key of the current least-recently-used entry, without changing order.
     * Used by the byte-budget eviction in AeroConcurrentLRU. Returns null
     * if the cache is empty.
     */
    public String peekTailKey() {
        return tail != null ? tail.key : null;
    }

    /**
     * Value of the current least-recently-used entry, without changing
     * order. Used by the byte-budget eviction in AeroConcurrentLRU.
     */
    public Object peekTailValue() {
        return tail != null ? tail.val : null;
    }

    /**
     * Snapshot of all live entries (key -> raw stored value), for WAL
     * compaction. Not safe to call concurrently with writers; callers must
     * only use this while traffic is quiesced (e.g. startup/shutdown).
     */
    public List<Map.Entry<String, Object>> snapshotEntries() {
        List<Map.Entry<String, Object>> list = new ArrayList<>(size);
        LRUNode curr = head;
        while (curr != null) {
            list.add(Map.entry(curr.key, curr.val));
            curr = curr.next;
        }
        return list;
    }
}
