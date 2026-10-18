package com.pesaguard.backend.security.authentication;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class UsernamePolicy {

    private static final String USERNAME_PATTERN = "[a-z]{6,25}";

    public String validate(String username) {
        if (username == null || username.isBlank()) {
            throw invalid("USERNAME_REQUIRED", "Enter a username to create your account.");
        }
        if (!username.matches(USERNAME_PATTERN)) {
            throw invalid("USERNAME_INVALID",
                    "Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        }
        return username;
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
