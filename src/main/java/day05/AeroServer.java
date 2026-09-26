package day05;

import day04.AeroConcurrentLRU;
import day04.AeroConcurrentLRU.HoldResult;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AeroServer {
    // Values larger than this are rejected outright rather than accepted
    // into memory unbounded — protects the JVM heap from a single client
    // sending an oversized payload.
    private static final int MAX_VALUE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final ServerSocket serverSocket;
    private final AeroConcurrentLRU cache;
    private final AeroWAL wal;
    // Virtual threads: one per connection, for that connection's entire
    // lifetime (see handleClient's while loop). Unlike a fixed platform-
    // thread pool, this scales to many thousands of concurrently blocked
    // socket connections without exhausting OS threads, so there's no
    // fixed ceiling to size for expected concurrent clients anymore.
    private final ExecutorService threadPool;
    private volatile boolean running;
    // null/blank means "no password required" — every connection starts
    // pre-authenticated, same as before this feature existed.
    private final String requiredPassword;

    public AeroServer(int port, int capacity, int numStripes, String logFilePath) throws IOException {
        this(port, capacity, numStripes, logFilePath, 0, null);
    }

    public AeroServer(int port, int capacity, int numStripes, String logFilePath,
                       long maxTotalBytes, String requiredPassword) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.cache = new AeroConcurrentLRU(capacity, numStripes, maxTotalBytes);
        this.wal = new AeroWAL(logFilePath);
        this.threadPool = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;
        this.requiredPassword = (requiredPassword == null || requiredPassword.isBlank()) ? null : requiredPassword;
        // Order matters: recover() and compact() must both finish reading
        // and rewriting the log file before the async writer thread opens
        // it for append (start()), otherwise compaction can race the
        // writer for the file handle.
        this.wal.recover(this.cache);
        this.wal.compact(this.cache);
        this.wal.start();
    }

    public void start() {
        System.out.println("AeroKV started on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Idle timeout: an unused connection is closed to free its
                // virtual thread eventually, rather than sitting open
                // forever against a client that vanished without closing.
                clientSocket.setSoTimeout(60_000);
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        // Each connection starts unauthenticated unless no password is
        // configured at all, in which case auth is effectively off.
        boolean authenticated = (requiredPassword == null);

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                int firstComma = line.indexOf(',');
                if (firstComma == -1) {
                    if (AeroCommand.PING.equalsIgnoreCase(line)) {
                        out.println("PONG");
                    } else {
                        out.println("ERR_INVALID_FORMAT");
                    }
                    out.flush();
                    continue;
                }

                String command = line.substring(0, firstComma).trim().toUpperCase();

                if (AeroCommand.AUTH.equals(command)) {
                    String suppliedPassword = line.substring(firstComma + 1).trim();
                    if (requiredPassword != null && requiredPassword.equals(suppliedPassword)) {
                        authenticated = true;
                        out.println("OK");
                    } else {
                        out.println("ERR_AUTH_FAILED");
                    }
                    out.flush();
                    continue;
                }

                if (!authenticated) {
                    out.println("ERR_NOT_AUTHENTICATED");
                    out.flush();
                    continue;
                }

                if (AeroCommand.SET.equals(command) || AeroCommand.PUT.equals(command)) {
                    handleSet(line, firstComma, out);
                }
                else if (AeroCommand.HOLD.equals(command)) {
                    handleHold(line, firstComma, out);
                }
                else if (AeroCommand.MHOLD.equals(command)) {
                    handleMultiHold(line, firstComma, out);
                }
                else if (AeroCommand.RELEASE_IF.equals(command)) {
                    handleReleaseIf(line, firstComma, out);
                }
                else if (AeroCommand.RELEASE.equals(command)) {
                    String key = line.substring(firstComma + 1).trim();
                    cache.release(key);
                    wal.logDelete(key);
                    out.println("OK");
                    out.flush();
                }
                else if (AeroCommand.DEL.equals(command) || AeroCommand.DELETE.equals(command)) {
                    String key = line.substring(firstComma + 1).trim();
                    cache.remove(key);
                    wal.logDelete(key);
                    out.println("OK");
                    out.flush();
                }
                else if (AeroCommand.GET.equals(command)) {
                    String key = line.substring(firstComma + 1).trim();
                    Object val = cache.get(key);
                    if (val != null) {
                        out.println("VALUE, " + val);
                    } else {
                        out.println("ERR_NOT_FOUND");
                    }
                    out.flush();
                }
                else {
                    out.println("ERR_UNKNOWN_COMMAND");
                    out.flush();
                }
            }
        } catch (SocketTimeoutException e) {
            // Idle client timed out: connection closes so its virtual thread ends
        } catch (IOException e) {
            // Client disconnected
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // SET,key,value[,ttlMillis] — unconditional write, always overwrites.
    private void handleSet(String line, int firstComma, PrintWriter out) {
        int secondComma = line.indexOf(',', firstComma + 1);
        if (secondComma == -1) {
            out.println("ERR_SYNTAX_ERROR");
            out.flush();
            return;
        }

        String key = line.substring(firstComma + 1, secondComma).trim();
        String[] valAndTtl = splitValueAndTtl(line, secondComma);
        String val = valAndTtl[0];
        long ttl = Long.parseLong(valAndTtl[1]);

        if (val.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            out.println("ERR_VALUE_TOO_LARGE");
            out.flush();
            return;
        }

        boolean inserted = cache.put(key, val, ttl);
        if (!inserted) {
            out.println("ERR_CAPACITY");
        } else {
            wal.logPut(key, val, expiresAt(ttl));
            out.println("OK");
        }
        out.flush();
    }

    // HOLD,key,value,ttlMillis — atomic acquire: succeeds only if key isn't already live-held.
    private void handleHold(String line, int firstComma, PrintWriter out) {
        int secondComma = line.indexOf(',', firstComma + 1);
        if (secondComma == -1) {
            out.println("ERR_SYNTAX_ERROR");
            out.flush();
            return;
        }

        String key = line.substring(firstComma + 1, secondComma).trim();
        String[] valAndTtl = splitValueAndTtl(line, secondComma);
        String val = valAndTtl[0];
        long ttl = Long.parseLong(valAndTtl[1]);

        if (val.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            out.println("ERR_VALUE_TOO_LARGE");
            out.flush();
            return;
        }

        HoldResult result = cache.putIfAbsent(key, val, ttl);
        respondToHoldResult(result, () -> wal.logPut(key, val, expiresAt(ttl)), out);
    }

    // MHOLD,key1|key2|key3,value,ttlMillis — all-or-nothing multi-key acquire.
    private void handleMultiHold(String line, int firstComma, PrintWriter out) {
        int secondComma = line.indexOf(',', firstComma + 1);
        if (secondComma == -1) {
            out.println("ERR_SYNTAX_ERROR");
            out.flush();
            return;
        }

        String keyList = line.substring(firstComma + 1, secondComma).trim();
        List<String> keys = Arrays.asList(keyList.split(AeroCommand.MULTI_KEY_SEPARATOR));
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank)) {
            out.println("ERR_SYNTAX_ERROR");
            out.flush();
            return;
        }

        String[] valAndTtl = splitValueAndTtl(line, secondComma);
        String val = valAndTtl[0];
        long ttl = Long.parseLong(valAndTtl[1]);

        if (val.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            out.println("ERR_VALUE_TOO_LARGE");
            out.flush();
            return;
        }

        HoldResult result = cache.holdAll(keys, val, ttl);
        long expiresAtMillis = expiresAt(ttl);
        respondToHoldResult(result, () -> {
            for (String key : keys) wal.logPut(key, val, expiresAtMillis);
        }, out);
    }

    // RELEASEIF,key,owner — deletes the key only if its current value is owner.
    private void handleReleaseIf(String line, int firstComma, PrintWriter out) {
        int secondComma = line.indexOf(',', firstComma + 1);
        if (secondComma == -1) {
            out.println("ERR_SYNTAX_ERROR");
            out.flush();
            return;
        }
        String key = line.substring(firstComma + 1, secondComma).trim();
        String owner = line.substring(secondComma + 1).trim();
        if (cache.releaseIfOwner(key, owner)) {
            wal.logDelete(key);
            out.println("OK");
        } else {
            out.println("ERR_NOT_HELD");
        }
        out.flush();
    }

    private void respondToHoldResult(HoldResult result, Runnable onAcquired, PrintWriter out) {
        switch (result) {
            case ACQUIRED -> {
                onAcquired.run();
                out.println("OK");
            }
            case CONFLICT -> out.println("ERR_CONFLICT");
            case CAPACITY_EXCEEDED -> out.println("ERR_CAPACITY");
        }
        out.flush();
    }

    private static long expiresAt(long ttlMillis) {
        return ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : -1;
    }

    /**
     * Splits "...,value[,ttlMillis]" starting after secondComma into
     * {value, ttlMillisAsString}. If the trailing token isn't a number,
     * it's treated as part of the value and ttl defaults to "0" (never
     * expires) — same delimiter-safe parsing SET has always used.
     */
    private static String[] splitValueAndTtl(String line, int secondComma) {
        int lastComma = line.lastIndexOf(',');
        if (lastComma > secondComma) {
            String possibleTtl = line.substring(lastComma + 1).trim();
            try {
                Long.parseLong(possibleTtl);
                return new String[] { line.substring(secondComma + 1, lastComma).trim(), possibleTtl };
            } catch (NumberFormatException e) {
                // falls through to treat the whole trailing text as the value
            }
        }
        return new String[] { line.substring(secondComma + 1).trim(), "0" };
    }

    public void stop() throws IOException {
        this.running = false;
        this.serverSocket.close();
        this.wal.shutdown();
        this.threadPool.shutdown();
    }
}
