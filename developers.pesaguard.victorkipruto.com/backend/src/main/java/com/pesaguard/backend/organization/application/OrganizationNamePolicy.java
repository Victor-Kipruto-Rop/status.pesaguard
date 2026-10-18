package com.pesaguard.backend.organization.application;

import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.exception.BusinessException;

public final class OrganizationNamePolicy {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 120;

    private OrganizationNamePolicy() {}

    public static String validate(String name) {
        String normalized = name == null ? "" : name.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORGANIZATION_NAME_INVALID",
                    "Organization name must contain 2 to 120 characters and cannot contain control characters.");
        }
        return normalized;
    }
}
