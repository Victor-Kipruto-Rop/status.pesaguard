package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.member.infrastructure.UserAccountRepository;

class UsernameGeneratorTest {

    @Test
    void derivesUsernameFromEmailAndAddsSuffixWhenOccupied() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.existsByUsernameIgnoreCase("alex.smith")).thenReturn(true);
        when(users.existsByUsernameIgnoreCase("alex.smith-2")).thenReturn(false);

        assertThat(UsernameGenerator.generate("Alex.Smith@example.com", users))
                .isEqualTo("alex.smith-2");
    }

    @Test
    void fallsBackToDeveloperWhenEmailLocalPartHasNoUsableUsername() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.existsByUsernameIgnoreCase("developer")).thenReturn(false);

        assertThat(UsernameGenerator.generate("..@example.com", users))
                .isEqualTo("developer");
    }
}
