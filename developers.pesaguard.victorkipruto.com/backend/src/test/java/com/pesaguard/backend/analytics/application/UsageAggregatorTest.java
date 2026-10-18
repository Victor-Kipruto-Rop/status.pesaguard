package com.pesaguard.backend.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;

/**
 * Aggregation correctness.
 *
 * <p>Three properties are load-bearing and each is asserted directly:
 * <ul>
 *   <li><b>Idempotence</b> — re-running over the same events gives the same
 *       numbers. This is what makes recovery from a failed rollup trivial.</li>
 *   <li><b>Late events</b> — an event belongs to the window it occurred in, not the
 *       one it was recorded in.</li>
 *   <li><b>Error-rate arithmetic</b> — including the empty case, which must not
 *       divide by zero.</li>
 * </ul>
 */
class UsageAggregatorTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");
    private final UUID org = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();

    private ApiRequestEvent event(int status, int latencyMs, Instant occurredAt, String requestId) {
        return ApiRequestEvent.record(requestId, org, project, environment, key, null,
                "/api/v1/payments", "GET", status, latencyMs, 100L, occurredAt, null);
    }

    private List<ApiRequestEvent> events() {
        List<ApiRequestEvent> list = new ArrayList<>();
        list.add(event(200, 10, T, "r1"));
        list.add(event(200, 20, T, "r2"));
        list.add(event(404, 30, T, "r3"));
        list.add(event(500, 40, T, "r4"));
        list.add(event(304, 5, T, "r5"));
        return list;
    }

    private UsageBucket bucket() {
        return UsageBucket.forWindow(UsageGranularity.MINUTE, T,
                org, project, environment, key, null, "/api/v1/payments", "GET");
    }

    @Test
    void countsVolumeSuccessAndFailure() {
        var aggregates = UsageAggregator.aggregate(events(), 0);

        assertThat(aggregates.total()).isEqualTo(5);
        // 304 is a correct answer, not a failure. Counting it as one would make the
        // error rate meaningless.
        assertThat(aggregates.successful()).isEqualTo(2);
        assertThat(aggregates.failed()).isEqualTo(2);
        assertThat(aggregates.clientErrors()).isEqualTo(1);
        assertThat(aggregates.serverErrors()).isEqualTo(1);
    }

    @Test
    void latencyAggregatesAreCorrect() {
        var aggregates = UsageAggregator.aggregate(events(), 0);

        assertThat(aggregates.latencySum()).isEqualTo(105);
        assertThat(aggregates.latencyMax()).isEqualTo(40);
        // Sorted latencies: 5, 10, 20, 30, 40. Nearest-rank p50 is the 3rd.
        assertThat(aggregates.p50()).isEqualTo(20);
        assertThat(aggregates.p95()).isEqualTo(40);
        assertThat(aggregates.p99()).isEqualTo(40);
    }

    @Test
    void aggregatingTwiceGivesIdenticalResults() {
        // The property that makes a failed rollup safe to re-run. An additive
        // implementation would double every figure here.
        var first = UsageAggregator.aggregate(events(), 0);
        var second = UsageAggregator.aggregate(events(), 0);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void applyingTwiceToABucketDoesNotDoubleCount() {
        UsageBucket bucket = bucket();
        List<ApiRequestEvent> raw = events();

        UsageAggregator.apply(bucket, raw, 0);
        long afterFirst = bucket.getTotalRequests();

        // Simulates an aggregation that ran, then was re-run after a failure.
        UsageAggregator.apply(bucket, raw, 0);

        assertThat(afterFirst).isEqualTo(5);
        assertThat(bucket.getTotalRequests()).isEqualTo(afterFirst);
    }

    @Test
    void recomputationCorrectsEarlierIncompleteTotals() {
        // A bucket closed early, then a late event arrives. Re-running recomputes
        // rather than incrementing, so the corrected figure is right.
        UsageBucket bucket = bucket();
        UsageAggregator.apply(bucket, List.of(event(200, 10, T, "r1")), 0);
        assertThat(bucket.getTotalRequests()).isEqualTo(1);

        UsageAggregator.apply(bucket, events(), 1);

        assertThat(bucket.getTotalRequests()).isEqualTo(5);
        assertThat(bucket.getLateEventCount()).isEqualTo(1);
    }

    @Test
    void anEmptySetProducesNoAggregate() {
        assertThat(UsageAggregator.aggregate(List.of(), 0)).isNull();
        assertThat(UsageAggregator.aggregate(null, 0)).isNull();
    }

    @Test
    void anEmptyBucketHasAZeroErrorRateNotNaN() {
        UsageBucket bucket = bucket();

        // An empty window is not meaningfully 0%, but returning NaN would break
        // dashboards, and an operator cannot tell a real 0% from a broken one.
        assertThat(bucket.errorRate()).isZero();
        assertThat(bucket.averageLatencyMs()).isZero();
        assertThat(bucket.errorRate()).isNotNaN();
    }

    @Test
    void errorRateIsAFractionNotAPercentage() {
        UsageBucket bucket = bucket();
        UsageAggregator.apply(bucket, events(), 0);

        // 2 failures of 5 requests = 0.4, not 40.
        assertThat(bucket.errorRate()).isEqualTo(0.4d);
    }

    @Test
    void responseBytesAreNullWhenNoEventReportedThem() {
        List<ApiRequestEvent> noBytes = List.of(
                ApiRequestEvent.record("r1", org, project, environment, key, null, "/e", "GET",
                        200, 10, null, T, null));

        assertThat(UsageAggregator.aggregate(noBytes, 0).responseBytes()).isNull();
    }

    @Test
    void theLatestEventTimeIsTracked() {
        Instant later = T.plusSeconds(30);
        List<ApiRequestEvent> mixed = List.of(
                event(200, 10, T, "r1"), event(200, 10, later, "r2"));

        assertThat(UsageAggregator.aggregate(mixed, 0).latestEventAt()).isEqualTo(later);
    }

    @Test
    void aWindowIsOnlyClosedAfterTheLatenessAllowance() {
        Instant windowEnd = Instant.parse("2026-03-15T14:38:00Z");
        java.time.Duration allowance = java.time.Duration.ofMinutes(5);

        // Closing eagerly would roll up a window and then have to correct it when a
        // late event arrived.
        assertThat(UsageAggregator.isWindowClosed(windowEnd,
                Instant.parse("2026-03-15T14:38:30Z"), allowance)).isFalse();
        assertThat(UsageAggregator.isWindowClosed(windowEnd,
                Instant.parse("2026-03-15T14:45:00Z"), allowance)).isTrue();
    }

    @Test
    void percentileUsesNearestRankOnObservedValues() {
        List<Integer> sorted = List.of(10, 20, 30, 40, 50);

        // A percentile should be a latency someone actually measured. Interpolating
        // would invent a number no request had.
        assertThat(UsageAggregator.percentile(sorted, 0)).isEqualTo(10);
        assertThat(UsageAggregator.percentile(sorted, 50)).isEqualTo(30);
        assertThat(UsageAggregator.percentile(sorted, 100)).isEqualTo(50);
        assertThat(UsageAggregator.percentile(List.of(), 50)).isZero();
        assertThat(UsageAggregator.percentile(null, 50)).isZero();
    }

    @Test
    void percentileClampsOutOfRangeInput() {
        List<Integer> sorted = List.of(10, 20, 30);

        assertThat(UsageAggregator.percentile(sorted, -5)).isEqualTo(10);
        assertThat(UsageAggregator.percentile(sorted, 500)).isEqualTo(30);
    }
}
