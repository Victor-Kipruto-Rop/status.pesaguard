package com.pesaguard.backend.oauth.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A pending authorization request awaiting the resource owner's decision.
 *
 * <p>This is the consent gate: nothing is issued until {@link #approve}. There is
 * no implicit-grant path, and approval requires the same user who started the
 * request.
 */
@Entity
@Table(name = "oauth_consent_requests")
public class ConsentRequest {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "text")
    private String redirectUri;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "state_hash", length = 64)
    private String stateHash;

    @Column(name = "state_plaintext", length = 512)
    private String statePlaintext;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "origin", length = 255)
    private String origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ConsentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsentRequest() {
    }

    private ConsentRequest(UUID organizationId, UUID applicationId, UUID userId, String redirectUri,
            Set<String> scopes, String stateHash, String statePlaintext, String codeChallenge,
            String origin, Instant expiresAt) {
        if (!Pkce.isValidVerifier(codeChallenge)) {
            throw new IllegalArgumentException("A PKCE code challenge is required");
        }
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.userId = userId;
        this.redirectUri = redirectUri;
        this.scopes = scopes == null || scopes.isEmpty() ? "" : String.join(",", scopes);
        this.stateHash = stateHash;
        this.statePlaintext = statePlaintext;
        this.codeChallenge = codeChallenge;
        this.origin = origin;
        this.status = ConsentStatus.PENDING;
        this.expiresAt = expiresAt;
    }

    public static ConsentRequest create(UUID organizationId, UUID applicationId, UUID userId,
            String redirectUri, Set<String> scopes, String stateHash, String statePlaintext,
            String codeChallenge, String origin, Instant expiresAt) {
        return new ConsentRequest(organizationId, applicationId, userId, redirectUri, scopes,
                stateHash, statePlaintext, codeChallenge, origin, expiresAt);
    }

    public void approve(Instant now) {
        requirePending(now);
        status = ConsentStatus.APPROVED;
        decidedAt = now;
        decidedBy = userId;
    }

    public void deny(Instant now) {
        requirePending(now);
        status = ConsentStatus.DENIED;
        decidedAt = now;
        decidedBy = userId;
    }

    private void requirePending(Instant now) {
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("This authorization request has expired");
        }
        if (status != ConsentStatus.PENDING) {
            throw new IllegalStateException("This authorization request has already been decided");
        }
    }

    public boolean isPending(Instant now) {
        return status == ConsentStatus.PENDING && expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getApplicationId() { return applicationId; }
    public UUID getUserId() { return userId; }
    public String getRedirectUri() { return redirectUri; }
    public String getScopes() { return scopes; }
    public Set<String> scopeSet() {
        return scopes == null || scopes.isBlank()
                ? Set.of()
                : Set.copyOf(java.util.Arrays.asList(scopes.split(",")));
    }
    public String getStateHash() { return stateHash; }

    public String getStatePlaintext() { return statePlaintext; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getOrigin() { return origin; }
    public ConsentStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getCreatedAt() { return createdAt; }
}