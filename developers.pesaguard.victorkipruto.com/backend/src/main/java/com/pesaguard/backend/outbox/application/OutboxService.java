package com.pesaguard.backend.outbox.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.infrastructure.OutboxEventRepository;

/**
 * Writes domain events into the outbox.
 *
 * <p>Every method here is {@code @Transactional} with the default
 * {@code REQUIRED} propagation, which is what makes the pattern work: it joins
 * the caller's transaction rather than opening its own. A caller that changes
 * domain state and records an event therefore gets both or neither.
 *
 * <p>Deliberately has no {@code REQUIRES_NEW} and no async path. An outbox
 * written outside the caller's transaction would reintroduce exactly the gap the
 * outbox exists to close.
 */
@Service
public class OutboxService {

    /** Identifies this platform as the source of its own events. */
    public static final String DEFAULT_SOURCE = "developer-platform";

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxService(OutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records a tenant-scoped event.
     *
     * <p>Partitioned by organization so every event for one customer lands on one
     * partition and is therefore consumed in the order it was committed. Keying
     * by project instead would break ordering for an organization that owns
     * several projects.
     *
     * <p>The payload is an already-serialized JSON object rather than a map. The
     * event contract belongs to the emitting domain, so the domain owns the
     * shape and this service stays a transport concern that cannot drift from it.
     */
    @Transactional
    public OutboxEvent recordTenantEvent(String eventType, String jsonPayload,
            UUID organizationId, UUID projectId, String correlationId, String traceId) {
        return recordTenantEvent(eventType, 1, jsonPayload, organizationId, projectId,
                correlationId, traceId);
    }

    @Transactional
    public OutboxEvent recordTenantEvent(String eventType, int eventVersion, String jsonPayload,
            UUID organizationId, UUID projectId, String correlationId, String traceId) {
        return record(eventType, jsonPayload, organizationId, projectId,
                organizationId.toString(), correlationId, traceId, eventVersion);
    }

    @Transactional
    public OutboxEvent recordTenantEvent(String eventType, int eventVersion, String jsonPayload,
            UUID organizationId, UUID projectId, UUID environmentId,
            String correlationId, String traceId) {
        Instant now = clock.instant();
        OutboxEvent event = OutboxEvent.record(UUID.randomUUID(), eventType, eventVersion,
                organizationId, projectId, environmentId, organizationId.toString(),
                correlationId, traceId, jsonPayload, DEFAULT_SOURCE, now);
        return repository.saveAndFlush(event);
    }

    /** Records a platform-wide event that belongs to no single tenant. */
    @Transactional
    public OutboxEvent recordPlatformEvent(String eventType, String jsonPayload,
            String correlationId, String traceId) {
        return record(eventType, jsonPayload, null, null, "platform", correlationId, traceId);
    }

    @Transactional
    public OutboxEvent record(String eventType, String jsonPayload,
            UUID organizationId, UUID projectId, String partitionKey,
            String correlationId, String traceId) {
        return record(eventType, jsonPayload, organizationId, projectId, partitionKey,
                correlationId, traceId, 1);
    }

    @Transactional
    public OutboxEvent record(String eventType, String jsonPayload,
            UUID organizationId, UUID projectId, String partitionKey,
            String correlationId, String traceId, int eventVersion) {
        Instant now = clock.instant();
        OutboxEvent event = OutboxEvent.record(
                UUID.randomUUID(), eventType, eventVersion,
                organizationId, projectId, partitionKey,
                correlationId, traceId, jsonPayload, DEFAULT_SOURCE, now);
        return repository.saveAndFlush(event);
    }

    /**
     * Records an event inside a transaction that then fails.
     *
     * <p>Exists so the rollback guarantee can be demonstrated end to end against a
     * real database rather than asserted. A caller that changes domain state,
     * records an event, and then fails must leave no row behind: a surviving
     * event would tell subscribers about a change that was rolled back.
     *
     * <p>Production code should not call this. It throws by construction.
     *
     * @throws IllegalStateException always, to roll the transaction back
     */
    @Transactional
    public void recordThenFail(UUID organizationId, UUID projectId) {
        recordTenantEvent("developer.project.created",
                "{\"projectId\":\"" + projectId + "\"}",
                organizationId, projectId, "corr-rollback", "trace-rollback");
        throw new IllegalStateException("Deliberate failure to prove the outbox rolls back with the caller");
    }
}
