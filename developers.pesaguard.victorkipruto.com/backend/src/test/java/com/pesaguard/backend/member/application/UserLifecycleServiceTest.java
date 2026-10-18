package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyService;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.api.AccountLifecycleView;
import com.pesaguard.backend.member.infrastructure.AccountDataPurgeRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.authentication.MfaService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

class UserLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final OrganizationMembershipRepository memberships = mock(OrganizationMembershipRepository.class);
    private final AccountDataPurgeRepository accountDataPurge = mock(AccountDataPurgeRepository.class);
    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final PasswordRecoveryService passwordRecovery = mock(PasswordRecoveryService.class);
    private final MfaService mfa = mock(MfaService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccountEmailDeliveryService emailDelivery = mock(AccountEmailDeliveryService.class);

    private UserLifecycleService service;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        service = new UserLifecycleService(accounts, memberships, accountDataPurge, apiKeys,
                passwordRecovery, mfa, sessions, refreshTokens, apiKeyService, audit,
                emailDelivery,
                Clock.fixed(NOW, ZoneOffset.UTC));
        account = UserAccount.create("person@example.com", "person", "Person", "password-hash");
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        when(mfa.isEnabled(account.getId())).thenReturn(false);
        when(memberships.existsByUserIdAndRole(account.getId(), OrganizationRole.OWNER)).thenReturn(false);
        when(accountDataPurge.purge(account.getId())).thenReturn(true);
        when(apiKeys.findByCreatedByOrderByCreatedAtDesc(account.getId())).thenReturn(List.of());
    }

    @Test
    void requestDeletionStartsTheThirtyDayGracePeriodAndAuditsIt() {
        UserAccount pending = service.requestDeletion(account.getId(), ORGANIZATION_ID, "current", null);

        assertThat(pending.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        assertThat(pending.deletionCompletesAt(UserLifecycleService.DELETION_GRACE_PERIOD))
                .contains(NOW.plus(UserLifecycleService.DELETION_GRACE_PERIOD));
        verify(passwordRecovery).requireCurrentPassword(account.getId(), "current");
        verify(accounts).save(account);
        verify(audit).append(eq(ORGANIZATION_ID), eq(account.getId()),
                eq("account.deletion.requested"), eq("user_account"),
                eq(account.getId().toString()), any(), any());
    }

    @Test
    void lifecycleReturnsTheDeletionScheduleWithoutOwnershipBlocking() {
        AccountLifecycleView lifecycle = service.lifecycle(account.getId());

        assertThat(lifecycle.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(lifecycle.deletionRequestedAt()).isNull();
    }

    @Test
    void deletionRequiresFreshMfaWhenEnabled() {
        when(mfa.isEnabled(account.getId())).thenReturn(true);
        when(mfa.verify(account.getId(), "123456")).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(
                account.getId(), ORGANIZATION_ID, "current", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("verification code");
        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(accounts, never()).save(account);
    }

    @Test
    void pauseSetsSupportedExpiryRevokesCredentialsAndSendsEmail() {
        service.deactivate(account.getId(), ORGANIZATION_ID, "current", null, 7);

        assertThat(account.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(account.getDeactivatedUntil()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60L));
        verify(sessions).revokeActiveByUserId(account.getId());
        verify(refreshTokens).revokeAllForUser(account.getId(), "ACCOUNT_DEACTIVATED");
        verify(emailDelivery).sendAccountPaused(account.getEmail(), account.getDeactivatedUntil());
    }

    @Test
    void pauseRejectsUnsupportedDuration() {
        assertThatThrownBy(() -> service.deactivate(account.getId(), ORGANIZATION_ID, "current", null, 14))
                .isInstanceOf(BusinessException.class);
        verify(accounts, never()).save(account);
    }

    @Test
    void expiredPauseAutomaticallyRestoresAccountAndNotifiesByEmail() {
        Instant expiredAt = NOW.minusSeconds(60);
        account.deactivate(NOW.minusSeconds(8 * 24 * 60 * 60L), expiredAt);
        when(accounts.findPausedAccountsDue(UserStatus.DEACTIVATED, NOW)).thenReturn(List.of(account));

        int restored = service.reactivateDueAccounts();

        assertThat(restored).isEqualTo(1);
        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.getDeactivatedUntil()).isNull();
        verify(emailDelivery).sendAccountReactivated(account.getEmail());
    }

    @Test
    void accountOwnerCanRequestDeletion() {
        when(memberships.existsByUserIdAndRole(account.getId(), OrganizationRole.OWNER)).thenReturn(true);

        assertThat(service.requestDeletion(account.getId(), ORGANIZATION_ID, "current", null).getStatus())
                .isEqualTo(UserStatus.PENDING_DELETION);
        verify(accounts).save(account);
    }

    @Test
    void deactivationStopsTheAccountAndRevokesPersonalCredentials() {
        service.deactivate(account.getId(), ORGANIZATION_ID, "current", null, 30);

        assertThat(account.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(sessions).revokeActiveByUserId(account.getId());
        verify(refreshTokens).revokeAllForUser(account.getId(), "ACCOUNT_DEACTIVATED");
        verify(mfa).eraseCredentials(account.getId());
        verify(passwordRecovery).revokeOutstanding(account.getId());
        verify(apiKeyService).revokeCreatedByUserId(account.getId(), NOW);
    }

    @Test
    void deletionDoesNotCompleteBeforeGraceAndPurgesAfterward() {
        account.requestDeletion(NOW);
        assertThat(service.completeDeletion(account.getId())).isFalse();

        service = new UserLifecycleService(accounts, memberships, accountDataPurge, apiKeys,
                passwordRecovery, mfa, sessions, refreshTokens, apiKeyService, audit,
                emailDelivery,
                Clock.fixed(NOW.plus(UserLifecycleService.DELETION_GRACE_PERIOD), ZoneOffset.UTC));

        assertThat(service.completeDeletion(account.getId())).isTrue();
        verify(accountDataPurge).purge(account.getId());
        verify(emailDelivery).sendAccountDeleted(account.getEmail());
        verify(sessions).revokeActiveByUserId(account.getId());
    }

    @Test
    void deletionCannotBeCancelledAfterTheGracePeriod() {
        account.requestDeletion(NOW.minus(UserLifecycleService.DELETION_GRACE_PERIOD));

        assertThatThrownBy(() -> service.cancelDeletion(
                account.getId(), ORGANIZATION_ID, "current", null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("DELETION_GRACE_EXPIRED");
        assertThat(account.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        verify(accounts, never()).save(account);
    }

    @Test
    void cancellationRestoresTheAccountDuringGrace() {
        account.requestDeletion(NOW.minusSeconds(60));

        service.cancelDeletion(account.getId(), ORGANIZATION_ID, "current", null);

        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(accounts).save(account);
        verify(audit).append(eq(ORGANIZATION_ID), eq(account.getId()),
                eq("account.deletion.cancelled"), eq("user_account"),
                eq(account.getId().toString()), any(), any());
    }

    @Test
    void rejectsPurgeWhenDatabaseSaysDeletionIsNotDue() {
        account.requestDeletion(NOW.minus(UserLifecycleService.DELETION_GRACE_PERIOD));
        when(accountDataPurge.purge(account.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.completeDeletion(account.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).code())
                .isEqualTo("ACCOUNT_DELETION_NOT_DUE");
        assertThat(account.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        verify(sessions).revokeActiveByUserId(account.getId());
    }

    @Test
    void exportContainsOnlyMetadataForKeysCreatedByTheAuthenticatedUser() {
        UUID keyId = UUID.randomUUID();
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(keyId);
        when(key.getOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(key.getProjectId()).thenReturn(UUID.randomUUID());
        when(key.getEnvironmentId()).thenReturn(UUID.randomUUID());
        when(key.getName()).thenReturn("test credential");
        when(key.getKeyPrefix()).thenReturn("pg_test_abcd");
        when(key.getScopes()).thenReturn("payments:read");
        when(key.getStatus()).thenReturn(ApiKeyStatus.ACTIVE);
        when(apiKeys.findByCreatedByOrderByCreatedAtDesc(account.getId())).thenReturn(List.of(key));
        when(memberships.findAllByUserId(account.getId())).thenReturn(List.of());

        var export = service.exportAccount(account.getId(), ORGANIZATION_ID, "current", null);

        assertThat(export.profile().id()).isEqualTo(account.getId());
        assertThat(export.createdApiKeys()).hasSize(1);
        assertThat(export.createdApiKeys().getFirst().prefix()).isEqualTo("pg_test_abcd");
        assertThat(export.createdApiKeys().getFirst().scopes()).containsExactly("payments:read");
        verify(apiKeys).findByCreatedByOrderByCreatedAtDesc(account.getId());
        verify(audit).append(eq(ORGANIZATION_ID), eq(account.getId()),
                eq("account.data_exported"), eq("user_account"),
                eq(account.getId().toString()), any(), any());
    }
}
