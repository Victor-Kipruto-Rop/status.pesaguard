package com.pesaguard.backend.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Quota period boundaries.
 *
 * <p>Periods anchor to UTC calendar boundaries so a developer can plan around a
 * reset. Months are handled through LocalDate because Instant supports neither
 * truncation nor addition by months, which is a bug this codebase already hit
 * once in the usage rollup.
 */
class QuotaPeriodTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");

    @Test
    void dailyPeriodStartsAtUtcMidnight() {
        assertThat(QuotaPeriod.DAILY.periodStart(T))
                .isEqualTo(Instant.parse("2026-03-15T00:00:00Z"));
    }

    @Test
    void monthlyPeriodStartsOnTheFirst() {
        assertThat(QuotaPeriod.MONTHLY.periodStart(T))
                .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void dailyPeriodIsTwentyFourHours() {
        assertThat(QuotaPeriod.DAILY.periodEnd(T))
                .isEqualTo(Instant.parse("2026-03-16T00:00:00Z"));
    }

    @Test
    void monthlyPeriodEndsAtTheStartOfTheNextMonth() {
        assertThat(QuotaPeriod.MONTHLY.periodEnd(T))
                .isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }

    @Test
    void monthlyPeriodHandlesYearBoundaries() {
        // Adding 30 days would drift; December must land exactly on January.
        assertThat(QuotaPeriod.MONTHLY.periodEnd(Instant.parse("2026-12-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void monthlyPeriodHandlesLeapDay() {
        Instant leapDay = Instant.parse("2028-02-29T12:00:00Z");

        assertThat(QuotaPeriod.MONTHLY.periodStart(leapDay))
                .isEqualTo(Instant.parse("2028-02-01T00:00:00Z"));
        assertThat(QuotaPeriod.MONTHLY.periodEnd(leapDay))
                .isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }

    @Test
    void periodKeysAreStableAndDistinct() {
        assertThat(QuotaPeriod.DAILY.periodKey(T)).isEqualTo("2026-03-15");
        assertThat(QuotaPeriod.MONTHLY.periodKey(T)).isEqualTo("2026-03");
    }

    @Test
    void everyInstantInAPeriodMapsToTheSameKey() {
        // Otherwise a quota could reset mid-period, which is the whole thing a
        // calendar-anchored period exists to prevent.
        Instant start = QuotaPeriod.DAILY.periodStart(T);
        Instant end = QuotaPeriod.DAILY.periodEnd(T);

        assertThat(QuotaPeriod.DAILY.periodKey(start))
                .isEqualTo(QuotaPeriod.DAILY.periodKey(T));
        assertThat(QuotaPeriod.DAILY.periodKey(end.minusSeconds(1)))
                .isEqualTo(QuotaPeriod.DAILY.periodKey(T));
        // The next instant belongs to the next period.
        assertThat(QuotaPeriod.DAILY.periodKey(end))
                .isNotEqualTo(QuotaPeriod.DAILY.periodKey(T));
    }

    @Test
    void periodWindowsAreHalfOpen() {
        Instant start = QuotaPeriod.DAILY.periodStart(T);
        Instant end = QuotaPeriod.DAILY.periodEnd(T);

        // An instant on the boundary belongs to exactly one period.
        assertThat(QuotaPeriod.DAILY.periodStart(end)).isEqualTo(end);
        assertThat(QuotaPeriod.DAILY.periodStart(start)).isEqualTo(start);
    }
}