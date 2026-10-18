package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.api.CreateOrganizationRequest;
import com.pesaguard.backend.organization.api.OrganizationView;
import com.pesaguard.backend.organization.api.TransferOwnershipRequest;
import com.pesaguard.backend.organization.api.UpdateOrganizationRequest;
import com.pesaguard.backend.organization.api.VerifyOrganizationRequest;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.domain.OrganizationType;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipHistoryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.security.authentication.SlugGenerator;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.tenancy.TenantAwareCache;
import com.pesaguard.backend.tenancy.TenantEventPublisher;

@Service
public class OrganizationLifecycleService {

    private static final String VIEW_CACHE = "organization-view";

    private final OrganizationRepository organizationRepository;
    private final UserAccountRepository userAccountRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationMembershipHistoryRepository historyRepository;
    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final OrganizationAuthorization authorization;
    private final OrganizationMetadataValidator metadataValidator;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final TenantEventPublisher eventPublisher;
    private final TenantAwareCache cache;
    private final Clock clock;

    public OrganizationLifecycleService(
            OrganizationRepository organizationRepository,
            UserAccountRepository userAccountRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationMembershipHistoryRepository historyRepository,
            OrganizationSecuritySettingsRepository settingsRepository,
            OrganizationAuthorization authorization,
            OrganizationMetadataValidator metadataValidator,
            SessionService sessionService,
            AuditService auditService,
            TenantEventPublisher eventPublisher,
            TenantAwareCache cache,
            Clock clock) {
        this.organizationRepository = organizationRepository;
        this.userAccountRepository = userAccountRepository;
        this.membershipRepository = membershipRepository;
        this.historyRepository = historyRepository;
        this.settingsRepository = settingsRepository;
        this.authorization = authorization;
        this.metadataValidator = metadataValidator;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrganizationView current(AuthenticatedUser principal) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN,
                OrganizationRole.DEVELOPER, OrganizationRole.VIEWER));
        OrganizationView cached = cache.get(principal.organizationId(), VIEW_CACHE, "current", OrganizationView.class);
        if (cached != null) {
            return cached;
        }
        Organization organization = find(principal.organizationId());
        OrganizationView view = toView(organization);
        cache.put(principal.organizationId(), VIEW_CACHE, "current", view);
        return view;
    }

    @Transactional
    public OrganizationView create(AuthenticatedUser principal, CreateOrganizationRequest request) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        metadataValidator.validate(request.metadata());
        String name = OrganizationNamePolicy.validate(request.name());
        String slug = uniqueSlug(name);
        Instant now = clock.instant();
        Organization organization = Organization.create(name, slug, principal.userId(), now);
        organization.update(name, request.type(), request.metadata());
        organizationRepository.saveAndFlush(organization);
        settingsRepository.saveAndFlush(OrganizationSecuritySettings.defaults(organization.getId(), now));
        OrganizationMembership owner = membershipRepository.saveAndFlush(
                OrganizationMembership.owner(organization, userAccountRepository.findById(principal.userId())
                        .orElseThrow(() -> new ResourceNotFoundException("User"))));
        historyRepository.save(OrganizationMembershipHistory.record(
                owner, null, null, principal.userId(), "organization.created", now));
        auditService.append(organization.getId(), principal.userId(), "organization.created", "organization",
                organization.getId().toString(), RequestContext.currentRequestId(),
                Map.of("type", request.type().name()));
        eventPublisher.publish(organization.getId(), principal.userId(), "organization.created",
                organization.getId(), RequestContext.currentRequestId(), Map.of());
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }

    @Transactional
    public OrganizationView update(AuthenticatedUser principal, UpdateOrganizationRequest request) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        metadataValidator.validate(request.metadata());
        String name = OrganizationNamePolicy.validate(request.name());
        Organization organization = findForUpdate(principal.organizationId());
        ensureNotDeleted(organization);
        organization.update(name, request.type(), request.metadata());
        organizationRepository.saveAndFlush(organization);
        auditService.append(principal.organizationId(), principal.userId(), "organization.updated", "organization",
                organization.getId().toString(), RequestContext.currentRequestId(),
                Map.of("type", request.type().name()));
        eventPublisher.publish(principal.organizationId(), principal.userId(), "organization.updated",
                organization.getId(), RequestContext.currentRequestId(), Map.of());
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }

    @Transactional
    public OrganizationView verify(AuthenticatedUser principal, VerifyOrganizationRequest request) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        Organization organization = findForUpdate(principal.organizationId());
        ensureNotDeleted(organization);
        organization.verify(request.reference().trim(), clock.instant());
        organizationRepository.saveAndFlush(organization);
        auditService.append(principal.organizationId(), principal.userId(), "organization.verified", "organization",
                organization.getId().toString(), RequestContext.currentRequestId(),
                Map.of("reference", request.reference().trim()));
        eventPublisher.publish(principal.organizationId(), principal.userId(), "organization.verified",
                organization.getId(), RequestContext.currentRequestId(), Map.of());
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }

    private OrganizationView transition(AuthenticatedUser principal, String action, OrganizationStatus target) {
        authorization.requireRole(principal, Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        Organization organization = findForUpdate(principal.organizationId());
        ensureNotDeleted(organization);
        Instant now = clock.instant();
        if (target == OrganizationStatus.SUSPENDED) {
            organization.suspend(now);
        } else if (target == OrganizationStatus.DISABLED) {
            organization.disable(now);
        } else {
            organization.restore(now);
        }
        organizationRepository.saveAndFlush(organization);
        sessionService.revokeActiveByOrganizationId(organization.getId());
        audit(organization.getId(), principal.userId(), action, Map.of("status", target.name()));
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }

    @Transactional
    public OrganizationView suspend(AuthenticatedUser principal) {
        return transition(principal, "organization.suspended", OrganizationStatus.SUSPENDED);
    }

    @Transactional
    public OrganizationView restore(AuthenticatedUser principal) {
        return transition(principal, "organization.restored", OrganizationStatus.ACTIVE);
    }

    @Transactional
    public OrganizationView disable(AuthenticatedUser principal) {
        return transition(principal, "organization.disabled", OrganizationStatus.DISABLED);
    }

    @Transactional
    public OrganizationView delete(AuthenticatedUser principal) {
        authorization.requireOwner(principal);
        Organization organization = findForUpdate(principal.organizationId());
        if (organization.getStatus() == OrganizationStatus.DELETED) {
            return toView(organization);
        }
        organization.delete(clock.instant());
        organizationRepository.saveAndFlush(organization);
        sessionService.revokeActiveByOrganizationId(organization.getId());
        audit(organization.getId(), principal.userId(), "organization.deleted", Map.of());
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }


    @Transactional
    public OrganizationView transferOwnership(AuthenticatedUser principal, TransferOwnershipRequest request) {
        authorization.requireOwner(principal);
        Organization organization = findForUpdate(principal.organizationId());
        OrganizationMembership currentOwner = membershipRepository.findByOrganizationIdAndUserId(
                        organization.getId(), principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner membership"));
        OrganizationMembership target = membershipRepository.findByIdAndOrganizationId(
                        request.membershipId(), organization.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Membership"));
        if (target.getRole() == OrganizationRole.OWNER
                || target.getStatus() != MembershipStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "OWNERSHIP_TRANSFER_INVALID",
                    "Ownership can only be transferred to an active non-owner member.");
        }
        Instant now = clock.instant();
        currentOwner.relinquishOwnership();
        target.makeOwner();
        organization.transferOwnership(target.getUser().getId());
        membershipRepository.saveAndFlush(currentOwner);
        membershipRepository.saveAndFlush(target);
        organizationRepository.saveAndFlush(organization);
        historyRepository.save(OrganizationMembershipHistory.record(
                currentOwner, MembershipStatus.ACTIVE, OrganizationRole.OWNER,
                principal.userId(), "ownership.transferred.out", now));
        historyRepository.save(OrganizationMembershipHistory.record(
                target, MembershipStatus.ACTIVE, OrganizationRole.ADMIN,
                principal.userId(), "ownership.transferred.in", now));
        audit(organization.getId(), principal.userId(), "organization.ownership_transferred",
                Map.of("membershipId", request.membershipId().toString()));
        sessionService.revokeActiveByOrganizationId(organization.getId());
        cache.invalidate(principal.organizationId());
        return toView(organization);
    }

    private Organization find(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .filter(organization -> organization.getStatus() != OrganizationStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    private Organization findForUpdate(UUID organizationId) {
        return organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    private String uniqueSlug(String name) {
        String base = SlugGenerator.slug(name);
        String candidate = base;
        while (organizationRepository.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return candidate;
    }

    private void audit(UUID organizationId, UUID actorId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, actorId, action, "organization", organizationId.toString(),
                RequestContext.currentRequestId(), metadata);
        eventPublisher.publish(organizationId, actorId, action, organizationId,
                RequestContext.currentRequestId(), metadata);
    }

    private void ensureNotDeleted(Organization organization) {
        if (organization.getStatus() == OrganizationStatus.DELETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORGANIZATION_DELETED",
                    "Deleted organizations cannot change lifecycle state.");
        }
    }

    private OrganizationView toView(Organization organization) {
        return new OrganizationView(organization.getId(), organization.getName(), organization.getSlug(),
                organization.getOrganizationType(), organization.getStatus(), organization.getOwnerUserId(),
                organization.getMetadata(), organization.getVerifiedAt(), organization.getVerificationReference(),
                organization.getStatusChangedAt(), organization.getDeletedAt(), organization.getCreatedAt(),
                organization.getUpdatedAt());
    }
}
