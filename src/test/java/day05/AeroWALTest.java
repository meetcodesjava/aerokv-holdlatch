package day05;

import static org.junit.jupiter.api.Assertions.*;

import day04.AeroConcurrentLRU;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AeroWALTest {

    @Test
    void entryRoundTrips() {
        var set = AeroWALEntry.decode(AeroWALEntry.set("A1", "u1", 123L).encode().trim()).orElseThrow();
        assertEquals("A1", set.getKey());
        assertEquals("u1", set.getValue());
        assertEquals(123L, set.getExpiresAtMillis());

        var del = AeroWALEntry.decode("DEL,A1").orElseThrow();
        assertEquals(AeroWALEntry.Op.DEL, del.getOp());
    }

    @Test
    void recoveryRestoresRemainingTtlAndDropsExpired(@TempDir Path dir) throws Exception {
        File log = dir.resolve("wal.log").toFile();
        long now = System.currentTimeMillis();
        Files.writeString(log.toPath(),
                "SET,live," + "u1," + (now + 60_000) + "\n"
              + "SET,dead," + "u2," + (now - 1_000) + "\n"
              + "SET,forever,u3,-1\n"
              + "SET,released,u4," + (now + 60_000) + "\n"
              + "DEL,released\n");

        AeroConcurrentLRU cache = new AeroConcurrentLRU(100, 4);
        new AeroWAL(log.getPath()).recover(cache);

        assertEquals("u1", cache.get("live"));
        assertNull(cache.get("dead"));
        assertEquals("u3", cache.get("forever"));
        assertNull(cache.get("released"));
        // still a real hold, not permanent: a second acquire must conflict
        assertEquals(AeroConcurrentLRU.HoldResult.CONFLICT, cache.putIfAbsent("live", "x", 1000));
    }

    @Test
    void holdsComeBackPinnedAfterARestartAndSurviveCompaction(@TempDir Path dir) throws Exception {
        File log = dir.resolve("wal.log").toFile();
        long now = System.currentTimeMillis();
        Files.writeString(log.toPath(), "HOLD,seat1,holderA," + (now + 60_000) + "\nSET,plain1,x,-1\n");

        AeroConcurrentLRU cache = new AeroConcurrentLRU(2, 4);
        AeroWAL wal = new AeroWAL(log.getPath());
        wal.recover(cache);
        wal.compact(cache);

        assertTrue(Files.readString(log.toPath()).contains("HOLD,seat1,holderA,"), "compaction must keep the hold marked as a hold");

        AeroConcurrentLRU second = new AeroConcurrentLRU(2, 4);
        new AeroWAL(log.getPath()).recover(second);
        second.put("n1", "1");
        second.put("n2", "2");
        assertEquals("holderA", second.get("seat1"));
    }
}
