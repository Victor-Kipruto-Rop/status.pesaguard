package com.pesaguard.backend.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.analytics.infrastructure.UsageBucketRepository;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Read-side authorization and tenant isolation.
 *
 * <p>The property that matters most: a caller can never retrieve another
 * tenant's usage. Asserted by checking the organization actually reaching the
 * repository, not merely that a filter ran.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T14:37:52Z");

    @Mock
    private UsageBucketRepository bucketRepository;

    @Mock
    private ProjectAccessScope projectAccessScope;

    @Mock
    private AuthorizationService authorizationService;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private UsageQueryService service;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UsageQueryService(bucketRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                projectAccessScope, authorizationService, apiKeyRepository);
        when(projectAccessScope.resolve(any(), any(), any())).thenAnswer(invocation -> {
            UUID requestedProject = invocation.getArgument(1);
            UUID requestedEnvironment = invocation.getArgument(2);
            return requestedProject == null && requestedEnvironment == null
                    ? new ProjectAccessScope.Scope(null, null, null)
                    : new ProjectAccessScope.Scope(Set.of(requestedProject == null ? projectId : requestedProject),
                            requestedProject, requestedEnvironment);
        });
        when(bucketRepository.findForOrganization(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private AuthenticatedUser withUsageRead() {
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), organizationId, UUID.randomUUID(),
                "user@example.com", "User", Set.of(Permission.USAGE_READ.value(), Permission.CREDENTIAL_READ.value()));
        when(authorizationService.hasPermission(principal, Permission.USAGE_READ)).thenReturn(true);
        return principal;
    }

    private AuthenticatedUser withoutUsageRead() {
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), organizationId, UUID.randomUUID(),
                "user@example.com", "User", Set.of(Permission.PROJECT_READ.value()));
        when(authorizationService.hasPermission(principal, Permission.USAGE_READ)).thenReturn(false);
        return principal;
    }

    @Test
    void queriesAreAlwaysScopedToTheCallersOrganization() {
        service.series(withUsageRead(), NOW.minus(Duration.ofHours(6)), NOW,
                UsageGranularity.HOUR, null, null);

        // The caller's organization, never a caller-supplied value.
        verify(bucketRepository).findForOrganization(eq(organizationId), eq(UsageGranularity.HOUR),
                any(), any());
    }

    @Test
    void aCallerWithoutUsageReadIsRefused() {
        // Being logged in is not permission to read usage.
        assertThatThrownBy(() -> service.series(withoutUsageRead(), NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        assertThatThrownBy(() -> service.series(null, NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aBackwardsRangeIsRejected() {
        assertThatThrownBy(() -> service.series(withUsageRead(), NOW, NOW.minusSeconds(60),
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anExcessiveRangeIsRejected() {
        // Without a cap, one request could ask for years of buckets.
        assertThatThrownBy(() -> service.series(withUsageRead(),
                NOW.minus(Duration.ofDays(400)), NOW, UsageGranularity.DAY, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("366");
    }

    @Test
    void granularityDefaultsToOneSuitingTheSpan() {
        // Reported rather than hidden, so a caller is never misled about which
        // resolution they were given.
        var result = service.series(withUsageRead(), NOW.minus(Duration.ofHours(6)), NOW,
                null, null, null);
        assertThat(result.granularity()).isEqualTo(UsageGranularity.HOUR);
    }

    @Test
    void anEmptyResultSummarisesToZeroRatherThanFailing() {
        var result = service.series(withUsageRead(), NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null);

        assertThat(result.summary().totalRequests()).isZero();
        assertThat(result.summary().errorRate()).isZero();
        assertThat(result.summary().errorRate()).isNotNaN();
        assertThat(result.points()).isEmpty();
    }

    @Test
    void endpointLookupIsAlsoTenantScoped() {
        when(bucketRepository.findDistinctEndpoints(any(), any(), any(), any()))
                .thenReturn(List.of("/api/v1/payments"));

        service.endpoints(withUsageRead(), NOW.minusSeconds(60), NOW, UsageGranularity.HOUR, null, null);

        verify(bucketRepository).findDistinctEndpoints(eq(organizationId),
                eq(UsageGranularity.HOUR), any(), any());
    }

    @Test
    void recommendedGranularityCoversEachRange() {
        assertThat(UsageAggregationService.recommendedFor(Duration.ofMinutes(30)))
                .isEqualTo(UsageGranularity.MINUTE);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofHours(6)))
                .isEqualTo(UsageGranularity.HOUR);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofDays(7)))
                .isEqualTo(UsageGranularity.DAY);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofDays(90)))
                .isEqualTo(UsageGranularity.MONTH);
    }

    @Test
    void projectAndEnvironmentFiltersNarrowTheResult() {
        UUID otherProject = UUID.randomUUID();
        UUID otherEnvironment = UUID.randomUUID();
        UsageBucket matching = bucket(UsageGranularity.HOUR, NOW, projectId, environmentId, 5, 1);
        UsageBucket otherProjectBucket = bucket(UsageGranularity.HOUR, NOW.plusSeconds(3600),
                otherProject, environmentId, 9, 9);
        UsageBucket otherEnvironmentBucket = bucket(UsageGranularity.HOUR, NOW.plusSeconds(7200),
                projectId, otherEnvironment, 7, 2);

        when(bucketRepository.findForOrganizationAndProjects(any(), any(), any(), any(), any()))
                .thenReturn(List.of(matching, otherProjectBucket, otherEnvironmentBucket));

        var result = service.series(withUsageRead(), NOW.minusSeconds(60),
                NOW.plusSeconds(10800), UsageGranularity.HOUR, projectId, environmentId);

        // Filters narrow; they never widen past what the tenant query returned.
        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).totalRequests()).isEqualTo(5);
        assertThat(result.summary().totalRequests()).isEqualTo(5);
        assertThat(result.summary().failedRequests()).isEqualTo(1);
    }

    @Test
    void apiKeyUsageIsRestrictedToThatKeyAndRequiresAnEnvironmentScope() {
        UUID selectedKeyId = UUID.randomUUID();
        UsageBucket matching = bucketWithKey(
                UsageGranularity.HOUR, NOW, projectId, environmentId, selectedKeyId, 7, 2);
        UsageBucket otherKey = bucketWithKey(
                UsageGranularity.HOUR, NOW.plusSeconds(3600), projectId, environmentId,
                UUID.randomUUID(), 20, 1);
        when(bucketRepository.findForOrganizationAndProjects(any(), any(), any(), any(), any()))
                .thenReturn(List.of(matching, otherKey));
        when(apiKeyRepository.existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                selectedKeyId, organizationId, projectId, environmentId)).thenReturn(true);

        var result = service.series(withUsageRead(), NOW.minusSeconds(60),
                NOW.plusSeconds(7200), UsageGranularity.HOUR, projectId, environmentId, selectedKeyId);

        assertThat(result.summary().totalRequests()).isEqualTo(7);
        assertThat(result.points()).hasSize(1);
        assertThatThrownBy(() -> service.series(withUsageRead(), NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, projectId, null, selectedKeyId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("environment");
    }

    private UsageBucket bucket(UsageGranularity granularity, Instant windowStart,
            UUID project, long total, long failed) {
        return bucket(granularity, windowStart, project, environmentId, total, failed);
    }

    private UsageBucket bucket(UsageGranularity granularity, Instant windowStart,
            UUID project, UUID environment, long total, long failed) {
        return bucketWithKey(granularity, windowStart, project, environment, UUID.randomUUID(), total, failed);
    }

    private UsageBucket bucketWithKey(UsageGranularity granularity, Instant windowStart,
            UUID project, UUID environment, UUID apiKeyId, long total, long failed) {
        UsageBucket created = UsageBucket.forWindow(granularity, windowStart, organizationId,
                project, environment, apiKeyId, null, "/api/v1/payments", "POST");
        created.replaceAggregates(new UsageBucket.Aggregates(total, total - failed, failed,
                failed, 0, total * 10L, 10, 10, 10, 10, 100L, windowStart), 0);
        return created;
    }
}
