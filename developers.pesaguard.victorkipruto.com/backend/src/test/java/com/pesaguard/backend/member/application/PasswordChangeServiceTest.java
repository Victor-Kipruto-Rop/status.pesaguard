package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.authentication.PasswordChangeChallengeView;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

class PasswordChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID SESSION_ID = UUID.randomUUID();
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = mock(PasswordPolicy.class);
    private final SessionService sessions = mock(SessionService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AccountEmailDeliveryService emailDelivery = mock(AccountEmailDeliveryService.class);
    private final UserAccount account =
            UserAccount.create("person@example.com", "person", "Person", "encoded-old");
    private final byte[] secret = "test-credential-hmac-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(accounts, passwordEncoder, passwordPolicy, sessions,
                refreshTokens, emailDelivery,
                new SecretKeySpec(secret, "HmacSHA256"), Clock.fixed(NOW, ZoneOffset.UTC));
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(accounts.findByPasswordChangeChallengeIdForUpdate(any())).thenAnswer(invocation -> {
            UUID challengeId = invocation.getArgument(0);
            return challengeId.equals(account.getPasswordChangeChallengeId())
                    ? Optional.of(account)
                    : Optional.empty();
        });
        when(passwordEncoder.matches("current", "encoded-old")).thenReturn(true);
        when(passwordEncoder.matches("new-password", "encoded-old")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
    }

    @Test
    void changesPasswordOnlyAfterSessionBoundEmailCodeVerification() {
        PasswordChangeChallengeView challenge = service.request(
                account.getId(), SESSION_ID, "current", "new-password");
        ArgumentCaptor<String> deliveredCode = ArgumentCaptor.forClass(String.class);
        verify(emailDelivery).sendPasswordChangeCode(eq(account.getEmail()), deliveredCode.capture());

        assertThat(challenge.maskedEmail()).isEqualTo("p***@example.com");
        assertThat(challenge.expiresAt()).isEqualTo(NOW.plusSeconds(600));

        String code = deliveredCode.getValue();
        assertThat(code).matches("\\d{6}");
        service.verify(account.getId(), SESSION_ID, challenge.challengeId(), code,
                "current", "new-password");

        assertThat(account.getPasswordHash()).isEqualTo("encoded-new");
        assertThat(account.getPasswordChangeChallengeId()).isNull();
        verify(sessions).revokeActiveByUserIdExcept(account.getId(), SESSION_ID);
        verify(refreshTokens).revokeAllForUser(account.getId(), "PASSWORD_CHANGED");
        verify(emailDelivery).sendPasswordChanged(account.getEmail());
    }
}
