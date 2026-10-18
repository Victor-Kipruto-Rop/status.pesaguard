package com.pesaguard.backend.analytics.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.analytics.infrastructure.UsageQuotaAlertStore;
import com.pesaguard.backend.notifications.application.UsageQuotaAlertService;

/** Evaluates recent environment request counts against their configured limits. */
@Component
public class UsageQuotaAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsageQuotaAlertScheduler.class);

    private final ApiRequestEventRepository eventRepository;
    private final UsageQuotaAlertService alertService;
    private final UsageQuotaAlertStore alertStore;
    private final Clock clock;

    public UsageQuotaAlertScheduler(ApiRequestEventRepository eventRepository,
            UsageQuotaAlertService alertService, UsageQuotaAlertStore alertStore, Clock clock) {
        this.eventRepository = eventRepository;
        this.alertService = alertService;
        this.alertStore = alertStore;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.usage.alert-interval-ms:60000}")
    public void checkRecentUsage() {
        var now = clock.instant();
        for (Object[] row : eventRepository.countByEnvironmentBetween(now.minusSeconds(60), now)) {
            try {
                if (row.length != 3 || !(row[0] instanceof java.util.UUID organizationId)
                        || !(row[1] instanceof java.util.UUID environmentId)
                        || !(row[2] instanceof Number requestCount)) {
                    throw new IllegalStateException("Usage alert aggregation returned an invalid row.");
                }
                alertService.evaluate(organizationId, environmentId, requestCount.longValue(), now);
            } catch (RuntimeException failure) {
                log.error("usage quota alert evaluation failed errorType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(cron = "0 20 2 * * *", zone = "UTC")
    public void pruneAlertDeduplicationHistory() {
        try {
            alertStore.deleteBefore(clock.instant().minus(java.time.Duration.ofDays(30)));
        } catch (RuntimeException failure) {
            log.error("usage quota alert retention failed errorType={}",
                    failure.getClass().getSimpleName());
        }
    }
}
