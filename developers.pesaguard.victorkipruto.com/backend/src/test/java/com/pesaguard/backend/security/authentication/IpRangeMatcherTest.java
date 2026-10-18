package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.BusinessException;

class IpRangeMatcherTest {

    private final IpRangeMatcher matcher = new IpRangeMatcher();

    @Test
    void emptyAllowlistAllowsEveryNetwork() {
        assertThat(matcher.isAllowed("203.0.113.10", Set.of())).isTrue();
        assertThat(matcher.isAllowed(null, Set.of())).isTrue();
    }

    @Test
    void ipv4CidrIsMatchedExactlyAndByPrefix() {
        Set<String> allowlist = Set.of("203.0.113.0/24");

        assertThat(matcher.isAllowed("203.0.113.7", allowlist)).isTrue();
        assertThat(matcher.isAllowed("203.0.114.7", allowlist)).isFalse();
        assertThat(matcher.isAllowed("10.0.0.1", allowlist)).isFalse();
    }

    @Test
    void partialOctetPrefixesAreMatchedBitwise() {
        Set<String> allowlist = Set.of("192.168.8.0/21");

        assertThat(matcher.isAllowed("192.168.15.254", allowlist)).isTrue();
        assertThat(matcher.isAllowed("192.168.16.1", allowlist)).isFalse();
    }

    @Test
    void ipv6RangesAreSupported() {
        Set<String> allowlist = Set.of("2001:db8::/32");

        assertThat(matcher.isAllowed("2001:db8:1234::1", allowlist)).isTrue();
        assertThat(matcher.isAllowed("2001:db9::1", allowlist)).isFalse();
    }

    @Test
    void ipv4AndIpv6RangesDoNotCrossMatch() {
        Set<String> allowlist = Set.of("203.0.113.7");

        assertThat(matcher.isAllowed("2001:db8::1", allowlist)).isFalse();
    }

    @Test
    void missingRemoteAddressIsRejectedWhenRestricted() {
        assertThat(matcher.isAllowed(" ", Set.of("203.0.113.0/24"))).isFalse();
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> matcher.validate(Set.of("not-an-ip")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IP restrictions");
        assertThatThrownBy(() -> matcher.validate(Set.of("203.0.113.0/33")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> matcher.validate(Set.of("203.0.113.0/24/8")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> matcher.validate(Set.of(" ")))
                .isInstanceOf(BusinessException.class);
    }
}