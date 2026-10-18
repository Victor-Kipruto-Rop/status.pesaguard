package com.pesaguard.backend.security.credentials;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Authenticated encryption for values the server must be able to read back.
 *
 * <p>Credentials that only need to be compared (session tokens, refresh tokens,
 * backup codes and API secrets) are hashed. A TOTP seed and webhook signing secret
 * must be read back to verify codes or sign outbound requests, so they are
 * encrypted instead.
 *
 * <p>AES-256-GCM is used so that the value is confidential <em>and</em>
 * authenticated. GCM's authentication tag means a modified ciphertext fails to
 * decrypt rather than silently yielding a different secret, so an attacker who
 * gains write access to the column cannot swap in a secret they know. The
 * decryption key is derived from the existing credential HMAC key rather than
 * introduced as a new secret, so no additional key needs provisioning or rotation
 * coordination; a full rotation of the credential key re-encrypts this at rest by
 * the same procedure as every other stored hash.
 *
 * <p>The IV is random per encryption and prefixed to the ciphertext. Reusing an
 * IV under GCM would leak the XOR of two plaintexts and, worse, destroy
 * authentication, so it is generated fresh on every call.
 */
@Service
public class SecretEncryptionService {

    private static final String CIPHER = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    /** 128-bit authentication tag. */
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom;

    public SecretEncryptionService(
            @Qualifier("credentialHmacKey") SecretKey credentialHmacKey,
            SecureRandom secureRandom) {
        this.key = deriveKey(credentialHmacKey);
        this.secureRandom = secureRandom;
    }

    /**
     * Derives a 256-bit AES key from the HMAC key.
     *
     * <p>Uses the HMAC key as input to SHA-256 with a fixed domain-separation
     * label, so the derived key is distinct from the HMAC key itself. Reusing one
     * key for two algorithms would leak across them.
     */
    private static SecretKey deriveKey(SecretKey credentialHmacKey) {
        // hmacSha256 returns lowercase hex, which is text rather than key bytes.
        // Hex-decoding is required: using the hex characters directly would give a
        // 64-character ASCII key that AES would reject for being the wrong length,
        // and would silently weaken it if it did not.
        byte[] material = HexFormat.of().parseHex(
                CredentialCryptoService.hmacSha256(credentialHmacKey, "totp-secret-encryption:v1"));
        return new SecretKeySpec(material, CIPHER);
    }

    /** Encrypts a UTF-8 secret to base64(iv || ciphertext || tag). */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("A secret is required");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            // Never surface the cause: it would describe the key material.
            throw new IllegalStateException("Unable to encrypt secret", exception);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     *
     * @throws IllegalStateException if the ciphertext is malformed, was produced
     *         under a different key, or has been tampered with. Callers treat this
     *         as "this factor cannot be verified" rather than surfacing the cause.
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("No encrypted secret to read");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("Encrypted secret is truncated");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt secret", exception);
        }
    }
}