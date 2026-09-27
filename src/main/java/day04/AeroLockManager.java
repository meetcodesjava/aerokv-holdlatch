package day04;

public class AeroLockManager {
    private final Object[] locks;
    private final int numStripes;

    public AeroLockManager(int numStripes) {
        this.numStripes = numStripes;
        this.locks = new Object[numStripes];
        for (int i = 0; i < numStripes; i++) {
            this.locks[i] = new Object();
        }
    }

    /**
     * Maps a given key to a specific lock stripe using its hash code.
     */
    public Object getLock(String key) {
        return locks[getStripeIndex(key)];
    }

    /** Stripe index a key hashes to - used to dedupe/order locks for multi-key operations. */
    public int getStripeIndex(String key) {
        int hash = key.hashCode();
        return (hash % numStripes + numStripes) % numStripes;
    }

    /** Lock object for a given stripe index, for callers that already resolved indices (e.g. multi-key holds). */
    public Object getLockByIndex(int stripeIndex) {
        return locks[stripeIndex];
    }
}
