package com.pesaguard.backend.member.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyService;
import com.pesaguard.backend.credentials.api.ScopeCodec;
import com.pesaguard.backend.member.api.AccountDataExport;
import com.pesaguard.backend.member.api.AccountLifecycleView;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.infrastructure.AccountDataPurgeRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.authentication.MfaService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

@Service
public class UserLifecycleService {

    public static final Duration DELETION_GRACE_PERIOD = Duration.ofDays(30);

    private final UserAccountRepository accountRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final AccountDataPurgeRepository accountDataPurgeRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordRecoveryService passwordRecoveryService;
    private final AccountEmailDeliveryService emailDelivery;
    private final MfaService mfaService;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final Clock clock;

    public UserLifecycleService(
            UserAccountRepository accountRepository,
            OrganizationMembershipRepository membershipRepository,
            AccountDataPurgeRepository accountDataPurgeRepository,
            ApiKeyRepository apiKeyRepository,
            PasswordRecoveryService passwordRecoveryService,
            MfaService mfaService,
            SessionService sessionService,
            RefreshTokenService refreshTokenService,
            ApiKeyService apiKeyService,
            AuditService auditService,
            AccountEmailDeliveryService emailDelivery,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.accountDataPurgeRepository = accountDataPurgeRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordRecoveryService = passwordRecoveryService;
        this.emailDelivery = emailDelivery;
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.refreshTokenService = refreshTokenService;
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserAccount requireAccount(UUID userId) {
        return accountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "The account could not be found."));
    }

    @Transactional(readOnly = true)
    public AccountLifecycleView lifecycle(UUID userId) {
        UserAccount account = requireAccount(userId);
        return new AccountLifecycleView(
                account.getStatus(),
                account.getDeletionRequestedAt(),
                account.deletionCompletesAt(DELETION_GRACE_PERIOD).orElse(null));
    }

    @Transactional
    public AccountDataExport exportAccount(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        UserAccount account = requireAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        List<AccountDataExport.Membership> memberships = membershipRepository.findAllByUserId(userId).stream()
                .map(membership -> new AccountDataExport.Membership(
                        membership.getOrganization().getId(),
                        membership.getOrganization().getName(),
                        membership.getRole(),
                        membership.getStatus(),
                        membership.getCreatedAt()))
                .toList();
        List<AccountDataExport.CreatedApiKey> createdApiKeys = apiKeyRepository
                .findByCreatedByOrderByCreatedAtDesc(userId).stream()
                .map(this::toExportKey)
                .toList();
        appendAudit(organizationId, userId, "account.data_exported",
                Map.of("membershipCount", memberships.size(), "apiKeyCount", createdApiKeys.size()));
        return new AccountDataExport(
                clock.instant(),
                new AccountDataExport.Profile(
                        account.getId(), account.getEmail(), account.getUsername(), account.getDisplayName(),
                        account.getStatus(), account.getCreatedAt(), account.getEmailVerifiedAt()),
                memberships,
                createdApiKeys);
    }

    @Transactional
    public UserAccount requestDeletion(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        requireNotDeleted(account);
        if (account.getStatus() == UserStatus.PENDING_DELETION) {
            throw conflict("DELETION_ALREADY_PENDING", "A deletion request is already pending.");
        }
        account.requestDeletion(now);
        accountRepository.save(account);
        appendAudit(organizationId, userId, "account.deletion.requested",
                Map.of("gracePeriodDays", DELETION_GRACE_PERIOD.toDays()));
        emailDelivery.sendDeletionRequested(
                account.getEmail(), now.plus(DELETION_GRACE_PERIOD));
        return account;
    }

    @Transactional
    public UserAccount cancelDeletion(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        if (account.getStatus() != UserStatus.PENDING_DELETION) {
            throw conflict("DELETION_NOT_PENDING", "This account has no pending deletion to cancel.");
        }
        if (account.deletionCompletesAt(DELETION_GRACE_PERIOD)
                .filter(completeAt -> !now.isBefore(completeAt)).isPresent()) {
            throw conflict("DELETION_GRACE_EXPIRED", "The deletion grace period has elapsed.");
        }
        account.cancelDeletion(now);
        accountRepository.save(account);
        appendAudit(organizationId, userId, "account.deletion.cancelled", Map.of());
        return account;
    }

    @Transactional
    public void deactivate(
            UUID userId, UUID organizationId, String currentPassword, String mfaCode, int durationDays) {
        if (durationDays != 7 && durationDays != 30 && durationDays != 90) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_PAUSE_DURATION_INVALID",
                    "Choose a pause duration of 7, 30, or 90 days.");
        }
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        requireStepUp(userId, currentPassword, mfaCode);
        if (account.getStatus() != UserStatus.ACTIVE) {
            throw conflict("ACCOUNT_NOT_ACTIVE", "Only an active account can be deactivated.");
        }
        Instant deactivatedUntil = now.plus(Duration.ofDays(durationDays));
        account.deactivate(now, deactivatedUntil);
        accountRepository.save(account);
        revokePersonalCredentials(userId, now, "ACCOUNT_DEACTIVATED");
        appendAudit(organizationId, userId, "account.deactivated", Map.of("durationDays", durationDays));
        emailDelivery.sendAccountPaused(account.getEmail(), deactivatedUntil);
    }

    @Transactional
    public int reactivateDueAccounts() {
        Instant now = clock.instant();
        List<UserAccount> dueAccounts = accountRepository.findPausedAccountsDue(UserStatus.DEACTIVATED, now);
        for (UserAccount account : dueAccounts) {
            if (!account.isPauseDue(now)) continue;
            account.restore(now);
            accountRepository.save(account);
            emailDelivery.sendAccountReactivated(account.getEmail());
        }
        return dueAccounts.size();
    }

    /** Completes a due deletion and permanently purges the linked organization data. */
    @Transactional
    public boolean completeDeletion(UUID userId) {
        Instant now = clock.instant();
        UserAccount account = lockAccount(userId);
        if (account.getStatus() != UserStatus.PENDING_DELETION) {
            return false;
        }
        if (account.deletionCompletesAt(DELETION_GRACE_PERIOD)
                .filter(completeAt -> !now.isBefore(completeAt)).isEmpty()) {
            return false;
        }
        revokePersonalCredentials(userId, now, "ACCOUNT_DELETED");
        String email = account.getEmail();
        if (!accountDataPurgeRepository.purge(userId)) {
            throw conflict("ACCOUNT_DELETION_NOT_DUE",
                    "The account deletion grace period has not elapsed.");
        }
        emailDelivery.sendAccountDeleted(email);
        return true;
    }

    private UserAccount lockAccount(UUID userId) {
        return accountRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "The account could not be found."));
    }

    private void requireStepUp(UUID userId, String currentPassword, String mfaCode) {
        passwordRecoveryService.requireCurrentPassword(userId, currentPassword);
        if (mfaService.isEnabled(userId)
                && (mfaCode == null || mfaCode.isBlank() || !mfaService.verify(userId, mfaCode))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_INVALID",
                    "The verification code is incorrect or has expired.");
        }
    }

    private void revokePersonalCredentials(UUID userId, Instant now, String reason) {
        sessionService.revokeActiveByUserId(userId);
        refreshTokenService.revokeAllForUser(userId, reason);
        mfaService.eraseCredentials(userId);
        passwordRecoveryService.revokeOutstanding(userId);
        apiKeyService.revokeCreatedByUserId(userId, now);
    }

    private void requireNotDeleted(UserAccount account) {
        if (account.getStatus() == UserStatus.DELETED) {
            throw conflict("ACCOUNT_DELETED", "This account has already been deleted.");
        }
    }

    private void appendAudit(UUID organizationId, UUID userId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, userId, action, "user_account", userId.toString(),
                RequestContext.currentRequestId(), metadata);
    }

    private AccountDataExport.CreatedApiKey toExportKey(ApiKey key) {
        return new AccountDataExport.CreatedApiKey(
                key.getId(), key.getOrganizationId(), key.getProjectId(), key.getEnvironmentId(),
                key.getName(), key.getKeyPrefix(),
                ScopeCodec.decode(key.getScopes()).stream().sorted().toList(), key.getStatus(),
                key.getCreatedAt(), key.getExpiresAt(), key.getLastUsedAt());
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
