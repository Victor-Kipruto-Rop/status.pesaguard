package com.pesaguard.backend.analytics.algorithm.velocity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sliding-window velocity checks.
 *
 * <p>Velocity measures how many times something happens (or how much money moves)
 * inside a recent time window. It is the simplest and one of the most effective
 * fraud signals: a payer who sends ten transfers in two minutes is behaving very
 * differently from one who sends ten in a week, even though the totals can be
 * identical.
 *
 * <p>The counters here are sliding windows, not fixed buckets. A fixed tumbling
 * window lets an attacker hide a burst across a boundary; a sliding window looks
 * back from the moment of the current event and cannot be gamed that way. Points
 * older than the window are evicted as the clock advances.
 *
 * <p>Windows are keyed by subject (typically the MSISDN). Each key's history is
 * independent, so one account spiking does not move another account's count - the
 * tenant-isolation guarantee enforced at the service layer also holds inside the
 * algorithm itself.
 */
public final class VelocityChecker {

    private VelocityChecker() {
    }

    /** An observed event: which subject, when, and how much. */
    public record Event(String subject, long timestampMillis, double amount) {
    }

    /** Outcome of evaluating the current state of one subject's window. */
    public record VelocityResult(int count, double sum, boolean exceededCount, boolean exceededAmount) {
    }

    /**
     * Evaluates events against a single sliding window and reports, per subject,
     * whether the count or the amount crossed the configured limits.
     *
     * <p>Events must be supplied in ascending timestamp order for the eviction logic
     * to be correct; an out-of-order event would be evicted prematurely. The caller
     * (service layer) is responsible for ordering, which the streaming watermark
     * path guarantees.
     *
     * @param events        ordered events to fold into the windows
     * @param windowMillis  width of the sliding window
     * @param maxCount      count limit; negative disables the count check
     * @param maxAmount     amount limit; negative disables the amount check
     */
    public static Map<String, VelocityResult> check(List<Event> events, long windowMillis,
                                                    int maxCount, double maxAmount) {
        Map<String, List<Event>> windows = new LinkedHashMap<>();
        Map<String, VelocityResult> results = new LinkedHashMap<>();
        for (Event event : events) {
            List<Event> window = windows.computeIfAbsent(event.subject(), s -> new ArrayList<>());
            // Evict anything now outside the lookback window before adding the point,
            // so the window is always exactly [now - windowMillis, now].
            window.removeIf(e -> event.timestampMillis() - e.timestampMillis() > windowMillis);
            window.add(event);
            int count = window.size();
            double sum = window.stream().mapToDouble(Event::amount).sum();
            boolean exceededCount = maxCount >= 0 && count > maxCount;
            boolean exceededAmount = maxAmount >= 0 && sum > maxAmount;
            results.put(event.subject(), new VelocityResult(count, sum, exceededCount, exceededAmount));
        }
        return results;
    }

    /**
     * Multi-window velocity: evaluate the same events against several window widths
     * at once (e.g. 1 minute, 10 minutes, 1 hour).
     *
     * <p>A single window cannot distinguish "just started spiking" from "spiking for
     * an hour"; a nested comparison of counts across widths is what gives velocity
     * its shape. The result maps each window width to its per-subject outcome.
     */
    public static Map<Long, Map<String, VelocityResult>> checkMultiWindow(
            List<Event> events, List<Long> windowWidthsMillis,
            int maxCount, double maxAmount) {
        Map<Long, Map<String, VelocityResult>> results = new LinkedHashMap<>();
        for (long width : windowWidthsMillis) {
            results.put(width, check(events, width, maxCount, maxAmount));
        }
        return results;
    }
}
