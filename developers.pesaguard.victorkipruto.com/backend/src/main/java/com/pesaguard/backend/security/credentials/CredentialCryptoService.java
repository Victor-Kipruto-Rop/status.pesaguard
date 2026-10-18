package com.pesaguard.backend.security.credentials;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CredentialCryptoService {

    private final SecretKey credentialKey;
    private final java.security.SecureRandom secureRandom;

    public CredentialCryptoService(
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            java.security.SecureRandom secureRandom) {
        this.credentialKey = credentialKey;
        this.secureRandom = secureRandom;
    }

    public String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String hmacSha256(String value) {
        return hmacSha256(credentialKey, value);
    }

    public String randomToken(int bytes) {
        byte[] token = new byte[bytes];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256(SecretKey key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
