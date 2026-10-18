package com.pesaguard.backend.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.pesaguard.backend.member.application.UserLifecycleService;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class AccountControllerTest {

    @Test
    void exportIsSelfScopedAndSentAsNoStoreAttachment() {
        UserLifecycleService service = mock(UserLifecycleService.class);
        AccountController controller = new AccountController(service);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, organizationId, UUID.randomUUID(), "person@example.com", "Person", Set.of("ROLE_OWNER"));
        AccountStepUpRequest request = new AccountStepUpRequest("current-password", "123456");
        AccountDataExport export = new AccountDataExport(
                Instant.parse("2026-10-05T12:00:00Z"),
                new AccountDataExport.Profile(userId, "person@example.com", "person", "Person",
                        UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), null),
                List.of(), List.of());
        when(service.exportAccount(userId, organizationId, "current-password", "123456")).thenReturn(export);

        var response = controller.export(principal, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"pesaguard-account-export.json\"");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isSameAs(export);
        verify(service).exportAccount(userId, organizationId, "current-password", "123456");
    }
}
