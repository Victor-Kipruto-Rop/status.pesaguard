package com.pesaguard.backend.analytics.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageBucket.Aggregates;

/**
 * Aggregates raw request events into a usage bucket.
 *
 * <p>Two properties drive the whole design:
 *
 * <ul>
 *   <li><b>Recompute, never increment.</b> The bucket is replaced with absolute
 *       values computed from the raw events for its window. Running the aggregation
 *       twice gives the same numbers, so recovering from a failure is just running
 *       it again. This is what makes a mid-rollup crash harmless instead of
 *       corrupting a customer's usage figures.</li>
 *   <li><b>Late events land in the right window.</b> An event is assigned to a
 *       bucket by {@code occurredAt}, never by when it was recorded. A backlog
 *       flushed after midnight belongs to yesterday's window, not today's.</li>
 * </ul>
 *
 * <p>Duplicate suppression happens upstream, at ingestion, on the request id's
 * unique constraint. By the time events reach here they are already distinct.
 */
public final class UsageAggregator {

    private UsageAggregator() {
    }

    /**
     * Folds a set of raw events into a bucket.
     *
     * @param events the events belonging to this bucket's window
     * @param lateEvents how many of them arrived after the bucket was first closed
     * @return the recomputed aggregates, or null when there is nothing to record
     */
    public static Aggregates aggregate(List<ApiRequestEvent> events, int lateEvents) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        long total = 0;
        long successful = 0;
        long failed = 0;
        long clientErrors = 0;
        long serverErrors = 0;
        long latencySum = 0;
        long latencyMax = 0;
        long responseBytes = 0;
        boolean anyBytes = false;
        Instant latest = null;
        List<Integer> latencies = new ArrayList<>(events.size());

        for (ApiRequestEvent event : events) {
            total++;
            if (event.isSuccessful()) {
                successful++;
            }
            if (event.isFailure()) {
                failed++;
            }
            if (event.isClientError()) {
                clientErrors++;
            }
            if (event.isServerError()) {
                serverErrors++;
            }
            latencySum += event.getLatencyMs();
            latencyMax = Math.max(latencyMax, event.getLatencyMs());
            latencies.add(event.getLatencyMs());
            if (event.getResponseBytes() != null) {
                anyBytes = true;
                responseBytes += event.getResponseBytes();
            }
            if (latest == null || event.getOccurredAt().isAfter(latest)) {
                latest = event.getOccurredAt();
            }
        }

        // Sorted once, then indexed for every percentile. Computing each percentile
        // with its own sort would be three extra passes over the same data.
        latencies.sort(Comparator.naturalOrder());
        return new Aggregates(total, successful, failed, clientErrors, serverErrors,
                latencySum, latencyMax,
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                anyBytes ? responseBytes : null, latest);
    }

    /**
     * Nearest-rank percentile over a pre-sorted list.
     *
     * <p>Nearest-rank rather than interpolating: a latency percentile should be a
     * latency someone actually observed. Interpolating between two samples invents
     * a number no request ever had, which is misleading when an operator uses it to
     * decide whether to raise a timeout.
     *
     * @param sorted ascending latency samples, non-empty
     * @param percentile 0-100
     */
    static long percentile(List<Integer> sorted, int percentile) {
        if (sorted == null || sorted.isEmpty()) {
            return 0L;
        }
        int bounded = Math.max(0, Math.min(100, percentile));
        // ceil(p/100 * n) - 1, clamped into range.
        int rank = (int) Math.ceil((bounded / 100.0d) * sorted.size()) - 1;
        if (rank < 0) {
            rank = 0;
        }
        if (rank >= sorted.size()) {
            rank = sorted.size() - 1;
        }
        return sorted.get(rank);
    }

    /**
     * Whether a window is safe to close.
     *
     * <p>A window is only final once it is in the past <em>and</em> old enough
     * that a reasonable backlog has arrived. Closing eagerly would roll up a window
     * and then have to correct it when a late event showed up.
     */
    public static boolean isWindowClosed(Instant windowEnd, Instant now,
            java.time.Duration latenessAllowance) {
        if (windowEnd == null || now == null) {
            return false;
        }
        return now.isAfter(windowEnd.plus(latenessAllowance));
    }

    /**
     * Applies a recomputed set to a bucket.
     *
     * <p>Exists so the "replace, never add" rule is expressed once and cannot be
     * bypassed by a caller incrementing fields directly.
     */
    public static void apply(UsageBucket bucket, List<ApiRequestEvent> events, int lateEvents) {
        Aggregates aggregates = aggregate(events, lateEvents);
        if (aggregates != null) {
            bucket.replaceAggregates(aggregates, lateEvents);
        }
    }
}