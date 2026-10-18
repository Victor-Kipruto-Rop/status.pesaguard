package com.pesaguard.backend.rbac.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One recorded step in a production access request's history.
 *
 * <p>Append-only by design: history that can be rewritten is not history. Every
 * transition writes a row, so "who approved this and when" is answerable years
 * later even if the request itself is later suspended or revoked.
 *
 * <p>The {@code fromStatus} is nullable only for the creating row, which is the
 * one event with no predecessor.
 */
@Entity
@Table(name = "production_access_history")
public class ProductionAccessHistoryEntry {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Null only on the entry that created the request. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private ProductionAccessStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private ProductionAccessStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "note", length = 2000)
    private String note;

    /**
     * What the reviewer was shown to justify the decision.
     *
     * <p>Free text and a URI, stored verbatim. A reviewer citing a ticket or a
     * control mapping is making an audit claim, and rewriting their wording would
     * destroy the record of what they actually said.
     */
    @Column(name = "evidence", length = 2000)
    private String evidence;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ProductionAccessHistoryEntry() {
    }

    private ProductionAccessHistoryEntry(UUID requestId, UUID organizationId,
            ProductionAccessStatus fromStatus, ProductionAccessStatus toStatus, UUID actorId,
            String note, String evidence) {
        this.id = UUID.randomUUID();
        this.requestId = requestId;
        this.organizationId = organizationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.note = normalise(note);
        this.evidence = normalise(evidence);
    }

    public static ProductionAccessHistoryEntry of(UUID requestId, UUID organizationId,
            ProductionAccessStatus fromStatus, ProductionAccessStatus toStatus, UUID actorId,
            String note, String evidence) {
        if (requestId == null || organizationId == null || toStatus == null || actorId == null) {
            throw new IllegalArgumentException(
                    "History requires a request, organization, target status, and actor");
        }
        return new ProductionAccessHistoryEntry(requestId, organizationId, fromStatus, toStatus,
                actorId, note, evidence);
    }

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getOrganizationId() { return organizationId; }
    public ProductionAccessStatus getFromStatus() { return fromStatus; }
    public ProductionAccessStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public String getNote() { return note; }
    public String getEvidence() { return evidence; }
    public Instant getRecordedAt() { return recordedAt; }
}