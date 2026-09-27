package day05;

import day04.AeroConcurrentLRU;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

public class AeroWAL {
    // Caps how many pending writes can queue up waiting for disk, so a
    // sustained burst can't grow the queue (and JVM memory) without limit.
    // Once full, logPut/logDelete block the calling client thread until
    // there is room — natural backpressure instead of unbounded growth.
    private static final int MAX_QUEUE_CAPACITY = 10_000;

    // Sentinel object (not text) used to tell the writer thread to stop.
    // Identity-compared, never written to the log file, unlike the old
    // approach of pushing a literal "SHUTDOWN\n" string through the same
    // queue used for real data.
    private static final Object SHUTDOWN_SIGNAL = new Object();

    private final LinkedBlockingQueue<Object> logQueue;
    private final String logFilePath;
    private Thread writerThread;
    private volatile boolean running;

    public AeroWAL(String logFilePath){
        this.logFilePath=logFilePath;
        this.logQueue=new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
        this.running=true;
        // Writer thread is NOT started here: it must not open the log file
        // for append until after recover()/compact() have finished reading
        // and rewriting it. Call start() once those are done.
    }

    /**
     * Begins asynchronously flushing queued writes to disk. Must be called
     * after recover() and compact(), so the append-mode file handle is only
     * opened once the log file has reached its final startup state.
     */
    public void start(){
        if(writerThread != null) return;
        this.writerThread=new Thread(this::processLogQueue);
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }


    /**
     * Replays the log into cache, restoring each SET with its remaining
     * TTL rather than treating it as permanent - an entry whose absolute
     * expiry has already passed by the time we recover is dropped instead
     * of being resurrected.
     */
    public void recover(AeroConcurrentLRU cache){
        File logFile=new File(logFilePath);
        if(!logFile.exists()){
            System.out.println("No Existing log file found. Staring fresh...");
            return;
        }

        System.out.println("Recovering data from Write-Ahead Log (" + logFilePath + ")...");
        int count=0;
        long now = System.currentTimeMillis();

        try(BufferedReader reader=new BufferedReader(new FileReader(logFile))){
            String line;
            while((line=reader.readLine()) != null){
                var decoded = AeroWALEntry.decode(line);
                if (decoded.isEmpty()) continue;
                AeroWALEntry entry = decoded.get();

                if (entry.getOp() == AeroWALEntry.Op.DEL) {
                    cache.remove(entry.getKey());
                    count++;
                    continue;
                }

                long expiresAt = entry.getExpiresAtMillis();
                if (expiresAt != -1 && expiresAt <= now) {
                    // Already expired by the time we're recovering - drop it
                    // rather than restore an entry that should be gone.
                    continue;
                }
                long remainingTtlMillis = (expiresAt == -1) ? -1 : (expiresAt - now);
                cache.put(entry.getKey(), entry.getValue(), remainingTtlMillis);
                count++;
            }
            System.out.println("Recovery Complete! Replayed " + count + " log entries into cache.");
        }
        catch(IOException e){
            System.err.println("Warning: Error during WAL recovery: " + e.getMessage());
        }
    }

    /**
     * Rewrites the log file to contain only the current live (non-expired)
     * key-value pairs as a single SET per key (preserving each one's
     * absolute expiry), discarding the accumulated history of overwrites,
     * deletes, and expired entries. Must run after recover() and before
     * start(), while no writer thread holds the file.
     */
    public void compact(AeroConcurrentLRU cache){
        File logFile=new File(logFilePath);
        if(!logFile.exists()) return;

        File tmpFile=new File(logFilePath + ".compact.tmp");
        long before = logFile.length();
        int written = 0;

        try(BufferedWriter writer=new BufferedWriter(new java.io.FileWriter(tmpFile, false))){
            for(AeroConcurrentLRU.LiveEntry e : cache.snapshotLiveEntries()){
                writer.write(AeroWALEntry.set(e.key(), String.valueOf(e.value()), e.expiresAtMillis()).encode());
                written++;
            }
        }
        catch(IOException e){
            System.err.println("Warning: WAL compaction failed, keeping existing log: " + e.getMessage());
            tmpFile.delete();
            return;
        }

        if(!logFile.delete() || !tmpFile.renameTo(logFile)){
            System.err.println("Warning: Could not swap in compacted WAL file; keeping pre-compaction log.");
            tmpFile.delete();
            return;
        }

        long after = logFile.length();
        System.out.println("WAL compacted: " + written + " live entries, " + before + " -> " + after + " bytes.");
    }


    /** Logs a SET carrying its absolute expiry timestamp (-1 = never expires). */
    public void logPut(String key, String value, long expiresAtMillis){
        enqueue(AeroWALEntry.set(key, value, expiresAtMillis).encode());
    }

    public void logDelete(String key){
        enqueue(AeroWALEntry.del(key).encode());
    }

    /**
     * Blocks the calling (client-handling) thread if the queue is full,
     * instead of growing it without bound. This is deliberate backpressure:
     * a write only returns once there is room to queue it for durable
     * persistence.
     */
    private void enqueue(String logLine){
        if(!running) return;
        try {
            logQueue.put(logLine);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private void processLogQueue(){
        try(FileOutputStream fos = new FileOutputStream(logFilePath, true);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            while (running || !logQueue.isEmpty()) {
                Object first = logQueue.take();
                if (first == SHUTDOWN_SIGNAL) break;

                writer.write((String) first);

                // Drain whatever else is already waiting so concurrent
                // writes share one flush+fsync (group commit) instead of
                // paying a disk sync per single write.
                Object next;
                while ((next = logQueue.poll()) != null) {
                    if (next == SHUTDOWN_SIGNAL) {
                        running = false;
                        break;
                    }
                    writer.write((String) next);
                }

                writer.flush();
                // Force the write to physical disk, not just the OS
                // buffer, so a completed write really survives a crash
                // or power loss right after the client got "OK".
                fos.getFD().sync();
            }
        }
        catch(IOException|InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }


    public void shutdown(){
        this.running=false;
        try {
            logQueue.put(SHUTDOWN_SIGNAL);
            // Wait for the writer thread to drain and fsync everything
            // still queued, so a graceful shutdown actually finishes
            // writing before the process exits, instead of racing it.
            if (writerThread != null) {
                writerThread.join(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
