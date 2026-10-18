package com.pesaguard.backend.ratelimit.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The period a quota is measured over.
 *
 * <p>Periods are anchored to <b>UTC calendar boundaries</b>, not to a rolling
 * 24 hours. A quota that resets at midnight UTC is something a developer can
 * plan around; one that resets 24 hours after their first request is not, and
 * would be impossible to display meaningfully.
 */
public enum QuotaPeriod {

    DAILY(ChronoUnit.DAYS),
    MONTHLY(ChronoUnit.MONTHS);

    private final ChronoUnit unit;

    QuotaPeriod(ChronoUnit unit) {
        this.unit = unit;
    }

    /**
     * Start of the period containing {@code instant}, in UTC.
     *
     * <p>Months go through {@link java.time.LocalDate} because
     * {@code Instant} supports neither truncation nor addition by months.
     */
    public Instant periodStart(Instant instant) {
        java.time.ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        if (this == MONTHLY) {
            return utc.withDayOfMonth(1).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive end of the period. */
    public Instant periodEnd(Instant instant) {
        java.time.ZonedDateTime start = periodStart(instant).atZone(ZoneOffset.UTC);
        return start.plus(1, unit).toInstant();
    }

    /**
     * The key suffix identifying the current period.
     *
     * <p>Periods are separate counters rather than one counter that resets, so a
     * reset can never lose a concurrent write and the previous period stays
     * readable for reporting.
     */
    public String periodKey(Instant instant) {
        Instant start = periodStart(instant);
        return this == MONTHLY
                ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
                        .withZone(ZoneOffset.UTC).format(start)
                : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(ZoneOffset.UTC).format(start);
    }
}