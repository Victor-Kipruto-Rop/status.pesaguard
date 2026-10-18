package com.pesaguard.backend.explorer.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the API Explorer may send a request to a given target.
 *
 * <p>This is the SSRF boundary. The primary defence is architectural: the
 * explorer only ever targets hosts that appear in the API catalog, and a caller
 * cannot supply a host. This class is the second layer, because "the catalog is
 * trusted" is a weaker guarantee than it sounds — a catalog row can be edited,
 * and a hostname can start resolving somewhere it did not before.
 *
 * <p>Every check below exists because omitting it has been a real vulnerability
 * somewhere:
 *
 * <ul>
 *   <li><b>Scheme</b> — https only. {@code file}, {@code gopher} and friends turn
 *       a "send a GET" feature into a local-file reader or worse.</li>
 *   <li><b>No userinfo</b> — {@code https://evil.com@127.0.0.1/} parses to host
 *       {@code 127.0.0.1}. Anything that reads the string without using the
 *       parsed host is bypassed by this.</li>
 *   <li><b>Resolved addresses, not the hostname string</b> — "localhost",
 *       {@code 127.0.0.1}, {@code 2130706433} and {@code 0x7f.0.0.1} all name the
 *       loopback interface. Blocking the literal string does nothing.</li>
 *   <li><b>Every resolved address</b> — a name resolving to both a public and a
 *       private address must be refused. Checking only the first would let the
 *       attacker choose which one the client used.</li>
 *   <li><b>IPv4-mapped IPv6</b> — {@code ::ffff:127.0.0.1} is loopback wearing an
 *       IPv6 costume; it is unwrapped before classification.</li>
 *   <li><b>Cloud metadata</b> — blocked by name as well as by address so the
 *       refusal is legible in a log.</li>
 *   <li><b>Non-global address space</b> — private, CGNAT, multicast and reserved
 *       ranges are refused, not just RFC1918.</li>
 * </ul>
 *
 * <p>Callers that make outbound requests must connect only to the addresses in
 * the returned {@link ValidatedTarget}; resolving the hostname again after this
 * check creates a DNS-rebinding vulnerability.
 */
public final class OutboundTargetGuard {

    /**
     * Host names that resolve to cloud instance metadata. Blocked by name as well
     * as by address so the refusal is recognisable when reading a log.
     */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "metadata");

    private final Set<String> allowedHosts;
    private final boolean allowInsecureScheme;

    public OutboundTargetGuard(Set<String> allowedHosts, boolean allowInsecureScheme) {
        this.allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        this.allowInsecureScheme = allowInsecureScheme;
    }

    /**
     * Allowlist membership, case-insensitive.
     *
     * <p>Separated from {@link #validate} so it can be tested without DNS: a test
     * that needs a real public host also needs a resolver, and in an offline
     * environment it would fail for reasons unrelated to what it is checking.
     */
    boolean isHostAllowed(String normalizedHost) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        // Lowercased here as well as in validate: a case-sensitive check is a
        // bypass, and this method should not depend on its caller remembering.
        return allowedHosts.contains(normalizedHost.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Validates a target and returns the addresses it resolved to.
     *
     * @throws UnsafeTargetException when the target must not be contacted
     */
    public ValidatedTarget validate(String rawUrl) {
        URI uri = parse(rawUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowInsecureScheme && "http".equals(scheme))) {
            throw unsafe("Only https targets may be contacted.");
        }
        if (uri.getUserInfo() != null) {
            // https://expected-host@attacker-host/ parses with host=attacker-host.
            // Refusing outright removes the parsing ambiguity entirely.
            throw unsafe("Targets must not embed credentials.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw unsafe("Target has no resolvable host.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(normalizedHost)) {
            throw unsafe("Cloud metadata endpoints are not contactable.");
        }
        if (!isHostAllowed(normalizedHost)) {
            throw unsafe("Host is not in the explorer allowlist.");
        }
        List<InetAddress> addresses = resolveAll(normalizedHost);
        if (addresses.isEmpty()) {
            throw unsafe("Host did not resolve to any address.");
        }
        // Every address must be safe. Checking only the first would let a name
        // resolving to both a public and a private address choose which is used.
        for (InetAddress address : addresses) {
            classify(address);
        }
        return new ValidatedTarget(uri, normalizedHost, addresses);
    }

    /** Exposed so tests and callers can classify an address directly. */
    public static void classify(InetAddress address) {
        InetAddress effective = unwrapIpv4Mapped(address);
        if (effective.isLoopbackAddress() || effective.isAnyLocalAddress()) {
            throw unsafe("Loopback and unspecified addresses are not contactable.");
        }
        if (effective.isLinkLocalAddress()) {
            throw unsafe("Link-local addresses are not contactable.");
        }
        if (effective.isMulticastAddress()) {
            throw unsafe("Multicast addresses are not contactable.");
        }
        byte[] bytes = effective.getAddress();
        if (effective instanceof Inet4Address) {
            classifyIpv4(bytes);
            return;
        }
        if (effective instanceof Inet6Address) {
            classifyIpv6(bytes);
        }
    }

    private static void classifyIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 10) {
            throw unsafe("Private network addresses are not contactable.");
        }
        if (first == 172 && second >= 16 && second <= 31) {
            throw unsafe("Private network addresses are not contactable.");
        }
        if (first == 192 && second == 168) {
            throw unsafe("Private network addresses are not contactable.");
        }
        if (first == 100 && second >= 64 && second <= 127) {
            // Carrier-grade NAT: not RFC1918, but not public either. It is shared
            // infrastructure inside someone's network.
            throw unsafe("Carrier-grade NAT addresses are not contactable.");
        }
        if (first == 169 && second == 254) {
            throw unsafe("Link-local addresses are not contactable.");
        }
        if (first == 0 || first >= 224) {
            throw unsafe("Reserved and multicast addresses are not contactable.");
        }
    }

    private static void classifyIpv6(byte[] bytes) {
        int first = bytes[0] & 0xff;
        if ((first & 0xfe) == 0xfc) {
            throw unsafe("IPv6 unique-local addresses are not contactable.");
        }
        if (first == 0xfe && (bytes[1] & 0xc0) == 0x80) {
            throw unsafe("Link-local addresses are not contactable.");
        }
        if (first == 0xff) {
            throw unsafe("Multicast addresses are not contactable.");
        }
        if (isAllZero(bytes)) {
            throw unsafe("Unspecified addresses are not contactable.");
        }
    }

    /** {@code ::ffff:127.0.0.1} is loopback wearing an IPv6 costume. */
    static InetAddress unwrapIpv4Mapped(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return address;
            }
        }
        if ((bytes[10] & 0xff) != 0xff || (bytes[11] & 0xff) != 0xff) {
            return address;
        }
        byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException exception) {
            return address;
        }
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<InetAddress> resolveAll(String host) {
        try {
            // getAllByName, not getByName: a name may resolve to several addresses
            // and every one of them must be inspected.
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException exception) {
            throw unsafe("Host could not be resolved.");
        }
    }

    private static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw unsafe("Target URL is required.");
        }
        if (rawUrl.length() > 2048) {
            throw unsafe("Target URL is too long.");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            if (!uri.isAbsolute()) {
                throw unsafe("Target URL must be absolute.");
            }
            if (uri.getFragment() != null) {
                throw unsafe("Target URL must not contain a fragment.");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw unsafe("Target URL is malformed.");
        }
    }

    private static UnsafeTargetException unsafe(String message) {
        return new UnsafeTargetException(message);
    }

    /** A target that passed every check, together with what it resolved to. */
    public record ValidatedTarget(URI uri, String host, List<InetAddress> addresses) {
    }
}
