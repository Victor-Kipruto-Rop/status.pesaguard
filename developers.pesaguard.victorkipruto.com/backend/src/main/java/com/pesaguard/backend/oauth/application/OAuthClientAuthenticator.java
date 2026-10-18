package com.pesaguard.backend.oauth.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.oauth.domain.OAuthApplication;
import com.pesaguard.backend.oauth.infrastructure.OAuthApplicationRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;

/**
 * Confidential-client authentication.
 *
 * <p>Both the client ID and the secret are required, and the secret is compared
 * against its stored HMAC in constant time. A wrong client ID, a wrong secret,
 * and an unknown client all produce the same failure so the endpoint cannot be
 * used to enumerate registered applications.
 *
 * <p>A revoked application can never authenticate, even with a valid secret.
 */
@Component
public class OAuthClientAuthenticator {

    private final OAuthApplicationRepository applicationRepository;
    private final CredentialCryptoService crypto;

    public OAuthClientAuthenticator(OAuthApplicationRepository applicationRepository,
            CredentialCryptoService crypto) {
        this.applicationRepository = applicationRepository;
        this.crypto = crypto;
    }

    /**
     * @return the authenticated application, or empty when authentication fails for
     *         any reason.
     */
    public Optional<OAuthApplication> authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Optional.empty();
        }
        return applicationRepository.findByClientId(clientId.trim())
                .filter(application -> !application.getStatus().isTerminal())
                .filter(application -> secretsMatch(application, clientSecret))
                .filter(application -> application.getStatus().canAuthorize());
    }

    public boolean secretsMatch(OAuthApplication application, String presentedSecret) {
        if (presentedSecret == null || presentedSecret.isBlank()) {
            return false;
        }
        String presentedHash = crypto.hmacSha256(presentedSecret.trim());
        return java.security.MessageDigest.isEqual(
                presentedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                application.getClientSecretHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}