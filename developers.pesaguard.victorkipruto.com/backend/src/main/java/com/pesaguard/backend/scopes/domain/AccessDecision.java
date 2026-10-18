package com.pesaguard.backend.scopes.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The result of evaluating one API access request against every factor that
 * constrains it.
 *
 * <p>A decision is built by evaluating factors in a fixed order and stopping at the
 * first failure. Two properties matter more than the ordering itself:
 *
 * <ul>
 *   <li><b>Fail closed.</b> A factor that cannot be resolved denies. An unknown
 *       organization, a missing scope definition, a credential that could not be
 *       loaded — all deny. Nothing here defaults to allow on an unexpected path.</li>
 *   <li><b>Explainable.</b> Every evaluated factor leaves a line in the trace, so
 *       a denial can be stated rather than guessed at. The trace is stored with the
 *       decision in {@link ApiAccessDecisionRecord}.</li>
 * </ul>
 *
 * <p>The eight factors are identity, organization, project, environment,
 * credential, role, scope, and credential status.
 */
public record AccessDecision(
        boolean allowed,
        ReasonCode reasonCode,
        String factorTrace,
        UUID organizationId,
        UUID userId,
        UUID apiKeyId,
        UUID projectId,
        UUID environmentId,
        ApiScope requestedScope,
        String requestId,
        String remoteAddress) {

    /**
     * Why an access request was allowed or refused.
     *
     * <p>These codes are part of the operator-facing contract: they appear in the
     * decision table and in API error bodies. Renaming one is a breaking change for
     * anyone integrating against error handling.
     */
    public enum ReasonCode {
        /** Every factor passed. */
        ALLOWED("ALLOWED"),
        /**
         * No factor was evaluated at all. This is a guard against an incomplete
         * caller, not a real access outcome: a decision that checked nothing has
         * not been shown to be safe, so it denies.
         */
        NO_FACTORS_EVALUATED("NO_FACTORS_EVALUATED"),
        /** No authenticated identity was presented. */
        IDENTITY_UNAUTHENTICATED("IDENTITY_UNAUTHENTICATED"),
        /** The identity is known but the membership is not active. */
        IDENTITY_INACTIVE("IDENTITY_INACTIVE"),
        /** The organization could not be resolved, or is not active. */
        ORGANIZATION_UNAVAILABLE("ORGANIZATION_UNAVAILABLE"),
        /** The project does not exist in this organization, or is not active. */
        PROJECT_UNAVAILABLE("PROJECT_UNAVAILABLE"),
        /** The environment does not exist in this project, or is not active. */
        ENVIRONMENT_UNAVAILABLE("ENVIRONMENT_UNAVAILABLE"),
        /** The credential does not exist, or is not owned by this organization. */
        CREDENTIAL_UNAVAILABLE("CREDENTIAL_UNAVAILABLE"),
        /** The credential exists but is not bound to this project/environment. */
        CREDENTIAL_CONTEXT_MISMATCH("CREDENTIAL_CONTEXT_MISMATCH"),
        /** The actor's role does not permit this action. */
        ROLE_INSUFFICIENT("ROLE_INSUFFICIENT"),
        /** The requested scope is not assigned to this credential. */
        SCOPE_NOT_ASSIGNED("SCOPE_NOT_ASSIGNED"),
        /** The requested scope is not in the registry. */
        SCOPE_UNKNOWN("SCOPE_UNKNOWN"),
        /** The scope is deprecated and the caller is not permitted to keep using it. */
        SCOPE_DEPRECATED("SCOPE_DEPRECATED"),
        /** The scope is restricted and the grant lacks the required review. */
        SCOPE_RESTRICTED("SCOPE_RESTRICTED"),
        /** The credential is suspended, revoked or expired. */
        CREDENTIAL_STATUS_BLOCKED("CREDENTIAL_STATUS_BLOCKED");

        private final String value;

        ReasonCode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Accumulates factor outcomes in evaluation order. */
    public static final class Builder {
        private final List<String> trace = new ArrayList<>();
        private ReasonCode failure;
        private UUID organizationId;
        private UUID userId;
        private UUID apiKeyId;
        private UUID projectId;
        private UUID environmentId;
        private ApiScope requestedScope;
        private String requestId;
        private String remoteAddress;

        /**
         * Records a factor outcome. The first failure wins: later factors are not
         * evaluated, because a decision that continues past an unauthenticated
         * identity would report misleading later results.
         */
        public Builder record(String factor, boolean passed, String detail) {
            if (failure == null) {
                trace.add(factor + "=" + (passed ? "PASS" : "FAIL") + "(" + detail + ")");
                if (!passed) {
                    failure = ReasonCode.valueOf(factor);
                }
            }
            return this;
        }

        public Builder context(UUID organizationId, UUID userId, UUID apiKeyId,
                UUID projectId, UUID environmentId, ApiScope scope) {
            this.organizationId = organizationId;
            this.userId = userId;
            this.apiKeyId = apiKeyId;
            this.projectId = projectId;
            this.environmentId = environmentId;
            this.requestedScope = scope;
            return this;
        }

        /** Correlates the stored decision with the HTTP request that caused it. */
        public Builder request(String requestId, String remoteAddress) {
            this.requestId = requestId;
            this.remoteAddress = remoteAddress;
            return this;
        }

        public boolean failed() {
            return failure != null;
        }

        public AccessDecision build() {
            // Fail closed: a decision that evaluated no factors at all is not a
            // passing decision, it is an incomplete one. Without this, a caller who
            // forgot to record a factor would silently get ALLOWED.
            ReasonCode resolved = failure;
            if (resolved == null && trace.isEmpty()) {
                resolved = ReasonCode.NO_FACTORS_EVALUATED;
            }
            return new AccessDecision(resolved == null, resolved == null ? ReasonCode.ALLOWED : resolved,
                    String.join("; ", trace), organizationId, userId, apiKeyId,
                    projectId, environmentId, requestedScope, requestId, remoteAddress);
        }
    }
}