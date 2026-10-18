package com.pesaguard.backend.security.authentication;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_UTF8_BYTES = 72;
    private static final java.util.List<String> COMMON_PASSWORDS = java.util.List.of(
            "password", "qwerty", "letmein", "welcome", "admin", "iloveyou",
            "monkey", "dragon", "football", "sunshine", "princess", "trustno1",
            "changeme", "passw0rd", "abc123", "welcome1", "secret", "passcode",
            "credential", "login", "hello", "security", "account", "developer",
            "master", "root", "starwars", "superman", "batman", "computer");
    private final PasswordBreachChecker breachedPasswordChecker;

    public PasswordPolicy(PasswordBreachChecker breachedPasswordChecker) {
        this.breachedPasswordChecker = breachedPasswordChecker;
    }

    public void validate(String password, String email) {
        validate(password, email, MIN_LENGTH, MAX_UTF8_BYTES, new String[0]);
    }

    public void validate(String password, String email, String... personalInfo) {
        validate(password, email, MIN_LENGTH, MAX_UTF8_BYTES, personalInfo);
    }

    public void validate(String password, String email, int minimumLength, int maximumUtf8Bytes) {
        validate(password, email, minimumLength, maximumUtf8Bytes, new String[0]);
    }

    private void validate(
            String password,
            String email,
            int minimumLength,
            int maximumUtf8Bytes,
            String... personalInfo) {
        int codePointCount = password.codePointCount(0, password.length());
        int utf8Bytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int effectiveMinimum = Math.max(MIN_LENGTH, minimumLength);
        int effectiveMaximum = Math.min(MAX_UTF8_BYTES, maximumUtf8Bytes);
        if (codePointCount < effectiveMinimum) {
            throw invalid("Use at least " + effectiveMinimum + " characters.");
        }
        if (utf8Bytes > effectiveMaximum) {
            throw invalid("Password must be no more than " + effectiveMaximum + " UTF-8 bytes.");
        }
        String localPart = email.substring(0, email.indexOf('@'));
        if (password.equalsIgnoreCase(email)
                || includesPersonalInfo(password, localPart, personalInfo)) {
            throw invalid("Password must not contain your email address or personal information.");
        }
        if (hasEasyPattern(password)) {
            throw invalid("Avoid common passwords, sequences, and repeated characters.");
        }
        if (breachedPasswordChecker.isCompromised(password)) {
            throw invalid("This password appears in known data breaches. Choose a different password.");
        }
    }

    public void validateMaximumLength(String password) {
        validateMaximumLength(password, MAX_UTF8_BYTES);
    }

    public void validateMaximumLength(String password, int maximumUtf8Bytes) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > Math.min(MAX_UTF8_BYTES, maximumUtf8Bytes)) {
            throw invalid("Password must be no more than "
                    + Math.min(MAX_UTF8_BYTES, maximumUtf8Bytes) + " UTF-8 bytes.");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "WEAK_PASSWORD",
                message);
    }

    private boolean includesPersonalInfo(String password, String localPart, String... personalInfo) {
        String candidate = normalize(password);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(localPart),
                        java.util.Arrays.stream(personalInfo == null ? new String[0] : personalInfo))
                .filter(java.util.Objects::nonNull)
                .flatMap(value -> java.util.Arrays.stream(
                        value.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")))
                .filter(token -> token.length() >= 3)
                .map(PasswordPolicy::normalize)
                .anyMatch(candidate::contains);
    }

    private boolean hasEasyPattern(String password) {
        String candidate = normalize(password);
        if (candidate.matches(".*([a-z0-9])\\1{3,}.*")) {
            return true;
        }
        if (COMMON_PASSWORDS.stream().map(PasswordPolicy::normalize).anyMatch(candidate::contains)) {
            return true;
        }
        if (java.util.List.of("qwerty", "asdfgh", "zxcvbn", "qazwsx").stream()
                .anyMatch(candidate::contains)) {
            return true;
        }
        for (int index = 0; index <= candidate.length() - 4; index++) {
            int first = candidate.charAt(index);
            boolean ascending = candidate.charAt(index + 1) == first + 1
                    && candidate.charAt(index + 2) == first + 2
                    && candidate.charAt(index + 3) == first + 3;
            boolean descending = candidate.charAt(index + 1) == first - 1
                    && candidate.charAt(index + 2) == first - 2
                    && candidate.charAt(index + 3) == first - 3;
            if (ascending || descending) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
