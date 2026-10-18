package com.pesaguard.backend.environment.api;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import com.pesaguard.backend.environment.domain.EnvironmentType;

/**
 * Canonical API hosts for environment-bound API keys.
 *
 * <p>Keep these hosts server-owned: a project or environment name is not a
 * security boundary and must never let a developer redirect API credentials.
 */
public final class EnvironmentApiBaseUrls {

    public static final String SANDBOX = "https://sandbox-api.pesaguard.co.ke";
    public static final String PRODUCTION = "https://api.pesaguard.co.ke";

    private EnvironmentApiBaseUrls() {
    }

    public static String forType(EnvironmentType type) {
        return type == EnvironmentType.PRODUCTION ? PRODUCTION : SANDBOX;
    }

    public static boolean matchesHost(EnvironmentType type, String host) {
        return matchesHost(type, host, false);
    }

    public static boolean matchesHost(EnvironmentType type, String host, boolean allowLocalSandboxHost) {
        if (type == null || host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (allowLocalSandboxHost && type != EnvironmentType.PRODUCTION
                && Set.of("localhost", "127.0.0.1", "::1").contains(normalizedHost)) {
            return true;
        }
        String expected = URI.create(forType(type)).getHost();
        return expected.equals(normalizedHost);
    }
}
