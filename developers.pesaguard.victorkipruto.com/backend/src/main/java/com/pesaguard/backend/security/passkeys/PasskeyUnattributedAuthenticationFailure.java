package com.pesaguard.backend.security.passkeys;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "passkey_unattributed_authentication_failures")
public class PasskeyUnattributedAuthenticationFailure {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasskeyUnattributedAuthenticationFailure() {
    }

    PasskeyUnattributedAuthenticationFailure(UUID id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }
}
