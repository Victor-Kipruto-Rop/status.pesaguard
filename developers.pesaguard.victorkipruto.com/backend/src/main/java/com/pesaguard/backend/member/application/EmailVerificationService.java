package com.pesaguard.backend.member.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.HttpStatus;
import com.pesaguard.backend.config.ApplicationProperties;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.authentication.EmailLoginMfaChallenge;
import com.pesaguard.backend.security.authentication.LoginEmailMfaInvalidException;

/**
 * Issues and confirms email verification codes.
 *
 * <p>The token is random, single-use, and time-boxed. Only its HMAC is stored,
 * so a leaked database cannot be used to verify an arbitrary address — the same
 * rule that applies to session and API-key secrets.
 *
 * <p>Every path returns the same generic failure for "no such token", "already
 * used", and "expired". Distinguishing them would let an attacker enumerate which
 * addresses have outstanding verification requests.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration LOGIN_MFA_TTL = Duration.ofMinutes(10);
    private static final int CODE_BOUND = 1_000_000;

    private final UserAccountRepository accountRepository;
    private final SecretKey credentialKey;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final AccountEmailDeliveryService emailDelivery;
    private final int maxAttempts;

    public EmailVerificationService(
            UserAccountRepository accountRepository,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            Clock clock,
            ApplicationProperties properties,
            AccountEmailDeliveryService emailDelivery) {
        this.accountRepository = accountRepository;
        this.credentialKey = credentialKey;
        this.clock = clock;
        this.emailDelivery = emailDelivery;
        this.maxAttempts = properties.security().accountLoginFailureLimit();
    }

    /** Issues a single-use six-digit code and delivers it by email. */
    @Transactional
    public VerificationChallenge issue(UserAccount account) {
        String code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(CODE_BOUND));
        Instant issuedAt = clock.instant();
        account.beginEmailVerification(hashCode(account.getId(), code), issuedAt);
        accountRepository.saveAndFlush(account);
        // The token itself must not appear in a log. The account id is enough to
        // correlate the send with the row.
        log.info("email verification issued accountId={}", account.getId());
        emailDelivery.sendEmailVerificationCode(account.getEmail(), code);
        return challenge(issuedAt);
    }

    /**
     * Confirms a verification token.
     *
     * @return the verified account
     * @throws BusinessException with a generic message for every failure mode
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public UserAccount confirm(String email, String code) {
        if (email == null || email.isBlank() || code == null || !code.matches("\\d{6}")) {
            throw genericFailure();
        }
        UserAccount account = accountRepository.findByEmailForUpdate(
                email.trim().toLowerCase(java.util.Locale.ROOT)).orElseThrow(this::genericFailure);
        if (account.isEmailVerified() || !account.getStatus().canAuthenticate()) throw genericFailure();
        Instant issuedAt = account.getEmailVerificationIssuedAt();
        if (issuedAt == null || account.getEmailVerificationHash() == null
                || !expiresAt(issuedAt).isAfter(clock.instant())) {
            throw new BusinessException(HttpStatus.GONE, "EMAIL_OTP_EXPIRED",
                    "This verification code has expired. Request a new code to continue.");
        }
        if (account.getEmailVerificationAttempts() >= maxAttempts) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_OTP_ATTEMPTS_EXCEEDED",
                    "Too many incorrect codes. Request a new code to continue.");
        }
        String candidateHash = hashCode(account.getId(), code);
        if (!account.matchesEmailVerificationHash(candidateHash)) {
            int attempts = account.recordEmailVerificationFailure();
            accountRepository.saveAndFlush(account);
            if (attempts >= maxAttempts) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_OTP_ATTEMPTS_EXCEEDED",
                        "Too many incorrect codes. Request a new code to continue.");
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMAIL_OTP_INVALID",
                    "That verification code is incorrect. Check the code and try again.");
        }
        account.verifyEmail(clock.instant());
        accountRepository.saveAndFlush(account);
        log.info("email verified accountId={}", account.getId());
        return account;
    }

    /** Confirms a legacy single-use link already issued before OTP verification was enabled. */
    @Transactional
    public UserAccount confirmLegacyLink(String token) {
        if (token == null || token.isBlank()) throw genericFailure();
        Optional<UserAccount> match = accountRepository.findByEmailVerificationHash(hashLegacyToken(token.trim()));
        if (match.isEmpty()) throw genericFailure();
        UserAccount account = match.get();
        Instant issuedAt = account.getEmailVerificationIssuedAt();
        if (account.isEmailVerified() || !account.getStatus().canAuthenticate() || issuedAt == null
                || !issuedAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) throw genericFailure();
        account.verifyEmail(clock.instant());
        accountRepository.saveAndFlush(account);
        log.info("email verified accountId={}", account.getId());
        return account;
    }

    /**
     * The stored hash for a token.
     *
     * <p>HMAC with the credential key rather than a bare digest, so a stolen table
     * plus knowledge of the input format cannot be brute-forced offline.
     */
    private String hashCode(UUID accountId, String code) {
        return CredentialCryptoService.hmacSha256(credentialKey, "email-verify:" + accountId + ":" + code);
    }

    private String hashLegacyToken(String token) {
        return CredentialCryptoService.hmacSha256(credentialKey, "email-verify:" + token);
    }

    private BusinessException genericFailure() {
        return new BusinessException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "EMAIL_VERIFICATION_INVALID",
                "This verification code is invalid or has expired.");
    }

    /** Re-issues a token, invalidating any previous one. */
    @Transactional
    public void reissue(String email) {
        Optional<UserAccount> match = accountRepository.findByEmailForUpdate(
                email.trim().toLowerCase(java.util.Locale.ROOT));
        if (match.isEmpty() || match.get().isEmailVerified()
                || !match.get().getStatus().canAuthenticate()) {
            return;
        }
        Instant issuedAt = match.get().getEmailVerificationIssuedAt();
        if (issuedAt != null && issuedAt.plus(RESEND_COOLDOWN).isAfter(clock.instant())) return;
        issue(match.get());
    }

    /** Sends the login-triggered code in its own transaction before login returns its challenge. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerificationChallenge issueIfCooldownElapsed(String email) {
        Optional<UserAccount> match = accountRepository.findByEmailForUpdate(
                email.trim().toLowerCase(java.util.Locale.ROOT));
        if (match.isEmpty() || match.get().isEmailVerified()
                || !match.get().getStatus().canAuthenticate()) return null;
        Instant issuedAt = match.get().getEmailVerificationIssuedAt();
        if (issuedAt != null && issuedAt.plus(RESEND_COOLDOWN).isAfter(clock.instant())
                && expiresAt(issuedAt).isAfter(clock.instant())) return challenge(issuedAt);
        return issue(match.get());
    }

    /** Starts a login-only email factor without modifying the account's verified address state. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmailLoginMfaChallenge issueLoginMfa(UUID userId, UUID organizationId) {
        UserAccount account = accountRepository.findByIdForUpdate(userId)
                .orElseThrow(this::invalidLoginChallenge);
        requireMfaEligibleAccount(account);
        Instant now = clock.instant();
        if (account.getLoginMfaChallengeId() != null
                && account.getLoginMfaIssuedAt() != null
                && account.getLoginMfaIssuedAt().plus(LOGIN_MFA_TTL).isAfter(now)) {
            if (!java.util.Objects.equals(account.getLoginMfaOrganizationId(), organizationId)) {
                account.bindLoginMfaToWorkspace(organizationId, now);
                accountRepository.saveAndFlush(account);
            }
            return loginMfaChallenge(account);
        }
        return issueLoginMfaCode(account, organizationId, UUID.randomUUID(), now);
    }

    /** Resends a login code only for a live challenge and after the cooldown. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmailLoginMfaChallenge resendLoginMfa(UUID challengeId) {
        UserAccount account = accountRepository.findByLoginMfaChallengeIdForUpdate(challengeId)
                .orElseThrow(this::invalidLoginChallenge);
        requireMfaEligibleAccount(account);
        Instant now = clock.instant();
        if (account.getLoginMfaIssuedAt() == null) {
            throw invalidLoginChallenge();
        }
        if (account.getLoginMfaIssuedAt().plus(RESEND_COOLDOWN).isAfter(now)) {
            return loginMfaChallenge(account);
        }
        return issueLoginMfaCode(account, account.getLoginMfaOrganizationId(), challengeId, now);
    }

    /** Consumes the challenge in an independent transaction so bad attempts cannot roll back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginEmailMfaInvalidException.class)
    public LoginMfaIdentity consumeLoginMfa(UUID challengeId, String code) {
        if (challengeId == null || code == null || !code.matches("\\d{6}")) {
            throw invalidLoginChallenge();
        }
        UserAccount account = accountRepository.findByLoginMfaChallengeIdForUpdate(challengeId)
                .orElseThrow(this::invalidLoginChallenge);
        requireMfaEligibleAccount(account);
        Instant now = clock.instant();
        Instant issuedAt = account.getLoginMfaIssuedAt();
        if (issuedAt == null || !issuedAt.plus(LOGIN_MFA_TTL).isAfter(now)) {
            throw invalidLoginChallenge();
        }
        if (account.getLoginMfaAttempts() >= maxAttempts) {
            throw invalidLoginChallenge();
        }
        if (!account.matchesLoginMfa(challengeId, hashLoginMfaCode(account.getId(), code))) {
            account.recordLoginMfaFailure();
            accountRepository.saveAndFlush(account);
            throw invalidLoginChallenge();
        }
        UUID organizationId = account.getLoginMfaOrganizationId();
        account.clearLoginMfa(now);
        accountRepository.saveAndFlush(account);
        return new LoginMfaIdentity(account.getId(), organizationId);
    }

    private EmailLoginMfaChallenge issueLoginMfaCode(
            UserAccount account, UUID organizationId, UUID challengeId, Instant now) {
        String code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(CODE_BOUND));
        account.issueLoginMfa(challengeId, organizationId, hashLoginMfaCode(account.getId(), code), now);
        accountRepository.saveAndFlush(account);
        emailDelivery.sendLoginEmailCode(account.getEmail(), code);
        log.info("login email MFA issued accountId={}", account.getId());
        return loginMfaChallenge(account);
    }

    private EmailLoginMfaChallenge loginMfaChallenge(UserAccount account) {
        Instant issuedAt = account.getLoginMfaIssuedAt();
        return new EmailLoginMfaChallenge(
                account.getLoginMfaChallengeId(),
                issuedAt.plus(LOGIN_MFA_TTL),
                issuedAt.plus(RESEND_COOLDOWN),
                maskEmail(account.getEmail()));
    }

    private String hashLoginMfaCode(UUID userId, String code) {
        return CredentialCryptoService.hmacSha256(
                credentialKey, "login-email-mfa:" + userId + ":" + code);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private void requireMfaEligibleAccount(UserAccount account) {
        if (!account.isActive() || !account.isEmailVerified()) {
            throw invalidLoginChallenge();
        }
    }

    private LoginEmailMfaInvalidException invalidLoginChallenge() {
        return new LoginEmailMfaInvalidException(
                "LOGIN_EMAIL_MFA_INVALID", "That sign-in verification code is invalid or has expired.");
    }

    private VerificationChallenge challenge(Instant issuedAt) {
        return new VerificationChallenge(
                issuedAt, expiresAt(issuedAt), issuedAt.plus(RESEND_COOLDOWN));
    }

    /** When an outstanding token issued at {@code issuedAt} expires. */
    public Instant expiresAt(Instant issuedAt) {
        return issuedAt.plus(CODE_TTL);
    }

    public record VerificationChallenge(
            Instant issuedAt,
            Instant expiresAt,
            Instant resendAvailableAt) {
    }

    public record LoginMfaIdentity(UUID userId, UUID organizationId) {
    }
}
