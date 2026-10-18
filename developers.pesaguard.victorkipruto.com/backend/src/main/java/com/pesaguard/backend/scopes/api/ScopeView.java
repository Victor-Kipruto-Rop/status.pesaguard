package com.pesaguard.backend.scopes.api;

import java.util.List;

import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;
import com.pesaguard.backend.scopes.domain.ApiScopeRestriction;

/**
 * Catalog view of one scope, as returned to the portal and to integrators.
 *
 * <p>Includes the deprecation target so a client can be told what to migrate to
 * without a second call, and the restriction reason so an operator can see why a
 * grant was refused without leaving the screen they are on.
 */
public record ScopeView(
        String name,
        String description,
        String category,
        String resource,
        String action,
        int version,
        boolean restricted,
        boolean apiKeyAssignable,
        boolean deprecated,
        String replacedBy,
        String changeReason,
        String restrictionReason,
        boolean requiresSecurityReview) {

    public static ScopeView from(ApiScopeDefinition definition, ApiScopeRestriction restriction) {
        return new ScopeView(definition.getName(), definition.getDescription(), definition.getCategory(),
                definition.getResource(), definition.getAction(), definition.getVersion(),
                definition.isRestricted(), definition.isApiKeyAssignable(),
                definition.isDeprecated(), definition.getReplacedBy(),
                definition.getChangeReason(),
                restriction == null ? null : restriction.getReason(),
                restriction != null && restriction.isRequiresSecurityReview());
    }

    public static List<ScopeView> all(List<ApiScopeDefinition> definitions,
            java.util.function.Function<String, ApiScopeRestriction> restrictions) {
        return definitions.stream()
                .map(definition -> from(definition, restrictions.apply(definition.getName())))
                .toList();
    }
}