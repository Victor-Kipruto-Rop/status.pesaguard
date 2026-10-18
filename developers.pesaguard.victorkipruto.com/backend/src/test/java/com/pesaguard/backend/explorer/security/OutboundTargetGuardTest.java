package com.pesaguard.backend.explorer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF tests for the explorer's outbound guard.
 *
 * <p>Every case here is a way an attacker reaches something they should not.
 * They are written as concrete attack strings rather than abstract properties,
 * because the point is that someone, someday, tries exactly these.
 */
class OutboundTargetGuardTest {

    /** No allowlist, so these tests exercise the address rules themselves. */
    private final OutboundTargetGuard guard = new OutboundTargetGuard(Set.of(), false);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/",
            "https://localhost:8080/admin",
            "https://127.0.0.1/",
            "https://127.0.0.1:5432/",
            "https://127.1/",
            "https://[::1]/",
            "https://[0:0:0:0:0:0:0:1]/",
    })
    void loopbackTargetsAreRefused(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://10.0.0.5/",
            "https://10.255.255.254/",
            "https://172.16.0.1/",
            "https://172.31.255.254/",
            "https://192.168.1.1/",
            "https://192.168.0.1:3000/",
    })
    void privateNetworkTargetsAreRefused(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void the172PrivateRangeBoundariesAreExact() {
        // 172.15.x.x and 172.32.x.x are public. Blocking the whole /16 would be
        // wrong; blocking only 172.16-31 wrongly would leave a gap if written
        // sloppily. This asserts behaviour, not arithmetic.
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("172.16.0.1")))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("172.31.255.255")))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("172.15.255.255")))
                .doesNotThrowAnyException();
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("172.32.0.1")))
                .doesNotThrowAnyException();
    }

    @Test
    void cloudMetadataTargetsAreRefused() {
        assertThatThrownBy(() -> guard.validate("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/"))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> guard.validate("https://[fd00:ec2::254]/latest/meta-data/"))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> guard.validate("https://metadata.google.internal/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void carrierGradeNatIsRefused() {
        // Not RFC1918, but not public either: shared infrastructure inside
        // someone's network.
        assertThatThrownBy(() -> guard.validate("https://100.64.0.1/"))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> guard.validate("https://100.127.255.255/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://0.0.0.0/",
            "https://224.0.0.1/",
            "https://255.255.255.255/",
            "https://[ff02::1]/",
            "https://[fd12:3456:789a::1]/",
            "https://[fe80::1]/",
    })
    void reservedAndNonUnicastTargetsAreRefused(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void ipv4MappedIpv6LoopbackIsUnwrappedAndRefused() {
        // ::ffff:127.0.0.1 is loopback wearing an IPv6 costume. Classifying the
        // raw v6 address would pass it as a global address.
        assertThatThrownBy(() -> OutboundTargetGuard.classify(InetAddress.getByName("::ffff:127.0.0.1")))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> OutboundTargetGuard.classify(InetAddress.getByName("::ffff:10.0.0.1")))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> OutboundTargetGuard.classify(InetAddress.getByName("::ffff:169.254.169.254")))
                .isInstanceOf(UnsafeTargetException.class);
    }
@ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://127.0.0.1:11211/_stats",
            "ftp://example.com/",
            "jar:file:///x.jar!/",
            "dict://example.com:11211/",
    })
    void nonHttpSchemesAreRefused(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void httpIsRefusedUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> new OutboundTargetGuard(Set.of(), false).validate("http://example.com/"))
                .isInstanceOf(UnsafeTargetException.class);

        // Even when http is permitted, the address rules are independent.
        OutboundTargetGuard permissive = new OutboundTargetGuard(Set.of(), true);
        assertThatThrownBy(() -> permissive.validate("http://127.0.0.1/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void embeddedCredentialsAreRefused() {
        // https://expected.example.com@127.0.0.1/ has host 127.0.0.1. Any check
        // that reads the string instead of the parsed host is bypassed by this.
        assertThatThrownBy(() -> guard.validate("https://expected.example.com@127.0.0.1/"))
                .isInstanceOf(UnsafeTargetException.class);
        assertThatThrownBy(() -> guard.validate("https://user:pass@127.0.0.1/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative/only", "not-a-url", "", "   "})
    void malformedOrRelativeTargetsAreRefused(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void anOverlongTargetIsRefused() {
        assertThatThrownBy(() -> guard.validate("https://example.com/" + "a".repeat(3000)))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void fragmentsAreRefused() {
        // A fragment is never sent to the server, so accepting one suggests the
        // caller believes it reaches the server.
        assertThatThrownBy(() -> guard.validate("https://example.com/#@127.0.0.1"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void hostsOutsideTheAllowlistAreRefused() {
        OutboundTargetGuard restricted = new OutboundTargetGuard(Set.of("api.pesaguard.com"), false);

        assertThatThrownBy(() -> restricted.validate("https://evil.example.com/"))
                .isInstanceOf(UnsafeTargetException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void allowlistMatchingIsCaseInsensitive() {
        // A case-sensitive check would let "API.PESAGUARD.COM" bypass it.
        // Tested directly on the membership check so it does not require DNS.
        OutboundTargetGuard restricted = new OutboundTargetGuard(Set.of("api.pesaguard.com"), false);

        assertThat(restricted.isHostAllowed("api.pesaguard.com")).isTrue();
        assertThat(restricted.isHostAllowed("API.PESAGUARD.COM")).isTrue();
        assertThat(restricted.isHostAllowed("evil.example.com")).isFalse();
    }

    @Test
    void anEmptyAllowlistAllowsAnyHost() {
        assertThat(new OutboundTargetGuard(Set.of(), false).isHostAllowed("anything.example.com"))
                .isTrue();
    }

    @Test
    void aHostWithNoAddressesIsRefused() {
        assertThatThrownBy(() -> guard.validate("https://this-name-does-not-exist.invalid/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void loopbackIsRefusedThroughAnyNameThatResolvesToIt() {
        // The guard resolves and classifies rather than matching strings, so any
        // spelling of loopback is caught.
        assertThatThrownBy(() -> guard.validate("https://127.0.0.1.nip.io/"))
                .isInstanceOf(UnsafeTargetException.class);
    }

    @Test
    void theClassificationPathIsSafeOnItsOwn() {
        for (String address : new String[] {"127.0.0.1", "10.1.2.3", "192.168.0.1",
                "172.20.0.1", "169.254.169.254", "100.64.0.1"}) {
            assertThatThrownBy(() -> OutboundTargetGuard.classify(InetAddress.getByName(address)))
                    .as("address %s must be refused", address)
                    .isInstanceOf(UnsafeTargetException.class);
        }
    }

    @Test
    void globallyRoutableAddressesAreAccepted() {
        // A guard that blocks everything is not a guard, it is an outage.
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("93.184.216.34")))
                .doesNotThrowAnyException();
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("2001:4860:4860::8888")))
                .doesNotThrowAnyException();
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("172.32.0.1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> OutboundTargetGuard.classify(InetAddress.getByName("100.63.255.255")))
                .doesNotThrowAnyException();
    }

    @Test
    void ipv4MappedUnwrappingLeavesRealIpv6Alone() throws Exception {
        assertThat(OutboundTargetGuard.unwrapIpv4Mapped(InetAddress.getByName("2001:db8::1")))
                .isEqualTo(InetAddress.getByName("2001:db8::1"));
    }
}
