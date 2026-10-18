package com.pesaguard.backend.security.authentication;

import java.util.Set;
import java.util.UUID;

public record CurrentSessionResponse(
        String sessionId,
        SessionUserResponse user,
        UUID organizationId,
        Set<String> authorities) {

    public CurrentSessionResponse {
        authorities = Set.copyOf(authorities);
    }
}
