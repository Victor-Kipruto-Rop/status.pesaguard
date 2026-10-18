package com.pesaguard.backend.security.authentication;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.MfaBackupCode;
import com.pesaguard.backend.member.domain.MfaSecret;
import com.pesaguard.backend.member.infrastructure.MfaBackupCodeRepository;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;

/**
 * TOTP enrolment and verification, with single-use recovery codes.
 *
 * <p>Two invariants drive the implementation:
 *
 * <p><b>A factor is inert until proven.</b> Enrolment returns a secret that is
 * stored encrypted but unconfirmed. Only a successful code submission promotes
 * it to active. Without that gate, anyone who could reach the enrolment response
 * could attach a factor to a victim's account.
 *
 * <p><b>A code works once.</b> TOTP codes are valid for a whole 30 second step,
 * so verification alone would leave an observed code reusable. Each factor keeps
 * the highest step it has accepted and refuses anything not strictly newer,
 * which is what makes a shoulder-surfed code worthless after it is entered.
 */
@Service
public class MfaService {

    /** How many recovery codes are issued. Ten is the common vendor default. */
    private static final int BACKUP_CODE_COUNT = 10;
    private static final int BACKUP_CODE_BYTES = 16;

    private final MfaSecretRepository secretRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final TotpService totpService;
    private final SecretEncryptionService encryptionService;
    private final CredentialCryptoService credentialCryptoService;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public MfaService(
            MfaSecretRepository secretRepository,
            MfaBackupCodeRepository backupCodeRepository,
            TotpService totpService,
            SecretEncryptionService encryptionService,
            CredentialCryptoService credentialCryptoService,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            Clock clock) {
        this.secretRepository = secretRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.credentialCryptoService = credentialCryptoService;
        this.secureRandom = new SecureRandom();
        this.clock = clock;
    }

    /**
     * Begins enrolment.
     *
     * <p>Any previous live factor is revoked first: the unique index permits one
     * live secret per user, and silently keeping an older one would leave a
     * factor the user has forgotten about still able to authenticate.
     */
    @Transactional
    public Enrolment begin(UUID userId, String issuer, String accountLabel) {
        Instant now = clock.instant();
        secretRepository.findByUserIdAndRevokedAtIsNull(userId).ifPresent(existing -> {
            existing.revoke(now);
            secretRepository.save(existing);
        });

        String secret = totpService.generateSecret();
        MfaSecret stored = secretRepository.save(MfaSecret.enrol(userId, encryptionService.encrypt(secret)));
        return new Enrolment(stored.getId(), secret,
                totpService.provisioningUri(issuer, accountLabel, secret));
    }
    /**
     * Confirms enrolment with a code from the new factor, then issues recovery codes.
     *
     * @return the plaintext recovery codes. Shown exactly once; only hashes are kept.
     */
    @Transactional
    public List<String> confirm(UUID userId, String code) {
        Instant now = clock.instant();
        MfaSecret secret = requireLiveSecret(userId);

        long counter = totpService.matchingCounter(decryptionOrThrow(secret), code);
        // The first code also consumes its step, so confirming enrolment cannot
        // leave that same code usable as a login factor. The conditional update is
        // what makes that hold if the confirm request is sent twice at once.
        if (counter < 0 || secretRepository.consumeStepIfNewer(secret.getId(), counter) != 1) {
            throw invalidCode();
        }
        secret.confirm(now);
        secretRepository.save(secret);
        return issueBackupCodes(secret);
    }

    /**
     * Replaces all recovery codes after the caller proves possession of the
     * current factor. The proof may be a fresh TOTP code or an unused recovery
     * code; the latter is consumed before the previous set is replaced.
     */
    @Transactional
    public List<String> regenerateBackupCodes(UUID userId, String code) {
        if (!verify(userId, code)) {
            throw invalidCode();
        }
        MfaSecret secret = secretRepository
                .findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(userId)
                .orElseThrow(this::invalidCode);
        return issueBackupCodes(secret);
    }

