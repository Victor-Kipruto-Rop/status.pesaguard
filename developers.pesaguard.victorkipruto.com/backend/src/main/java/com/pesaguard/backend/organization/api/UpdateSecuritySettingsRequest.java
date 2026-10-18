package com.pesaguard.backend.organization.api;

import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateSecuritySettingsRequest(
        @NotEmpty @Size(max = 4) Set<@Pattern(regexp = "PASSWORD|MFA|OIDC") String> allowedAuthMethods,
        @Min(5) @Max(10080) int sessionTtlMinutes,
        @Min(5) @Max(10080) int idleTimeoutMinutes,
        @Min(1) @Max(100) int maxSessions,
        @Min(12) @Max(72) int credentialMinLength,
        @Min(12) @Max(72) int credentialMaxLength,
        boolean mfaRequired,
        Boolean mfaRequiredForAdmins,
        @NotNull @Size(max = 50) Set<@Size(min = 3, max = 64) String> ipAllowlist,
        @NotEmpty @Size(max = 20) Set<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String> securityEventTypes) {

    public UpdateSecuritySettingsRequest(Set<String> allowedAuthMethods, int sessionTtlMinutes,
            int idleTimeoutMinutes, int maxSessions, int credentialMinLength, int credentialMaxLength,
            boolean mfaRequired, Set<String> ipAllowlist, Set<String> securityEventTypes) {
        this(allowedAuthMethods, sessionTtlMinutes, idleTimeoutMinutes, maxSessions,
                credentialMinLength, credentialMaxLength, mfaRequired, false, ipAllowlist, securityEventTypes);
    }
}