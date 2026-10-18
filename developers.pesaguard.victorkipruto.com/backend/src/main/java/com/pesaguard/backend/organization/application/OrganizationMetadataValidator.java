package com.pesaguard.backend.organization.application;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class OrganizationMetadataValidator {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_STRING_LENGTH = 500;

    public void validate(Map<String, Object> metadata) {
        if (metadata == null) {
            throw invalid();
        }
        validateMap(metadata, 1);
    }

    private void validateMap(Map<?, ?> metadata, int depth) {
        if (depth > MAX_DEPTH || metadata.size() > 50) {
            throw invalid();
        }
        for (Map.Entry<?, ?> entry : metadata.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            if (key.isBlank() || key.length() > 80 || isSensitiveKey(key)) {
                throw invalid();
            }
            validateValue(entry.getValue(), depth + 1);
        }
    }

    private void validateCollection(Collection<?> value, int depth) {
        if (depth > MAX_DEPTH || value.size() > 50) {
            throw invalid();
        }
        value.forEach(item -> validateValue(item, depth + 1));
    }

    private void validateValue(Object value, int depth) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH) {
                throw invalid();
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            validateMap(map, depth);
            return;
        }
        if (value instanceof Collection<?> collection) {
            validateCollection(collection, depth);
            return;
        }
        throw invalid();
    }

    private boolean isSensitiveKey(String key) {
        return key.contains("password") || key.contains("secret") || key.contains("token")
                || key.contains("private_key") || key.contains("api_key");
    }

    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ORGANIZATION_METADATA",
                "Organization metadata is invalid or contains a prohibited key.");
    }
}
