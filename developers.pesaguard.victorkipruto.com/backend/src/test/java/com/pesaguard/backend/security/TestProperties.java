package com.pesaguard.backend.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.pesaguard.backend.config.ApplicationProperties;

/**
 * Builds {@link ApplicationProperties} for unit tests.
 *
 * <p>These constructors gained fields three times during Phase 1 — refresh-token
 * TTL, then the Argon2id parameters, then the JWT signing block — and each time
 * the same three test classes had to be edited in lockstep. A shared builder
 * means a new configuration field breaks this one file instead of every test
 * that happens to need a properties object.
 *
 * <p>Every value here is test-only. The signing keys are throwaway values used
 * to exercise the real RS256 path, and the credential/audit keys are fixed
 * strings with no relationship to any deployment.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static ApplicationProperties.Security security() {
        return new ApplicationProperties.Security(
                List.of(URI.create("https://developers.pesaguard.co.ke")),
                Duration.ofHours(8),
                Duration.ofDays(30),
                Duration.ofMinutes(30),
                "Y3JlZGVudGlhbC1rZXktMzItYnl0ZXMh",
                "YXVkaXQta2V5LTMyLWJ5dGVzISEhISE=",
                true,
                // Argon2 at the contract floor. ApplicationProperties enforces a 64 KiB
                // minimum, so 8 is no longer a valid value; this is the smallest
                // setting that both passes validation and keeps the suite fast, since
                // these tests exercise logic around the encoder rather than the KDF.
                64,
                1,
                1,
                10,
                3,
                10,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                30,
                Duration.ofMinutes(15));
    }

    public static ApplicationProperties.JsonWebToken jwt() {
        return new ApplicationProperties.JsonWebToken(
                "pesaguard-developer-platform",
                "pesaguard-developer-platform",
                "test-v1",
                TestKeys.PRIVATE_KEY_BASE64,
                TestKeys.PUBLIC_KEY_BASE64,
                Duration.ofMinutes(5));
    }

    public static ApplicationProperties.Platform platformRecord() {
        return new ApplicationProperties.Platform("b3BlcmF0b3Ita2V5LTMyLWJ5dGVzISEhISEh");
    }

    public static ApplicationProperties platform() {
        return new ApplicationProperties(security(), platformRecord(), jwt());
    }
}