package com.pesaguard.backend.scopes.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The record of one access decision, kept whether it allowed or denied.
 *
 * <p>Denials are the interesting half. When an integration reports that it was
 * refused, this table is what answers why, per factor, instead of the answer being
 * reconstructed from memory or from a log line someone has already rotated away.
 *
 * <p>Append-only: the database rejects updates and deletes on this table.
 */
@Entity
@Table(name = "api_access_decisions")
public class ApiAccessDecisionRecord {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "requested_scope", length = 64)
    private String requestedScope;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "reason_code", nullable = false, length = 48)
    private String reasonCode;

    @Column(name = "factor_trace", nullable = false, columnDefinition = "text")
    private String factorTrace;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "remote_address", length = 45)
    private String remoteAddress;

    @CreationTimestamp
    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected ApiAccessDecisionRecord() {
    }

    private ApiAccessDecisionRecord(AccessDecision decision) {
        this.id = UUID.randomUUID();
        this.organizationId = decision.organizationId();
        this.userId = decision.userId();
        this.apiKeyId = decision.apiKeyId();
        this.projectId = decision.projectId();
        this.environmentId = decision.environmentId();
        this.requestedScope = decision.requestedScope() == null ? null : decision.requestedScope().value();
        this.allowed = decision.allowed();
        this.reasonCode = decision.reasonCode().value();
        this.factorTrace = decision.factorTrace();
        this.requestId = decision.requestId();
        this.remoteAddress = truncate(decision.remoteAddress());
    }

    public static ApiAccessDecisionRecord of(AccessDecision decision) {
        return new ApiAccessDecisionRecord(decision);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 45 ? value : value.substring(0, 45);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getRequestedScope() { return requestedScope; }
    public boolean isAllowed() { return allowed; }
    public String getReasonCode() { return reasonCode; }
    public String getFactorTrace() { return factorTrace; }
    public String getRequestId() { return requestId; }
    public String getRemoteAddress() { return remoteAddress; }
    public Instant getDecidedAt() { return decidedAt; }
}