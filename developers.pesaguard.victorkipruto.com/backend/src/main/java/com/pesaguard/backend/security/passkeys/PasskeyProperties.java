package com.pesaguard.backend.security.passkeys;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "pesaguard.passkeys")
public record PasskeyProperties(
        @NotBlank String rpId,
        @NotBlank String rpName,
        @NotEmpty Set<String> origins,
        @NotNull Duration challengeTtl) {

    public PasskeyProperties {
        origins = origins == null ? Set.of() : Set.copyOf(origins);
        if (rpId != null && (rpId.contains("://") || rpId.contains("/") || rpId.contains(":"))) {
            throw new IllegalArgumentException("Passkey RP ID must be a host name, not a URL or host:port");
        }
        if (challengeTtl != null && (challengeTtl.isZero() || challengeTtl.isNegative()
                || challengeTtl.compareTo(Duration.ofMinutes(10)) > 0)) {
            throw new IllegalArgumentException("Passkey challenge TTL must be between zero and ten minutes");
        }
        if (rpId != null && origins != null) {
            for (String configuredOrigin : origins) {
                URI origin = URI.create(configuredOrigin);
                String host = origin.getHost();
                boolean loopbackHttp = "http".equalsIgnoreCase(origin.getScheme())
                        && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                                || "::1".equals(host));
                if (host == null || (!"https".equalsIgnoreCase(origin.getScheme()) && !loopbackHttp)
                        || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                        || origin.getRawQuery() != null || origin.getRawFragment() != null
                        || origin.getRawUserInfo() != null
                        || !(host.equalsIgnoreCase(rpId)
                                || host.toLowerCase(java.util.Locale.ROOT)
                                        .endsWith("." + rpId.toLowerCase(java.util.Locale.ROOT)))) {
                    throw new IllegalArgumentException(
                            "Passkey origins must use HTTPS and match the configured RP ID (except local loopback)");
                }
            }
        }
    }
}
