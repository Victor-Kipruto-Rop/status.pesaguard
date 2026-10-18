package com.pesaguard.backend.explorer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

class PinnedTargetDnsResolverTest {

    @Test
    void returnsOnlyAddressesFromTheValidatedTargetWithoutResolvingAgain() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByAddress(new byte[] {1, 1, 1, 1});
        OutboundTargetGuard.ValidatedTarget target = new OutboundTargetGuard.ValidatedTarget(
                URI.create("https://webhook.example.test/events"),
                "webhook.example.test",
                List.of(pinnedAddress));
        PinnedTargetDnsResolver resolver = new PinnedTargetDnsResolver(target);

        assertThat(resolver.resolve("WEBHOOK.EXAMPLE.TEST")).containsExactly(pinnedAddress);
        assertThat(resolver.resolveCanonicalHostname("webhook.example.test"))
                .isEqualTo("webhook.example.test");
    }

    @Test
    void refusesAnyHostOtherThanTheOneThatWasValidated() throws Exception {
        OutboundTargetGuard.ValidatedTarget target = new OutboundTargetGuard.ValidatedTarget(
                URI.create("https://webhook.example.test/events"),
                "webhook.example.test",
                List.of(InetAddress.getByAddress(new byte[] {1, 1, 1, 1})));
        PinnedTargetDnsResolver resolver = new PinnedTargetDnsResolver(target);

        assertThatThrownBy(() -> resolver.resolve("attacker.example.test"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("attacker.example.test"))
                .isInstanceOf(UnknownHostException.class);
    }
}
