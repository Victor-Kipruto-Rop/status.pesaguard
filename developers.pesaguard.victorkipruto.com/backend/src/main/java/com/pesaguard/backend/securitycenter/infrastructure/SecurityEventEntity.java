package com.pesaguard.backend.securitycenter.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.securitycenter.domain.SecurityEvent.Resolution;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persisted security signal.
 *
 * <p>The observation itself ({@code type}, {@code subjectId}, {@code detail},
 * {@code detectedAt}) is never updated. Only the resolution columns are, which is
 * what lets a single row carry both "what was seen" and "what a human decided
 * about it" without the two being able to overwrite each other.
 */
@Entity
@Table(name = "security_events")
public class SecurityEventEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private SecurityEventType type;

    /**
     * The credential, session, or endpoint concerned.
     *
     * <p>No foreign key: these are different id spaces and the referenced row may
     * already be deleted, which is precisely when the signal matters most.
     */
    @Column(name = "subject_id")
    private UUID subjectId;

    /** Labels which id space {@code subjectId} belongs to. */
    @Column(name = "subject_kind", length = 32)
    private String subjectKind;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 24)
    private Resolution resolution;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected SecurityEventEntity() {
    }

    public SecurityEventEntity(com.pesaguard.backend.securitycenter.domain.SecurityEvent.Record record) {
        this.id = record.id();
        this.organizationId = record.organizationId();
        this.type = record.type();
        this.subjectId = record.subjectId();
        this.subjectKind = record.subjectKind();
        this.detail = record.detail();
        this.detectedAt = record.detectedAt();
        this.resolution = record.resolution();
        this.resolvedBy = record.resolvedBy();
        this.resolvedAt = record.resolvedAt();
        this.resolutionNote = record.resolutionNote();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public SecurityEventType getType() { return type; }
    public UUID getSubjectId() { return subjectId; }
    public String getSubjectKind() { return subjectKind; }
    public String getDetail() { return detail; }
    public Instant getDetectedAt() { return detectedAt; }
    public Resolution getResolution() { return resolution; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getRecordedAt() { return recordedAt; }
}