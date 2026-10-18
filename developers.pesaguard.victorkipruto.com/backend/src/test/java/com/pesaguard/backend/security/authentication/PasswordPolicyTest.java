package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(password -> false);

    @Test
    void acceptsLongPassphrase() {
        assertThatCode(() -> policy.validate("correct horse battery staple", "person@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortPasswordAndEmailEquality() {
        assertThatThrownBy(() -> policy.validate("short", "person@example.com"))
                .hasMessage("Use at least 12 characters.");
        assertThatThrownBy(() -> policy.validate("person@example.com", "person@example.com"))
                .hasMessage("Password must not contain your email address or personal information.");
    }

    @Test
    void explainsUtf8ByteLimit() {
        assertThatThrownBy(() -> policy.validate("a".repeat(73), "person@example.com"))
                .hasMessage("Password must be no more than 72 UTF-8 bytes.");
    }

    @Test
    void rejectsCommonPatternsAndPersonalInformation() {
        assertThatThrownBy(() -> policy.validate("SomePassword123!", "person@example.com"))
                .hasMessage("Avoid common passwords, sequences, and repeated characters.");
        assertThatThrownBy(() -> policy.validate("Long1234SecurePhrase!", "person@example.com"))
                .hasMessage("Avoid common passwords, sequences, and repeated characters.");
        assertThatThrownBy(() -> policy.validate("PersonLongSecurePhrase!", "person@example.com"))
                .hasMessage("Password must not contain your email address or personal information.");
        assertThatThrownBy(() -> policy.validate(
                        "AlexandraLongSecurePhrase!", "alex@example.com", "Alexandra Kipruto"))
                .hasMessage("Password must not contain your email address or personal information.");
    }

    @Test
    void rejectsPasswordFoundInKnownBreaches() {
        PasswordPolicy breachedPasswordPolicy = new PasswordPolicy(password -> true);

        assertThatThrownBy(() -> breachedPasswordPolicy.validate("LongUniquePhrase!42", "person@example.com"))
                .hasMessage("This password appears in known data breaches. Choose a different password.");
    }
}
