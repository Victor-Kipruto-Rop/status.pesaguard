package com.pesaguard.backend.credentials.api;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;

import org.springframework.stereotype.Component;

@Component
public class ApiKeyGenerator {

    private final CredentialCryptoService crypto;

    public ApiKeyGenerator(CredentialCryptoService crypto) {
        this.crypto = crypto;
    }

    public GeneratedKey generate(EnvironmentType environmentType) {
        String secret = prefix(environmentType) + crypto.randomToken(32);
        return new GeneratedKey(secret, secret.substring(0, 16));
    }

    private String prefix(EnvironmentType environmentType) {
        return switch (environmentType) {
            case DEVELOPMENT -> "pgk_dev_";
            case SANDBOX -> "pgk_sbx_";
            case STAGING -> "pgk_stg_";
            case PRODUCTION -> "pgk_live_";
        };
    }

    public String hash(String rawKey) {
        return crypto.hmacSha256(rawKey);
    }

    public String pipelineHash(String rawKey) {
        return crypto.sha256(rawKey);
    }

    public record GeneratedKey(String rawKey, String prefix) {
    }
}
