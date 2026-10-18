package com.pesaguard.backend.scopes.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The stated reason a scope is restricted.
 *
 * <p>A restricted scope without a recorded reason is indistinguishable, months
 * later, from a restriction nobody understands. This table exists so the answer to
 * "why was my key refused?" is in the database rather than in someone's memory.
 */
@Entity
@Table(name = "api_scope_restrictions")
public class ApiScopeRestriction {

    @Id
    @Column(name = "scope_name", nullable = false, length = 64)
    private String scopeName;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "requires_security_review", nullable = false)
    private boolean requiresSecurityReview;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiScopeRestriction() {
    }

    ApiScopeRestriction(String scopeName, String reason, boolean requiresSecurityReview) {
        this.scopeName = scopeName;
        this.reason = reason;
        this.requiresSecurityReview = requiresSecurityReview;
    }

    public String getScopeName() { return scopeName; }
    public String getReason() { return reason; }
    public boolean isRequiresSecurityReview() { return requiresSecurityReview; }
    public Instant getCreatedAt() { return createdAt; }
}