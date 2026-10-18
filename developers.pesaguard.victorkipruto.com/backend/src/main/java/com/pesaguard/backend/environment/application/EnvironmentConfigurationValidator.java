package com.pesaguard.backend.environment.application;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.project.application.ProjectMetadataValidator;

/** Validates bounded non-secret configuration and environment origin policy. */
@Component
public class EnvironmentConfigurationValidator {

    private final ProjectMetadataValidator metadataValidator;

    public EnvironmentConfigurationValidator(ProjectMetadataValidator metadataValidator) {
        this.metadataValidator = metadataValidator;
    }

    public void validate(Map<String, Object> configuration, EnvironmentType environmentType) {
        metadataValidator.validate(configuration);
        if (!configuration.containsKey("allowedOrigins")) return;
        Object origins = configuration.get("allowedOrigins");
        if (!(origins instanceof Collection<?> values) || values.size() > 50) invalid();
        Set<String> uniqueOrigins = new HashSet<>();
        for (Object value : (Collection<?>) origins) {
            if (!(value instanceof String origin) || !validOrigin(origin, environmentType)
                    || !uniqueOrigins.add(origin.trim().toLowerCase(java.util.Locale.ROOT))) {
                invalid();
            }
        }
    }

    private boolean validOrigin(String value, EnvironmentType type) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getRawPath() != null
                            && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && (type == EnvironmentType.SANDBOX || type == EnvironmentType.DEVELOPMENT)
                    && isLoopback(uri.getHost());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private boolean isLoopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1")
                || normalized.equals("::1") || normalized.equals("[::1]");
    }

    private void invalid() {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ENVIRONMENT_CONFIGURATION",
                "Environment configuration is invalid. Allowed origins must be unique HTTPS origins; "
                        + "HTTP is permitted only for local development and sandbox origins.");
    }
}
