package com.pesaguard.backend.explorer.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import org.apache.hc.client5.http.DnsResolver;

/** Resolves only the addresses already checked by {@link OutboundTargetGuard}. */
public final class PinnedTargetDnsResolver implements DnsResolver {

    private final String host;
    private final List<InetAddress> addresses;

    public PinnedTargetDnsResolver(OutboundTargetGuard.ValidatedTarget target) {
        this.host = target.host().toLowerCase(Locale.ROOT);
        this.addresses = List.copyOf(target.addresses());
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        verifyExpectedHost(requestedHost);
        return addresses.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        verifyExpectedHost(requestedHost);
        return host;
    }

    private void verifyExpectedHost(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
            throw new UnknownHostException("Unexpected host requested by webhook client.");
        }
    }
}
