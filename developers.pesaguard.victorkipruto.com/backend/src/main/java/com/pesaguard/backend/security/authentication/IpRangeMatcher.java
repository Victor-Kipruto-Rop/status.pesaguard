package com.pesaguard.backend.security.authentication;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

@Component
public class IpRangeMatcher {

    public boolean isAllowed(String remoteAddress, Set<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        InetAddress remote = parseAddress(remoteAddress);
        return allowlist.stream().anyMatch(range -> matches(range, remote));
    }

    public void validate(Set<String> allowlist) {
        for (String range : allowlist == null ? Set.<String>of() : allowlist) {
            parseRange(range);
        }
    }

    private boolean matches(String range, InetAddress remote) {
        ParsedRange parsed = parseRange(range);
        byte[] remoteBytes = remote.getAddress();
        if (remoteBytes.length != parsed.address().getAddress().length) {
            return false;
        }
        byte[] networkBytes = parsed.address().getAddress();
        int fullBytes = parsed.prefix() / 8;
        int remainingBits = parsed.prefix() % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (remoteBytes[index] != networkBytes[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (remoteBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }

    private ParsedRange parseRange(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String[] parts = value.trim().split("/", -1);
        if (parts.length > 2) {
            throw invalid();
        }
        InetAddress address = parseAddress(parts[0]);
        int prefix = parts.length == 2 ? parsePrefix(parts[1], address.getAddress().length * 8) : address.getAddress().length * 8;
        return new ParsedRange(address, prefix);
    }

    private int parsePrefix(String value, int maximum) {
        try {
            int prefix = Integer.parseInt(value);
            if (prefix < 0 || prefix > maximum) {
                throw invalid();
            }
            return prefix;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private InetAddress parseAddress(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) {
            throw invalid();
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IP_RESTRICTION",
                "IP restrictions must be valid IPv4 or IPv6 addresses and CIDR ranges.");
    }

    private record ParsedRange(InetAddress address, int prefix) {
    }
}
