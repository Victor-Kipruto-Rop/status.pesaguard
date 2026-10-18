package com.pesaguard.backend.security.passkeys;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.security.authentication.AuthenticationResponse;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.AssertionCompletion;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.AssertionStart;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.CredentialView;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.MfaAssertionStart;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.Options;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.RegistrationCompletion;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.Removal;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.securitycenter.domain.SessionDevice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth/passkeys")
public class PasskeyController {

    private final PasskeyService passkeyService;

    public PasskeyController(PasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @PostMapping("/registration/options")
    ResponseEntity<ApiResponse<Options>> registrationOptions(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return noStore(ApiResponse.of(passkeyService.registrationOptions(principal)));
    }

    @PostMapping("/registration/verify")
    ResponseEntity<ApiResponse<CredentialView>> finishRegistration(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RegistrationCompletion request) {
        return noStore(ApiResponse.of(passkeyService.finishRegistration(principal, request)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<CredentialView>>> list(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return noStore(ApiResponse.of(passkeyService.list(principal)));
    }

    @DeleteMapping("/{credentialId}")
    ResponseEntity<Void> remove(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID credentialId,
            @Valid @RequestBody Removal request) {
        passkeyService.remove(principal, credentialId, request.currentPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login/options")
    ResponseEntity<ApiResponse<Options>> passwordlessOptions(
            @RequestBody(required = false) AssertionStart request, HttpServletRequest servletRequest) {
        throw passkeyLoginDisabled();
    }

    @PostMapping("/login/verify")
    ResponseEntity<ApiResponse<AuthenticationResponse>> finishPasswordless(
            @Valid @RequestBody AssertionCompletion request,
            HttpServletRequest servletRequest) {
        throw passkeyLoginDisabled();
    }

    @PostMapping("/mfa/options")
    ResponseEntity<ApiResponse<Options>> passwordMfaOptions(
            @Valid @RequestBody MfaAssertionStart request,
            HttpServletRequest servletRequest) {
        throw passkeyLoginDisabled();
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<ApiResponse<AuthenticationResponse>> finishPasswordMfa(
            @Valid @RequestBody AssertionCompletion request,
            HttpServletRequest servletRequest) {
        throw passkeyLoginDisabled();
    }

    private BusinessException passkeyLoginDisabled() {
        return new BusinessException(HttpStatus.GONE, "PASSKEY_LOGIN_DISABLED",
                "Passkeys can only be used to recover account access.");
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
