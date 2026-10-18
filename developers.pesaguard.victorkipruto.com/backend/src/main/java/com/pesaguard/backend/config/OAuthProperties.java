package com.pesaguard.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * OAuth server timing and throttling.
 *
 * <p>Short-lived by design: authorization codes live minutes and access tokens
 * minutes-to-an-hour, so a leaked token has a small window. These values are
 * configuration, not secrets, and every one of them is validated at startup so a
 * misconfigured server fails to boot rather than issuing long-lived tokens.
 */
@Validated
@ConfigurationProperties(prefix = "pesaguard.oauth")
public record OAuthProperties(
        @NotNull Duration authorizationCodeTtl,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration consentRequestTtl,
        @NotNull Duration throttleWindow,
        @Min(1) @Max(1000) int tokenEndpointAttemptLimit) {

    public OAuthProperties {
        requireSane(authorizationCodeTtl, "authorization-code-ttl", Duration.ofMinutes(1), Duration.ofMinutes(30));
        requireSane(accessTokenTtl, "access-token-ttl", Duration.ofMinutes(1), Duration.ofHours(6));
        requireSane(refreshTokenTtl, "refresh-token-ttl", Duration.ofMinutes(5), Duration.ofDays(90));
        requireSane(consentRequestTtl, "consent-request-ttl", Duration.ofMinutes(1), Duration.ofHours(1));
        requireSane(throttleWindow, "throttle-window", Duration.ofSeconds(30), Duration.ofHours(1));
    }

    private static void requireSane(Duration value, String name, Duration minimum, Duration maximum) {
        if (value == null) {
            return;
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "pesaguard.oauth." + name + " must be between " + minimum + " and " + maximum);
        }
    }
}