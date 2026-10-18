package com.pesaguard.backend.security.passkeys;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "passkey_challenges")
public class PasskeyChallenge {

    @Id
    private UUID id;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "request_json", nullable = false, columnDefinition = "text")
    private String requestJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PasskeyChallenge() {
    }

    public static PasskeyChallenge create(UUID id, String purpose, UUID userId, UUID organizationId,
            String requestJson, Instant createdAt, Instant expiresAt) {
        PasskeyChallenge challenge = new PasskeyChallenge();
        challenge.id = id;
        challenge.purpose = purpose;
        challenge.userId = userId;
        challenge.organizationId = organizationId;
        challenge.requestJson = requestJson;
        challenge.createdAt = createdAt;
        challenge.expiresAt = expiresAt;
        return challenge;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    public UUID getId() { return id; }
    public String getPurpose() { return purpose; }
    public UUID getUserId() { return userId; }
    public UUID getOrganizationId() { return organizationId; }
    public String getRequestJson() { return requestJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
