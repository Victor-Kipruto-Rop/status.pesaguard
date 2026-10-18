package com.pesaguard.backend.credentials.api;

/**
 * API key lifecycle.
 *
 * <pre>
 * CREATED -&gt; ACTIVE -&gt; SUSPENDED -&gt; ACTIVE
 *                  |            |
 *                  +------------+----&gt; REVOKED (terminal)
 *                  |
 *                  +----&gt; EXPIRED (terminal)
 * </pre>
 *
 * CREATED is transient by design: issuance persists the key as CREATED and
 * activates it in the same transaction, so the transition is recorded in history
 * without forcing a second round trip before the key works.
 */
public enum ApiKeyStatus {
    CREATED,
    ACTIVE,
    SUSPENDED,
    REVOKED,
    EXPIRED,
    COMPROMISED;

    public boolean isTerminal() {
        return this == REVOKED || this == EXPIRED || this == COMPROMISED;
    }

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
