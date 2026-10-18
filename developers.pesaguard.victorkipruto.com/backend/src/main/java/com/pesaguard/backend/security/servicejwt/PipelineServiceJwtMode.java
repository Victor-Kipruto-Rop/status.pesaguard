package com.pesaguard.backend.security.servicejwt;

import java.util.Locale;

public enum PipelineServiceJwtMode {
    HMAC_ONLY,
    DUAL_REQUIRED,
    JWT_PRIMARY;

    public static PipelineServiceJwtMode parse(String value) {
        if (value == null) {
            throw new IllegalStateException("Pipeline service JWT mode is not configured.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Pipeline service JWT mode is invalid.", exception);
        }
    }

    public boolean jwtEnabled() {
        return this != HMAC_ONLY;
    }

    public boolean hmacRequired() {
        return this != JWT_PRIMARY;
    }
}
