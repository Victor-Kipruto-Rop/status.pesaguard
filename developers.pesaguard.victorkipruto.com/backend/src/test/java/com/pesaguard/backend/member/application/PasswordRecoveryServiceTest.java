package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pesaguard.backend.member.domain.PasswordResetToken;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.PasswordResetTokenRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;

class PasswordRecoveryServiceTest {

    @Test
    void treatsUnknownEmailAsACompletedRequestToPreventAccountEnumeration() {
        UserAccountRepository accountRepository = mock(UserAccountRepository.class);
        when(accountRepository.findByEmailForUpdate("unknown@example.com")).thenReturn(Optional.empty());
        PasswordRecoveryService service = new PasswordRecoveryService(
                accountRepository,
                mock(PasswordResetTokenRepository.class),
                mock(PasswordEncoder.class),
                mock(PasswordPolicy.class),
                mock(SessionService.class),
                mock(RefreshTokenService.class),
                mock(CredentialCryptoService.class),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                Clock.fixed(Instant.parse("2026-10-08T18:00:00Z"), ZoneOffset.UTC));

        assertThat(service.request("unknown@example.com")).isNull();
    }

    @Test
    void flushesRevokedResetBeforeIssuingReplacement() {
        Instant now = Instant.parse("2026-10-08T18:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UserAccount account = UserAccount.create("developer@example.com", "developer", "Developer", "password-hash");
        PasswordResetToken existing = PasswordResetToken.issue(
                account.getId(), "existing-hash", now.minusSeconds(60), now.plusSeconds(3600), null);

        UserAccountRepository accountRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        CredentialCryptoService credentialCryptoService = mock(CredentialCryptoService.class);
        when(accountRepository.findByEmailForUpdate("developer@example.com")).thenReturn(Optional.of(account));
        when(tokenRepository.findOutstandingByUserId(account.getId())).thenReturn(List.of(existing));
        when(credentialCryptoService.randomToken(32)).thenReturn("replacement-token");

        PasswordRecoveryService service = new PasswordRecoveryService(
                accountRepository,
                tokenRepository,
                mock(PasswordEncoder.class),
                mock(PasswordPolicy.class),
                mock(SessionService.class),
                mock(RefreshTokenService.class),
                credentialCryptoService,
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                clock);

        assertThat(service.request(" DEVELOPER@example.com ")).isEqualTo("replacement-token");
        assertThat(existing.getRevokedAt()).isEqualTo(now);

        InOrder order = inOrder(tokenRepository);
        order.verify(tokenRepository).save(existing);
        order.verify(tokenRepository).flush();
        order.verify(tokenRepository).save(argThat(token -> !token.getId().equals(existing.getId())));
    }
}