    private List<String> issueBackupCodes(MfaSecret secret) {
        // Replace rather than append: previously issued codes belong to a factor
        // that no longer exists once the user re-enrols.
        backupCodeRepository.deleteAll(backupCodeRepository.findBySecretId(secret.getId()));
        List<String> plaintextCodes = new ArrayList<>(BACKUP_CODE_COUNT);
        for (int index = 0; index < BACKUP_CODE_COUNT; index++) {
            byte[] raw = new byte[BACKUP_CODE_BYTES];
            secureRandom.nextBytes(raw);
            String code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            plaintextCodes.add(code);
            backupCodeRepository.save(MfaBackupCode.issue(secret.getId(),
                    credentialCryptoService.sha256(normaliseBackupCode(code))));
        }
        return plaintextCodes;
    }

    /**
     * Verifies a second factor during login.
     *
     * @return true when a TOTP code or a recovery code was accepted
     */
    @Transactional
    public boolean verify(UUID userId, String code) {
        Instant now = clock.instant();
        // Only a confirmed factor is consulted. An unconfirmed one cannot satisfy
        // a challenge even if its secret is known.
        MfaSecret secret = secretRepository
                .findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(userId)
                .orElseThrow(this::invalidCode);

        String candidate = normaliseBackupCode(code);

        long counter = totpService.matchingCounter(decryptionOrThrow(secret), candidate);
        if (counter >= 0) {
            // Replay of an already-consumed step fails here.
            if (secret.consumeStep(counter)) {
                secretRepository.save(secret);
                return true;
            }
            return false;
        }

        // Conditional update: two concurrent presentations of the same recovery
        // code cannot both succeed, because only the first matches "unused".
        return backupCodeRepository.consumeIfUnused(
                credentialCryptoService.sha256(candidate), now) == 1;
    }

    /** Removes the factor and its recovery codes. */
    @Transactional
    public void disable(UUID userId) {
        Instant now = clock.instant();
        secretRepository.findByUserIdAndRevokedAtIsNull(userId).ifPresent(secret -> {
            secret.revoke(now);
            secretRepository.save(secret);
            backupCodeRepository.deleteAll(backupCodeRepository.findBySecretId(secret.getId()));
        });
    }

    /** Erases every personal factor and recovery code for account deactivation/deletion. */
    @Transactional
    public void eraseCredentials(UUID userId) {
        List<MfaSecret> secrets = secretRepository.findByUserId(userId);
        for (MfaSecret secret : secrets) {
            backupCodeRepository.deleteBySecretId(secret.getId());
        }
        secretRepository.deleteAll(secrets);
    }

    /** Whether the user has a factor that can satisfy a login challenge. */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return secretRepository
                .findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(userId).isPresent();
    }

    private MfaSecret requireLiveSecret(UUID userId) {
        return secretRepository.findByUserIdAndRevokedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "MFA_NOT_ENROLLED", "No factor is being enrolled for this account."));
    }

    private String decryptionOrThrow(MfaSecret secret) {
        try {
            return encryptionService.decrypt(secret.getSecretCiphertext());
        } catch (IllegalStateException exception) {
            // The stored value cannot be read. Fail closed rather than treating
            // the factor as absent, which would silently downgrade a secured
            // account to password-only authentication.
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "MFA_UNAVAILABLE",
                    "This factor could not be read. Contact support.");
        }
    }

    private String normaliseBackupCode(String code) {
        return code == null ? "" : code.trim().replace(" ", "").replace("-", "");
    }

    private BusinessException invalidCode() {
        // One message for every failure: wrong code, no factor, spent code. Any
        // distinction would let an attacker probe which factor state exists.
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_INVALID",
                "The verification code is incorrect or has expired.");
    }

    /** Enrolment material, returned once and never recoverable afterwards. */
    public record Enrolment(UUID secretId, String secret, String provisioningUri) {
    }
}