package com.pesaguard.backend.oauth.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use authorization code.
 *
 * <p>Only the HMAC of the code is stored. The code is bound to the application,
 * the user, the exact redirect URI, the granted scopes and the PKCE challenge, so
 * a code captured from one client or redirect target cannot be redeemed elsewhere.
 * Redemption is single-use: {@link #consume} is what makes replay fail.
 */
@Entity
@Table(name = "oauth_authorization_codes")
public class AuthorizationCode {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "text")
    private String redirectUri;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "code_challenge_method", nullable = false, length = 8)
    private String codeChallengeMethod;

    @Column(name = "state_hash", length = 64)
    private String stateHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthorizationCode() {
    }

    private AuthorizationCode(UUID applicationId, UUID organizationId, UUID userId, String codeHash,
            String redirectUri, Set<String> scopes, String codeChallenge, String codeChallengeMethod,
            String stateHash, Instant expiresAt) {
        if (!Pkce.isSupportedMethod(codeChallengeMethod)) {
            throw new IllegalArgumentException("Only the S256 code challenge method is supported");
        }
        if (!Pkce.isValidVerifier(codeChallenge)) {
            throw new IllegalArgumentException("The code challenge is not a valid S256 challenge");
        }
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.codeHash = codeHash;
        this.redirectUri = redirectUri;
        this.scopes = scopes == null || scopes.isEmpty() ? "" : String.join(",", scopes);
        this.codeChallenge = codeChallenge;
        this.codeChallengeMethod = "S256";
        this.stateHash = stateHash;
        this.expiresAt = expiresAt;
    }

    public static AuthorizationCode issue(UUID applicationId, UUID organizationId, UUID userId,
            String codeHash, String redirectUri, Set<String> scopes, String codeChallenge,
            String stateHash, Instant expiresAt) {
        return new AuthorizationCode(applicationId, organizationId, userId, codeHash, redirectUri,
                scopes, codeChallenge, "S256", stateHash, expiresAt);
    }

    /**
     * Redeems the code exactly once. Any second call throws, which is what turns a
     * captured code into a detectable replay rather than a silent second issue.
     */
    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException("This authorization code has already been redeemed");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("This authorization code has expired");
        }
        this.consumedAt = now;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public String getRedirectUri() { return redirectUri; }
    public String getScopes() { return scopes; }
    public Set<String> scopeSet() {
        return scopes == null || scopes.isBlank()
                ? Set.of()
                : Set.copyOf(java.util.Arrays.asList(scopes.split(",")));
    }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public String getStateHash() { return stateHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
}