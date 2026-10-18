package com.pesaguard.backend.analytics.infrastructure;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.credentials.api.ApiKeyAuthenticator;
import com.pesaguard.backend.tenancy.TenantContext;
import com.pesaguard.backend.tenancy.TenantContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides which organization, project, environment, and credential a request
 * belongs to.
 *
 * <p>Every value is derived from the <b>authenticated</b> context, never from a
 * request body, query parameter, or path segment on its own. The project and
 * environment ids in the URL are used only because they have already been
 * validated against the authenticated organization by the time a request reaches
 * a controller; the organization itself comes solely from the security context.
 *
 * <p>This is the tenant-isolation boundary for usage data. Attribution decides
 * which tenant a customer's traffic is billed and reported to, so it must not be
 * satisfiable by anything the caller controls.
 */
public interface RequestAttribution {

    /**
     * Resolves the owning context for a request.
     *
     * @return the owning context, or null when the request cannot be attributed
     */
    Resolved resolve(HttpServletRequest request);

    /**
     * The attributed owner of one request.
     *
     * <p>Deliberately a plain data holder with no behaviour: it carries what was
     * decided about a request and nothing else, so it cannot become a second
     * place where authorization rules are applied.
     */
    record Resolved(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID apiKeyId,
            UUID oauthApplicationId,
            UUID userId) {
    }

    /**
     * Default resolver, reading the security and tenant contexts.
     *
     * <p>Credential ids are null in the current build: {@code ApiKeyAuthenticator}
     * exists but is not yet invoked by any filter, so there is no live API-key
     * request to attribute. Once the credential filter lands, this is where the
     * key id is added — the usage schema and rollups already carry the column.
     */
    @Component
    class DefaultResolver implements RequestAttribution {

        @Override
        public Resolved resolve(HttpServletRequest request) {
            Object keyAttribute = request.getAttribute(
                    ApiKeyAuthenticator.REQUEST_ATTRIBUTE_AUTHENTICATED_KEY);
            if (keyAttribute instanceof ApiKeyAuthenticator.AuthenticatedApiKey key) {
                return new Resolved(key.organizationId(), key.projectId(), key.environmentId(),
                        key.keyId(), null, null);
            }
            java.util.Optional<TenantContext> context = TenantContextHolder.current();
            if (context.isPresent()) {
                TenantContext value = context.get();
                return new Resolved(value.organizationId(), value.projectId(),
                        value.environmentId(), null, null, value.actorId());
            }

            // Fall back to the security context. TenantContextFilter may not have
            // run, for example on an error dispatch.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                return new Resolved(user.organizationId(), null, null, null, null, user.userId());
            }
            return null;
        }
    }
}
