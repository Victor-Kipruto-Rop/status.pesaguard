package com.pesaguard.backend.analytics.algorithm.partitioning;

import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent hashing for key-based partitioning.
 *
 * <p>When events are spread across Kafka partitions by key, the naive scheme
 * {@code partition = hash(key) mod partitionCount} has a costly property: changing the
 * number of partitions remaps almost every key, so a scale-up or a broker failure
 * reshuffles the entire dataset and destroys ordering guarantees. Consistent hashing
 * places both keys and partitions on a ring and assigns each key to the next partition
 * clockwise. Adding or removing a partition only moves the keys that fall into that
 * partition's arc; the overwhelming majority of keys keep their partition, and so keep
 * their ordering.
 *
 * <p>To avoid one arc of the ring being disproportionately large and its owner
 * receiving a lopsided share of keys, each partition is placed at many virtual points
 * (virtual nodes) on the ring. More virtual nodes mean a more even spread at the cost
 * of a slightly larger ring to search.
 *
 * <p>The key point for financial correctness is that this only chooses *where* an
 * event is processed, never *what* it means. Two events that must stay ordered (the
 * same transaction) must share a key and therefore a partition; events that may be
 * reordered relative to each other should not share a key.
 */
public final class ConsistentHashing {

    /** Virtual nodes per partition; a balance between even spread and ring size. */
    private static final int DEFAULT_VIRTUAL_NODES = 160;

    private final NavigableMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes;

    /**
     * Build the ring over an initial set of partitions.
     *
     * @param partitions    the partition identifiers
     * @param virtualNodes  virtual nodes per partition (>= 1)
     */
    public ConsistentHashing(List<String> partitions, int virtualNodes) {
        if (partitions == null || partitions.isEmpty()) {
            throw new IllegalArgumentException("partitions must not be empty");
        }
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be at least 1");
        }
        this.virtualNodes = virtualNodes;
        for (String partition : partitions) {
            addPartition(partition);
        }
    }

    /** Build the ring with the default virtual-node count. */
    public ConsistentHashing(List<String> partitions) {
        this(partitions, DEFAULT_VIRTUAL_NODES);
    }

    /**
     * Add a partition to the ring.
     *
     * <p>Only the keys that hash into the new partition's arcs are remapped; every
     * other key keeps its existing partition. This is the whole advantage over
     * modulo hashing and is why the operation is safe to perform while traffic flows.
     */
    public void addPartition(String partition) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(partition + "#" + i);
            ring.put(hash, partition);
        }
    }

    /**
     * Remove a partition from the ring.
     *
     * <p>Keys that mapped to it move to the next partition clockwise. They move to a
     * real partition, never to nothing, so no event is orphaned by the removal.
     */
    public void removePartition(String partition) {
        ring.values().removeIf(partition::equals);
    }

    /**
     * Resolve the partition that owns a key.
     *
     * <p>Finds the first ring point at or after the key's hash, wrapping to the start
     * of the ring (the lowest hash) when the key hashes past the last point - hence the
     * "ring".
     */
    public String getPartition(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("ring is empty");
        }
        long hash = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(hash, true);
        Long point = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(point);
    }

    /**
     * A stable 64-bit hash of a string.
     *
     * <p>A 128-bit MD5 would be stronger, but the FNV-1a 64-bit hash used here has no
     * external dependency, is fast, and has a collision rate low enough that, combined
     * with virtual nodes, it distributes keys evenly. Determinism across JVM restarts
     * is what matters for consistent hashing, and FNV-1a is fully deterministic.
     */
    static long hash(String key) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        // Final avalanche mix so similar keys land far apart on the ring.
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        return hash;
    }

    /** Number of distinct partitions currently on the ring. */
    public int partitionCount() {
        return new java.util.HashSet<>(ring.values()).size();
    }
}
