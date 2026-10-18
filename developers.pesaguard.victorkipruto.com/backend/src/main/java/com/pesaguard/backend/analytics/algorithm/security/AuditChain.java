package com.pesaguard.backend.analytics.algorithm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Hash-chained, tamper-evident audit log.
 *
 * <p>An audit log is only worth keeping if its integrity can be proven. A hash chain
 * gives that: each entry's hash covers its own content <em>and</em> the hash of the
 * entry before it. The result is that altering any past entry invalidates the hash of
 * that entry and every entry after it, because each later hash was computed from the
 * tampered value's original successor. Detecting tampering is then a single forward
 * pass recomputing the chain and checking each link.
 *
 * <p>The first entry chains from a genesis hash. The genesis is fixed here to the
 * SHA-256 of an empty string so the very first entry is anchored to a known value
 * rather than to something an attacker could choose; in a deployed system the genesis
 * would be a value agreed out of band and recorded independently.
 *
 * <p>The chain binds the entries, not the storage. This class proves that the
 * <em>sequence of entries</em> is internally consistent and unmodified; it does not by
 * itself prove the entries were persisted (that requires writing them durably) nor
 * that the chain was not truncated (that requires the head hash to be published or
 * signed externally). Both limitations are inherent to an in-process chain and are
 * called out rather than hidden.
 *
 * <p>The chain hash uses SHA-256 from the JDK; no custom hashing is invented.
 */
public final class AuditChain {

    /** The genesis hash: SHA-256 of empty input, anchoring the first entry. */
    public static final String GENESIS_HASH = sha256Hex("");

    private AuditChain() {
    }

    /** One audit entry and its position in the chain. */
    public record Entry(long sequence, String actor, String action, String subject,
                        long timestampMillis, String previousHash, String hash) {
    }

    /** Mutable builder that appends entries and maintains the running chain. */
    public static final class ChainBuilder {
        private final List<Entry> entries = new ArrayList<>();
        private String previousHash = GENESIS_HASH;

        private ChainBuilder() {
        }

        /**
         * Append a new entry to the chain.
         *
         * <p>The entry's hash is computed over a canonical serialisation of its fields
         * plus the previous hash. A field separator is used that cannot appear inside
         * the fields (a control character) so that two different field splits cannot
         * collide into the same serialised string - a length-prefix would be stronger
         * still and is a reasonable hardening if fields could contain arbitrary bytes.
         */
        public Entry append(String actor, String action, String subject, long timestampMillis) {
            long sequence = entries.size();
            String canonical = sequence + "\u001f" + actor + "\u001f" + action + "\u001f"
                    + subject + "\u001f" + timestampMillis + "\u001f" + previousHash;
            String hash = sha256Hex(canonical);
            Entry entry = new Entry(sequence, actor, action, subject, timestampMillis,
                    previousHash, hash);
            entries.add(entry);
            previousHash = hash;
            return entry;
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }

        public String headHash() {
            return previousHash;
        }
    }

    /** Start a new, empty chain anchored at the genesis hash. */
    public static ChainBuilder newChain() {
        return new ChainBuilder();
    }

    /**
     * Verify the integrity of a chain of entries.
     *
     * <p>Recomputes every hash from its content and the preceding hash and checks that
     * each stored hash matches and each stored previous-hash equals the prior entry's
     * hash. Returns true only if the whole chain is consistent from the first entry
     * through the head.
     *
     * <p>A chain verified here is tamper-evident, not tamper-proof: it detects
     * modification after the fact. It cannot detect an entry that was never written,
     * which is why durable persistence and an externally-published head hash are part
     * of the complete control, not this class's responsibility alone.
     */
    public static boolean verify(List<Entry> entries) {
        String expectedPrevious = GENESIS_HASH;
        for (Entry entry : entries) {
            if (!entry.previousHash().equals(expectedPrevious)) {
                return false;
            }
            String canonical = entry.sequence() + "\u001f" + entry.actor() + "\u001f"
                    + entry.action() + "\u001f" + entry.subject() + "\u001f"
                    + entry.timestampMillis() + "\u001f" + entry.previousHash();
            String recomputed = sha256Hex(canonical);
            if (!entry.hash().equals(recomputed)) {
                return false;
            }
            expectedPrevious = entry.hash();
        }
        return true;
    }

    /** SHA-256 of a string, hex-encoded lower-case. */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HmacVerifier.toHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute SHA-256", e);
        }
    }
}
