package com.pesaguard.backend.loadtest.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class LoadGeneratorTokenGuard {
    private final String configuredToken;

    public LoadGeneratorTokenGuard(
            @Value("${pesaguard.load-testing.generator-token:}") String configuredToken) {
        this.configuredToken = configuredToken;
    }

    public void requireValid(String suppliedToken) {
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOAD_TEST_EXECUTOR_UNAVAILABLE",
                    "The load-generator control plane is not configured.");
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "LOAD_GENERATOR_UNAUTHORIZED",
                    "A valid load-generator credential is required.");
        }
    }
}
