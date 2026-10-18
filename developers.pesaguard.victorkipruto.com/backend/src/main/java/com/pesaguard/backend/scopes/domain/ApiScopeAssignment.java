package com.pesaguard.backend.scopes.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A grant of one scope to one API credential.
 *
 * <p>The registry version at grant time is recorded. If a scope's meaning later
 * changes, a grant made against version 1 is distinguishable from one made against
 * version 2 — without this, "who was allowed to do this, and under what meaning"
 * becomes unanswerable after a semantic change.
 *
 * <p>Assignment is separate from the key's {@code scopes} column. The key column is
 * the fast path used on the hot authentication path; this table is the auditable
 * record of who granted what, and when.
 */
@Entity
@Table(name = "api_key_scope_assignments")
public class ApiScopeAssignment {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(name = "scope_name", nullable = false, length = 64)
    private String scopeName;

    @Column(name = "scope_version", nullable = false)
    private int scopeVersion;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @CreationTimestamp
    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;

    protected ApiScopeAssignment() {
    }

    private ApiScopeAssignment(UUID organizationId, UUID apiKeyId, String scopeName,
            int scopeVersion, UUID grantedBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.apiKeyId = apiKeyId;
        this.scopeName = scopeName;
        this.scopeVersion = scopeVersion;
        this.grantedBy = grantedBy;
    }

    public static ApiScopeAssignment grant(UUID organizationId, UUID apiKeyId,
            ApiScopeDefinition definition, UUID grantedBy) {
        return new ApiScopeAssignment(organizationId, apiKeyId, definition.getName(),
                definition.getVersion(), grantedBy);
    }

    public void revoke(Instant now, UUID revokedByUser, String reason) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = now;
        this.revokedBy = revokedByUser;
        this.revocationReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public String getScopeName() { return scopeName; }
    public int getScopeVersion() { return scopeVersion; }
    public UUID getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public UUID getRevokedBy() { return revokedBy; }
    public String getRevocationReason() { return revocationReason; }
}