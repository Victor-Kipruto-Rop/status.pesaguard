package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class UsernameServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final UsernamePolicy usernamePolicy = new UsernamePolicy();
    private final UsernameService service = new UsernameService(
            users, audit, Clock.fixed(NOW, ZoneOffset.UTC), usernamePolicy);

    @Test
    void updatesUsernameThatMeetsLowercasePolicy() {
        UserAccount user = UserAccount.create("person@example.com", "person", "Person", "hash");
        UUID userId = user.getId();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        AuthenticatedUser principal = principal(userId);

        UsernameResponse response = service.update(principal, new UpdateUsernameRequest("devuser"));

        assertThat(response.username()).isEqualTo("devuser");
        assertThat(user.getUsername()).isEqualTo("devuser");
        verify(users).saveAndFlush(user);
    }

    @Test
    void refusesUsernameAlreadyOwnedByAnotherAccount() {
        UserAccount user = UserAccount.create("person@example.com", "person", "Person", "hash");
        UUID userId = user.getId();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.existsByUsernameIgnoreCaseAndIdNot("takenuser", userId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(principal(userId), new UpdateUsernameRequest("takenuser")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("already in use");
        verify(users, never()).saveAndFlush(user);
    }

    @Test
    void rejectsUppercaseUsernameWithoutNormalizingIt() {
        UserAccount user = UserAccount.create("person@example.com", "person", "Person", "hash");
        UUID userId = user.getId();
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.update(principal(userId), new UpdateUsernameRequest("Devuser")))
                .hasMessage("Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.");
        verify(users, never()).saveAndFlush(user);
    }

    private static AuthenticatedUser principal(UUID userId) {
        return new AuthenticatedUser(
                userId, UUID.randomUUID(), UUID.randomUUID(),
                "person@example.com", "Person", Set.of("ROLE_OWNER"));
    }
}
