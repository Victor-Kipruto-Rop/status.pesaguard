package com.pesaguard.backend.project.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.project.api.AddProjectMemberRequest;
import com.pesaguard.backend.project.api.ChangeProjectMemberRoleRequest;
import com.pesaguard.backend.project.api.ProjectMemberView;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectMember;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Project membership. Only users who are already active members of the owning
 * organization can be added to a project, and removal is recorded as REVOKED
 * rather than deleting the row.
 */
@Service
public class ProjectMembershipService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final UserAccountRepository userAccountRepository;
    private final ProjectAuthorization authorization;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectMembershipService(
            ProjectRepository projectRepository,
            ProjectMemberRepository memberRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            UserAccountRepository userAccountRepository,
            ProjectAuthorization authorization,
            AuditService auditService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.userAccountRepository = userAccountRepository;
        this.authorization = authorization;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberView> list(AuthenticatedUser principal, UUID projectId) {
        requireProject(principal, projectId);
        authorization.requireProjectRead(principal, projectId);
        return memberRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(this::toView).toList();
    }

    @Transactional
    public ProjectMemberView add(AuthenticatedUser principal, UUID projectId, AddProjectMemberRequest request) {
        Project project = requireProject(principal, projectId, true);
        authorization.requireProjectManage(principal, projectId);
        requireActiveProject(project);
        organizationMembershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), request.userId())
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "PROJECT_MEMBER_NOT_IN_ORGANIZATION",
                        "The user must be an active member of the organization."));
        ProjectMember existing = memberRepository.findByProjectIdAndUserIdForUpdate(projectId, request.userId())
                .orElse(null);
        if (existing != null && existing.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROJECT_MEMBER_ALREADY_EXISTS",
                    "The user is already an active member of this project.");
        }
        ProjectMember member;
        if (existing == null) {
            member = ProjectMember.add(projectId, principal.organizationId(), request.userId(),
                    request.role(), principal.userId());
        } else {
            existing.restore();
            existing.changeRole(request.role());
            member = existing;
        }
        memberRepository.saveAndFlush(member);
        audit(principal, "project.member_added", projectId,
                Map.of("membershipId", member.getId().toString(), "role", request.role().name()));
        return toView(member);
    }

    @Transactional
    public ProjectMemberView changeRole(AuthenticatedUser principal, UUID projectId, UUID memberId,
            ChangeProjectMemberRoleRequest request) {
        Project project = requireProject(principal, projectId, true);
        authorization.requireProjectManage(principal, projectId);
        requireActiveProject(project);
        ProjectMember member = memberRepository.findByIdAndProjectIdForUpdate(memberId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project membership"));
        member.changeRole(request.role());
        memberRepository.saveAndFlush(member);
        audit(principal, "project.member_role_changed", projectId,
                Map.of("membershipId", memberId.toString(), "role", request.role().name()));
        return toView(member);
    }

    @Transactional
    public void revoke(AuthenticatedUser principal, UUID projectId, UUID memberId) {
        Project project = requireProject(principal, projectId, true);
        authorization.requireProjectManage(principal, projectId);
        ProjectMember member = memberRepository.findByIdAndProjectIdForUpdate(memberId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project membership"));
        if (member.getUserId().equals(principal.userId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROJECT_MEMBER_SELF_REVOKE",
                    "You cannot remove your own project membership.");
        }
        boolean changed = member.isActive();
        member.revoke();
        memberRepository.saveAndFlush(member);
        if (changed) {
            audit(principal, "project.member_revoked", projectId, Map.of("membershipId", memberId.toString()));
        }
    }

    private Project requireProject(AuthenticatedUser principal, UUID projectId) {
        return requireProject(principal, projectId, false);
    }

    private Project requireProject(AuthenticatedUser principal, UUID projectId, boolean forUpdate) {
        return (forUpdate
                ? projectRepository.findByIdAndOrganizationIdForUpdate(projectId, principal.organizationId())
                : projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
    }

    private void requireActiveProject(Project project) {
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROJECT_NOT_ACTIVE",
                    "Only an active project accepts membership changes.");
        }
    }

    private void audit(AuthenticatedUser principal, String action, UUID projectId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action, "project_member",
                projectId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private ProjectMemberView toView(ProjectMember member) {
        var user = userAccountRepository.findById(member.getUserId()).orElse(null);
        return new ProjectMemberView(member.getId(), member.getProjectId(), member.getUserId(),
                user == null ? null : user.getEmail(), user == null ? null : user.getDisplayName(),
                member.getRole(), member.getStatus(), member.getCreatedAt(), member.getUpdatedAt());
    }
}
