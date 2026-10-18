package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UsernamePolicyTest {

    private final UsernamePolicy policy = new UsernamePolicy();

    @Test
    void acceptsLowercaseUsernamesAtBothLengthBoundaries() {
        assertThat(policy.validate("abcdef")).isEqualTo("abcdef");
        assertThat(policy.validate("a".repeat(25))).isEqualTo("a".repeat(25));
    }

    @Test
    void requiresUsername() {
        assertThatThrownBy(() -> policy.validate("  "))
                .hasMessage("Enter a username to create your account.");
    }

    @Test
    void rejectsValuesOutsideLowercaseLettersAndSixToTwentyFiveCharacters() {
        assertThatThrownBy(() -> policy.validate("abcde"))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        assertThatThrownBy(() -> policy.validate("a".repeat(26)))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        assertThatThrownBy(() -> policy.validate("Abcdef"))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        assertThatThrownBy(() -> policy.validate("abc123"))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        assertThatThrownBy(() -> policy.validate("abc def"))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        assertThatThrownBy(() -> policy.validate("person@example.com"))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
    }
}
