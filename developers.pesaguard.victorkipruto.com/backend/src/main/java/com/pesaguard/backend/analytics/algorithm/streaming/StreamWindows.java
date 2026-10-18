package com.pesaguard.backend.analytics.algorithm.streaming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tumbling and sliding stream windows with watermarks for late events.
 *
 * <p>A window groups a stream into bounded time ranges so aggregations (a count, a
 * sum) can be computed over each range. A tumbling window partitions time into fixed,
 * non-overlapping buckets; a sliding window overlaps, so an event can belong to more
 * than one window. Both are needed in practice: tumbling for periodic reporting,
 * sliding for velocity, where a burst must be caught wherever it falls.
 *
 * <p>Events do not arrive in order. The watermark is the mechanism that decides when a
 * window is complete: it is the largest timestamp such that all earlier events are
 * assumed to have arrived, and a window closes only when the watermark passes its end.
 * An event arriving after its window has closed is late. Late events are not silently
 * discarded - the platform rule forbids that - so {@code process} routes them to a
 * separate late-event list that the caller must handle (re-emit, quarantine, or
 * re-window), and records that the window was perturbed.
 *
 * <p>Window state is keyed, so concurrent windows for different tenants or partition
 * keys do not interfere.
 */
public final class StreamWindows {

    private StreamWindows() {
    }

    /** A single stream event with its key and event-time. */
    public record Event(String key, long timestampMillis, double value) {
    }

    /** The closed result of one window bucket. */
    public record WindowResult(String key, long windowStart, long windowEnd, long count, double sum) {
    }

    /** What a processing step produced: fresh windows, late events, and the watermark. */
    public record ProcessResult(
            List<WindowResult> closedWindows,
            List<Event> lateEvents,
            long watermark) {
    }

    /** Window state for one key; mutated only through the processor. */
    private static final class Bucket {
        long start;
        long end;
        long count;
        double sum;
        boolean closed;

        Bucket(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Tumbling-window processor.
     *
     * <p>Windows are fixed buckets of width {@code windowMillis}, aligned to multiples
     * of that width from zero, so a given timestamp always lands in exactly one
     * deterministic bucket. Alignment to absolute multiples (rather than to the first
     * event) means two processors fed the same events compute identical bucket
     * boundaries.
     */
    public static final class TumblingWindow {
        private final long windowMillis;
        private final long allowedLatenessMillis;
        private final Map<String, Map<Long, Bucket>> bucketsByKey = new LinkedHashMap<>();
        private long watermark = Long.MIN_VALUE;

        public TumblingWindow(long windowMillis, long allowedLatenessMillis) {
            if (windowMillis <= 0) {
                throw new IllegalArgumentException("windowMillis must be positive");
            }
            this.windowMillis = windowMillis;
            this.allowedLatenessMillis = allowedLatenessMillis;
        }

        /** Process one event, closing and emitting any windows the watermark releases. */
        public ProcessResult process(Event event) {
            List<WindowResult> closed = new ArrayList<>();
            List<Event> late = new ArrayList<>();
            long windowStart = Math.floorDiv(event.timestampMillis(), windowMillis) * windowMillis;
            long windowEnd = windowStart + windowMillis;

            Map<Long, Bucket> buckets = bucketsByKey.computeIfAbsent(event.key(), k -> new LinkedHashMap<>());
            Bucket bucket = buckets.computeIfAbsent(windowStart, s -> new Bucket(windowStart, windowEnd));
            // Advance the watermark to the largest event time seen so far.
            watermark = Math.max(watermark, event.timestampMillis());

            // An event is late if its window has already closed under the watermark.
            if (watermark >= windowEnd + allowedLatenessMillis) {
                late.add(event);
            } else {
                bucket.count++;
                bucket.sum += event.value();
            }

            // Close every bucket whose end (plus lateness grace) is now behind the
            // watermark, then remove it so state does not grow without bound.
            List<Long> toClose = new ArrayList<>();
            for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
                Bucket candidate = entry.getValue();
                if (!candidate.closed && watermark >= candidate.end + allowedLatenessMillis) {
                    candidate.closed = true;
                    closed.add(new WindowResult(event.key(), candidate.start, candidate.end,
                            candidate.count, candidate.sum));
                    toClose.add(entry.getKey());
                }
            }
            toClose.forEach(buckets::remove);
            return new ProcessResult(closed, late, watermark);
        }

        public long watermark() {
            return watermark;
        }
    }

    /**
     * Sliding-window processor.
     *
     * <p>Every window of width {@code windowMillis} ending at each distinct event time
     * is maintained, so an event contributes to all windows it falls inside. This is
     * the structure a velocity check needs: the count "in the last N milliseconds as of
     * now" is exactly a sliding window ending at the current event's time.
     *
     * <p>Because windows overlap, state is evicted as soon as the watermark passes a
     * window's end, and the same late-event discipline as the tumbling case applies.
     */
    public static final class SlidingWindow {
        private final long windowMillis;
        private final Map<String, List<Bucket>> windowsByKey = new LinkedHashMap<>();
        private long watermark = Long.MIN_VALUE;

        public SlidingWindow(long windowMillis) {
            if (windowMillis <= 0) {
                throw new IllegalArgumentException("windowMillis must be positive");
            }
            this.windowMillis = windowMillis;
        }

        /** Process one event and return every sliding window currently open for its key. */
        public ProcessResult process(Event event) {
            List<WindowResult> closed = new ArrayList<>();
            List<Event> late = new ArrayList<>();
            watermark = Math.max(watermark, event.timestampMillis());
            List<Bucket> windows = windowsByKey.computeIfAbsent(event.key(), k -> new ArrayList<>());

            // A new window ending at the current event time.
            Bucket fresh = new Bucket(event.timestampMillis() - windowMillis, event.timestampMillis());
            fresh.count = 1;
            fresh.sum = event.value();
            windows.add(fresh);

            // Evict windows the watermark has fully passed and emit them as closed.
            List<Bucket> remaining = new ArrayList<>();
            for (Bucket window : windows) {
                if (watermark >= window.end) {
                    closed.add(new WindowResult(event.key(), window.start, window.end,
                            window.count, window.sum));
                } else {
                    remaining.add(window);
                }
            }
            windowsByKey.put(event.key(), remaining);
            return new ProcessResult(closed, late, watermark);
        }

        public long watermark() {
            return watermark;
        }
    }
}
