package com.pesaguard.backend.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Windowing for usage rollups.
 *
 * <p>The property that matters most is truncation in UTC and by flooring. Both
 * have produced wrong usage numbers in real systems: local-time truncation makes
 * the same request land in different buckets depending on where the node ran, and
 * rounding places an event in a window that has not closed yet, where it is
 * double-counted by the later rollup.
 */
class UsageGranularityTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52.123Z");

    @Test
    void minuteWindowFloorsRatherThanRounds() {
        // 14:37:52 belongs to the 14:37 minute. Rounding would put it in 14:38,
        // a window that has not closed yet.
        assertThat(UsageGranularity.MINUTE.windowStart(T))
                .isEqualTo(Instant.parse("2026-03-15T14:37:00Z"));
    }

    @Test
    void hourAndDayWindowsFloor() {
        assertThat(UsageGranularity.HOUR.windowStart(T))
                .isEqualTo(Instant.parse("2026-03-15T14:00:00Z"));
        assertThat(UsageGranularity.DAY.windowStart(T))
                .isEqualTo(Instant.parse("2026-03-15T00:00:00Z"));
    }

    @Test
    void monthWindowFloors() {
        assertThat(UsageGranularity.MONTH.windowStart(T))
                .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void windowEndIsTheNextBoundary() {
        assertThat(UsageGranularity.MINUTE.windowEnd(Instant.parse("2026-03-15T14:37:00Z")))
                .isEqualTo(Instant.parse("2026-03-15T14:38:00Z"));
        assertThat(UsageGranularity.MONTH.windowEnd(Instant.parse("2026-03-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }

    @Test
    void containmentIsHalfOpen() {
        Instant start = UsageGranularity.HOUR.windowStart(T);

        // Exactly on the boundary belongs to the window that starts there, not the
        // previous one. A closed interval at both ends would count it twice.
        assertThat(UsageGranularity.HOUR.contains(start, start)).isTrue();
        assertThat(UsageGranularity.HOUR.contains(start, start.plusSeconds(3599))).isTrue();
        assertThat(UsageGranularity.HOUR.contains(start, start.minusSeconds(1))).isFalse();
        assertThat(UsageGranularity.HOUR.contains(start, start.plusSeconds(3600))).isFalse();
    }

    @Test
    void everyInstantBelongsToExactlyOneWindow() {
        // The invariant that makes double counting impossible: an instant cannot be
        // contained by two consecutive windows of the same granularity.
        Instant start = UsageGranularity.HOUR.windowStart(T);
        Instant next = UsageGranularity.HOUR.windowEnd(start);

        assertThat(UsageGranularity.HOUR.contains(start, next)).isFalse();
        assertThat(UsageGranularity.HOUR.contains(next, next)).isTrue();
    }

    @Test
    void monthHandlesYearBoundaries() {
        assertThat(UsageGranularity.MONTH.windowEnd(Instant.parse("2026-12-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void monthHandlesLeapDay() {
        // 2028 is a leap year; a naive month arithmetic that assumed 30 days would
        // drift here.
        Instant leapDay = Instant.parse("2028-02-29T12:00:00Z");
        assertThat(UsageGranularity.MONTH.windowStart(leapDay))
                .isEqualTo(Instant.parse("2028-02-01T00:00:00Z"));
        assertThat(UsageGranularity.MONTH.windowEnd(UsageGranularity.MONTH.windowStart(leapDay)))
                .isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }

    @Test
    void windowsTruncateInUtcNotTheJvmDefault() {
        // The zone is pinned so a node running in another region produces identical
        // buckets.
        assertThat(UsageGranularity.zone()).isEqualTo(java.time.ZoneOffset.UTC);
        // An Instant has no zone of its own; what matters is that the truncated
        // boundary is a UTC midnight, not a local one.
        assertThat(UsageGranularity.DAY.windowStart(T))
                .isEqualTo(T.atZone(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant());
    }

    @Test
    void parentChainEndsAtMonth() {
        assertThat(UsageGranularity.MINUTE.parent())
                .contains(UsageGranularity.HOUR);
        assertThat(UsageGranularity.HOUR.parent())
                .contains(UsageGranularity.DAY);
        assertThat(UsageGranularity.DAY.parent())
                .contains(UsageGranularity.MONTH);
        // No coarser level; rolling MONTH into itself would double-count.
        assertThat(UsageGranularity.MONTH.parent()).isEmpty();
    }
}