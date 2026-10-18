package com.pesaguard.backend.developerpreferences.application;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.developerpreferences.api.DeveloperPreferencesView;
import com.pesaguard.backend.developerpreferences.api.UpdateAppearanceRequest;
import com.pesaguard.backend.developerpreferences.api.UpdateDeveloperPreferencesRequest;
import com.pesaguard.backend.developerpreferences.domain.DeveloperPreferences;
import com.pesaguard.backend.developerpreferences.infrastructure.DeveloperPreferencesRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class DeveloperPreferencesService {

    private final DeveloperPreferencesRepository repository;
    private final Clock clock;

    public DeveloperPreferencesService(DeveloperPreferencesRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DeveloperPreferencesView get(AuthenticatedUser principal) {
        return repository.findByUserId(principal.userId())
                .map(DeveloperPreferencesService::toView)
                .orElseGet(() -> toView(DeveloperPreferences.defaults(principal.userId(), clock.instant())));
    }

    @Transactional
    public DeveloperPreferencesView update(
            AuthenticatedUser principal, UpdateDeveloperPreferencesRequest request) {
        DeveloperPreferences preferences = repository.findByUserId(principal.userId())
                .orElseGet(() -> DeveloperPreferences.defaults(principal.userId(), clock.instant()));
        preferences.update(request.requestTimeoutMs(), request.retryCount(), null, clock.instant());
        return toView(repository.saveAndFlush(preferences));
    }

    @Transactional
    public DeveloperPreferencesView updateAppearance(
            AuthenticatedUser principal, UpdateAppearanceRequest request) {
        DeveloperPreferences preferences = repository.findByUserId(principal.userId())
                .orElseGet(() -> DeveloperPreferences.defaults(principal.userId(), clock.instant()));
        if (preferences.getVersion() != request.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "These preferences changed elsewhere. Refresh before saving.");
        }
        preferences.updateTheme(request.theme(), clock.instant());
        return toView(repository.saveAndFlush(preferences));
    }

    private static DeveloperPreferencesView toView(DeveloperPreferences preferences) {
        return new DeveloperPreferencesView(preferences.getRequestTimeoutMs(), preferences.getRetryCount(),
                preferences.getTheme(), preferences.getVersion());
    }
}
