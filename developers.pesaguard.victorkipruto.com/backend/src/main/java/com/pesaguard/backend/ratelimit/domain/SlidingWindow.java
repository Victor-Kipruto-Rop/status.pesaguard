package com.pesaguard.backend.ratelimit.domain;

/**
 * A sliding-window log counter.
 *
 * <p>Records individual request timestamps and counts only those inside the
 * window. Unlike a fixed window this cannot be defeated at a boundary: a fixed
 * window over 00:00-01:00 and 01:00-02:00 would let a caller spend the full limit
 * at 00:59 and again at 01:01, twice the configured rate within two seconds.
 *
 * <p>The cost is memory proportional to traffic inside the window, which is why
 * {@link #pruned} must be called on every touch. A distributed implementation
 * normally trades exactness for a two-bucket approximation; this is the exact
 * form, used where the limit is not high enough for the approximation's error to
 * matter.
 *
 * <p>Immutable. Every mutation returns a new instance, so a store can hold a
 * reference without risking a partially updated counter.
 */
public final class SlidingWindow {

    private SlidingWindow() {
    }

    /**
     * Count of timestamps within {@code windowMillis} of {@code now}.
     *
     * <p>Timestamps at exactly the window boundary are excluded, so a timestamp
     * belongs to exactly one window and cannot be counted twice across adjacent
     * windows.
     */
    public static int countWithin(long[] timestamps, long now, long windowMillis) {
        if (timestamps == null || timestamps.length == 0) {
            return 0;
        }
        long cutoff = now - windowMillis;
        int count = 0;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff && timestamp <= now) {
                count++;
            }
        }
        return count;
    }

    /**
     * The first timestamp that will age out of the window.
     *
     * <p>Drives {@code Retry-After}: the wait is exactly until the oldest
     * recorded request falls out of the window, because that is the earliest
     * moment a slot can free. Computing it any other way (the whole window, or a
     * fixed guess) would make clients wait longer than necessary or retry too
     * early and be refused again.
     *
     * @return the oldest in-window timestamp, or empty when the window is clear
     */
    public static java.util.Optional<Long> oldestWithin(long[] timestamps, long now,
            long windowMillis) {
        if (timestamps == null || timestamps.length == 0) {
            return java.util.Optional.empty();
        }
        long cutoff = now - windowMillis;
        long oldest = Long.MAX_VALUE;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff && timestamp <= now && timestamp < oldest) {
                oldest = timestamp;
            }
        }
        return oldest == Long.MAX_VALUE ? java.util.Optional.empty()
                : java.util.Optional.of(oldest);
    }

    /**
     * Appends a request, discarding anything that has aged out.
     *
     * <p>Pruning here rather than in a background job is what bounds memory: an
     * untouched counter does not grow, and a busy one stays at the traffic rate
     * rather than accumulating forever.
     */
    public static long[] append(long[] timestamps, long now, long windowMillis) {
        long cutoff = now - windowMillis;
        int survivors = 0;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff) {
                survivors++;
            }
        }
        long[] result = new long[survivors + 1];
        int index = 0;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff) {
                result[index++] = timestamp;
            }
        }
        result[index] = now;
        return result;
    }

    /**
     * The stored timestamps with expired ones removed, without recording a
     * request.
     */
    public static long[] pruned(long[] timestamps, long now, long windowMillis) {
        if (timestamps == null || timestamps.length == 0) {
            return new long[0];
        }
        long cutoff = now - windowMillis;
        int survivors = 0;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff) {
                survivors++;
            }
        }
        if (survivors == timestamps.length) {
            return timestamps;
        }
        long[] result = new long[survivors];
        int index = 0;
        for (long timestamp : timestamps) {
            if (timestamp > cutoff) {
                result[index++] = timestamp;
            }
        }
        return result;
    }

    /** Milliseconds until a slot frees, or 0 when one is already free. */
    public static long retryAfterMillis(long[] timestamps, long now, long windowMillis) {
        return oldestWithin(timestamps, now, windowMillis)
                .map(oldest -> Math.max(1L, oldest + windowMillis - now))
                .orElse(0L);
    }
}