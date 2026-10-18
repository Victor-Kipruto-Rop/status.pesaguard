package com.pesaguard.backend.webhooks.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.events.domain.SubscriptionStatus;
import com.pesaguard.backend.events.infrastructure.EventSubscriptionRepository;
import com.pesaguard.backend.explorer.security.OutboundTargetGuard;
import com.pesaguard.backend.explorer.security.UnsafeTargetException;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.events.application.DeveloperEventEmitter;
import com.pesaguard.backend.webhooks.domain.WebhookEndpoint;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

@Service
public class WebhookEndpointService {

    private final WebhookEndpointRepository endpointRepository;
    private final EventSubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final SecretEncryptionService encryption;
    private final CredentialCryptoService crypto;
    private final OutboundTargetGuard targetGuard;
    private final AuthorizationService authorizationService;
    private final ProjectAuthorization projectAuthorization;
    private final DeveloperEventEmitter developerEventEmitter;
    private final EnvironmentAccessPolicyService environmentAccessPolicyService;

    public WebhookEndpointService(WebhookEndpointRepository endpointRepository,
            EventSubscriptionRepository subscriptionRepository,
            ProjectRepository projectRepository, ProjectEnvironmentRepository environmentRepository,
            SecretEncryptionService encryption,
            CredentialCryptoService crypto, AuthorizationService authorizationService,
            ProjectAuthorization projectAuthorization, DeveloperEventEmitter developerEventEmitter,
            EnvironmentAccessPolicyService environmentAccessPolicyService) {
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.encryption = encryption;
        this.crypto = crypto;
        this.targetGuard = new OutboundTargetGuard(Set.of(), false);
        this.authorizationService = authorizationService;
        this.projectAuthorization = projectAuthorization;
        this.developerEventEmitter = developerEventEmitter;
        this.environmentAccessPolicyService = environmentAccessPolicyService;
    }

