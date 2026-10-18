package com.pesaguard.backend.security.passkeys;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.OrganizationSelectionException;
import com.pesaguard.backend.member.application.PasswordRecoveryService;
import com.pesaguard.backend.security.authentication.AuthenticationResponse;
import com.pesaguard.backend.security.authentication.LoginService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import tools.jackson.databind.ObjectMapper;

import static com.pesaguard.backend.security.passkeys.PasskeyApiModels.*;

@Service
public class PasskeyService {

    private static final String REGISTRATION = "REGISTRATION";
    private static final String PASSWORD_MFA = "PASSWORD_MFA";
    private static final String PASSWORDLESS_LOGIN = "PASSWORDLESS_LOGIN";

    private final RelyingParty relyingParty;
    private final PasskeyCredentialRepository credentials;
    private final PasskeyChallengeService challenges;
    private final PasswordRecoveryService passwordRecoveryService;
    private final LoginService loginService;
    private final PasskeyAuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate assertionTransaction;

    public PasskeyService(
            RelyingParty relyingParty,
            PasskeyCredentialRepository credentials,
            PasskeyChallengeService challenges,
            PasswordRecoveryService passwordRecoveryService,
            LoginService loginService,
            PlatformTransactionManager transactionManager,
            PasskeyAuditWriter auditWriter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.relyingParty = relyingParty;
        this.credentials = credentials;
        this.challenges = challenges;
        this.passwordRecoveryService = passwordRecoveryService;
        this.loginService = loginService;
        this.assertionTransaction = new TransactionTemplate(transactionManager);
        this.assertionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Options registrationOptions(AuthenticatedUser principal) {
        String username = principal.userId().toString();
        UserIdentity user = UserIdentity.builder()
                .name(username)
                .displayName(principal.displayName())
                .id(JpaWebAuthnCredentialRepository.handleFor(principal.userId()))
                .build();
        PublicKeyCredentialCreationOptions request = relyingParty.startRegistration(
                StartRegistrationOptions.builder()
                        .user(user)
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                .userVerification(UserVerificationRequirement.REQUIRED)
                                .residentKey(com.yubico.webauthn.data.ResidentKeyRequirement.REQUIRED)
                                .build())
                        .timeout(120_000L)
                        .build());
        try {
            UUID challengeId = challenges.issue(REGISTRATION, principal.userId(),
                    principal.organizationId(), request.toJson());
            return new Options(challengeId, objectMapper.readTree(request.toCredentialsCreateJson()));
        } catch (IOException serializationFailure) {
            throw unavailable();
        }
    }

