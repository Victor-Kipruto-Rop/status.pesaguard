package com.pesaguard.backend.security.passkeys;

import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;

@Configuration
public class PasskeyConfiguration {

    @Bean
    RelyingParty passkeyRelyingParty(PasskeyProperties properties, CredentialRepository repository) {
        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(properties.rpId())
                        .name(properties.rpName())
                        .build())
                .credentialRepository(repository)
                .origins(Set.copyOf(properties.origins()))
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .validateSignatureCounter(true)
                .build();
    }
}
