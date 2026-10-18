package com.pesaguard.backend.changelog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_changelog_entry_audit")
public class ChangelogEntryAudit {

    @Id
    private UUID id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "operator_subject", nullable = false, columnDefinition = "text")
    private String operatorSubject;

    @Column(nullable = false, length = 24)
    private String action;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ChangelogEntryAudit() {
    }

    private ChangelogEntryAudit(
            UUID entryId,
            UUID operatorId,
            String operatorSubject,
            String action,
            String reason,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.entryId = entryId;
        this.operatorId = operatorId;
        this.operatorSubject = operatorSubject;
        this.action = action;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public static ChangelogEntryAudit record(
            UUID entryId,
            UUID operatorId,
            String operatorSubject,
            String action,
            String reason,
            Instant occurredAt) {
        return new ChangelogEntryAudit(entryId, operatorId, operatorSubject, action, reason, occurredAt);
    }

    public UUID getId() { return id; }
    public UUID getEntryId() { return entryId; }
    public UUID getOperatorId() { return operatorId; }
    public String getOperatorSubject() { return operatorSubject; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
