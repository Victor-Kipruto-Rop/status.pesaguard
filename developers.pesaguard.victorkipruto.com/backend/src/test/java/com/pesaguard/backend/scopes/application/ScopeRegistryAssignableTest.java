package com.pesaguard.backend.scopes.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.scopes.domain.ApiScope;
import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeAssignmentRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeDefinitionRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeRestrictionRepository;

class ScopeRegistryAssignableTest {

    @Test
    void rateLimitScopeUsesTheSpecifiedUnderscoreName() {
        assertTrue(ApiScope.tryParse("rate_limits:read").isPresent());
    }

    @Test
    void unimplementedScopeCannotBeGrantedToApiKey() {
        ApiScopeDefinitionRepository definitions = mock(ApiScopeDefinitionRepository.class);
        ApiScopeDefinition paymentsRead = mock(ApiScopeDefinition.class);
        when(definitions.findByName("payments:read")).thenReturn(Optional.of(paymentsRead));
        when(paymentsRead.getName()).thenReturn("payments:read");
        when(paymentsRead.isApiKeyAssignable()).thenReturn(false);

        ScopeRegistryService service = new ScopeRegistryService(definitions,
                mock(ApiScopeRestrictionRepository.class),
                mock(ApiScopeAssignmentRepository.class),
                mock(AuthorizationService.class),
                mock(AuditService.class),
                Clock.systemUTC(),
                mock(com.pesaguard.backend.credentials.api.ApiKeyRepository.class));

        assertThrows(BusinessException.class, () -> service.validate(Set.of("payments:read")));
    }
}
