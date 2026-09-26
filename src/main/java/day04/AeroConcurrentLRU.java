package day04;

import day03.AeroLRU;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class AeroConcurrentLRU {

    /** Outcome of an atomic hold attempt (putIfAbsent / holdAll). */
    public enum HoldResult {
        ACQUIRED,
        CONFLICT,           // key already held by a live, non-expired entry
        CAPACITY_EXCEEDED   // cache is full of live (non-evictable) entries, no room to admit a new key
    }

    // Internal wrapper to support Time-To-Live (TTL) and total-memory accounting
    private static class CacheEntry {
        final Object value;
        final long expiresAtMillis;
        final long sizeBytes;

        CacheEntry(Object value, long ttlMillis) {
            this.value = value;
            this.expiresAtMillis = (ttlMillis > 0) ? (System.currentTimeMillis() + ttlMillis) : -1;
            this.sizeBytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        }

        boolean isExpired() {
            return expiresAtMillis != -1 && System.currentTimeMillis() > expiresAtMillis;
        }
    }

    /** One live entry as exposed to callers outside this class (WAL compaction). */
    public record LiveEntry(String key, Object value, long expiresAtMillis) {}

    private static boolean isEvictable(Object rawVal) {
        return rawVal instanceof CacheEntry && ((CacheEntry) rawVal).isExpired();
    }

    private static long sizeOf(Object raw) {
        return (raw instanceof CacheEntry) ? ((CacheEntry) raw).sizeBytes : 0;
    }

    private final AeroLRU cache;
    private final AeroLockManager lockManager;

    // <=0 means "no byte budget enforced" (entry-count capacity still applies).
    private final long maxTotalBytes;
    private final AtomicLong totalBytes = new AtomicLong(0);

    // Guards total-byte accounting and byte-budget eviction only. Note: the
    // per-key stripe locks (lockManager) do NOT protect the single shared
    // AeroLRU's internal linked-list structure across different stripes —
    // that is a pre-existing concurrency gap in this cache, unrelated to
    // memory accounting, and out of scope for this change. memoryLock is
    // nested inside the stripe lock here purely to keep totalBytes correct.
    private final Object memoryLock = new Object();

    public AeroConcurrentLRU(int capacity, int numStripes) {
        this(capacity, numStripes, 0);
    }

    public AeroConcurrentLRU(int capacity, int numStripes, long maxTotalBytes) {
        this.cache = new AeroLRU(capacity);
        this.lockManager = new AeroLockManager(numStripes);
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Thread-safe get with lazy TTL expiration check. Returns the plain
     * stored value (already unwrapped), or null if absent or expired.
     */
    public Object get(String key) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            CacheEntry entry = (CacheEntry) cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                synchronized (memoryLock) {
                    cache.remove(key);
                    totalBytes.addAndGet(-entry.sizeBytes);
                }
                return null;
            }
            return entry.value;
        }
    }

    /**
     * Standard put without expiration (lives until evicted by LRU).
     */
    public boolean put(String key, Object val) {
        return put(key, val, -1);
    }

    /**
     * Put with specific Time-To-Live in milliseconds. Enforces the total
     * byte budget (if configured) and the entry-count capacity, evicting
     * least-recently-used entries first - but only ones that are already
     * expired; a still-live entry is never evicted to make room. Returns
     * false (without writing) if a brand-new key can't be admitted because
     * every entry in the cache is a live hold.
     */
    public boolean put(String key, Object val, long ttlMillis) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            synchronized (memoryLock) {
                CacheEntry newEntry = new CacheEntry(val, ttlMillis);
                CacheEntry existing = (CacheEntry) cache.get(key);
                long oldSize = (existing != null) ? existing.sizeBytes : 0;

                if (maxTotalBytes > 0) {
                    long projected = totalBytes.get() - oldSize + newEntry.sizeBytes;
                    while (projected > maxTotalBytes) {
                        Map.Entry<String, Object> victim = cache.evictFirstMatching(AeroConcurrentLRU::isEvictable);
                        if (victim == null) break;
                        long victimSize = sizeOf(victim.getValue());
                        totalBytes.addAndGet(-victimSize);
                        projected -= victimSize;
                    }
                }

                boolean inserted = cache.put(key, newEntry, AeroConcurrentLRU::isEvictable);
                if (!inserted) {
                    return false;
                }
                totalBytes.addAndGet(newEntry.sizeBytes - oldSize);
                return true;
            }
        }
    }

    /**
     * Atomic put-if-absent: acquires key only if it is not currently held
     * by a live, non-expired entry. See HoldResult for the three outcomes.
     */
    public HoldResult putIfAbsent(String key, Object val, long ttlMillis) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            CacheEntry current = (CacheEntry) cache.get(key);
            if (current != null && !current.isExpired()) {
                return HoldResult.CONFLICT;
            }
            synchronized (memoryLock) {
                long oldSize = (current != null) ? current.sizeBytes : 0;
                CacheEntry newEntry = new CacheEntry(val, ttlMillis);

                if (maxTotalBytes > 0) {
                    long projected = totalBytes.get() - oldSize + newEntry.sizeBytes;
                    while (projected > maxTotalBytes) {
                        Map.Entry<String, Object> victim = cache.evictFirstMatching(AeroConcurrentLRU::isEvictable);
                        if (victim == null) break;
                        long victimSize = sizeOf(victim.getValue());
                        totalBytes.addAndGet(-victimSize);
                        projected -= victimSize;
                    }
                }

                boolean inserted = cache.put(key, newEntry, AeroConcurrentLRU::isEvictable);
                if (!inserted) {
                    return HoldResult.CAPACITY_EXCEEDED;
                }
                totalBytes.addAndGet(newEntry.sizeBytes - oldSize);
            }
            return HoldResult.ACQUIRED;
        }
    }

    /**
     * Atomic multi-key hold: acquires ALL of the given keys, or none of
     * them. Locks every distinct stripe the keys map to, in a fixed
     * ascending order (dedup + sort), before checking or touching any of
     * them - this is what makes it safe against another client's holdAll
     * or putIfAbsent interleaving with only part of this batch. Callers
     * are still expected to pass keys in a stable, agreed order (e.g.
     * sorted seat IDs) so that unrelated multi-key operations naturally
     * lock in the same order too and never deadlock against each other.
     */
    public HoldResult holdAll(List<String> keys, String val, long ttlMillis) {
        TreeSet<Integer> stripeIndices = new TreeSet<>();
        for (String key : keys) {
            stripeIndices.add(lockManager.getStripeIndex(key));
        }
        List<Object> locksInOrder = new ArrayList<>();
        for (int idx : stripeIndices) {
            locksInOrder.add(lockManager.getLockByIndex(idx));
        }

        Supplier<HoldResult> action = () -> {
            for (String key : keys) {
                CacheEntry current = (CacheEntry) cache.get(key);
                if (current != null && !current.isExpired()) {
                    return HoldResult.CONFLICT;
                }
            }

            List<String> inserted = new ArrayList<>();
            synchronized (memoryLock) {
                for (String key : keys) {
                    CacheEntry existing = (CacheEntry) cache.get(key);
                    long oldSize = (existing != null) ? existing.sizeBytes : 0;
                    CacheEntry newEntry = new CacheEntry(val, ttlMillis);

                    if (maxTotalBytes > 0) {
                        long projected = totalBytes.get() - oldSize + newEntry.sizeBytes;
                        while (projected > maxTotalBytes) {
                            Map.Entry<String, Object> victim = cache.evictFirstMatching(AeroConcurrentLRU::isEvictable);
                            if (victim == null) break;
                            long victimSize = sizeOf(victim.getValue());
                            totalBytes.addAndGet(-victimSize);
                            projected -= victimSize;
                        }
                    }

                    boolean ok = cache.put(key, newEntry, AeroConcurrentLRU::isEvictable);
                    if (!ok) {
                        // Roll back everything this call already inserted -
                        // safe because we still hold every relevant stripe
                        // lock, so no other thread can have observed the
                        // partial state.
                        for (String rollbackKey : inserted) {
                            Object raw = cache.get(rollbackKey);
                            if (raw instanceof CacheEntry) {
                                totalBytes.addAndGet(-((CacheEntry) raw).sizeBytes);
                            }
                            cache.remove(rollbackKey);
                        }
                        return HoldResult.CAPACITY_EXCEEDED;
                    }
                    totalBytes.addAndGet(newEntry.sizeBytes - oldSize);
                    inserted.add(key);
                }
            }
            return HoldResult.ACQUIRED;
        };

        return lockAllAndRun(locksInOrder, 0, action);
    }

    private HoldResult lockAllAndRun(List<Object> locks, int index, Supplier<HoldResult> action) {
        if (index == locks.size()) {
            return action.get();
        }
        synchronized (locks.get(index)) {
            return lockAllAndRun(locks, index + 1, action);
        }
    }

    /**
     * Explicit removal of a key.
     */
    public void remove(String key) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            synchronized (memoryLock) {
                Object raw = cache.get(key);
                if (raw instanceof CacheEntry) {
                    totalBytes.addAndGet(-((CacheEntry) raw).sizeBytes);
                }
                cache.remove(key);
            }
        }
    }

    /** Explicit release of a held key. Same effect as remove(); kept as a distinct, intent-revealing name for the HOLD/RELEASE protocol pair. */
    public void release(String key) {
        remove(key);
    }

    /**
     * Compare-and-delete: removes the key only if it is still live and its
     * stored value equals owner. Stops a holder whose hold already expired
     * (and was re-acquired by someone else) from releasing that new hold.
     */
    public boolean releaseIfOwner(String key, String owner) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            synchronized (memoryLock) {
                Object raw = cache.get(key);
                if (!(raw instanceof CacheEntry)) {
                    return false;
                }
                CacheEntry entry = (CacheEntry) raw;
                if (entry.isExpired() || !owner.equals(String.valueOf(entry.value))) {
                    return false;
                }
                totalBytes.addAndGet(-entry.sizeBytes);
                cache.remove(key);
                return true;
            }
        }
    }

    /** Current best-effort total size, in bytes, of all live values. */
    public long getTotalBytes() {
        return totalBytes.get();
    }

    /**
     * Snapshot of all live (non-expired) entries, including each one's
     * absolute expiry timestamp, for WAL compaction. Only safe to call
     * while traffic is quiesced (startup/shutdown), since it does not hold
     * every stripe lock at once.
     */
    public List<LiveEntry> snapshotLiveEntries() {
        List<LiveEntry> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : cache.snapshotEntries()) {
            CacheEntry ce = (CacheEntry) e.getValue();
            if (ce == null || ce.isExpired()) continue;
            out.add(new LiveEntry(e.getKey(), ce.value, ce.expiresAtMillis));
        }
        return out;
    }
}
