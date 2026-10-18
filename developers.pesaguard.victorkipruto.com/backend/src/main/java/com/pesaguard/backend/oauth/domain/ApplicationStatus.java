package com.pesaguard.backend.oauth.domain;

public enum ApplicationStatus {
    REGISTERED,
    ACTIVE,
    SUSPENDED,
    REVOKED;

    public boolean isTerminal() {
        return this == REVOKED;
    }

    /** Only an active application may start or complete an authorization flow. */
    public boolean canAuthorize() {
        return this == ACTIVE;
    }
}