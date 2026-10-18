package com.pesaguard.backend.analytics.algorithm.streaming;

/**
 * A Bloom filter for fast duplicate pre-checks.
 *
 * <p>A Bloom filter answers "have I definitely never seen this key?" in constant time
 * and constant, bounded memory. It may return a false positive ("maybe seen") but
 * never a false negative: if it says an element is absent, that element is truly
 * absent. That one-sided error is exactly what makes it the right pre-check for
 * duplicate detection - it can safely veto an obvious duplicate and let everything
 * else through to the authoritative, exact check.
 *
 * <p>It is intentionally a filter, not a set. It cannot enumerate its contents,
 * cannot delete an element, and cannot be the sole authority on whether something is
 * a duplicate. The authoritative duplicate decision must come from the processed-
 * events store, because acting on a Bloom false positive as if it were a confirmed
 * duplicate would drop a genuine transaction and lose money.
 *
 * <p>Hashing uses the double-hashing scheme: two independent base hashes generate all
 * {@code k} bit positions, which avoids computing {@code k} separate hash functions
 * while keeping the positions well distributed.
 */
public final class BloomFilter {

    private final long[] bits;
    private final int bitSize;
    private final int hashFunctions;
    private final int seed;

    /**
     * @param expectedElements anticipated number of distinct keys
     * @param falsePositiveRate target false-positive probability in {@code (0, 1)}
     * @param seed              hash seed; distinct filters should use distinct seeds
     */
    public BloomFilter(int expectedElements, double falsePositiveRate, int seed) {
        if (expectedElements < 1) {
            throw new IllegalArgumentException("expectedElements must be at least 1");
        }
        if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
        }
        // Optimal bit count m = -n ln(p) / (ln 2)^2 and hash count k = (m/n) ln 2.
        this.bitSize = optimalBitSize(expectedElements, falsePositiveRate);
        this.hashFunctions = Math.max(1, optimalHashFunctions(this.bitSize, expectedElements));
        this.seed = seed;
        this.bits = new long[(this.bitSize + Long.SIZE - 1) / Long.SIZE];
    }

    private static int optimalBitSize(int n, double p) {
        double m = -((double) n * Math.log(p)) / (Math.log(2.0) * Math.log(2.0));
        return Math.max(1, (int) Math.ceil(m));
    }

    private static int optimalHashFunctions(int m, int n) {
        return (int) Math.round(((double) m / n) * Math.log(2.0));
    }

    /** Add a key to the filter. */
    public void add(String key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < hashFunctions; i++) {
            // Double hashing: position i = (h1 + i*h2) mod m, all positive.
            long combined = Math.floorMod(h1 + (long) i * h2, bitSize);
            setBit(combined);
        }
    }

    /**
     * Test membership.
     *
     * @return {@code true} if the key was possibly added, {@code false} if it was
     *         definitely never added. A {@code true} is a candidate only; the caller
     *         must confirm with an exact check before acting on it.
     */
    public boolean mightContain(String key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < hashFunctions; i++) {
            long combined = Math.floorMod(h1 + (long) i * h2, bitSize);
            if (!getBit(combined)) {
                return false;
            }
        }
        return true;
    }

    private void setBit(long position) {
        int word = (int) (position >>> 6);
        int offset = (int) (position & (Long.SIZE - 1));
        bits[word] |= (1L << offset);
    }

    private boolean getBit(long position) {
        int word = (int) (position >>> 6);
        int offset = (int) (position & (Long.SIZE - 1));
        return (bits[word] & (1L << offset)) != 0;
    }

    /**
     * First base hash: a seeded FNV-1a over the key bytes. FNV is chosen because it
     * is cheap and has no external dependency; the seed decorrelates separate filters.
     */
    private long hash1(String key) {
        long hash = 0xcbf29ce484222325L ^ seed;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Second base hash, independent of the first so the double-hashing positions do
     * not collapse onto a single line. Mixing the seed differently from {@link #hash1}
     * keeps the two streams decorrelated.
     */
    private long hash2(String key) {
        long hash = 0x9e3779b97f4a7c15L ^ (seed * 0x100000001b3L);
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash = Long.rotateLeft(hash, 27) * 0x94d049bb133111ebL;
        }
        // Ensure h2 is non-zero; a zero stride would probe the same bit every time.
        return hash == 0L ? 1L : hash;
    }
}
