package com.pesaguard.backend.security.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the AES-GCM wrapper that protects TOTP secrets.
 *
 * <p>GCM's authentication tag is the property under test here. Without it, a
 * tampered ciphertext would decrypt to attacker-chosen plaintext, which for a TOTP
 * secret means swapping in a factor the attacker controls. These tests assert
 * that tampering is detected rather than silently accepted.
 */
class SecretEncryptionServiceTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private SecretEncryptionService service;

    @BeforeEach
    void setUp() {
        service = serviceFor("01234567890123456789012345678901");
    }

    @Test
    void roundTripsASecret() {
        String encrypted = service.encrypt(SECRET);

        assertThat(service.decrypt(encrypted)).isEqualTo(SECRET);
    }

    @Test
    void neverStoresThePlaintext() {
        String encrypted = service.encrypt(SECRET);

        // The stored form must not reveal the secret in either encoding. Checking
        // the raw bytes as well as the base64 guards against an implementation
        // that base64s plaintext rather than encrypting it.
        assertThat(encrypted).doesNotContain(SECRET);
        assertThat(new String(Base64.getDecoder().decode(encrypted), StandardCharsets.ISO_8859_1))
                .doesNotContain(SECRET);
    }

    @Test
    void encryptionIsNonDeterministic() {
        // A fresh IV per call is what stops GCM reuse from leaking the XOR of two
        // plaintexts. Identical output here would mean a fixed IV.
        assertThat(service.encrypt(SECRET)).isNotEqualTo(service.encrypt(SECRET));
    }

    @Test
    void aTamperedCiphertextFailsToDecrypt() {
        byte[] combined = Base64.getDecoder().decode(service.encrypt(SECRET));
        combined[combined.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(combined);

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTamperedIvFailsToDecrypt() {
        byte[] combined = Base64.getDecoder().decode(service.encrypt(SECRET));
        combined[0] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(combined);

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSubstitutedCiphertextFailsToDecrypt() {
        // The realistic attack: an attacker with write access replaces the stored
        // secret with one they generated, wrapped under a key they do not hold.
        String attackerCiphertext = serviceFor("01234567890123456789012345678902").encrypt("ATTACKERSECRET");

        assertThatThrownBy(() -> service.decrypt(attackerCiphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedInputIsRejected() {
        assertThatThrownBy(() -> service.decrypt(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt("not base64 !!!")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyPlaintextIsRejected() {
        assertThatThrownBy(() -> service.encrypt("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private SecretEncryptionService serviceFor(String keyMaterial) {
        return new SecretEncryptionService(
                new SecretKeySpec(keyMaterial.getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                new SecureRandom());
    }
}