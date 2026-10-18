package com.pesaguard.backend.oauth.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.exception.BusinessException;

/**
 * Client credentials taken from an HTTP Basic {@code Authorization} header.
 *
 * <p>Query-parameter credentials are deliberately not supported. URLs are written
 * to access logs, proxy logs, load-balancer logs and browser history; a client
 * secret must never be transported in one. Per RFC 6749 §2.3.1 the client id and
 * secret are form-urlencoded before being base64 encoded, so both halves are
 * decoded as {@code application/x-www-form-urlencoded} rather than raw.
 */
public record ClientCredentials(String clientId, String clientSecret) {

    public static ClientCredentials fromBasic(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw unauthorized("Client authentication is required.");
        }
        String header = authorizationHeader.trim();
        if (header.length() < 6 || !header.regionMatches(true, 0, "Basic", 0, 5)) {
            throw unauthorized("Client authentication must use HTTP Basic.");
        }
        String encoded = header.substring(5).trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw unauthorized("Client authentication is malformed.");
        }
        String pair = new String(decoded, StandardCharsets.UTF_8);
        int separator = pair.indexOf(':');
        if (separator < 0) {
            throw unauthorized("Client authentication is malformed.");
        }
        String clientId = urlDecode(pair.substring(0, separator));
        String clientSecret = urlDecode(pair.substring(separator + 1));
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw unauthorized("Client authentication is incomplete.");
        }
        return new ClientCredentials(clientId, clientSecret);
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT", message);
    }
}