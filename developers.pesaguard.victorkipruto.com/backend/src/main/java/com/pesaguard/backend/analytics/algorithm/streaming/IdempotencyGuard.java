package com.pesaguard.backend.analytics.algorithm.streaming;

import java.util.HashMap;
import java.util.Map;

/**
 * Idempotency guard for processed events.
 *
 * <p>Financial event processing must be safe to repeat. Provider webhooks arrive more
 * than once, consumers re-deliver after a crash, and operators replay topics. If
 * processing a retried event produces a second financial effect - a second ledger
 * posting, a second settlement - money is duplicated. An idempotency guard makes
 * "process this key" a no-op when the key has already been handled, so a retry changes
 * nothing.
 *
 * <p>The guard distinguishes two outcomes. {@link #acquire} claims a key and returns
 * whether the claim was granted; a granted claim means "you are the first to process
 * this, proceed", a denied claim means "already processed, do not act again". Crucially
 * the caller must {@link #complete} or {@link #release} a claimed key: completing marks
 * it permanently processed, releasing frees it so a failed attempt can be retried.
 * Without release, a transient failure would leave the key claimed forever and the
 * event would be silently dropped - the exact failure the platform rules forbid.
 *
 * <p>In-memory here, so a single instance is the unit of idempotency. A multi-instance
 * deployment must back this with a shared store (e.g. a unique constraint on a
 * processed-events table) because two instances holding separate maps can each believe
 * they are first. The semantics - claim, complete, release - are the ones that must
 * survive that swap.
 *
 * <p>Not thread-safe; synchronise externally or use a concurrent store for the
 * distributed form.
 */
public final class IdempotencyGuard {

    /** The lifecycle of a claimed key. */
    private enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    private record Entry(Status status, long completedAtMillis) {
    }

    private final Map<String, Entry> processed = new HashMap<>();

    /**
     * The outcome of a claim attempt.
     *
     * @param granted whether the caller is the first to hold this key
     * @param reason  "granted", "already completed", or "already in progress"
     */
    public record Claim(boolean granted, String reason) {
    }

    /**
     * Attempt to claim a key for processing.
     *
     * <p>Returns granted=true only when the key has never been completed and is not
     * currently held. A denied claim is not an error - it is the normal, expected
     * outcome when a duplicate delivery arrives, and the caller should treat it as
     * "nothing to do".
     */
    public Claim acquire(String key) {
        Entry existing = processed.get(key);
        if (existing == null) {
            processed.put(key, new Entry(Status.IN_PROGRESS, 0L));
            return new Claim(true, "granted");
        }
        if (existing.status() == Status.COMPLETED) {
            return new Claim(false, "already completed");
        }
        return new Claim(false, "already in progress");
    }

    /**
     * Mark a previously-acquired key as fully processed.
     *
     * <p>After this, every future {@link #acquire} for the key is denied, permanently.
     * Only call this once the financial effect has been durably committed, never
     * before - marking complete before the write lands would turn a crash into lost
     * money.
     */
    public void complete(String key, long nowMillis) {
        processed.put(key, new Entry(Status.COMPLETED, nowMillis));
    }

    /**
     * Release a claimed key without completing it, so a later attempt can retry.
     *
     * <p>Used when processing failed before committing. This is the escape hatch that
     * keeps a failed attempt from becoming a silently-dropped event.
     */
    public void release(String key) {
        processed.remove(key);
    }

    /** Whether a key has been fully processed. */
    public boolean isCompleted(String key) {
        Entry entry = processed.get(key);
        return entry != null && entry.status() == Status.COMPLETED;
    }

    /** Number of tracked keys, for observability. */
    public int size() {
        return processed.size();
    }
}
