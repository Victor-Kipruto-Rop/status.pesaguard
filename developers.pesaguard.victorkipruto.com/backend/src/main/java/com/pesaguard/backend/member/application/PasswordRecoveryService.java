package com.pesaguard.backend.member.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.PasswordResetToken;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.PasswordResetTokenRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

/**
 * Password reset and password change.
 *
 * <p>Password reset requests do not reveal whether an email has an account.
 * Completing a reset ends every session, everywhere. Changing a password
 * because it may be compromised must not leave the attacker holding a refresh
 * token, in any organization the user belongs to. Refresh families and access
 * sessions are both revoked, which is why this cannot live in the controller
 * layer.
 */
@Service
public class PasswordRecoveryService {

    /**
     * Short enough that an abandoned reset expires before it can be found.
     *
     * <p>Deliberately shorter than the email-verification window: unlike an
     * address confirmation, this value authorises full account takeover, so it
     * should not sit in an inbox for a day.
     */
    static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserAccountRepository accountRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final CredentialCryptoService credentialCryptoService;
    private final SecretKey credentialKey;
    private final Clock clock;

    public PasswordRecoveryService(
            UserAccountRepository accountRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            RefreshTokenService refreshTokenService,
            CredentialCryptoService credentialCryptoService,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.refreshTokenService = refreshTokenService;
        this.credentialCryptoService = credentialCryptoService;
        this.credentialKey = credentialKey;
        this.clock = clock;
    }
    /**
     * Starts a reset if the address is registered.
     *
     * <p>Returns null when there is nothing to send, which the caller treats
     * like success to prevent account enumeration. Any outstanding token is
     * revoked first so that requesting a second reset cannot leave the first one
     * live and usable.
     */
    @Transactional
    public String request(String email) {
        Instant now = clock.instant();
        Optional<UserAccount> account = accountRepository.findByEmailForUpdate(
                email.trim().toLowerCase(java.util.Locale.ROOT));
        if (account.isEmpty()) {
            return null;
        }
        UserAccount user = account.get();
        if (!user.getStatus().canAuthenticate()) {
            return null;
        }

        var outstandingTokens = tokenRepository.findOutstandingByUserId(user.getId());
        for (PasswordResetToken existing : outstandingTokens) {
            existing.revoke(now);
            tokenRepository.save(existing);
        }
        if (!outstandingTokens.isEmpty()) {
            tokenRepository.flush();
        }

        String token = credentialCryptoService.randomToken(32);
        tokenRepository.save(PasswordResetToken.issue(user.getId(), hash(token),
                now, now.plus(RESET_TTL), null));
        return token;
    }

    /**
     * Completes a reset.
     *
     * <p>The policy is validated before anything is persisted: a reset that stored
     * an unacceptable password would lock the user out of their own account with
     * no second chance to choose a different one.
     */
    @Transactional
    public void complete(String token, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken resetToken = tokenRepository.findByTokenHash(hash(token.trim()))
                .orElseThrow(this::invalidToken);
        if (!resetToken.isRedeemable(now)) {
            throw invalidToken();
        }

        UserAccount account = accountRepository.findById(resetToken.getUserId())
                .orElseThrow(this::invalidToken);
        passwordPolicy.validate(newPassword, account.getEmail(), account.getDisplayName());

        account.changePassword(passwordEncoder.encode(newPassword), now);
        accountRepository.save(account);
        resetToken.consume(now);
        tokenRepository.save(resetToken);

        // Everything that could still authenticate as this user dies with the old
        // password: live sessions and every refresh family, in all organizations.
        sessionService.revokeActiveByUserId(account.getId());
        refreshTokenService.revokeAllForUser(account.getId(), "PASSWORD_RESET");
    }

    /** Requires a fresh password proof before a sensitive account-security action. */
    @Transactional(readOnly = true)
    public void requireCurrentPassword(UUID userId, String currentPassword) {
        UserAccount account = accountRepository.findById(userId)
                .orElseThrow(this::invalidCredentials);
        requirePasswordMatch(currentPassword, account);
    }

    /** Revokes every outstanding reset credential when an account is disabled or deleted. */
    @Transactional
    public void revokeOutstanding(UUID userId) {
        Instant now = clock.instant();
        for (PasswordResetToken token : tokenRepository.findOutstandingByUserId(userId)) {
            token.revoke(now);
            tokenRepository.save(token);
        }
    }

    private void requirePasswordMatch(String currentPassword, UserAccount account) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw invalidCredentials();
        }
    }

    private String hash(String token) {
        return CredentialCryptoService.hmacSha256(credentialKey, "password-reset:" + token);
    }

    private BusinessException invalidToken() {
        // One message for unknown, spent and expired alike: distinguishing them
        // would confirm that a guessed token once existed.
        return new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_INVALID",
                "This password reset link is invalid or has expired.");
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "The current password is incorrect.");
    }
}