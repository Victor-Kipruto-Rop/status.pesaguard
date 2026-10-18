package com.pesaguard.backend.analytics.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Aggregation granularity for usage rollups.
 *
 * <p>Windows are truncated in **UTC**, never in a local zone. Usage buckets are
 * compared across the platform and queried by operators in several regions;
 * truncating in local time would make the same instant land in different buckets
 * depending on where the computing node happened to be, so the same request would
 * be counted twice or not at all.
 */
public enum UsageGranularity {
    MINUTE(ChronoUnit.MINUTES),
    HOUR(ChronoUnit.HOURS),
    DAY(ChronoUnit.DAYS),
    MONTH(ChronoUnit.MONTHS);

    /**
     * Pinned to UTC, never {@code ZoneId.systemDefault()}.
     *
     * <p>Buckets are compared across the platform and queried by operators in
     * several regions. Truncating in local time would put the same instant in
     * different buckets depending on which node ran, so the same request would be
     * counted twice or not at all.
     */
    private static final java.time.ZoneOffset UTC_ZONE = ZoneOffset.UTC;

    private final ChronoUnit unit;

    UsageGranularity(ChronoUnit unit) {
        this.unit = unit;
    }

    /**
     * The start of the window containing {@code instant}.
     *
     * <p>Truncation is floor, not round: an event at 10:59:59 belongs to the
     * <p>Months are handled separately because {@link Instant} supports truncation
     * to days and smaller but <em>not</em> to months. Passing
     * {@code ChronoUnit.MONTHS} to {@code truncatedTo} throws at runtime, which would
     * have made every monthly bucket fail.
     */
    public Instant windowStart(Instant instant) {
        if (this == MONTH) {
            java.time.LocalDate date = instant.atZone(UTC_ZONE).toLocalDate();
            return date.withDayOfMonth(1).atStartOfDay(UTC_ZONE).toInstant();
        }
        return instant.truncatedTo(unit);
    }

    /**
     * The first instant <em>after</em> this window, exclusive.
     *
     * <p>Used for range queries. Expressed as a half-open interval
     * {@code [start, end)} rather than two closed bounds, so an event landing
     * exactly on a boundary belongs to exactly one window instead of two.
     */
    public Instant windowEnd(Instant windowStart) {
        if (this == MONTH) {
            // Year arithmetic handles December correctly; adding 30 days would drift.
            return windowStart.atZone(UTC_ZONE).plusMonths(1).toInstant();
        }
        return windowStart.plus(1, unit);
    }

    public boolean contains(Instant windowStart, Instant instant) {
        Instant start = windowStart(windowStart);
        return !instant.isBefore(start) && instant.isBefore(windowEnd(start));
    }

    /**
     * The coarser granularity a bucket rolls up into.
     *
     * <p>{@code MONTH} has no coarser level and returns empty rather than rolling
     * into itself, which would double-count.
     */
    public java.util.Optional<UsageGranularity> parent() {
        return switch (this) {
            case MINUTE -> java.util.Optional.of(HOUR);
            case HOUR -> java.util.Optional.of(DAY);
            case DAY -> java.util.Optional.of(MONTH);
            case MONTH -> java.util.Optional.empty();
        };
    }

    /** Zone used for every truncation. Fixed to UTC, never the JVM default. */
    public static ZoneOffset zone() {
        return ZoneOffset.UTC;
    }
}
