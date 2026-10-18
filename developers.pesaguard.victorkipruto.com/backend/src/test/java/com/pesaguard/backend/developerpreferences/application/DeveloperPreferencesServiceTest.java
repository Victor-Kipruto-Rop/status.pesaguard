package com.pesaguard.backend.developerpreferences.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.developerpreferences.api.UpdateDeveloperPreferencesRequest;
import com.pesaguard.backend.developerpreferences.api.DeveloperPreferencesView;
import com.pesaguard.backend.developerpreferences.api.UpdateAppearanceRequest;
import com.pesaguard.backend.developerpreferences.domain.DeveloperPreferences;
import com.pesaguard.backend.developerpreferences.infrastructure.DeveloperPreferencesRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@ExtendWith(MockitoExtension.class)
class DeveloperPreferencesServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            userId, UUID.randomUUID(), UUID.randomUUID(), "developer@example.com", "Developer", Set.of());

    @Mock
    private DeveloperPreferencesRepository repository;

    private DeveloperPreferencesService service;

    @BeforeEach
    void setUp() {
        service = new DeveloperPreferencesService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsDocumentedDefaultsUntilTheDeveloperSavesPreferences() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service.get(principal).requestTimeoutMs()).isEqualTo(30000);
        assertThat(service.get(principal).retryCount()).isZero();
        assertThat(service.get(principal).theme()).isEqualTo("SYSTEM");
    }

    @Test
    void persistsPreferencesForTheAuthenticatedUser() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(DeveloperPreferences.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(principal, new UpdateDeveloperPreferencesRequest(15000, 2));

        assertThat(result).isEqualTo(new DeveloperPreferencesView(15000, 2, "SYSTEM", 0));
        verify(repository).saveAndFlush(any(DeveloperPreferences.class));
    }

    @Test
    void updatesOnlyTheAuthenticatedUsersThemeAndRequiresCurrentVersion() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(DeveloperPreferences.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.updateAppearance(principal,
                new UpdateAppearanceRequest("DARK", 0));

        assertThat(result.theme()).isEqualTo("DARK");
        verify(repository).saveAndFlush(any(DeveloperPreferences.class));
    }

    @Test
    void rejectsAppearanceUpdatesFromAStaleSettingsVersion() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAppearance(principal,
                new UpdateAppearanceRequest("LIGHT", 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed elsewhere");
    }
}
