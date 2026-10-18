package com.pesaguard.backend.platformadmin.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;

/**
 * Resolves an internal operator token.
 *
 * <p>Signed with a key distinct from any developer credential key, so a token
 * minted for one purpose cannot be replayed as the other. Sharing a key would
 * mean a developer API key presented as an operator token is a matter of
 * formatting, and the entire separation rests on the tokens being unrelated.
 *
 * <p>The token carries its own capability set. It is <b>signed, not looked up</b>,
 * so revoking an operator means rotating the key rather than editing a row, and
 * there is no table an attacker with partial database access could widen their
 * own capabilities in.
 */
@Service
public class OperatorTokenService {

    private static final String TOKEN_PREFIX = "pgop_";
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final SecretKey operatorKey;
    private final Clock clock;

    public OperatorTokenService(
            @Qualifier("operatorHmacKey") SecretKey operatorKey,
            Clock clock) {
        this.operatorKey = operatorKey;
        this.clock = clock;
    }

    /**
     * Mints an operator token.
     *
     * @param capabilities what the token grants; the caller must not be able to
     *        widen these after issuance
     */
    public String issue(UUID operatorId, String subject, Set<OperatorCapability> capabilities,
            Duration ttl) {
        if (operatorId == null || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An operator token requires an id and subject");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("An operator token must have a positive lifetime");
        }
        long expiresAt = clock.instant().plus(ttl).getEpochSecond();
        StringBuilder payload = new StringBuilder()
                .append(operatorId).append('\n')
                .append(subject.trim()).append('\n')
                .append(expiresAt).append('\n');
        for (OperatorCapability capability : capabilities.stream().sorted().toList()) {
            payload.append(capability.name()).append(',');
        }
        String signature = CredentialCryptoService.hmacSha256(operatorKey, payload.toString());
        return TOKEN_PREFIX + base64Url(payload.toString()) + "." + signature;
    }

    /**
     * Verifies a token and returns the operator, or empty if it is not valid.
     *
     * <p>Returns empty rather than throwing: an absent or forged token is simply
     * an unauthenticated request, and must not be distinguishable from a missing
     * one to a caller probing the endpoint.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AuthenticatedOperator> authenticate(String presented) {
        if (presented == null || !presented.startsWith(TOKEN_PREFIX)) {
            return java.util.Optional.empty();
        }
        if (presented.length() > MAX_TOKEN_LENGTH) {
            return java.util.Optional.empty();
        }
        String body = presented.substring(TOKEN_PREFIX.length());
        int separator = body.lastIndexOf('.');
        if (separator <= 0) {
            return java.util.Optional.empty();
        }
        String encodedPayload = body.substring(0, separator);
        String presentedSignature = body.substring(separator + 1);

        String payload;
        try {
            payload = new String(java.util.Base64.getUrlDecoder().decode(encodedPayload),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return java.util.Optional.empty();
        }

        String expected = CredentialCryptoService.hmacSha256(operatorKey, payload);
        // Constant time: the comparison is reachable from the network, and an early
        // exit would leak the signature a byte at a time.
        if (!constantTimeEquals(expected, presentedSignature)) {
            return java.util.Optional.empty();
        }

        String[] parts = payload.split("\n", 4);
        if (parts.length < 4) {
            return java.util.Optional.empty();
        }
        long expiresAt;
        UUID operatorId;
        try {
            expiresAt = Long.parseLong(parts[2].trim());
            operatorId = UUID.fromString(parts[0].trim());
        } catch (NumberFormatException malformed) {
            return java.util.Optional.empty();
        }
        if (clock.instant().getEpochSecond() >= expiresAt) {
            return java.util.Optional.empty();
        }

        Set<OperatorCapability> capabilities = EnumSet.noneOf(OperatorCapability.class);
        String encoded = parts[3].trim();
        if (!encoded.isEmpty()) {
            for (String token : encoded.split(",")) {
                try {
                    capabilities.add(OperatorCapability.valueOf(token.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException unknown) {
                    // An unrecognised capability is dropped rather than failing the
                    // whole token: an operator with one stale capability should not
                    // lose access to the rest, and dropping is the fail-safe side.
                }
            }
        }
        return java.util.Optional.of(
                new AuthenticatedOperator(operatorId, parts[1].trim(), capabilities, null));
    }

    /**
     * Compares two hex signatures without leaking their contents through timing.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: an early
     * exit would let a caller recover the signature one character at a time, and
     * this comparison is reachable from the network.
     */
    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private String base64Url(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}