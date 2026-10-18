package com.pesaguard.backend.credentials.api;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.common.exception.TooManyRequestsException;
import com.pesaguard.backend.environment.domain.EnvironmentLimits;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.ratelimit.application.RateLimitService;
import com.pesaguard.backend.ratelimit.application.RateLimitUnavailableException;
import com.pesaguard.backend.ratelimit.application.RateLimitService.RateLimitRequest;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.RateLimitScope;
import com.pesaguard.backend.golive.infrastructure.GoLiveLaunchRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;
/**
 * Resolves a presented API key to the key it belongs to.
 *
 * <p>This is the enforcement point for key state: a revoked, expired, suspended,
 * or not-yet-activated key never authenticates, and a key with an IP allowlist
 * only authenticates from those networks. Lookup is by HMAC hash, so the database
 * cannot be queried with a prefix to enumerate keys.
 */
@Component
public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    /** Request-local authenticated key identity for telemetry attribution only. */
    public static final String REQUEST_ATTRIBUTE_AUTHENTICATED_KEY =
            ApiKeyAuthenticator.class.getName() + ".authenticatedKey";

    private final ApiKeyRepository apiKeyRepository;
    private final CredentialCryptoService crypto;
    private final IpRangeMatcher ipRangeMatcher;
    private final ApiKeyRequestMetadataResolver metadataResolver;
    private final Clock clock;
    private final EnvironmentLimitsRepository environmentLimitsRepository;
    private final RateLimitService rateLimitService;
    private final GoLiveLaunchRepository goLiveLaunchRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final SecurityEventService securityEvents;
    private final boolean allowLocalSandboxHost;

    public ApiKeyAuthenticator(ApiKeyRepository apiKeyRepository, CredentialCryptoService crypto,
            IpRangeMatcher ipRangeMatcher, ApiKeyRequestMetadataResolver metadataResolver, Clock clock,
            EnvironmentLimitsRepository environmentLimitsRepository, RateLimitService rateLimitService,
            GoLiveLaunchRepository goLiveLaunchRepository,
            ProjectEnvironmentRepository environmentRepository,
            SecurityEventService securityEvents,
            @Value("${pesaguard.api-key.allow-local-sandbox-host:false}") boolean allowLocalSandboxHost) {
        this.apiKeyRepository = apiKeyRepository;
        this.crypto = crypto;
        this.ipRangeMatcher = ipRangeMatcher;
        this.metadataResolver = metadataResolver;
        this.clock = clock;
        this.environmentLimitsRepository = environmentLimitsRepository;
        this.rateLimitService = rateLimitService;
        this.goLiveLaunchRepository = goLiveLaunchRepository;
        this.environmentRepository = environmentRepository;
        this.securityEvents = securityEvents;
        this.allowLocalSandboxHost = allowLocalSandboxHost;
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedApiKey> authenticate(String presentedKey, String remoteAddress) {
        return authenticateKey(presentedKey, remoteAddress)
                .map(key -> new AuthenticatedApiKey(key.getId(), key.getOrganizationId(),
                        key.getProjectId(), key.getEnvironmentId(), key.scopeSet()));
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticateKey(String presentedKey, String remoteAddress) {
        return authenticateKey(presentedKey, remoteAddress, null);
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticateKeyForRequest(String presentedKey, HttpServletRequest httpRequest) {
        return authenticateKey(presentedKey,
                httpRequest == null ? null : httpRequest.getRemoteAddr(), httpRequest);
    }

    private Optional<ApiKey> authenticateKey(String presentedKey, String remoteAddress,
            HttpServletRequest httpRequest) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKey> candidate = apiKeyRepository.findBySecretHash(
                crypto.hmacSha256(presentedKey.trim()));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = candidate.get();
        if (!key.isUsable(clock.instant())) {
            recordSignal(key, SecurityEventType.REVOKED_CREDENTIAL_USAGE,
                    "A non-active API credential was presented.");
            return Optional.empty();
        }
        if (!ipAllowed(key, remoteAddress)) {
            recordSignal(key, SecurityEventType.ALLOWLIST_VIOLATION,
                    "An API credential request was refused by its IP allowlist.");
            return Optional.empty();
        }
        Optional<ApiKey> authenticated = Optional.of(key);
        authenticated.ifPresent(authenticatedKey -> {
            enforceProductionAccess(authenticatedKey, httpRequest);
            enforceEnvironmentLimit(authenticatedKey, httpRequest);
            if (httpRequest != null) {
                httpRequest.setAttribute(REQUEST_ATTRIBUTE_AUTHENTICATED_KEY,
                        new AuthenticatedApiKey(authenticatedKey.getId(),
                                authenticatedKey.getOrganizationId(), authenticatedKey.getProjectId(),
                                authenticatedKey.getEnvironmentId(), authenticatedKey.scopeSet()));
            }
        });
        return authenticated;
    }

    private void recordSignal(ApiKey key, SecurityEventType type, String detail) {
        try {
            securityEvents.recordIfNew(key.getOrganizationId(), type, key.getId(), "api_key", detail);
        } catch (RuntimeException signalFailure) {
            // Observation failures must never turn an otherwise correct denial
            // into a platform error.
            log.warn("could not persist API-key security signal type={} keyId={}", type, key.getId(),
                    signalFailure);
        }
    }

    private void enforceProductionAccess(ApiKey key, HttpServletRequest httpRequest) {
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                key.getEnvironmentId(), key.getOrganizationId(), key.getProjectId());
        if (environment.isEmpty()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "API_KEY_ENVIRONMENT_UNAVAILABLE",
                    "The API key environment is unavailable.");
        }
        if (httpRequest != null && !com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls
                .matchesHost(environment.get().getType(), httpRequest.getServerName(), allowLocalSandboxHost)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "API_KEY_ENVIRONMENT_MISMATCH",
                    "This API key cannot be used with the selected environment API host.");
        }
        if (environment.get().getType() != EnvironmentType.PRODUCTION) {
            return;
        }
        if (environment.get().getStatus() != EnvironmentStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "GOLIVE_ENVIRONMENT_SUSPENDED",
                    "Production traffic is unavailable while this environment is suspended.");
        }
        if (!goLiveLaunchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                key.getOrganizationId(), key.getProjectId(), key.getEnvironmentId(), "LIVE")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "GOLIVE_NOT_LIVE",
                    "Production traffic is unavailable until this environment is launched through Go-Live.");
        }
    }

    /**
     * Records successful use. Kept separate from {@link #authenticate} so a read-only
     * check never mutates usage metadata.
     */
    @Transactional
    public void recordUsage(ApiKey key, String remoteAddress) {
        recordUsage(key, remoteAddress, null, null);
    }

    /**
     * Records a successful request using a coarse device family and a country
     * supplied only by a trusted edge adapter. The raw user-agent string is not
     * persisted.
     */
    @Transactional
    public void recordUsage(ApiKey key, String remoteAddress, String userAgent, String trustedCountryCode) {
        key.recordUsage(clock.instant(), remoteAddress, userAgent, trustedCountryCode);
        apiKeyRepository.saveAndFlush(key);
    }

    @Transactional
    public void recordUsage(ApiKey key, HttpServletRequest request) {
        ApiKeyRequestMetadataResolver.Metadata metadata = metadataResolver.resolve(request);
        recordUsage(key, request.getRemoteAddr(), request.getHeader("User-Agent"), metadata.countryCode());
    }

    private boolean ipAllowed(ApiKey key, String remoteAddress) {
        if (key.getIpAllowlist() == null || key.getIpAllowlist().isBlank()) {
            return true;
        }
        return ipRangeMatcher.isAllowed(remoteAddress, Set.of(key.getIpAllowlist().split(",")));
    }

    private void enforceEnvironmentLimit(ApiKey key, HttpServletRequest httpRequest) {
        EnvironmentLimits limits = environmentLimitsRepository.findByEnvironmentId(key.getEnvironmentId())
                .orElse(null);
        if (limits == null) {
            log.error("environment limits missing; denying API key request organizationId={} environmentId={}",
                    key.getOrganizationId(), key.getEnvironmentId());
            throw new RateLimitUnavailableException();
        }
        UUID policyId = UUID.nameUUIDFromBytes(
                ("environment-rate-limit:" + key.getEnvironmentId()).getBytes(StandardCharsets.UTF_8));
        UUID burstPolicyId = UUID.nameUUIDFromBytes(
                ("environment-burst-limit:" + key.getEnvironmentId()).getBytes(StandardCharsets.UTF_8));
        RateLimitPolicy minutePolicy = RateLimitPolicy.slidingWindow(policyId, key.getOrganizationId(),
                RateLimitScope.ENVIRONMENT, key.getEnvironmentId(), null,
                limits.getRequestsPerMinute(), Duration.ofMinutes(1));
        RateLimitPolicy burstPolicy = RateLimitPolicy.slidingWindow(burstPolicyId, key.getOrganizationId(),
                RateLimitScope.ENVIRONMENT, key.getEnvironmentId(), null,
                limits.getBurstRequests(), Duration.ofSeconds(1));
        RateLimitRequest request = new RateLimitRequest(key.getOrganizationId(), key.getId(), key.getProjectId(),
                key.getEnvironmentId(), null, "api-key", null, null, clock.instant());
        var decision = rateLimitService.check(request, java.util.List.of(minutePolicy, burstPolicy));
        if (httpRequest != null) {
            httpRequest.setAttribute(RateLimitService.RESPONSE_DECISION_ATTRIBUTE, decision);
        }
        if (!decision.allowed()) {
            throw new TooManyRequestsException("Environment request limit exceeded.", decision.retryAfterSeconds());
        }
    }

    /**
     * The authenticated key's identity and grants. Deliberately carries no secret
     * material of any kind.
     */
    public record AuthenticatedApiKey(
            UUID keyId,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            Set<String> scopes) {
    }
}
