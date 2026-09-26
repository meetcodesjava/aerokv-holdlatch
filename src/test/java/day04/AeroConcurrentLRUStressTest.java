package day04;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Reads, holds, releases and overwrites hammering overlapping keys from many
 * threads at once, on a cache small enough to evict constantly. The shared LRU
 * list must never be corrupted: no exceptions, no cycles, no duplicate keys,
 * and byte accounting that still adds up.
 */
class AeroConcurrentLRUStressTest {

    @RepeatedTest(5)
    void mixedConcurrentOperationsLeaveTheCacheStructurallySound() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(64, 16, 4_096);
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);

        for (int t = 0; t < threads; t++) {
            String me = "owner-" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (System.nanoTime() < deadline) {
                        String key = "k" + rnd.nextInt(200);
                        switch (rnd.nextInt(7)) {
                            case 0 -> cache.get(key);
                            case 1 -> cache.put(key, "v" + rnd.nextInt(1_000), rnd.nextBoolean() ? 30 : -1);
                            case 2 -> cache.putIfAbsent(key, me, 30);
                            case 3 -> cache.releaseIfOwner(key, me);
                            case 4 -> cache.remove(key);
                            case 5 -> cache.holdAll(List.of(key, "k" + rnd.nextInt(200) + "x"), me, 30);
                            default -> cache.get("k" + rnd.nextInt(200));
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "operations threw: " + failures.peek());

        // If the linked list were corrupted, walking it could loop forever - hence the timeout.
        List<AeroConcurrentLRU.LiveEntry> live = assertTimeoutPreemptively(Duration.ofSeconds(10), cache::snapshotLiveEntries);
        Set<String> keys = new HashSet<>();
        for (AeroConcurrentLRU.LiveEntry entry : live) {
            assertTrue(keys.add(entry.key()), "key listed twice: " + entry.key());
        }
        assertTrue(live.size() <= 64, "capacity exceeded: " + live.size());
        assertTrue(cache.getTotalBytes() >= 0, "byte accounting went negative");

        long expectedBytes = live.stream().mapToLong(e -> String.valueOf(e.value()).length()).sum();
        // Expired-but-unswept entries may still be counted, so the tracked total can only be at or above the live total.
        assertTrue(cache.getTotalBytes() >= expectedBytes, "tracked bytes " + cache.getTotalBytes() + " < live bytes " + expectedBytes);
        assertEquals(live.size(), keys.size());
    }
}
