package com.pesaguard.backend.security.passkeys;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.application.PasswordRecoveryService;
import com.pesaguard.backend.security.authentication.LoginService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.passkeys.PasskeyApiModels.AssertionCompletion;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;

import tools.jackson.databind.ObjectMapper;

class PasskeyServiceSecurityTest {

    private final RelyingParty relyingParty = mock(RelyingParty.class);
    private final PasskeyCredentialRepository credentials = mock(PasskeyCredentialRepository.class);
    private final PasskeyChallengeService challenges = mock(PasskeyChallengeService.class);
    private final PasswordRecoveryService passwordRecovery = mock(PasswordRecoveryService.class);
    private final LoginService loginService = mock(LoginService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final PasskeyAuditWriter auditWriter = mock(PasskeyAuditWriter.class);
    private final PasskeyService service = new PasskeyService(
            relyingParty, credentials, challenges, passwordRecovery, loginService, transactionManager, auditWriter,
            mock(ObjectMapper.class), Clock.systemUTC());

    @BeforeEach
    void configureTransactionManager() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void registrationMustReportUserVerification() {
        RegistrationResult result = mock(RegistrationResult.class);
        when(result.isUserVerified()).thenReturn(false);

        assertThatThrownBy(() -> service.requireRegistrationUserVerification(result))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assertionMustHaveValidSignatureCounterAndUserVerification() {
        AssertionResult result = mock(AssertionResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isUserVerified()).thenReturn(true);
        when(result.isSignatureCounterValid()).thenReturn(false);

        assertThatThrownBy(() -> service.requireAssertionVerification(result))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void yubicoAssertionSignatureFailureNeverIssuesASession() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        AssertionRequest request = AssertionRequest.builder()
                .publicKeyCredentialRequestOptions(PublicKeyCredentialRequestOptions.builder()
                        .challenge(new ByteArray(new byte[] { 1, 2, 3 }))
                        .rpId("localhost")
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .username(userId.toString())
                .build();
        when(challenges.consume(challengeId)).thenReturn(new PasskeyChallengeService.ConsumedChallenge(
                "PASSWORD_MFA", userId, organizationId, request.toJson(),
                Instant.now().plusSeconds(60), Instant.now()));
        when(relyingParty.finishAssertion(any(FinishAssertionOptions.class)))
                .thenThrow(new AssertionFailedException("invalid signature"));
        String authenticatorData = new ByteArray(new byte[37]).getBase64Url();
        String clientDataJson = new ByteArray(
                "{\"type\":\"webauthn.get\",\"challenge\":\"AQ\",\"origin\":\"http://localhost:5173\"}"
                        .getBytes(StandardCharsets.UTF_8)).getBase64Url();
        AssertionCompletion completion = new AssertionCompletion(challengeId,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
                        {"id":"AQ","rawId":"AQ","type":"public-key","clientExtensionResults":{},"response":{
                          "clientDataJSON":"%s","authenticatorData":"%s","signature":"AQ","userHandle":"AQ"
                        }}
                        """.formatted(clientDataJson, authenticatorData)));
        com.yubico.webauthn.data.PublicKeyCredential.parseAssertionResponseJson(
                completion.credential().toString());

        assertThatThrownBy(() -> service.finishPasswordMfa(completion, "browser", "203.0.113.15"))
                .isInstanceOf(BusinessException.class);
        verify(relyingParty).finishAssertion(any(FinishAssertionOptions.class));
        verify(loginService, never()).loginWithVerifiedPasskey(userId, organizationId, "browser", "203.0.113.15");
        verify(loginService).recordPasskeyFailure(userId, "203.0.113.15");
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void passkeyCompletionDoesNotKeepAnOuterTransactionAcrossSessionAndAuditWrites() throws Exception {
        Transactional passwordless = PasskeyService.class
                .getMethod("finishPasswordless", AssertionCompletion.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional passwordMfa = PasskeyService.class
                .getMethod("finishPasswordMfa", AssertionCompletion.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(passwordless.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(passwordMfa.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void passwordlessChallengeFailureIsAuditedWithoutUnverifiedIdentity() {
        UUID challengeId = UUID.randomUUID();
        when(challenges.consume(challengeId)).thenThrow(new BusinessException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "PASSKEY_INVALID", "The passkey response could not be verified."));
        AssertionCompletion completion = new AssertionCompletion(challengeId,
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());

        assertThatThrownBy(() -> service.finishPasswordless(completion, "browser", "203.0.113.15"))
                .isInstanceOf(BusinessException.class);

        verify(auditWriter).recordUnattributedAuthenticationFailure();
        verify(loginService).recordPasskeyFailure(null, "203.0.113.15");
        verify(loginService, never()).loginWithVerifiedPasskey(
                any(UUID.class), any(UUID.class), any(), any());
    }

    @Test
    void duplicateCredentialIdIsRejectedBeforePersistence() {
        byte[] credentialId = "duplicate".getBytes(StandardCharsets.UTF_8);
        when(credentials.findByCredentialId(credentialId))
                .thenReturn(Optional.of(PasskeyCredential.create(
                        UUID.randomUUID(), credentialId, new byte[16], new byte[] { 1 }, 1,
                        false, false, "Existing", Instant.now())));

        assertThatThrownBy(() -> service.requireCredentialIdIsNew(credentialId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("PASSKEY_ALREADY_REGISTERED"));
        verify(credentials, never()).save(any());
    }

    @Test
    void removalCannotDeleteAnotherUsersCredential() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId);
        UUID foreignId = UUID.randomUUID();
        when(credentials.findByIdAndUserId(foreignId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(principal, foreignId, "current-password"))
                .isInstanceOf(BusinessException.class);
        verify(passwordRecovery).requireCurrentPassword(userId, "current-password");
        verify(credentials, never()).delete(any(PasskeyCredential.class));
    }

    @Test
    void removalRequiresPasswordStepUpBeforeLookingUpOrDeletingCredential() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId);
        UUID credentialId = UUID.randomUUID();
        doThrow(new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS", "The current password is incorrect."))
                .when(passwordRecovery).requireCurrentPassword(userId, "wrong-password");

        assertThatThrownBy(() -> service.remove(principal, credentialId, "wrong-password"))
                .isInstanceOf(BusinessException.class);
        verify(credentials, never()).findByIdAndUserId(credentialId, userId);
        verify(credentials, never()).delete(any(PasskeyCredential.class));
    }

    private AuthenticatedUser principal(UUID userId) {
        return new AuthenticatedUser(userId, UUID.randomUUID(), UUID.randomUUID(),
                "person@example.com", "Person", java.util.Set.of("ROLE_OWNER"));
    }
}
