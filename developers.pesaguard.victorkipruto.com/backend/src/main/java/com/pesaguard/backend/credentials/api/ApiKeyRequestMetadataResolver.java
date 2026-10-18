package com.pesaguard.backend.credentials.api;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.security.authentication.IpRangeMatcher;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class ApiKeyRequestMetadataResolver {

    private final IpRangeMatcher ipRangeMatcher;
    private final Set<String> trustedProxyCidrs;
    private final String countryHeader;

    public ApiKeyRequestMetadataResolver(
            IpRangeMatcher ipRangeMatcher,
            @Value("${pesaguard.telemetry.trusted-proxy-cidrs:}") String trustedProxyCidrs,
            @Value("${pesaguard.telemetry.country-header:CF-IPCountry}") String countryHeader) {
        this.ipRangeMatcher = ipRangeMatcher;
        this.trustedProxyCidrs = Arrays.stream(trustedProxyCidrs.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.ipRangeMatcher.validate(this.trustedProxyCidrs);
        this.countryHeader = countryHeader;
    }

    public Metadata resolve(HttpServletRequest request) {
        String country = null;
        if (!trustedProxyCidrs.isEmpty()
                && ipRangeMatcher.isAllowed(request.getRemoteAddr(), trustedProxyCidrs)) {
            country = normalizedCountry(request.getHeader(countryHeader));
        }
        return new Metadata(
                ApiKey.deviceFamily(request.getHeader("User-Agent")),
                country);
    }

    private String normalizedCountry(String value) {
        if (value == null) return null;
        String country = value.trim().toUpperCase(java.util.Locale.ROOT);
        return country.matches("[A-Z]{2}") && !country.equals("XX") ? country : null;
    }

    public record Metadata(String deviceFamily, String countryCode) {
    }
}
