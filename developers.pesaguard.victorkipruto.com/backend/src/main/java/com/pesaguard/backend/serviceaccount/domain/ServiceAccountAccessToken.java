package com.pesaguard.backend.serviceaccount.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "service_account_access_tokens")
public class ServiceAccountAccessToken {

    @Id
    private UUID id;

    @Column(name = "service_account_id", nullable = false)
    private UUID serviceAccountId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ServiceAccountAccessToken() {
    }

    private ServiceAccountAccessToken(UUID serviceAccountId, String tokenHash, Set<String> scopes,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.serviceAccountId = serviceAccountId;
        this.tokenHash = tokenHash;
        this.scopes = scopes == null || scopes.isEmpty() ? "" : String.join(",", scopes.stream().sorted().toList());
        this.expiresAt = expiresAt;
    }

    public static ServiceAccountAccessToken issue(
            UUID serviceAccountId, String tokenHash, Set<String> scopes, Instant expiresAt) {
        return new ServiceAccountAccessToken(serviceAccountId, tokenHash, scopes, expiresAt);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }

    public Set<String> scopeSet() {
        return scopes == null || scopes.isBlank()
                ? Set.of()
                : Set.copyOf(Arrays.asList(scopes.split(",")));
    }

    public UUID getId() { return id; }
    public UUID getServiceAccountId() { return serviceAccountId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
