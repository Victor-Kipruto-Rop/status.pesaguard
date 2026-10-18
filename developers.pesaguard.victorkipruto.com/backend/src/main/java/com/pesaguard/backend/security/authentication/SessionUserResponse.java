package com.pesaguard.backend.security.authentication;

import java.util.UUID;

public record SessionUserResponse(UUID id, String email, String displayName) {
}
