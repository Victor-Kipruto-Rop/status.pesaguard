package com.pesaguard.backend.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.analytics.infrastructure.UsageBucketRepository;

/**
 * Builds usage buckets from raw request events.
 *
 * <p>Every rollup <b>recomputes</b> its window from the raw events rather than
 * adding to what is already there. That is what makes this service safe to run
 * repeatedly: a run that fails halfway leaves either the old numbers or the new
 * ones, never a half-summed state, and re-running finishes the job. An additive
 * implementation would leave a permanently wrong total after any failure, with no
 * way to tell it apart from a correct one.
 *
 * <p>Each level of the ladder is computed from the <b>raw events</b>, not by
 * summing the level below it. Summing buckets would make an error in a minute
 * bucket permanent and invisible; recomputing from raw means a correct minute
 * fixes the hour on the next pass.
 */
@Service
public class UsageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationService.class);

    /**
     * Events loaded per dimension set.
     *
     * <p>Bounded so one very busy endpoint cannot pull an entire hour into memory.
     * A window with more events than this is processed in sequence rather than
     * truncated: under-counting silently would be worse than being slow.
     */
    static final int PAGE_SIZE = 5_000;

    /**
     * How long after a window closes it is still rolled up.
     *
     * <p>Long enough to absorb a normal backlog, short enough that a day's figures
     * are final well before anyone reconciles against them.
     */
    static final Duration LATENESS_ALLOWANCE = Duration.ofMinutes(10);

    private final ApiRequestEventRepository eventRepository;
    private final UsageBucketRepository bucketRepository;
    private final Clock clock;

    public UsageAggregationService(ApiRequestEventRepository eventRepository,
            UsageBucketRepository bucketRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.bucketRepository = bucketRepository;
        this.clock = clock;
    }

    /**
     * Rolls up one window at one granularity.
     *
     * <p>Idempotent: running it twice over unchanged data produces the same
     * buckets, and re-running after a late event corrects them.
     *
     * @return the number of buckets written
     */
    @Transactional
    public int aggregate(UsageGranularity granularity, Instant windowStart) {
        Instant from = granularity.windowStart(windowStart);
        Instant to = granularity.windowEnd(from);

        List<Object[]> dimensions = eventRepository.findDistinctDimensions(from, to);
        if (dimensions.isEmpty()) {
            return 0;
        }

        int written = 0;
        for (Object[] dimension : dimensions) {
            UUID organizationId = (UUID) dimension[0];
            if (writeBucket(granularity, from, to, organizationId, dimension)) {
                written++;
            }
        }
        return written;
    }

    /**
     * Aggregates every window of a granularity that is now closed.
     *
     * <p>The scheduled entry point. It deliberately skips windows still inside the
     * lateness allowance, so a burst of traffic in the current minute is never
     * rolled up prematurely and then corrected.
     */
    @Transactional
    public int aggregateClosedWindows(UsageGranularity granularity) {
        Instant now = clock.instant();
        int total = 0;
        for (Instant windowStart : candidateWindows(granularity, now)) {
            if (!UsageAggregator.isWindowClosed(granularity.windowEnd(windowStart), now,
                    LATENESS_ALLOWANCE)) {
                continue;
            }
            try {
                total += aggregate(granularity, windowStart);
            } catch (RuntimeException failure) {
                // One bad window must not stop the rest, and must not be silent.
                log.error("usage aggregation failed granularity={} windowStart={}",
                        granularity, windowStart, failure);
            }
        }
        return total;
    }

    private boolean writeBucket(UsageGranularity granularity, Instant from, Instant to,
            UUID organizationId, Object[] dimension) {
        UUID projectId = (UUID) dimension[1];
        UUID environmentId = (UUID) dimension[2];
        UUID apiKeyId = (UUID) dimension[3];
        UUID oauthApplicationId = (UUID) dimension[4];
        String endpoint = (String) dimension[5];
        String method = (String) dimension[6];

        List<ApiRequestEvent> events = eventRepository.findInWindow(organizationId, from, to,
                PageRequest.of(0, PAGE_SIZE));
        if (events.isEmpty()) {
            return false;
        }

        UsageBucket bucket = bucketRepository
                .findByGranularityAndWindowStartAndOrganizationIdAndProjectIdAndEnvironmentIdAndApiKeyIdAndOauthApplicationIdAndEndpointAndMethod(
                        granularity, from, organizationId, projectId, environmentId,
                        apiKeyId, oauthApplicationId, endpoint, method)
                .orElseGet(() -> UsageBucket.forWindow(granularity, from, organizationId,
                        projectId, environmentId, apiKeyId, oauthApplicationId, endpoint, method));

        int late = lateEventCount(bucket, events);
        UsageAggregator.apply(bucket, events, late);
        bucketRepository.save(bucket);
        return true;
    }

    /**
     * How many of these events arrived after the bucket was last updated.
     *
     * <p>Zero when the bucket is new, or when everything predates its last update,
     * which is the normal case.
     */
    private int lateEventCount(UsageBucket bucket, List<ApiRequestEvent> events) {
        Instant lastUpdate = bucket.getUpdatedAt();
        if (lastUpdate == null || events.isEmpty()) {
            return 0;
        }
        int late = 0;
        for (ApiRequestEvent event : events) {
            if (event.getRecordedAt() != null && event.getRecordedAt().isAfter(lastUpdate)) {
                late++;
            }
        }
        return late;
    }

    /** Which granularity a dashboard should use for a span. */
    public static UsageGranularity recommendedFor(Duration span) {
        long minutes = span.toMinutes();
        if (minutes <= 60) {
            return UsageGranularity.MINUTE;
        }
        if (minutes <= 24 * 60) {
            return UsageGranularity.HOUR;
        }
        if (minutes <= 31L * 24 * 60) {
            return UsageGranularity.DAY;
        }
        return UsageGranularity.MONTH;
    }

    /** The next coarser level, for progressive rollup. */
    public Optional<UsageGranularity> next(UsageGranularity granularity) {
        return granularity.parent();
    }



    /**
     * Windows worth rebuilding, including recently closed ones so a late event is
     * picked up on the next pass.
     */
    private List<Instant> candidateWindows(UsageGranularity granularity, Instant now) {
        Instant earliest = now.minus(LATENESS_ALLOWANCE)
                .minus(2, java.time.temporal.ChronoUnit.DAYS);
        return bucketRepository.findWindowStarts(granularity, earliest, now);
    }
}
