package com.pesaguard.backend.environment.domain;

public enum EnvironmentCredentialType {
    API_KEY,
    WEBHOOK_SECRET,
    HMAC_SECRET,
    BASIC,
    BEARER,
    TLS_CERTIFICATE
}