package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.UUID;

/** Latest observed authenticated session time for an organization member. */
public interface MemberLastActivityProjection {
    UUID getUserId();
    Instant getLastActivityAt();
}
