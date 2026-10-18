package com.pesaguard.backend.outbox.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.domain.OutboxStatus;
import com.pesaguard.backend.outbox.infrastructure.OutboxEventRepository;

/**
 * Drains the outbox to the broker.
 *
 * <p>Each event is published in its own transaction ({@code REQUIRES_NEW}) so one
 * poison event cannot roll back a whole batch and strand the rest behind it.
 *
 * <p>Ordering is preserved per partition key: events sharing a partition key are
 * drained in creation order and one at a time, so a subscriber sees them in the
 * order the domain committed them. Without that, a retry could overtake an
 * event that had already been published, and a consumer would apply a revocation
 * before the creation it supersedes.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Attempts before an event is parked for inspection. */
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final OutboxEventRepository repository;
    /**
     * Optional. Absent until a broker adapter is configured.
     *
     * <p>Injected via {@code ObjectProvider} rather than required, so the
     * application still starts and still records events when no broker is
     * configured. Requiring the bean would mean a deployment without Redpanda
     * could not start, and could not accept the events that are piling up for
     * when it does.
     */
    private final ObjectProvider<EventPublisher> publisherProvider;
    private final Random random = new Random();
    private final int maxAttempts;
    private final int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRelay(OutboxEventRepository repository,
            ObjectProvider<EventPublisher> publisherProvider) {
        this(repository, publisherProvider, DEFAULT_MAX_ATTEMPTS, 100);
    }

    /**
     * Configures the attempt budget and batch size.
     *
     * <p>Public so a test can reach a dead-letter in a handful of attempts rather
     * than waiting out the production backoff of eight.
     */
    public OutboxRelay(OutboxEventRepository repository,
            ObjectProvider<EventPublisher> publisherProvider,
            int maxAttempts, int batchSize) {
        this.repository = repository;
        this.publisherProvider = publisherProvider;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
    }

    /**
     * Publishes one batch of due events.
     *
     * @return how many were published
     */
    public int drain() {
        List<OutboxEvent> due = claimDue();
        int published = 0;
        for (OutboxEvent event : due) {
            if (publishOne(event.getId())) {
                published++;
            }
        }
        return published;
    }

    @Transactional(readOnly = true)
    List<OutboxEvent> claimDue() {
        return repository.findDue(Instant.now(), PageRequest.of(0, batchSize));
    }

    /**
     * Publishes one event, recording success or failure independently.
     *
     * @return true if it reached the broker
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishOne(UUID rowId) {
        OutboxEvent event = repository.findById(rowId).orElse(null);
        if (event == null || event.getStatus() != OutboxStatus.PENDING) {
            // Already published or dead-lettered by another relay. Re-publishing
            // would duplicate the message for no benefit.
            return false;
        }
        EventPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            // No broker configured. The event stays PENDING and is retried, rather
            // than being marked published and lost. Failing loudly here is what
            // makes the missing adapter visible instead of silent.
            log.warn("No event publisher is configured; leaving eventId={} pending",
                    event.getEventId());
            return false;
        }
        Instant now = Instant.now();
        try {
            publisher.publish(event);
            event.markPublished(now);
            repository.saveAndFlush(event);
            return true;
        } catch (EventPublisher.EventPublishException failure) {
            handleFailure(event, failure, now);
            return false;
        } catch (RuntimeException unexpected) {
            // A transport bug must not be recorded as a clean retry: it is
            // logged with its type so it is distinguishable from broker refusal.
            log.error("Outbox publish raised an unexpected error eventId={} type={}",
                    event.getEventId(), unexpected.getClass().getSimpleName());
            handleFailure(event, unexpected, now);
            return false;
        }
    }

    private void handleFailure(OutboxEvent event, Exception failure, Instant now) {
        // The exception message may contain broker detail. Only the type is
        // stored, so an operational string can never carry a payload or a
        // credential into the outbox table.
        String reason = failure.getClass().getSimpleName();
        if (event.getAttemptCount() + 1 >= maxAttempts) {
            event.deadLetter(reason, now);
            log.error("Outbox event dead-lettered eventId={} attempts={} reason={}",
                    event.getEventId(), event.getAttemptCount(), reason);
        } else {
            event.recordFailure(reason, now.plus(backoffFor(event.getAttemptCount())), now);
            log.warn("Outbox publish failed eventId={} attempt={} reason={}",
                    event.getEventId(), event.getAttemptCount(), reason);
        }
        repository.saveAndFlush(event);
    }

    /**
     * Exponential backoff with full jitter.
     *
     * <p>Jitter matters under a broker outage: without it every event that failed
     * at the same moment retries at the same moment, and the retry wave takes
     * down the broker that was already struggling.
     */
    Duration backoffFor(int attempt) {
        long baseMillis = BASE_BACKOFF.toMillis();
        long ceiling = Math.min(baseMillis * (1L << Math.min(attempt, 20)), MAX_BACKOFF.toMillis());
        return Duration.ofMillis((long) (random.nextDouble() * ceiling));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByStatus(OutboxStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long deadLetterCount() {
        return repository.countByStatus(OutboxStatus.DEAD_LETTERED);
    }
}