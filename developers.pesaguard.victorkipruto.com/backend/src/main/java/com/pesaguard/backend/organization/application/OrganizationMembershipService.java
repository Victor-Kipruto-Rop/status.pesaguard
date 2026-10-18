package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.application.UsernameGenerator;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.api.AcceptInvitationRequest;
import com.pesaguard.backend.organization.api.AcceptedInvitationView;
import com.pesaguard.backend.organization.api.ChangeMemberRoleRequest;
import com.pesaguard.backend.organization.api.CreateInvitationRequest;
import com.pesaguard.backend.organization.api.CreatedInvitationView;
import com.pesaguard.backend.organization.api.InvitationPreviewView;
import com.pesaguard.backend.organization.api.InvitationView;
import com.pesaguard.backend.organization.api.MemberView;
import com.pesaguard.backend.organization.api.MembershipHistoryView;
import com.pesaguard.backend.organization.api.UpdateMemberStatusRequest;
import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationInvitation;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationInvitationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipHistoryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.project.infrastructure.ProjectAssignmentProjection;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.sessions.AuthSessionRepository;
import com.pesaguard.backend.security.sessions.MemberLastActivityProjection;
import com.pesaguard.backend.security.authentication.PasswordPolicy;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.authentication.RegistrationService;
import com.pesaguard.backend.security.authentication.SessionOrganizationResponse;
import com.pesaguard.backend.security.authentication.SessionUserResponse;
import com.pesaguard.backend.security.authentication.AuthenticationResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.throttling.RequestThrottleService;
import com.pesaguard.backend.tenancy.TenantEventPublisher;

@Service
public class OrganizationMembershipService {

    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationMembershipHistoryRepository historyRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationSecuritySettingsRepository settingsRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AuthSessionRepository authSessionRepository;
    private final OrganizationRepository organizationRepository;
    private final UserAccountRepository userRepository;
    private final OrganizationAuthorization authorization;
    private final AuthorizationService authorizationService;
    private final CredentialCryptoService crypto;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final TenantEventPublisher eventPublisher;
    private final ApplicationProperties properties;
    private final RequestThrottleService throttleService;
    private final InvitationEmailOutboxService invitationEmailOutboxService;
    private final Clock clock;

