package com.pesaguard.backend.security.authentication;

import java.util.UUID;

public record SessionOrganizationResponse(UUID id, String name, String slug) {
}
