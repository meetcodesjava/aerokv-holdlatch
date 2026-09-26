package day04;

import static org.junit.jupiter.api.Assertions.*;

import day04.AeroConcurrentLRU.HoldResult;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AeroConcurrentLRUTest {

    @Test
    void liveHoldIsNeverEvictedAndNewKeyIsRejectedWhenFull() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A1", "u1", 60_000));
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A2", "u2", 60_000));

        assertEquals(HoldResult.CAPACITY_EXCEEDED, cache.putIfAbsent("A3", "u3", 60_000));
        assertEquals("u1", cache.get("A1"));
        assertEquals("u2", cache.get("A2"));
    }

    @Test
    void expiredEntryIsEvictedToMakeRoom() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(1, 4);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A1", "u1", 50));
        Thread.sleep(120);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A2", "u2", 60_000));
        assertNull(cache.get("A1"));
        assertEquals("u2", cache.get("A2"));
    }

    @Test
    void expiredHoldCanBeReacquired() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(10, 4);
        cache.putIfAbsent("A1", "u1", 50);
        assertEquals(HoldResult.CONFLICT, cache.putIfAbsent("A1", "u2", 60_000));
        Thread.sleep(120);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A1", "u2", 60_000));
        assertEquals("u2", cache.get("A1"));
    }

    @Test
    void releaseFreesTheKey() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(10, 4);
        cache.putIfAbsent("A1", "u1", 60_000);
        cache.release("A1");
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A1", "u2", 60_000));
    }

    @Test
    void holdAllIsAllOrNothing() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(10, 4);
        cache.putIfAbsent("B2", "other", 60_000);

        assertEquals(HoldResult.CONFLICT, cache.holdAll(List.of("B1", "B2", "B3"), "u1", 60_000));
        assertNull(cache.get("B1"));
        assertNull(cache.get("B3"));

        assertEquals(HoldResult.ACQUIRED, cache.holdAll(List.of("C1", "C2"), "u1", 60_000));
        assertEquals("u1", cache.get("C1"));
        assertEquals("u1", cache.get("C2"));
    }

    @Test
    void holdAllRollsBackWhenCapacityRunsOut() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertEquals(HoldResult.CAPACITY_EXCEEDED, cache.holdAll(List.of("S1", "S2", "S3"), "u1", 60_000));
        assertNull(cache.get("S1"));
        assertNull(cache.get("S2"));
    }

    @Test
    void exactlyOneThreadWinsContestedSeat() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(100, 16);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String user = "u" + i;
            pool.submit(() -> {
                start.await();
                if (cache.putIfAbsent("HOT", user, 60_000) == HoldResult.ACQUIRED) winners.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, winners.get());
    }

    @Test
    void opposingOrderMultiSeatHoldsDoNotDeadlock() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(1000, 16);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 200; round++) {
            String a = "R" + round + "-1", b = "R" + round + "-2";
            var f1 = pool.submit(() -> cache.holdAll(List.of(a, b), "x", 60_000));
            var f2 = pool.submit(() -> cache.holdAll(List.of(b, a), "y", 60_000));
            f1.get(5, java.util.concurrent.TimeUnit.SECONDS);
            f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    @Test
    void releaseIfOwnerOnlyRemovesTheCallersOwnHold() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(10, 4);
        cache.putIfAbsent("A1", "holdA", 60_000);

        assertFalse(cache.releaseIfOwner("A1", "someone-else"));
        assertEquals("holdA", cache.get("A1"));
        assertFalse(cache.releaseIfOwner("missing", "holdA"));

        assertTrue(cache.releaseIfOwner("A1", "holdA"));
        assertNull(cache.get("A1"));
    }

    @Test
    void lateReleaseCannotFreeAHoldThatWasReacquiredAfterExpiry() throws Exception {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(10, 4);
        cache.putIfAbsent("A1", "oldHold", 50);
        Thread.sleep(120);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("A1", "newHold", 60_000));

        assertFalse(cache.releaseIfOwner("A1", "oldHold"));
        assertEquals("newHold", cache.get("A1"));
    }

    @Test
    void plainEntriesWithoutATtlAreStillEvictedOldestFirst() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertTrue(cache.put("old", "1"));
        assertTrue(cache.put("newer", "2"));
        cache.get("old");
        assertTrue(cache.put("newest", "3"));

        assertNull(cache.get("newer"), "least recently used plain entry goes first");
        assertEquals("1", cache.get("old"));
        assertEquals("3", cache.get("newest"));
    }

    @Test
    void aLiveHoldSurvivesWhileOrdinaryEntriesAreEvictedAroundIt() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertEquals(HoldResult.ACQUIRED, cache.putIfAbsent("seat", "holder", 60_000));
        assertTrue(cache.put("plain1", "x"));
        assertTrue(cache.put("plain2", "y"));

        assertEquals("holder", cache.get("seat"));
        assertNull(cache.get("plain1"));
    }

    @Test
    void setEntriesWithATtlAreOrdinaryCacheDataAndAreEvictedLikeAnyOther() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertTrue(cache.put("a", "1", 120_000));
        assertTrue(cache.put("b", "2", 120_000));
        assertTrue(cache.put("c", "3", 120_000), "a TTL alone must not pin an entry; only a hold does");

        assertNull(cache.get("a"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void aPinnedEntryRestoredFromTheLogStaysProtected() {
        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        assertTrue(cache.putPinned("seat", "holder", 60_000));
        assertTrue(cache.put("x", "1"));
        assertTrue(cache.put("y", "2"));

        assertEquals("holder", cache.get("seat"));
        assertNull(cache.get("x"));
    }
}
