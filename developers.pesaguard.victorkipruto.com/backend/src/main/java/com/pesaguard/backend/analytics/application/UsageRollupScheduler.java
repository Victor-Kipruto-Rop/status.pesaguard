package com.pesaguard.backend.analytics.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.SafeExceptionDiagnostics;

/**
 * Drives the rollup ladder on a schedule.
 *
 * <p>Each level runs on its own cadence, matched to how quickly it is read. Minute
 * buckets are for live dashboards and roll up every minute; hourly and above
 * back longer-range reports and run less often.
 *
 * <p>Minutes are aggregated <b>after</b> hours on purpose. Rolling up an hour
 * before all its minutes exist would produce an hourly total that is then
 * silently wrong, and nothing would go back and fix it — recomputation only
 * happens for windows the worker revisits.
 *
 * <p>Enabled by {@code pesaguard.usage.rollup-enabled}. Off by default in tests
 * so a suite is not racing a background thread.
 */
@Component
public class UsageRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsageRollupScheduler.class);

    private final UsageAggregationService aggregation;
    private final Clock clock;
    private final boolean enabled;

    public UsageRollupScheduler(UsageAggregationService aggregation, Clock clock,
            org.springframework.core.env.Environment environment) {
        this.aggregation = aggregation;
        this.clock = clock;
        this.enabled = Boolean.parseBoolean(
                environment.getProperty("pesaguard.usage.rollup-enabled", "true"));
    }

    @Scheduled(cron = "${pesaguard.usage.minute-cron:0 * * * * *}")
    public void rollUpMinutes() {
        run(com.pesaguard.backend.analytics.domain.UsageGranularity.MINUTE);
    }

    @Scheduled(cron = "${pesaguard.usage.hour-cron:0 5 * * * *}")
    public void rollUpHours() {
        run(com.pesaguard.backend.analytics.domain.UsageGranularity.HOUR);
    }

    @Scheduled(cron = "${pesaguard.usage.day-cron:0 10 0 * * *}")
    public void rollUpDays() {
        run(com.pesaguard.backend.analytics.domain.UsageGranularity.DAY);
    }

    @Scheduled(cron = "${pesaguard.usage.month-cron:0 20 0 1 * *}")
    public void rollUpMonths() {
        run(com.pesaguard.backend.analytics.domain.UsageGranularity.MONTH);
    }

    /**
     * Runs one level, isolating failures.
     *
     * <p>A scheduled method that throws is cancelled by Spring and never runs
     * again — one failed window would silently stop all future rollups for that
     * level. Catching here keeps the schedule alive, and the error is logged
     * rather than swallowed.
     */
    private void run(com.pesaguard.backend.analytics.domain.UsageGranularity granularity) {
        if (!enabled) {
            return;
        }
        try {
            int buckets = aggregation.aggregateClosedWindows(granularity);
            if (buckets > 0) {
                log.info("usage rollup complete granularity={} buckets={} at={}",
                        granularity, buckets, clock.instant());
            }
        } catch (RuntimeException failure) {
            log.error("usage rollup failed granularity={} type={} diagnostic={}",
                    granularity, failure.getClass().getName(),
                    SafeExceptionDiagnostics.stackTrace(failure));
        }
    }
}