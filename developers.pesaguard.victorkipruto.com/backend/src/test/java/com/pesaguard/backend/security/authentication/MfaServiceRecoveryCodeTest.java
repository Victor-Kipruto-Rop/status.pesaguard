package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.member.domain.MfaBackupCode;
import com.pesaguard.backend.member.domain.MfaSecret;
import com.pesaguard.backend.member.infrastructure.MfaBackupCodeRepository;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;

class MfaServiceRecoveryCodeTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void recoveryCodeIsNormalizedAndAtomicallyConsumedOnlyOnce() {
        MfaSecretRepository secrets = mock(MfaSecretRepository.class);
        MfaBackupCodeRepository backupCodes = mock(MfaBackupCodeRepository.class);
        TotpService totp = mock(TotpService.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        UUID userId = UUID.randomUUID();
        MfaSecret secret = MfaSecret.enrol(userId, "ciphertext");
        String presentedCode = "4AZ_uq0 - Q8xNuU1f";
        String normalizedCode = "4AZ_uq0Q8xNuU1f";
        CredentialCryptoService crypto = new CredentialCryptoService(
                new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                new SecureRandom());
        when(secrets.findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(userId))
                .thenReturn(Optional.of(secret));
        when(encryption.decrypt("ciphertext")).thenReturn("totp-secret");
        when(totp.matchingCounter("totp-secret", normalizedCode)).thenReturn(-1L);
        when(backupCodes.consumeIfUnused(
                crypto.sha256(normalizedCode), NOW)).thenReturn(1, 0);
        MfaService service = new MfaService(
                secrets, backupCodes, totp, encryption, crypto,
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.verify(userId, presentedCode)).isTrue();
        assertThat(service.verify(userId, presentedCode)).isFalse();
    }

    @Test
    void regeneratedRecoveryCodesHaveStrongRandomMaterialAndOnlyHashesAreStored() {
        MfaSecretRepository secrets = mock(MfaSecretRepository.class);
        MfaBackupCodeRepository backupCodes = mock(MfaBackupCodeRepository.class);
        TotpService totp = mock(TotpService.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        UUID userId = UUID.randomUUID();
        MfaSecret secret = MfaSecret.enrol(userId, "ciphertext");
        CredentialCryptoService crypto = new CredentialCryptoService(
                new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                new SecureRandom());
        when(secrets.findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(userId))
                .thenReturn(Optional.of(secret));
        when(encryption.decrypt("ciphertext")).thenReturn("totp-secret");
        when(totp.matchingCounter("totp-secret", "123456")).thenReturn(100L);
        when(backupCodes.findBySecretId(secret.getId())).thenReturn(List.of());
        MfaService service = new MfaService(
                secrets, backupCodes, totp, encryption, crypto,
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<String> codes = service.regenerateBackupCodes(userId, "123456");

        assertThat(codes).hasSize(10).allMatch(code -> code.matches("[A-Za-z0-9_-]{22}"));
        assertThat(codes).doesNotHaveDuplicates();
        ArgumentCaptor<MfaBackupCode> stored = ArgumentCaptor.forClass(MfaBackupCode.class);
        verify(backupCodes, org.mockito.Mockito.times(10)).save(stored.capture());
        assertThat(stored.getAllValues()).allSatisfy(code ->
                assertThat(code.getCodeHash()).hasSize(64).isNotIn(codes));
    }
}
