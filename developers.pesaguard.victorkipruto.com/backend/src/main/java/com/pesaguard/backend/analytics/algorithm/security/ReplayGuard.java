package com.pesaguard.backend.analytics.algorithm.security;

/**
 * Replay protection using a nonce set and a timestamp window.
 *
 * <p>An attacker who captures a valid, signed webhook can resend it to make the
 * platform process the same event twice - a duplicate payment, a duplicated
 * settlement. Replay protection defeats that on two axes. First, a timestamp check
 * rejects any message outside an allowed freshness window, so an old capture is
 * useless once its window has closed. Second, a nonce (number-used-once) check
 * rejects any exact duplicate within the window, so even a fresh re-send of the same
 * message is refused.
 *
 * <p>Both are needed. A timestamp alone does not stop an immediate re-send inside the
 * window; a nonce alone requires unbounded state to cover all of history. Together,
 * the timestamp bounds how long a nonce must be remembered.
 *
 * <p>This tracks nonces per key so a nonce from one tenant or one endpoint cannot
 * cancel or collide with another's. Consumed nonces are pruned as the window advances
 * so memory stays bounded. {@link #checkAndConsume} is deliberately the single entry
 * point that both validates and marks a nonce consumed, so callers cannot forget the
 * second half and open a replay hole.
 *
 * <p>Not thread-safe; confine to a single thread or synchronise externally, consistent
 * with the other streaming primitives.
 */
public final class ReplayGuard {

    /** A single observed nonce with the time it was first seen. */
    private record SeenNonce(String nonce, long firstSeenMillis) {
    }

    private final long windowMillis;
    private final java.util.Map<String, java.util.List<SeenNonce>> noncesByKey = new java.util.HashMap<>();
    private long lastPruneMillis;

    /**
     * @param windowMillis how long a message remains acceptable, and how long a nonce
     *                     is remembered after first sight
     */
    public ReplayGuard(long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.windowMillis = windowMillis;
    }

    /**
     * The result of a replay check.
     *
     * @param accepted whether the message is fresh and its nonce unused
     * @param reason   why it was rejected, or {@code "ok"} when accepted
     */
    public record ReplayResult(boolean accepted, String reason) {
    }

    /**
     * Validate freshness and nonce uniqueness, and consume the nonce if accepted.
     *
     * <p>This must be called exactly once per message and its result honoured. If a
     * caller checks but does not consume (or consumes but ignores the result), the
     * guard no longer prevents replay. Combining validation and consumption in one
     * atomic step removes that failure mode.
     *
     * @param key              isolation key (e.g. tenant id or endpoint id)
     * @param nonce            the unique value carried by the message
     * @param timestampMillis  the message's own claimed timestamp
     * @param nowMillis        the current time
     */
    public ReplayResult checkAndConsume(String key, String nonce, long timestampMillis, long nowMillis) {
        if (nonce == null || nonce.isEmpty()) {
            return new ReplayResult(false, "missing nonce");
        }
        // Reject messages outside the freshness window. Allow a small tolerance for
        // clock skew by accepting timestamps up to the window in the past and a little
        // in the future is intentionally not granted: a future timestamp is treated as
        // invalid rather than granting extra life to a replayed message.
        long age = nowMillis - timestampMillis;
        if (age > windowMillis || age < -windowMillis) {
            return new ReplayResult(false, "timestamp outside window");
        }
        prune(nowMillis);
        java.util.List<SeenNonce> nonces =
                noncesByKey.computeIfAbsent(key, k -> new java.util.ArrayList<>());
        for (SeenNonce seen : nonces) {
            if (seen.nonce().equals(nonce)) {
                return new ReplayResult(false, "duplicate nonce");
            }
        }
        nonces.add(new SeenNonce(nonce, nowMillis));
        return new ReplayResult(true, "ok");
    }

    /** Drop nonces that have aged out of the window, per key. */
    private void prune(long nowMillis) {
        if (nowMillis - lastPruneMillis < windowMillis) {
            return;
        }
        lastPruneMillis = nowMillis;
        noncesByKey.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(seen -> nowMillis - seen.firstSeenMillis() > windowMillis);
            return entry.getValue().isEmpty();
        });
    }

    /** Current number of remembered nonces, for observability. */
    public int rememberedCount() {
        return noncesByKey.values().stream().mapToInt(java.util.List::size).sum();
    }
}