    @Transactional
    public CredentialView finishRegistration(
            AuthenticatedUser principal, RegistrationCompletion completion) {
        PasskeyChallengeService.ConsumedChallenge challenge = challenges.consume(completion.challengeId());
        try {
            challenges.requireValid(challenge, REGISTRATION, principal.userId(), principal.organizationId());
            PublicKeyCredentialCreationOptions request =
                    PublicKeyCredentialCreationOptions.fromJson(challenge.requestJson());
            PublicKeyCredential<?, ?> parsed = PublicKeyCredential.parseRegistrationResponseJson(
                    completion.credential().toString());
            @SuppressWarnings("unchecked")
            PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAttestationResponse,
                    com.yubico.webauthn.data.ClientRegistrationExtensionOutputs> response =
                    (PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAttestationResponse,
                            com.yubico.webauthn.data.ClientRegistrationExtensionOutputs>) parsed;
            var result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(response)
                    .build());
            requireRegistrationUserVerification(result);
            byte[] credentialId = result.getKeyId().getId().getBytes();
            requireCredentialIdIsNew(credentialId);
            PasskeyCredential credential = PasskeyCredential.create(
                    principal.userId(), credentialId,
                    JpaWebAuthnCredentialRepository.handleFor(principal.userId()).getBytes(),
                    result.getPublicKeyCose().getBytes(), result.getSignatureCount(),
                    result.isBackupEligible(), result.isBackedUp(), completion.displayName().trim(),
                    clock.instant());
            PasskeyCredential saved = credentials.saveAndFlush(credential);
            auditWriter.record(principal.organizationId(), principal.userId(),
                    "auth.passkey.added", "succeeded");
            return CredentialView.from(saved);
        } catch (BusinessException failure) {
            auditWriter.record(principal.organizationId(), principal.userId(),
                    "auth.passkey.registration", "failed");
            throw failure;
        } catch (IOException | RegistrationFailedException | RuntimeException failure) {
            auditWriter.record(principal.organizationId(), principal.userId(),
                    "auth.passkey.registration", "failed");
            throw invalidResponse();
        }
    }

    @Transactional
    public List<CredentialView> list(AuthenticatedUser principal) {
        return credentials.findAllByUserIdOrderByCreatedAtAsc(principal.userId()).stream()
                .map(CredentialView::from)
                .toList();
    }

    @Transactional
    public void remove(AuthenticatedUser principal, UUID credentialId, String currentPassword) {
        try {
            passwordRecoveryService.requireCurrentPassword(principal.userId(), currentPassword);
            PasskeyCredential credential = credentials.findByIdAndUserId(credentialId, principal.userId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                            "PASSKEY_NOT_FOUND", "That passkey was not found."));
            credentials.delete(credential);
            auditWriter.record(principal.organizationId(), principal.userId(), "auth.passkey.removed", "succeeded");
        } catch (RuntimeException failure) {
            auditWriter.record(principal.organizationId(), principal.userId(), "auth.passkey.removal", "failed");
            throw failure;
        }
    }

    @Transactional
    public Options passwordlessOptions(AssertionStart start, String remoteAddress) {
        loginService.assertPasskeyAttemptAllowed(remoteAddress);
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(120_000L)
                .build());
        try {
            UUID challengeId = challenges.issue(PASSWORDLESS_LOGIN, null, start.organizationId(), request.toJson());
            return new Options(challengeId, objectMapper.readTree(request.toCredentialsGetJson()));
        } catch (IOException serializationFailure) {
            throw unavailable();
        }
    }

    @Transactional
    public Options passwordMfaOptions(MfaAssertionStart start, String remoteAddress) {
        LoginService.PasskeyLoginBinding binding =
                loginService.verifyPasswordForPasskey(start.email(), start.password(),
                        start.organizationId(), remoteAddress);
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .username(binding.userId().toString())
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(120_000L)
                .build());
        try {
            UUID challengeId = challenges.issue(
                    PASSWORD_MFA, binding.userId(), binding.organizationId(), request.toJson());
            return new Options(challengeId, objectMapper.readTree(request.toCredentialsGetJson()));
        } catch (IOException serializationFailure) {
            throw unavailable();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthenticationResponse finishPasswordless(
            AssertionCompletion completion, String deviceLabel, String remoteAddress) {
        PasskeyChallengeService.ConsumedChallenge challenge = null;
        VerifiedAssertion verified;
        try {
            challenge = challenges.consume(completion.challengeId());
            challenges.requireValid(challenge, PASSWORDLESS_LOGIN, null, challenge.organizationId());
            PasskeyChallengeService.ConsumedChallenge assertionChallenge = challenge;
            verified = assertionTransaction.execute(
                    status -> verifyAssertion(assertionChallenge, completion, null));
        } catch (RuntimeException failure) {
            return failedPasswordless(challenge, failure, remoteAddress);
        }
        PasskeyCredential credential = verified.credential();
        UUID userId = credential.getUserId();
        AuthenticationResponse result;
        try {
            result = loginService.loginWithVerifiedPasskey(
                    userId, challenge.organizationId(), deviceLabel, remoteAddress);
        } catch (OrganizationSelectionException selectionRequired) {
            throw selectionRequired;
        } catch (RuntimeException failure) {
            loginService.recordPasskeyFailure(userId, remoteAddress);
            auditWriter.recordUnattributedAuthenticationFailure();
            throw failure;
        }
        auditWriter.record(result.organization().id(), userId, "auth.passkey.authentication", "succeeded");
        return result;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthenticationResponse finishPasswordMfa(
            AssertionCompletion completion, String deviceLabel, String remoteAddress) {
        PasskeyChallengeService.ConsumedChallenge challenge = null;
        VerifiedAssertion verified;
        try {
            challenge = challenges.consume(completion.challengeId());
            if (challenge.userId() == null || challenge.organizationId() == null) {
                throw invalidResponse();
            }
            challenges.requireValid(challenge, PASSWORD_MFA, challenge.userId(), challenge.organizationId());
            PasskeyChallengeService.ConsumedChallenge assertionChallenge = challenge;
            verified = assertionTransaction.execute(
                    status -> verifyAssertion(assertionChallenge, completion, assertionChallenge.userId()));
        } catch (RuntimeException failure) {
            loginService.recordPasskeyFailure(challenge == null ? null : challenge.userId(), remoteAddress);
            if (challenge != null && challenge.userId() != null) {
                auditWriter.record(challenge.organizationId(), challenge.userId(),
                        "auth.passkey.authentication", "failed");
            } else {
                auditWriter.recordUnattributedAuthenticationFailure();
            }
            throw failure;
        }
        AuthenticationResponse result;
        try {
            result = loginService.loginWithVerifiedPasskey(
                    challenge.userId(), challenge.organizationId(), deviceLabel, remoteAddress);
        } catch (RuntimeException failure) {
            loginService.recordPasskeyFailure(challenge.userId(), remoteAddress);
            auditWriter.record(challenge.organizationId(), challenge.userId(),
                    "auth.passkey.authentication", "failed");
            throw failure;
        }
        auditWriter.record(challenge.organizationId(), challenge.userId(),
                "auth.passkey.authentication", "succeeded");
        return result;
    }

    private AuthenticationResponse failedPasswordless(
            PasskeyChallengeService.ConsumedChallenge challenge, RuntimeException failure, String remoteAddress) {
        loginService.recordPasskeyFailure(challenge == null ? null : challenge.userId(), remoteAddress);
        if (challenge != null && challenge.userId() != null) {
            auditWriter.record(challenge.organizationId(), challenge.userId(),
                    "auth.passkey.authentication", "failed");
        } else {
            auditWriter.recordUnattributedAuthenticationFailure();
        }
        throw failure;
    }

    private VerifiedAssertion verifyAssertion(
            PasskeyChallengeService.ConsumedChallenge challenge,
            AssertionCompletion completion,
            UUID expectedUserId) {
        try {
            AssertionRequest request = AssertionRequest.fromJson(challenge.requestJson());
            PublicKeyCredential<?, ?> parsed = PublicKeyCredential.parseAssertionResponseJson(
                    completion.credential().toString());
            @SuppressWarnings("unchecked")
            PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse,
                    com.yubico.webauthn.data.ClientAssertionExtensionOutputs> response =
                    (PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse,
                            com.yubico.webauthn.data.ClientAssertionExtensionOutputs>) parsed;
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(response)
                    .build());
            requireAssertionVerification(result);
            UUID authenticatedUserId = UUID.fromString(result.getUsername());
            if (expectedUserId != null && !expectedUserId.equals(authenticatedUserId)) {
                throw invalidResponse();
            }
            PasskeyCredential credential = credentials.findByCredentialId(result.getCredentialId().getBytes())
                    .filter(found -> found.getUserId().equals(authenticatedUserId))
                    .orElseThrow(this::invalidResponse);
            if (!java.security.MessageDigest.isEqual(credential.getUserHandle(), result.getUserHandle().getBytes())) {
                throw invalidResponse();
            }
            credential.recordUse(result.getSignatureCount(), result.isBackupEligible(),
                    result.isBackedUp(), clock.instant());
            return new VerifiedAssertion(result, credential);
        } catch (IOException | AssertionFailedException | IllegalArgumentException failure) {
            throw invalidResponse();
        }
    }

    private BusinessException invalidResponse() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "PASSKEY_INVALID",
                "The passkey response could not be verified. Start a new sign-in attempt.");
    }

    void requireCredentialIdIsNew(byte[] credentialId) {
        if (credentials.findByCredentialId(credentialId).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PASSKEY_ALREADY_REGISTERED",
                    "This passkey is already registered to an account.");
        }
    }

    void requireRegistrationUserVerification(com.yubico.webauthn.RegistrationResult result) {
        if (!result.isUserVerified()) throw invalidResponse();
    }

    void requireAssertionVerification(AssertionResult result) {
        if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()) {
            throw invalidResponse();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "PASSKEY_UNAVAILABLE",
                "Passkey authentication is temporarily unavailable.");
    }

    private record VerifiedAssertion(AssertionResult result, PasskeyCredential credential) {
    }
}
