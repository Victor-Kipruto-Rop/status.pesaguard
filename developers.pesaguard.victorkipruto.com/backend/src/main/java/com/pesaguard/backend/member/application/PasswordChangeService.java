package com.pesaguard.backend.member.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.authentication.PasswordChangeChallengeView;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

@Service
public class PasswordChangeService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;
    private static final int CODE_BOUND = 1_000_000;

    private final UserAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final AccountEmailDeliveryService emailDelivery;
    private final SecretKey credentialKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordChangeService(
            UserAccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            RefreshTokenService refreshTokenService,
            AccountEmailDeliveryService emailDelivery,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.refreshTokenService = refreshTokenService;
        this.emailDelivery = emailDelivery;
        this.credentialKey = credentialKey;
        this.clock = clock;
    }

    @Transactional
    public PasswordChangeChallengeView request(
            UUID userId, UUID sessionId, String currentPassword, String newPassword) {
        UserAccount account = accountRepository.findByIdForUpdate(userId)
                .orElseThrow(this::invalidCredentials);
        requirePasswordMatch(currentPassword, account);
        validateNewPassword(newPassword, account);

        UUID challengeId = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(CODE_BOUND));
        Instant now = clock.instant();
        account.issuePasswordChangeChallenge(challengeId, sessionId, codeHash(challengeId, code), now);
        accountRepository.saveAndFlush(account);
        emailDelivery.sendPasswordChangeCode(account.getEmail(), code);
        return new PasswordChangeChallengeView(challengeId, maskEmail(account.getEmail()), now.plus(CHALLENGE_TTL));
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void verify(
            UUID userId,
            UUID sessionId,
            UUID challengeId,
            String code,
            String currentPassword,
            String newPassword) {
        Instant now = clock.instant();
        UserAccount account = challengeId == null
                ? null
                : accountRepository.findByPasswordChangeChallengeIdForUpdate(challengeId).orElse(null);
        if (account == null || !account.getId().equals(userId)
                || !sessionId.equals(account.getPasswordChangeSessionId())) {
            throw invalidChallenge();
        }
        Instant issuedAt = account.getPasswordChangeIssuedAt();
        if (issuedAt == null || !issuedAt.plus(CHALLENGE_TTL).isAfter(now)) {
            account.clearPasswordChange(now);
            accountRepository.saveAndFlush(account);
            throw new BusinessException(HttpStatus.GONE, "PASSWORD_CHANGE_CODE_EXPIRED",
                    "This password change code has expired. Request a new code to continue.");
        }
        if (account.getPasswordChangeAttempts() >= MAX_ATTEMPTS) {
            account.clearPasswordChange(now);
            accountRepository.saveAndFlush(account);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_CHANGE_ATTEMPTS_EXCEEDED",
                    "Too many incorrect codes. Request a new code to continue.");
        }
        if (!account.matchesPasswordChangeChallenge(challengeId, sessionId, codeHash(challengeId, code))) {
            account.recordPasswordChangeFailure();
            accountRepository.saveAndFlush(account);
            if (account.getPasswordChangeAttempts() >= MAX_ATTEMPTS) {
                account.clearPasswordChange(now);
                accountRepository.saveAndFlush(account);
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_CHANGE_ATTEMPTS_EXCEEDED",
                        "Too many incorrect codes. Request a new code to continue.");
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_CODE_INVALID",
                    "That verification code is incorrect. Check the code and try again.");
        }

        requirePasswordMatch(currentPassword, account);
        validateNewPassword(newPassword, account);
        account.changePassword(passwordEncoder.encode(newPassword), now);
        accountRepository.saveAndFlush(account);
        sessionService.revokeActiveByUserIdExcept(userId, sessionId);
        refreshTokenService.revokeAllForUser(userId, "PASSWORD_CHANGED");
        emailDelivery.sendPasswordChanged(account.getEmail());
    }

    private void validateNewPassword(String newPassword, UserAccount account) {
        if (newPassword != null && passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED",
                    "The new password must differ from the current one.");
        }
        passwordPolicy.validate(newPassword, account.getEmail(), account.getDisplayName());
    }

    private void requirePasswordMatch(String currentPassword, UserAccount account) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw invalidCredentials();
        }
    }

    private String codeHash(UUID challengeId, String code) {
        return CredentialCryptoService.hmacSha256(
                credentialKey, "password-change:" + challengeId + ":" + code);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 1) return "***";
        String local = email.substring(0, at);
        String visible = local.substring(0, 1);
        return visible + "***" + email.substring(at);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "The current password is incorrect.");
    }

    private BusinessException invalidChallenge() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_CODE_INVALID",
                "This password change code is invalid or no longer available.");
    }
}
