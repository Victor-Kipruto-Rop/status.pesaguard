package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.organization.api.OrganizationSecuritySettingsView;
import com.pesaguard.backend.organization.api.UpdateSecuritySettingsRequest;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.tenancy.TenantAwareCache;
import com.pesaguard.backend.tenancy.TenantEventPublisher;

@Service
public class OrganizationSecuritySettingsService {

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "LOGIN_FAILURE", "MEMBERSHIP_CHANGED", "SECURITY_SETTING_CHANGED",
            "ORGANIZATION_LIFECYCLE", "INVITATION_CREATED", "INVITATION_ACCEPTED",
            "INVITATION_DECLINED", "INVITATION_REVOKED");

    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationAuthorization authorization;
    private final IpRangeMatcher ipRangeMatcher;
    private final AuditService auditService;
    private final TenantEventPublisher eventPublisher;
    private final TenantAwareCache cache;
    private final Clock clock;

    public OrganizationSecuritySettingsService(
            OrganizationSecuritySettingsRepository settingsRepository,
            OrganizationRepository organizationRepository,
            OrganizationAuthorization authorization,
            IpRangeMatcher ipRangeMatcher,
            AuditService auditService,
            TenantEventPublisher eventPublisher,
            TenantAwareCache cache,
            Clock clock) {
        this.settingsRepository = settingsRepository;
        this.organizationRepository = organizationRepository;
        this.authorization = authorization;
        this.ipRangeMatcher = ipRangeMatcher;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public OrganizationSecuritySettings getOrCreate(UUID organizationId) {
        return settingsRepository.findById(organizationId)
                .orElseGet(() -> settingsRepository.saveAndFlush(
                        OrganizationSecuritySettings.defaults(organizationId, clock.instant())));
    }

    @Transactional
    public OrganizationSecuritySettingsView get(AuthenticatedUser principal) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        return toView(getOrCreate(principal.organizationId()));
    }


    @Transactional
    public OrganizationSecuritySettingsView update(AuthenticatedUser principal, UpdateSecuritySettingsRequest request) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        Organization organization = organizationRepository.findByIdForUpdate(principal.organizationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND",
                        "Organization was not found."));
        if (organization.getStatus() == OrganizationStatus.DELETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORGANIZATION_DELETED",
                    "Deleted organizations cannot change security settings.");
        }
        validate(request);
        OrganizationSecuritySettings settings = getOrCreate(principal.organizationId());
        boolean mfaRequiredForAdmins = request.mfaRequiredForAdmins() == null
                ? settings.isMfaRequiredForAdmins()
                : request.mfaRequiredForAdmins();
        settings.update(encode(request.allowedAuthMethods()), request.sessionTtlMinutes(), request.idleTimeoutMinutes(),
                request.maxSessions(), request.credentialMinLength(), request.credentialMaxLength(),
                request.mfaRequired(), mfaRequiredForAdmins,
                encode(request.ipAllowlist()), encode(request.securityEventTypes()),
                principal.userId(), clock.instant());
        settingsRepository.saveAndFlush(settings);
        cache.invalidate(principal.organizationId());
        auditService.append(principal.organizationId(), principal.userId(), "organization.security_settings.updated",
                "organization_security_settings", principal.organizationId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("mfaRequired", request.mfaRequired(),
                        "mfaRequiredForAdmins", mfaRequiredForAdmins,
                        "sessionTtlMinutes", request.sessionTtlMinutes()));
        eventPublisher.publish(principal.organizationId(), principal.userId(), "organization.security_settings.updated",
                principal.organizationId(), RequestContext.currentRequestId(), java.util.Map.of());
        return toView(settings);
    }

    public void assertPasswordLoginAllowed(OrganizationSecuritySettings settings) {
        if (!decode(settings.getAllowedAuthMethods()).contains("PASSWORD")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "AUTH_METHOD_DISABLED",
                    "Password authentication is disabled for the organization.");
        }
    }

    public boolean requiresMfa(OrganizationSecuritySettings settings, OrganizationRole role) {
        return settings.isMfaRequired()
                || (settings.isMfaRequiredForAdmins()
                        && (role == OrganizationRole.ADMIN || role == OrganizationRole.OWNER));
    }

    public void assertIpAllowed(OrganizationSecuritySettings settings, String remoteAddress) {
        if (!ipRangeMatcher.isAllowed(remoteAddress, decode(settings.getIpAllowlist()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "IP_NOT_ALLOWED",
                    "Access from this network is not permitted for the organization.");
        }
    }

    private void validate(UpdateSecuritySettingsRequest request) {
        if (request.credentialMinLength() > request.credentialMaxLength()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_POLICY",
                    "Credential minimum length cannot exceed maximum length.");
        }
        if (!request.allowedAuthMethods().contains("PASSWORD")
                || request.allowedAuthMethods().stream().anyMatch(method -> !"PASSWORD".equals(method))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AUTH_METHOD",
                    "Only PASSWORD authentication is available in this deployment.");
        }
        if (!SUPPORTED_EVENT_TYPES.containsAll(request.securityEventTypes())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SECURITY_EVENT",
                    "One or more security event types are not supported.");
        }
        ipRangeMatcher.validate(request.ipAllowlist());
    }

    private String encode(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    private Set<String> decode(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private OrganizationSecuritySettingsView toView(OrganizationSecuritySettings settings) {
        return new OrganizationSecuritySettingsView(settings.getOrganizationId(),
                decode(settings.getAllowedAuthMethods()), settings.getSessionTtlMinutes(), settings.getIdleTimeoutMinutes(),
                settings.getMaxSessions(), settings.getCredentialMinLength(), settings.getCredentialMaxLength(),
                settings.isMfaRequired(), settings.isMfaRequiredForAdmins(),
                decode(settings.getIpAllowlist()), decode(settings.getSecurityEventTypes()),
                settings.getUpdatedAt());
    }
}
