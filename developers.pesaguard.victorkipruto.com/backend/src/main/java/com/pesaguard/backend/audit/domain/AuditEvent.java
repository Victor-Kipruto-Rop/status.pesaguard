package com.pesaguard.backend.audit.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /**
     * The project the event concerns.
     *
     * <p>Nullable: an organization-level change such as a membership removal
     * belongs to no project, and storing a null is more honest than inventing one.
     */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "hash_version", nullable = false)
    private short hashVersion;

    @Column(name = "metadata", nullable = false, columnDefinition = "text")
    private String metadata;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(name = "event_hash", nullable = false, unique = true, length = 64)
    private String eventHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(
            UUID id,
            UUID organizationId,
            UUID projectId,
            long sequenceNumber,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            UUID requestId,
            UUID correlationId,
            String ipAddress,
            String userAgent,
            short hashVersion,
            String metadata,
            String previousHash,
            String eventHash) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.sequenceNumber = sequenceNumber;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.requestId = requestId;
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.hashVersion = hashVersion;
        this.metadata = metadata;
        this.previousHash = previousHash;
        this.eventHash = eventHash;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }

    public UUID getOrganizationId() { return organizationId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public UUID getRequestId() { return requestId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public short getHashVersion() { return hashVersion; }
    public String getMetadata() { return metadata; }
    public String getPreviousHash() { return previousHash; }
    public String getEventHash() { return eventHash; }
    public Instant getCreatedAt() { return createdAt; }
}
