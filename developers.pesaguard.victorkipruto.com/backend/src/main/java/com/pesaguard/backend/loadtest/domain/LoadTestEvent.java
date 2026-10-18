package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "load_test_events")
public class LoadTestEvent {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "load_test_id")
    private UUID loadTestId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoadTestEvent() {
    }

    public static LoadTestEvent record(UUID organizationId, UUID projectId, UUID environmentId,
            UUID loadTestId, UUID runId, UUID actorId, String eventType,
            Map<String, Object> payload, Instant now) {
        LoadTestEvent event = new LoadTestEvent();
        event.id = UUID.randomUUID();
        event.organizationId = organizationId;
        event.projectId = projectId;
        event.environmentId = environmentId;
        event.loadTestId = loadTestId;
        event.runId = runId;
        event.actorId = actorId;
        event.eventType = eventType;
        event.payload = Map.copyOf(payload);
        event.occurredAt = now;
        return event;
    }
}