    @Transactional(readOnly = true)
    public List<EndpointView> list(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        require(principal, Permission.WEBHOOK_READ);
        requireProject(principal, projectId, false);
        requireEnvironment(principal, projectId, environmentId, EnvironmentPermission.READ);
        return endpointRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusNotOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId,
                        "DELETED", PageRequest.of(0, 100))
                .stream().map(EndpointView::from).toList();
    }

    @Transactional
    public CreatedEndpoint create(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            String name, String url) {
        require(principal, Permission.WEBHOOK_CREATE);
        requireProject(principal, projectId, true);
        requireEnvironment(principal, projectId, environmentId, EnvironmentPermission.WRITE);
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_NAME_INVALID",
                    "Webhook name must contain between 1 and 120 characters.");
        }
        final String normalizedUrl;
        try {
            normalizedUrl = targetGuard.validate(url).uri().toASCIIString();
        } catch (UnsafeTargetException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_URL_UNSAFE",
                    exception.getMessage());
        }
        String signingSecret = "whsec_" + crypto.randomToken(32);
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.create(
                principal.organizationId(), projectId, environmentId, name, normalizedUrl,
                encryption.encrypt(signingSecret)));
        developerEventEmitter.webhookCreated(endpoint);
        return new CreatedEndpoint(EndpointView.from(endpoint), signingSecret);
    }

    @Transactional
    public EndpointView changeStatus(AuthenticatedUser principal, UUID endpointId,
            UUID environmentId, String requestedStatus) {
        Permission permission = "DELETED".equalsIgnoreCase(requestedStatus)
                ? Permission.WEBHOOK_DELETE : Permission.WEBHOOK_UPDATE;
        require(principal, permission);
        WebhookEndpoint endpoint = endpointRepository.findByIdAndOrganizationId(
                        endpointId, principal.organizationId())
                .orElseThrow(() -> notFound());
        requireEndpointEnvironment(endpoint, environmentId);
        requireProject(principal, endpoint.getProjectId(), true);
        requireEnvironment(principal, endpoint.getProjectId(), environmentId, EnvironmentPermission.WRITE);
        try {
            switch (requestedStatus == null ? "" : requestedStatus.toUpperCase(java.util.Locale.ROOT)) {
                case "ACTIVE" -> endpoint.resume();
                case "SUSPENDED" -> endpoint.suspend();
                case "DELETED" -> {
                    if (subscriptionRepository.existsByEndpointIdAndOrganizationIdAndStatusNot(
                            endpointId.toString(), endpoint.getOrganizationId(),
                            SubscriptionStatus.CANCELLED)) {
                        throw new BusinessException(HttpStatus.CONFLICT,
                                "WEBHOOK_HAS_ACTIVE_SUBSCRIPTIONS",
                                "Suspend or cancel subscriptions before deleting this endpoint.");
                    }
                    endpoint.delete();
                }
                default -> throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "WEBHOOK_STATUS_INVALID", "Status must be ACTIVE, SUSPENDED, or DELETED.");
            }
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "WEBHOOK_STATUS_CONFLICT",
                    exception.getMessage());
        }
        return EndpointView.from(endpoint);
    }

    @Transactional
    public EndpointView updateConfiguration(AuthenticatedUser principal, UUID endpointId,
            UUID environmentId, String name, String url) {
        require(principal, Permission.WEBHOOK_UPDATE);
        WebhookEndpoint endpoint = endpointRepository.findByIdAndOrganizationId(
                        endpointId, principal.organizationId())
                .orElseThrow(() -> notFound());
        requireEndpointEnvironment(endpoint, environmentId);
        requireProject(principal, endpoint.getProjectId(), true);
        requireEnvironment(principal, endpoint.getProjectId(), environmentId, EnvironmentPermission.WRITE);
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_NAME_INVALID",
                    "Webhook name must contain between 1 and 120 characters.");
        }
        final String normalizedUrl;
        try {
            normalizedUrl = targetGuard.validate(url).uri().toASCIIString();
        } catch (UnsafeTargetException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_URL_UNSAFE",
                    exception.getMessage());
        }
        try {
            endpoint.updateConfiguration(name, normalizedUrl);
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "WEBHOOK_STATUS_CONFLICT",
                    exception.getMessage());
        }
        return EndpointView.from(endpoint);
    }

    @Transactional
    public CreatedEndpoint rotateSigningSecret(AuthenticatedUser principal, UUID endpointId,
            UUID environmentId) {
        require(principal, Permission.WEBHOOK_UPDATE);
        WebhookEndpoint endpoint = endpointRepository.findByIdAndOrganizationId(
                        endpointId, principal.organizationId())
                .orElseThrow(() -> notFound());
        requireEndpointEnvironment(endpoint, environmentId);
        requireProject(principal, endpoint.getProjectId(), true);
        requireEnvironment(principal, endpoint.getProjectId(), environmentId,
                EnvironmentPermission.ROTATE_CREDENTIALS);
        String signingSecret = "whsec_" + crypto.randomToken(32);
        try {
            endpoint.rotateSigningSecret(encryption.encrypt(signingSecret));
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "WEBHOOK_STATUS_CONFLICT",
                    exception.getMessage());
        }
        return new CreatedEndpoint(EndpointView.from(endpoint), signingSecret);
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEndpoint> findActiveEndpoint(UUID organizationId, UUID projectId,
            UUID environmentId, String endpointId) {
        UUID parsed;
        try {
            parsed = UUID.fromString(endpointId);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return endpointRepository.findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        parsed, organizationId, projectId, environmentId)
                .filter(endpoint -> "ACTIVE".equals(endpoint.getStatus()));
    }

    private void requireEnvironment(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            EnvironmentPermission permission) {
        var environment = environmentId == null ? java.util.Optional.<com.pesaguard.backend.environment.domain.ProjectEnvironment>empty()
                : environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId);
        if (environment.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND",
                "Environment was not found in this project.");
        environmentAccessPolicyService.requireAccess(principal, environment.get(), permission);
    }

    private static void requireEndpointEnvironment(WebhookEndpoint endpoint, UUID environmentId) {
        if (environmentId == null || !endpoint.getEnvironmentId().equals(environmentId)) {
            throw notFound();
        }
    }

    private void requireProject(AuthenticatedUser principal, UUID projectId, boolean manage) {
        if (projectId == null || projectRepository.findByIdAndOrganizationId(
                projectId, principal.organizationId()).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                    "Project was not found in this organization.");
        }
        if (manage) projectAuthorization.requireProjectManage(principal, projectId);
        else projectAuthorization.requireProjectRead(principal, projectId);
    }

    private void require(AuthenticatedUser principal, Permission permission) {
        if (principal == null) {
            throw new UnauthorizedException("WEBHOOK_UNAUTHENTICATED",
                    "Authentication is required.");
        }
        if (!authorizationService.hasPermission(principal, permission)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "WEBHOOK_FORBIDDEN",
                    "You do not have permission to manage webhook endpoints.");
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND",
                "Webhook endpoint was not found.");
    }

    public record EndpointView(UUID id, UUID projectId, UUID environmentId, String name, String url,
            String status, java.time.Instant createdAt, java.time.Instant updatedAt) {
        static EndpointView from(WebhookEndpoint endpoint) {
            return new EndpointView(endpoint.getId(), endpoint.getProjectId(), endpoint.getEnvironmentId(),
                    endpoint.getName(), endpoint.getUrl(), endpoint.getStatus(),
                    endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }
    }

    public record CreatedEndpoint(EndpointView endpoint, String signingSecret) {
    }
}
