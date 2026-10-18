package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.member.application.PasswordRecoveryService;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class MfaControllerTest {

    private final MfaService mfaService = mock(MfaService.class);
    private final PasswordRecoveryService passwordRecoveryService = mock(PasswordRecoveryService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final OrganizationSecuritySettingsService organizationSecuritySettingsService =
            mock(OrganizationSecuritySettingsService.class);
    private final LoginService loginService = mock(LoginService.class);
    private final MfaController controller =
            new MfaController(mfaService, passwordRecoveryService, auditService,
                    organizationSecuritySettingsService, loginService);

    @Test
    void disablingAnEnabledFactorRequiresFreshPasswordAndMfaProof() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId);
        when(mfaService.isEnabled(userId)).thenReturn(true);
        when(mfaService.verify(userId, "123456")).thenReturn(true);

        controller.disable(principal, new MfaDisableRequest("current-password", "123456"));

        verify(passwordRecoveryService).requireCurrentPassword(userId, "current-password");
        verify(mfaService).verify(userId, "123456");
        verify(mfaService).disable(userId);
    }

    @Test
    void invalidMfaProofDoesNotDisableTheFactor() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId);
        when(mfaService.isEnabled(userId)).thenReturn(true);
        when(mfaService.verify(userId, "123456")).thenReturn(false);

        assertThatThrownBy(() -> controller.disable(
                principal, new MfaDisableRequest("current-password", "123456")))
                .isInstanceOf(com.pesaguard.backend.common.exception.BusinessException.class)
                .hasMessageContaining("verification code");

        verify(passwordRecoveryService).requireCurrentPassword(userId, "current-password");
        verify(mfaService, never()).disable(userId);
    }

    @Test
    void regeneratingRecoveryCodesReturnsThemWithoutCaching() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId);
        when(mfaService.regenerateBackupCodes(userId, "123456")).thenReturn(List.of("new-recovery-code"));

        var response = controller.regenerateRecoveryCodes(principal, new MfaCodeRequest("123456"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(mfaService).regenerateBackupCodes(userId, "123456");
    }

    @Test
    void restrictedEnrollmentConfirmationReturnsRecoveryCodesWithoutIssuingASession() {
        AuthenticatedUser principal = enrollmentPrincipal(UUID.randomUUID());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(mfaService.confirm(principal.userId(), "123456")).thenReturn(List.of("recovery-code"));

        var response = controller.confirm(principal, new MfaCodeRequest("123456"), request);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody().data().backupCodes()).containsExactly("recovery-code");
        assertThat(response.getBody().data().session()).isNull();
        verify(loginService).revokeCompletedMfaEnrollmentChallenge(principal);
    }

    @Test
    void invalidEnrollmentTotpIsRecordedAgainstTheAccountAndAddress() {
        AuthenticatedUser principal = enrollmentPrincipal(UUID.randomUUID());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        when(mfaService.confirm(principal.userId(), "000000"))
                .thenThrow(new com.pesaguard.backend.common.exception.BusinessException(
                        HttpStatus.UNAUTHORIZED, "MFA_INVALID", "Invalid code."));

        assertThatThrownBy(() -> controller.confirm(
                principal, new MfaCodeRequest("000000"), request))
                .isInstanceOf(com.pesaguard.backend.common.exception.BusinessException.class);

        verify(loginService).recordMfaEnrollmentFailure(principal, "192.0.2.10");
    }

    @Test
    void mfaStatusIncludesOrganizationRequirementForNonAdminAccountScreens() {
        AuthenticatedUser principal = principal(UUID.randomUUID());
        OrganizationSecuritySettings settings = mock(OrganizationSecuritySettings.class);
        when(organizationSecuritySettingsService.getOrCreate(principal.organizationId())).thenReturn(settings);
        when(organizationSecuritySettingsService.requiresMfa(settings, OrganizationRole.DEVELOPER)).thenReturn(true);
        when(mfaService.isEnabled(principal.userId())).thenReturn(false);

        var response = controller.status(principal);

        assertThat(response.data()).containsEntry("enabled", false)
                .containsEntry("organizationRequired", true);
    }

    private AuthenticatedUser principal(UUID userId) {
        return new AuthenticatedUser(userId, UUID.randomUUID(), UUID.randomUUID(),
                "developer@example.com", "Developer", Set.of());
    }

    private AuthenticatedUser enrollmentPrincipal(UUID userId) {
        return new AuthenticatedUser(userId, UUID.randomUUID(), UUID.randomUUID(),
                "", "", Set.of("ROLE_DEVELOPER"),
                com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                false, Set.of(), true);
    }
}
