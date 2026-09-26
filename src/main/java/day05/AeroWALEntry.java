package day05;

import java.util.Optional;

/**
 * One decoded WAL log line: either a SET carrying an absolute expiry
 * timestamp, or a DEL. Centralizes the log line format so AeroWAL's
 * writer and reader (recover/compact) can't drift apart.
 */
public final class AeroWALEntry {
    public enum Op { SET, HOLD, DEL }

    private final Op op;
    private final String key;
    private final String value; // null for DEL
    private final long expiresAtMillis; // absolute epoch millis; -1 = never expires; unused for DEL

    private AeroWALEntry(Op op, String key, String value, long expiresAtMillis) {
        this.op = op;
        this.key = key;
        this.value = value;
        this.expiresAtMillis = expiresAtMillis;
    }

    public static AeroWALEntry set(String key, String value, long expiresAtMillis) {
        return new AeroWALEntry(Op.SET, key, value, expiresAtMillis);
    }

    /** A reservation (HOLD/MHOLD): replayed as a pinned entry so a restart cannot make it evictable. */
    public static AeroWALEntry hold(String key, String value, long expiresAtMillis) {
        return new AeroWALEntry(Op.HOLD, key, value, expiresAtMillis);
    }

    public static AeroWALEntry del(String key) {
        return new AeroWALEntry(Op.DEL, key, null, -1);
    }

    public String encode() {
        return switch (op) {
            case SET -> "SET," + key + "," + value + "," + expiresAtMillis + "\n";
            case HOLD -> "HOLD," + key + "," + value + "," + expiresAtMillis + "\n";
            case DEL -> "DEL," + key + "\n";
        };
    }

    /** Empty if the line is blank, malformed, or an unrecognized op. */
    public static Optional<AeroWALEntry> decode(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        String[] parts = line.split(",", 4);
        String op = parts[0].trim();

        if ("DEL".equalsIgnoreCase(op) && parts.length >= 2) {
            return Optional.of(del(parts[1].trim()));
        }
        boolean isHold = "HOLD".equalsIgnoreCase(op);
        if ((isHold || "SET".equalsIgnoreCase(op) || "PUT".equalsIgnoreCase(op)) && parts.length >= 4) {
            String key = parts[1].trim();
            String value = parts[2].trim();
            long expiresAtMillis;
            try {
                expiresAtMillis = Long.parseLong(parts[3].trim());
            } catch (NumberFormatException e) {
                expiresAtMillis = -1;
            }
            return Optional.of(isHold ? hold(key, value, expiresAtMillis) : set(key, value, expiresAtMillis));
        }
        return Optional.empty();
    }

    public Op getOp() { return op; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public long getExpiresAtMillis() { return expiresAtMillis; }
}
