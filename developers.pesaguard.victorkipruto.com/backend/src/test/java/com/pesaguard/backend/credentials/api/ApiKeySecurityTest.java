package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.environment.domain.EnvironmentLimits;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.golive.infrastructure.GoLiveLaunchRepository;
import com.pesaguard.backend.ratelimit.application.RateLimitService;
import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Proves the four guarantees for API credentials: secrets are never stored in
 * plaintext, never returned after creation, never logged, and never exposed
 * through the listing view.
 */
class ApiKeySecurityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final CredentialCryptoService crypto = new CredentialCryptoService(
            new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
            new SecureRandom());
    private final ApiKeyGenerator generator = new ApiKeyGenerator(crypto);
    private final ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
    private final GoLiveLaunchRepository goLiveLaunchRepository = mock(GoLiveLaunchRepository.class);
    private final EnvironmentLimitsRepository limitsRepository = mock(EnvironmentLimitsRepository.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private ApiKeyAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        IpRangeMatcher ipRangeMatcher = new IpRangeMatcher();
        when(limitsRepository.findByEnvironmentId(any())).thenReturn(Optional.of(EnvironmentLimits.defaults(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW)));
        when(rateLimitService.check(any(), any())).thenReturn(RateLimitDecision.unlimited());
        var sandboxEnvironment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(sandboxEnvironment.getType()).thenReturn(EnvironmentType.SANDBOX);
        when(sandboxEnvironment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(any(), any(), any()))
                .thenReturn(Optional.of(sandboxEnvironment));
        authenticator = new ApiKeyAuthenticator(apiKeyRepository, crypto, ipRangeMatcher,
                new ApiKeyRequestMetadataResolver(ipRangeMatcher, "127.0.0.1/32", "CF-IPCountry"),
                Clock.fixed(NOW, ZoneOffset.UTC), limitsRepository, rateLimitService,
                goLiveLaunchRepository, environmentRepository, mock(SecurityEventService.class), false);
    }

    private ApiKey key(ApiKeyStatus status, String ipAllowlist) {
        ApiKey key = ApiKey.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "worker", "pgk_prefix00000000", crypto.hmacSha256("secret"), "projects:read",
                NOW.plusSeconds(3600), UUID.randomUUID());
        if (status != ApiKeyStatus.CREATED) {
            key.activate();
        }
        if (status == ApiKeyStatus.SUSPENDED) {
            key.suspend(NOW);
        }
        if (status == ApiKeyStatus.REVOKED) {
            key.revoke(NOW);
        }
        if (status == ApiKeyStatus.COMPROMISED) {
            key.markCompromised(NOW);
        }
        if (ipAllowlist != null) {
            key.restrictToIps(Set.of(ipAllowlist));
        }
        return key;
    }

    @Test
    void generatedSecretsAreUnpredictableAndPrefixed() {
        ApiKeyGenerator.GeneratedKey first = generator.generate(EnvironmentType.SANDBOX);
        ApiKeyGenerator.GeneratedKey second = generator.generate(EnvironmentType.SANDBOX);

        assertThat(first.rawKey()).startsWith("pgk_sbx_").hasSize(51);
        assertThat(first.rawKey()).isNotEqualTo(second.rawKey());
        assertThat(first.prefix()).hasSize(16).isEqualTo(first.rawKey().substring(0, 16));
    }

    @Test
    void generatedSecretsIdentifyTheirEnvironmentTier() {
        var development = generator.generate(EnvironmentType.DEVELOPMENT);
        var sandbox = generator.generate(EnvironmentType.SANDBOX);
        var staging = generator.generate(EnvironmentType.STAGING);
        var production = generator.generate(EnvironmentType.PRODUCTION);

        assertThat(development.rawKey()).startsWith("pgk_dev_");
        assertThat(sandbox.rawKey()).startsWith("pgk_sbx_");
        assertThat(staging.rawKey()).startsWith("pgk_stg_");
        assertThat(production.rawKey()).startsWith("pgk_live_");
        assertThat(Set.of(development.prefix(), sandbox.prefix(), staging.prefix(), production.prefix()))
                .hasSize(4);
    }

    @Test
    void onlyTheHmacOfTheSecretIsPersisted() {
        String raw = "pgk_this-is-the-secret-value";
        String stored = generator.hash(raw);

        assertThat(stored).hasSize(64).doesNotContain(raw).isNotEqualTo(raw);
        assertThat(generator.hash(raw)).isEqualTo(stored);
    }

    @Test
    void aPresentedSecretResolvesOnlyThroughTheHashLookup() {
        String presented = "pgk_this-is-the-secret-value";
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        key.restrictToIps(null);
        // Only the correct HMAC resolves, exactly as the real repository behaves.
        when(apiKeyRepository.findBySecretHash(crypto.hmacSha256(presented))).thenReturn(Optional.of(key));

        assertThat(authenticator.authenticate(presented, "203.0.113.5")).isPresent();
        assertThat(authenticator.authenticate("pgk_wrong-secret", "203.0.113.5")).isEmpty();
        assertThat(authenticator.authenticate(null, "203.0.113.5")).isEmpty();
        assertThat(authenticator.authenticate("   ", "203.0.113.5")).isEmpty();
    }

    @Test
    void theAuthenticatedIdentityNeverCarriesTheSecret() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));

        var authenticated = authenticator.authenticate("pgk_secret", "203.0.113.5").orElseThrow();

        assertThat(authenticated.scopes()).containsExactly("projects:read");
        assertThat(authenticated.keyId()).isEqualTo(key.getId());
        assertThat(authenticated.toString())
                .doesNotContain(key.getSecretHash())
                .doesNotContain("pgk_");
    }

    @Test
    void sandboxKeyIsRejectedOnProductionApiHost() {
        assertHostMismatch(EnvironmentType.SANDBOX, "api.pesaguard.co.ke");
    }

    @Test
    void productionKeyIsRejectedOnSandboxApiHost() {
        assertHostMismatch(EnvironmentType.PRODUCTION, "sandbox-api.pesaguard.co.ke");
    }

    @Test
    void localHostAliasIsOptInAndNeverAppliesToProduction() {
        assertThat(com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls
                .matchesHost(EnvironmentType.SANDBOX, "localhost")).isFalse();
        assertThat(com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls
                .matchesHost(EnvironmentType.SANDBOX, "localhost", true)).isTrue();
        assertThat(com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls
                .matchesHost(EnvironmentType.PRODUCTION, "localhost", true)).isFalse();
    }

    private void assertHostMismatch(EnvironmentType keyEnvironmentType, String requestHost) {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(keyEnvironmentType);
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn(requestHost);

        assertThatThrownBy(() -> authenticator.authenticateKeyForRequest("secret", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("API_KEY_ENVIRONMENT_MISMATCH"))
                .hasMessage("This API key cannot be used with the selected environment API host.");
    }

    @Test
    void sandboxKeyIsAcceptedOnSandboxApiHost() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(EnvironmentType.SANDBOX);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn("sandbox-api.pesaguard.co.ke");

        assertThat(authenticator.authenticateKeyForRequest("secret", request)).isPresent();
    }

    @Test
    void sandboxKeyIsAcceptedOnLocalDeveloperApiWhenLocalAliasIsEnabled() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(EnvironmentType.SANDBOX);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn("localhost");
        ApiKeyAuthenticator localAuthenticator = new ApiKeyAuthenticator(apiKeyRepository, crypto,
                new IpRangeMatcher(),
                new ApiKeyRequestMetadataResolver(new IpRangeMatcher(), "127.0.0.1/32", "CF-IPCountry"),
                Clock.fixed(NOW, ZoneOffset.UTC), limitsRepository, rateLimitService,
                goLiveLaunchRepository, environmentRepository, mock(SecurityEventService.class), true);

        assertThat(localAuthenticator.authenticateKeyForRequest("secret", request)).isPresent();
    }

    @Test
    void productionKeyRequiresAGoLiveLaunch() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(EnvironmentType.PRODUCTION);
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn("api.pesaguard.co.ke");

        assertThatThrownBy(() -> authenticator.authenticateKeyForRequest("secret", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("GOLIVE_NOT_LIVE"));
    }

    @Test
    void launchedProductionKeyAuthenticatesWithoutRequestReview() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(EnvironmentType.PRODUCTION);
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        when(goLiveLaunchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                key.getOrganizationId(), key.getProjectId(), key.getEnvironmentId(), "LIVE")).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn("api.pesaguard.co.ke");

        assertThat(authenticator.authenticateKeyForRequest("secret", request)).isPresent();
    }

    @Test
    void suspendedProductionEnvironmentRejectsAnOtherwiseLiveKey() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
        var environment = mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(environment.getType()).thenReturn(EnvironmentType.PRODUCTION);
        when(environment.getStatus()).thenReturn(EnvironmentStatus.SUSPENDED);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.of(environment));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getServerName()).thenReturn("api.pesaguard.co.ke");

        assertThatThrownBy(() -> authenticator.authenticateKeyForRequest("secret", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("GOLIVE_ENVIRONMENT_SUSPENDED"));
    }

    @Test
    void onlyActiveKeysAuthenticate() {
        for (ApiKeyStatus status : new ApiKeyStatus[] {
                ApiKeyStatus.CREATED, ApiKeyStatus.SUSPENDED, ApiKeyStatus.REVOKED,
                ApiKeyStatus.EXPIRED, ApiKeyStatus.COMPROMISED}) {
            ApiKey key = key(status, null);
            if (status == ApiKeyStatus.EXPIRED) {
                key.markExpired(NOW);
            }
            when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));
            assertThat(authenticator.authenticate("pgk_secret", "203.0.113.5"))
                    .as("status %s must not authenticate", status)
                    .isEmpty();
        }
    }

    @Test
    void ipRestrictedKeysRejectOtherNetworks() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, "203.0.113.0/24");
        when(apiKeyRepository.findBySecretHash(any())).thenReturn(Optional.of(key));

        assertThat(authenticator.authenticate("pgk_secret", "203.0.113.9")).isPresent();
        assertThat(authenticator.authenticate("pgk_secret", "198.51.100.9")).isEmpty();
    }

    @Test
    void listingViewNeverExposesTheSecret() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);

        ApiKeyView view = new ApiKeyView(key.getId(), key.getProjectId(), key.getEnvironmentId(),
                key.getName(), key.getKeyPrefix(), key.scopeSet(), key.getStatus().name(),
                key.getExpiresAt(), key.getLastUsedAt(), key.getRevokedAt(), key.getRequestCount(),
                key.getRotatedFromId(), Set.of(), key.getLastUsedIp(), key.getLastUsedCountry(),
                key.getLastUsedDevice(), key.getCreatedAt());

        assertThat(view.toString()).doesNotContain(key.getSecretHash());
        assertThat(view.id()).isEqualTo(key.getId());
        assertThat(view.scopes()).containsExactly("projects:read");
        assertThat(view.requestCount()).isZero();
    }

    @Test
    void recordsOnlyCoarseActivityMetadata() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);

        key.recordUsage(NOW, "203.0.113.9", "Mozilla/5.0 (iPhone; CPU iPhone OS)", "KE");

        assertThat(key.getRequestCount()).isEqualTo(1);
        assertThat(key.getLastUsedIp()).isEqualTo("203.0.113.9");
        assertThat(key.getLastUsedCountry()).isEqualTo("KE");
        assertThat(key.getLastUsedDevice()).isEqualTo("Mobile");
        assertThat(key.toString()).doesNotContain("Mozilla/5.0");
    }

    @Test
    void ignoresInvalidOrUnknownCountryMetadata() {
        ApiKey key = key(ApiKeyStatus.ACTIVE, null);

        key.recordUsage(NOW, "203.0.113.9", "Mozilla/5.0", "XX");

        assertThat(key.getLastUsedCountry()).isNull();
        assertThat(key.getLastUsedDevice()).isEqualTo("Desktop");
    }

    @Test
    void onlyAcceptsCountryMetadataFromConfiguredTrustedProxies() {
        IpRangeMatcher matcher = new IpRangeMatcher();
        ApiKeyRequestMetadataResolver resolver = new ApiKeyRequestMetadataResolver(
                matcher, "10.0.0.0/8", "CF-IPCountry");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("CF-IPCountry")).thenReturn("KE");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (iPhone; Mobile)");

        when(request.getRemoteAddr()).thenReturn("198.51.100.9");
        assertThat(resolver.resolve(request).countryCode()).isNull();
        assertThat(resolver.resolve(request).deviceFamily()).isEqualTo("Mobile");

        when(request.getRemoteAddr()).thenReturn("10.2.3.4");
        assertThat(resolver.resolve(request).countryCode()).isEqualTo("KE");
    }
}
