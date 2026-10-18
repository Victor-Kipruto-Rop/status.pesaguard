package com.pesaguard.backend.support.application;

import java.util.regex.Pattern;

public final class SensitiveDataRedactor {

    private static final Pattern CREDENTIAL_HEADERS = Pattern.compile(
            "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|password|secret|token|webhook-signing-secret)\\s*[:=]\\s*).+$");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PESAGUARD_KEY = Pattern.compile(
            "\\b(?:pg_(?:test|live)_|whsec_)[A-Za-z0-9_-]{12,}\\b");

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = CREDENTIAL_HEADERS.matcher(value).replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        redacted = JWT.matcher(redacted).replaceAll("[REDACTED]");
        return PESAGUARD_KEY.matcher(redacted).replaceAll("[REDACTED]");
    }
}
