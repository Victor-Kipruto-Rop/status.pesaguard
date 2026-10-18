package com.pesaguard.backend.integration.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.integration.application.IntegrationProviderRegistry;
import com.pesaguard.backend.integration.domain.IntegrationProviderDefinition;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;

@RestController
@RequestMapping("/api/v1/integrations/providers")
@PreAuthorize("isAuthenticated()")
public class IntegrationProviderController {

    private final IntegrationProviderRegistry providerRegistry;

    public IntegrationProviderController(IntegrationProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @GetMapping
    ApiResponse<List<IntegrationProviderDefinition>> listProviders() {
        return ApiResponse.of(providerRegistry.providers());
    }

    @GetMapping("/{providerId}")
    ApiResponse<IntegrationProviderDefinition> getProvider(@PathVariable String providerId) {
        IntegrationProviderDefinition provider = providerRegistry.find(providerId);
        if (provider == null) {
            throw new ResourceNotFoundException("Integration provider");
        }
        return ApiResponse.of(provider);
    }
}