    public OrganizationMembershipService(
            OrganizationMembershipRepository membershipRepository,
            OrganizationMembershipHistoryRepository historyRepository,
            OrganizationInvitationRepository invitationRepository,
            OrganizationSecuritySettingsRepository settingsRepository,
            ProjectMemberRepository projectMemberRepository,
            AuthSessionRepository authSessionRepository,
            OrganizationRepository organizationRepository,
            UserAccountRepository userRepository,
            OrganizationAuthorization authorization,
            AuthorizationService authorizationService,
            CredentialCryptoService crypto,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            RefreshTokenService refreshTokenService,
            AuditService auditService,
            TenantEventPublisher eventPublisher,
            ApplicationProperties properties,
            RequestThrottleService throttleService,
            InvitationEmailOutboxService invitationEmailOutboxService,
            Clock clock) {
        this.membershipRepository = membershipRepository;
        this.historyRepository = historyRepository;
        this.invitationRepository = invitationRepository;
        this.settingsRepository = settingsRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.authSessionRepository = authSessionRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.authorization = authorization;
        this.authorizationService = authorizationService;
        this.crypto = crypto;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.throttleService = throttleService;
        this.invitationEmailOutboxService = invitationEmailOutboxService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(AuthenticatedUser principal) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN,
                OrganizationRole.DEVELOPER, OrganizationRole.VIEWER));
        List<OrganizationMembership> memberships = membershipRepository.findAllByOrganizationId(principal.organizationId());
        List<UUID> userIds = memberships.stream().map(membership -> membership.getUser().getId()).toList();
        Map<UUID, List<String>> assignedProjects = userIds.isEmpty() ? Map.of()
                : projectMemberRepository.findActiveAssignmentsForOrganizationMembers(principal.organizationId(), userIds)
                        .stream().collect(java.util.stream.Collectors.groupingBy(
                                ProjectAssignmentProjection::getUserId,
                                java.util.stream.Collectors.mapping(ProjectAssignmentProjection::getProjectName,
                                        java.util.stream.Collectors.toList())));
        Map<UUID, Instant> lastActivity = userIds.isEmpty() ? Map.of()
                : authSessionRepository.findLatestActivityForOrganizationMembers(principal.organizationId(), userIds)
                        .stream().collect(java.util.stream.Collectors.toMap(
                                MemberLastActivityProjection::getUserId,
                                MemberLastActivityProjection::getLastActivityAt));
        return memberships.stream().map(membership -> toView(membership,
                assignedProjects.getOrDefault(membership.getUser().getId(), List.of()),
                lastActivity.get(membership.getUser().getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<MembershipHistoryView> history(AuthenticatedUser principal, UUID membershipId) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        membershipRepository.findByIdAndOrganizationId(membershipId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Membership"));
        return historyRepository.findByMembershipIdOrderByCreatedAtDesc(membershipId).stream().map(this::toHistoryView).toList();
    }

    @Transactional
    public MemberView updateStatus(AuthenticatedUser principal, UUID membershipId, UpdateMemberStatusRequest request) {
        authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN));
        OrganizationMembership membership = membershipRepository.findByIdAndOrganizationIdForUpdate(
                membershipId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Membership"));
        if (membership.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNER_MEMBERSHIP_PROTECTED", "The organization owner membership cannot be changed.");
        }
        MembershipStatus previous = membership.getStatus();
        if (request.status() == MembershipStatus.SUSPENDED) {
            membership.suspend();
        } else if (request.status() == MembershipStatus.ACTIVE) {
            membership.activate();
        } else if (request.status() == MembershipStatus.REVOKED) {
            membership.remove();
        } else {
            throw conflict("UNSUPPORTED_MEMBERSHIP_STATUS", "Unsupported membership status.");
        }
        membershipRepository.saveAndFlush(membership);
        historyRepository.save(OrganizationMembershipHistory.record(membership, previous, membership.getRole(),
                principal.userId(), request.reason(), clock.instant()));
        if (request.status() != MembershipStatus.ACTIVE) {
            sessionService.revokeActiveByMembershipId(membershipId);
        }
        audit(principal.organizationId(), principal.userId(), "organization.membership.status_changed",
                Map.of("membershipId", membershipId.toString(), "status", request.status().name()));
        return toView(membership);
    }

    @Transactional
    public MemberView updateRole(AuthenticatedUser principal, UUID membershipId, ChangeMemberRoleRequest request) {
        authorization.requireOwner(principal);
        OrganizationMembership membership = membershipRepository.findByIdAndOrganizationIdForUpdate(
                membershipId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Membership"));
        if (membership.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNER_MEMBERSHIP_PROTECTED", "The organization owner membership cannot be changed.");
        }
        if (request.role() == OrganizationRole.OWNER) {
            throw conflict("OWNERSHIP_TRANSFER_REQUIRED", "Use the ownership transfer operation to assign ownership.");
        }
        OrganizationRole previous = membership.getRole();
        membership.changeRole(request.role());
        membershipRepository.saveAndFlush(membership);
        // The access token carries the prior role. End every session for this
        // membership now so the changed authorization takes effect immediately.
        sessionService.revokeActiveByMembershipId(membershipId);
        historyRepository.save(OrganizationMembershipHistory.record(membership, membership.getStatus(), previous,
                principal.userId(), "role.changed", clock.instant()));
        audit(principal.organizationId(), principal.userId(), "organization.membership.role_changed",
                Map.of("membershipId", membershipId.toString(), "role", request.role().name()));
        return toView(membership);
    }

    @Transactional
    public CreatedInvitationView invite(AuthenticatedUser principal, CreateInvitationRequest request,
            String idempotencyKey) {
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        if (request.role() == OrganizationRole.OWNER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OWNERSHIP_TRANSFER_REQUIRED",
                    "Ownership is transferred explicitly, not by invitation.");
        }
        if (request.role() == OrganizationRole.ADMIN) {
            authorization.requireRole(principal, java.util.Set.of(OrganizationRole.OWNER));
        }
        Organization organization = organizationRepository.findByIdForUpdate(principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        String normalizedIdempotencyKey = requireInvitationIdempotencyKey(idempotencyKey);
        String idempotencyKeyHash = crypto.hmacSha256("invitation-create:" + normalizedIdempotencyKey);
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw conflict("ORGANIZATION_NOT_ACTIVE", "Invitations require an active organization.");
        }
        String email = RegistrationService.normalizeEmail(request.email());
        String requestHash = crypto.sha256(email + "\n" + request.role().name());
        var replay = invitationRepository.findByOrganizationIdAndInvitedByAndIdempotencyKeyHash(
                organization.getId(), principal.userId(), idempotencyKeyHash);
        if (replay.isPresent()) {
            if (!requestHash.equals(replay.get().getIdempotencyRequestHash())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different invitation request.");
            }
            return new CreatedInvitationView(replay.get().getId(), replay.get().getEmail(),
                    replay.get().getRole().name(), replay.get().getExpiresAt(),
                    invitationEmailOutboxService.deliveryStatus(replay.get().getId()));
        }
        throttleInvitationCreation(principal, organization.getId(), email);
        if (membershipRepository.existsByOrganizationIdAndUserEmailIgnoreCase(organization.getId(), email)) {
            throw new ResourceConflictException("MEMBER_ALREADY_EXISTS", "The user is already a member of this organization.");
        }
        if (invitationRepository.existsByOrganizationIdAndEmailIgnoreCaseAndStatus(
                organization.getId(), email, InvitationStatus.PENDING)) {
            throw new ResourceConflictException("INVITATION_ALREADY_PENDING",
                    "A pending invitation already exists for this email address.");
        }
        String rawToken = crypto.randomToken(32);
        String tokenHash = crypto.sha256(rawToken);
        Instant now = clock.instant();
        OrganizationInvitation invitation = invitationRepository.saveAndFlush(OrganizationInvitation.create(
                organization.getId(), email, request.role(), tokenHash,
                idempotencyKeyHash, requestHash,
                now.plus(properties.security().invitationTtl()), principal.userId()));
        UserAccount inviter = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User"));
        invitationEmailOutboxService.enqueue(invitation.getId(), email, rawToken, tokenHash,
                organization.getName(), inviter.getDisplayName(), request.role().name(), invitation.getExpiresAt());
        audit(organization.getId(), principal.userId(), "organization.invitation.created",
                Map.of("invitationId", invitation.getId().toString(), "role", request.role().name()));
        audit(organization.getId(), principal.userId(), "organization.invitation.email_queued",
                Map.of("invitationId", invitation.getId().toString()));
        return new CreatedInvitationView(invitation.getId(), email, request.role().name(),
                invitation.getExpiresAt(), "QUEUED");
    }

    @Transactional
    public InvitationPreviewView previewInvitation(String token, String remoteAddress) {
        throttleInvitationPreview(token, remoteAddress);
        OrganizationInvitation invitation = invitationRepository.findByTokenHash(crypto.sha256(token))
                .orElseThrow(this::invalidInvitation);
        Instant now = clock.instant();
        Organization organization = organizationRepository.findById(invitation.getOrganizationId())
                .filter(item -> item.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(this::invalidInvitation);
        String inviterName = userRepository.findById(invitation.getInvitedBy())
                .map(UserAccount::getDisplayName).orElse("An organization administrator");
        InvitationStatus status = invitation.getStatus() == InvitationStatus.PENDING
                && !invitation.getExpiresAt().isAfter(now)
                ? InvitationStatus.EXPIRED
                : invitation.getStatus();
        return new InvitationPreviewView(status, organization.getName(),
                invitation.getRole().name(), inviterName, maskEmail(invitation.getEmail()),
                invitation.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(AuthenticatedUser principal) {
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        return invitationRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                .stream().map(this::toInvitationView).toList();
    }

    @Transactional
    public CreatedInvitationView invite(AuthenticatedUser principal, UUID organizationId,
            CreateInvitationRequest request, String idempotencyKey) {
        requireInvitationManagementScope(principal, organizationId);
        return invite(principal, request, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(AuthenticatedUser principal, UUID organizationId) {
        requireInvitationManagementScope(principal, organizationId);
        return invitations(principal);
    }

    @Transactional(readOnly = true)
    public InvitationView invitation(AuthenticatedUser principal, UUID organizationId, UUID invitationId) {
        requireInvitationManagementScope(principal, organizationId);
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        return invitationRepository.findByIdAndOrganizationId(invitationId, organizationId)
                .map(this::toInvitationView)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation"));
    }

    @Transactional
    public CreatedInvitationView resendInvitation(AuthenticatedUser principal, UUID invitationId) {
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        String subject = crypto.hmacSha256("invitation-resend:" + invitationId);
        OrganizationInvitation invitation = invitationRepository.findByIdAndOrganizationIdForUpdate(
                invitationId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Invitation"));
        Instant now = clock.instant();
        invitation.expire(now);
        if (invitation.getStatus() != InvitationStatus.PENDING && invitation.getStatus() != InvitationStatus.EXPIRED) {
            throw conflict("INVITATION_NOT_RESENDABLE", "Only pending or expired invitations can be resent.");
        }
        throttleService.assertAllowed("invitation_resend", subject, 1);
        throttleService.recordAttempt("invitation_resend", subject, 1, Duration.ofSeconds(60));
        String rawToken = crypto.randomToken(32);
        String tokenHash = crypto.sha256(rawToken);
        Instant expiresAt = now.plus(properties.security().invitationTtl());
        invitation.rotateToken(tokenHash, expiresAt, now);
        invitationRepository.saveAndFlush(invitation);
        Organization organization = organizationRepository.findById(invitation.getOrganizationId())
                .filter(item -> item.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(() -> conflict("ORGANIZATION_NOT_ACTIVE", "Invitations require an active organization."));
        UserAccount inviter = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User"));
        invitationEmailOutboxService.enqueue(invitation.getId(), invitation.getEmail(), rawToken, tokenHash,
                organization.getName(), inviter.getDisplayName(), invitation.getRole().name(), expiresAt);
        audit(principal.organizationId(), principal.userId(), "organization.invitation.resent",
                Map.of("invitationId", invitationId.toString()));
        audit(principal.organizationId(), principal.userId(), "organization.invitation.email_queued",
                Map.of("invitationId", invitationId.toString()));
        return new CreatedInvitationView(invitation.getId(), invitation.getEmail(),
                invitation.getRole().name(), expiresAt, "QUEUED");
    }

    @Transactional
    public CreatedInvitationView resendInvitation(
            AuthenticatedUser principal, UUID organizationId, UUID invitationId) {
        requireInvitationManagementScope(principal, organizationId);
        return resendInvitation(principal, invitationId);
    }

    @Transactional
    public void revokeInvitation(AuthenticatedUser principal, UUID invitationId) {
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        OrganizationInvitation invitation = invitationRepository.findByIdAndOrganizationIdForUpdate(
                invitationId, principal.organizationId()).orElseThrow(() -> new ResourceNotFoundException("Invitation"));
        Instant now = clock.instant();
        invitation.expire(now);
        boolean changed = invitation.getStatus() == InvitationStatus.PENDING;
        invitation.revoke(now);
        invitationRepository.saveAndFlush(invitation);
        if (changed) {
            audit(principal.organizationId(), principal.userId(), "organization.invitation.revoked",
                    Map.of("invitationId", invitationId.toString()));
        }
    }

    @Transactional
    public void revokeInvitation(AuthenticatedUser principal, UUID organizationId, UUID invitationId) {
        requireInvitationManagementScope(principal, organizationId);
        revokeInvitation(principal, invitationId);
    }

    @Transactional
    public void cancelInvitation(AuthenticatedUser principal, UUID organizationId, UUID invitationId) {
        requireInvitationManagementScope(principal, organizationId);
        authorizationService.requirePermission(principal, Permission.WORKSPACE_INVITE);
        OrganizationInvitation invitation = invitationRepository.findByIdAndOrganizationIdForUpdate(
                invitationId, organizationId).orElseThrow(() -> new ResourceNotFoundException("Invitation"));
        Instant now = clock.instant();
        invitation.expire(now);
        if (invitation.getStatus() == InvitationStatus.EXPIRED) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_EXPIRED",
                    "This invitation has expired and can no longer be cancelled.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw conflict("INVITATION_NOT_CANCELLABLE", "Only pending invitations can be cancelled.");
        }
        invitation.cancel(now);
        invitationRepository.saveAndFlush(invitation);
        audit(organizationId, principal.userId(), "organization.invitation.cancelled",
                Map.of("invitationId", invitationId.toString()));
    }



    @Transactional
    public AcceptedInvitationView accept(
            AcceptInvitationRequest request, AuthenticatedUser principal, String remoteAddress) {
        if (principal.serviceAccount()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVITATION_NOT_AUTHORIZED",
                    "A developer account is required to accept this invitation.");
        }
        throttleInvitationAcceptance(request.token(), remoteAddress);
        UserAccount user = userRepository.findByIdForUpdate(principal.userId())
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.UnauthorizedException(
                        "INVITATION_NOT_AUTHORIZED", "Sign in with the invited developer account to continue."));
        switch (user.getStatus()) {
            case ACTIVE -> { }
            case DEACTIVATED -> throw new BusinessException(HttpStatus.FORBIDDEN,
                    "INVITATION_ACCOUNT_DEACTIVATED",
                    "Reactivate your developer account before accepting this invitation.");
            case SUSPENDED -> throw new BusinessException(HttpStatus.FORBIDDEN,
                    "INVITATION_ACCOUNT_SUSPENDED",
                    "This developer account is suspended. Contact your organization administrator for help.");
            case PENDING_DELETION -> throw new BusinessException(HttpStatus.FORBIDDEN,
                    "INVITATION_ACCOUNT_PENDING_DELETION",
                    "Cancel the pending account deletion before accepting this invitation.");
            case DELETED -> throw new BusinessException(HttpStatus.FORBIDDEN,
                    "INVITATION_ACCOUNT_DELETED",
                    "This developer account has been deleted. Create a new account to continue.");
        }
        if (!user.isEmailVerified()) {
            throw new com.pesaguard.backend.common.exception.UnauthorizedException(
                    "INVITATION_EMAIL_NOT_VERIFIED", "Verify your email address before accepting this invitation.");
        }
        String email = RegistrationService.normalizeEmail(user.getEmail());
        Instant now = clock.instant();
        OrganizationInvitation invitation = invitationRepository.findByTokenHashForUpdate(crypto.sha256(request.token()))
                .orElseThrow(this::invalidInvitation);
        invitation.expire(now);
        if (invitation.getStatus() == InvitationStatus.EXPIRED) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_EXPIRED",
                    "This invitation has expired. Ask an organization administrator to resend it.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_NOT_ACTIVE",
                    "This invitation is no longer active.");
        }
        if (!email.equals(invitation.getEmail())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH",
                    "This invitation is associated with a different account. Sign in using the invited email address.");
        }
        Organization organization = organizationRepository.findByIdForUpdate(invitation.getOrganizationId())
                .orElseThrow(this::invalidInvitation);
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_NOT_ACTIVE",
                    "This invitation is no longer active.");
        }
        if (invitation.getRole() == OrganizationRole.OWNER) {
            throw conflict("OWNERSHIP_TRANSFER_REQUIRED", "The invitation cannot assign ownership.");
        }
        if (membershipRepository.existsByOrganizationIdAndUserEmailIgnoreCase(organization.getId(), email)) {
            throw new ResourceConflictException("MEMBER_ALREADY_EXISTS", "The user is already a member of this organization.");
        }
        OrganizationMembership membership = membershipRepository.saveAndFlush(
                OrganizationMembership.member(organization, user, invitation.getRole()));
        historyRepository.save(OrganizationMembershipHistory.record(membership, null, null, user.getId(),
                "invitation.accepted", now));
        invitation.accept(user.getId(), now);
        invitationRepository.saveAndFlush(invitation);
        audit(organization.getId(), user.getId(), "organization.invitation.accepted",
                Map.of("invitationId", invitation.getId().toString(), "membershipId", membership.getId().toString()));
        audit(organization.getId(), user.getId(), "organization.membership.created",
                Map.of("invitationId", invitation.getId().toString(), "membershipId", membership.getId().toString()));
        return new AcceptedInvitationView(organization.getId());
    }

    @Transactional
    public void decline(String token, AuthenticatedUser principal, String remoteAddress) {
        if (principal.serviceAccount()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVITATION_NOT_AUTHORIZED",
                    "A developer account is required to decline this invitation.");
        }
        throttleInvitationAcceptance(token, remoteAddress);
        OrganizationInvitation invitation = invitationRepository.findByTokenHashForUpdate(crypto.sha256(token))
                .orElseThrow(this::invalidInvitation);
        Instant now = clock.instant();
        invitation.expire(now);
        if (invitation.getStatus() == InvitationStatus.EXPIRED) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_EXPIRED", "This invitation has expired.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(HttpStatus.GONE, "INVITATION_NOT_ACTIVE",
                    "This invitation is no longer active.");
        }
        String email = userRepository.findById(principal.userId())
                .filter(UserAccount::isActive)
                .filter(UserAccount::isEmailVerified)
                .map(UserAccount::getEmail)
                .map(RegistrationService::normalizeEmail)
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.UnauthorizedException(
                        "INVITATION_NOT_AUTHORIZED", "Sign in with the invited developer account to continue."));
        if (!email.equals(invitation.getEmail())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH",
                    "This invitation is associated with a different account.");
        }
        invitation.decline(now);
        invitationRepository.saveAndFlush(invitation);
        audit(invitation.getOrganizationId(), principal.userId(), "organization.invitation.declined",
                Map.of("invitationId", invitation.getId().toString()));
    }

    private void throttleInvitationAcceptance(String token, String remoteAddress) {
        int limit = properties.security().invitationAcceptAttemptLimit();
        java.time.Duration window = properties.security().invitationAcceptWindow();
        String addressSubject = subjectHash("invite_ip", remoteAddress == null ? "unknown" : remoteAddress);
        String tokenSubject = subjectHash("invite_token", token);
        throttleService.assertAllowed("invite_ip", addressSubject, limit);
        throttleService.assertAllowed("invite_token", tokenSubject, limit);
        throttleService.recordAttempt("invite_ip", addressSubject, limit, window);
        throttleService.recordAttempt("invite_token", tokenSubject, limit, window);
    }

    private void throttleInvitationPreview(String token, String remoteAddress) {
        int limit = properties.security().invitationAcceptAttemptLimit();
        Duration window = properties.security().invitationAcceptWindow();
        String addressSubject = subjectHash("invite_ip", remoteAddress == null ? "unknown" : remoteAddress);
        String tokenSubject = subjectHash("invite_token", token);
        throttleService.assertAllowed("invite_ip", addressSubject, limit);
        throttleService.assertAllowed("invite_token", tokenSubject, limit);
        throttleService.recordAttempt("invite_ip", addressSubject, limit, window);
        throttleService.recordAttempt("invite_token", tokenSubject, limit, window);
    }

    private void throttleInvitationCreation(AuthenticatedUser principal, UUID organizationId, String email) {
        Duration hour = Duration.ofHours(1);
        Duration day = Duration.ofDays(1);
        String userSubject = subjectHash("invite_create_user",
                organizationId + ":" + principal.userId());
        String organizationSubject = subjectHash("invite_create_org", organizationId.toString());
        String recipientSubject = subjectHash("invite_create_recipient",
                organizationId + ":" + RegistrationService.normalizeEmail(email));
        throttleService.assertAllowed("invite_create_user", userSubject, 10);
        throttleService.assertAllowed("invite_create_org", organizationSubject, 50);
        throttleService.assertAllowed("invite_create_recipient", recipientSubject, 3);
        throttleService.recordAttempt("invite_create_user", userSubject, 10, hour);
        throttleService.recordAttempt("invite_create_org", organizationSubject, 50, hour);
        throttleService.recordAttempt("invite_create_recipient", recipientSubject, 3, day);
    }

    private com.pesaguard.backend.common.exception.UnauthorizedException invalidInvitation() {
        return new com.pesaguard.backend.common.exception.UnauthorizedException(
                "INVITATION_INVALID", "This invitation is invalid or no longer available.");
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(at);
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private String subjectHash(String type, String value) {
        return crypto.hmacSha256("invitation-throttle:" + type + ":" + value);
    }

    private String requireInvitationIdempotencyKey(String value) {
        if (value == null || !value.trim().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must contain 8 to 128 letters, digits, dots, underscores, colons, or hyphens.");
        }
        return value.trim();
    }

    private MemberView toView(OrganizationMembership membership) {
        List<String> assignedProjects = projectMemberRepository.findActiveAssignmentsForOrganizationMembers(
                membership.getOrganization().getId(), List.of(membership.getUser().getId())).stream()
                .map(ProjectAssignmentProjection::getProjectName).toList();
        Instant lastActivity = authSessionRepository.findLatestActivityForOrganizationMembers(
                membership.getOrganization().getId(), List.of(membership.getUser().getId())).stream()
                .map(MemberLastActivityProjection::getLastActivityAt).findFirst().orElse(null);
        return toView(membership, assignedProjects, lastActivity);
    }

    private MemberView toView(OrganizationMembership membership, List<String> assignedProjects, Instant lastActivity) {
        return new MemberView(membership.getId(), membership.getUser().getId(), membership.getUser().getEmail(),
                membership.getUser().getDisplayName(), membership.getRole(), membership.getStatus(),
                membership.getCreatedAt(), membership.getUpdatedAt(), assignedProjects, lastActivity);
    }

    private InvitationView toInvitationView(OrganizationInvitation invitation) {
        return new InvitationView(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getStatus(), invitation.getExpiresAt(), invitation.getInvitedBy(),
                invitation.getAcceptedAt(), invitation.getDeclinedAt(), invitation.getRevokedAt(),
                invitation.getCancelledAt(), invitation.getCreatedAt());
    }

    private void requireInvitationManagementScope(AuthenticatedUser principal, UUID organizationId) {
        if (!principal.organizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Organization");
        }
    }

    private MembershipHistoryView toHistoryView(OrganizationMembershipHistory history) {
        return new MembershipHistoryView(history.getId(), history.getMembershipId(), history.getUserId(),
                history.getFromStatus(), history.getToStatus(), history.getFromRole(), history.getToRole(),
                history.getActorUserId(), history.getReason(), history.getCreatedAt());
    }

    private void audit(UUID organizationId, UUID actorId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, actorId, action, action.contains("invitation") ? "invitation" : "membership",
                organizationId.toString(), RequestContext.currentRequestId(), metadata);
        eventPublisher.publish(organizationId, actorId, action, organizationId, RequestContext.currentRequestId(), metadata);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
