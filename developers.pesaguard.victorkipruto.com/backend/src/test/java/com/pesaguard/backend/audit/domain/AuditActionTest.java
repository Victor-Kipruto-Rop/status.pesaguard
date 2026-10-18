package com.pesaguard.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The declared audit vocabulary.
 *
 * <p>The point of declaring it is that these are the properties a free-text
 * action column cannot offer: coverage of the required event classes is
 * provable, and an unqueryable typo is impossible to express.
 */
class AuditActionTest {

    @Test
    void everyRequiredEventClassIsCovered() {
        // The thirteen classes the phase requires. Declaring them in a test rather
        // than in a document means dropping one fails the build.
        assertThat(EnumSet.allOf(AuditCategory.class))
                .containsExactlyInAnyOrder(
                        AuditCategory.ORGANIZATION,
                        AuditCategory.MEMBERSHIP,
                        AuditCategory.PROJECT,
                        AuditCategory.ENVIRONMENT,
                        AuditCategory.ROLE,
                        AuditCategory.PERMISSION,
                        AuditCategory.CREDENTIAL,
                        AuditCategory.OAUTH_APPLICATION,
                        AuditCategory.WEBHOOK,
                        AuditCategory.SCOPE,
                        AuditCategory.PRODUCTION_ACCESS,
                        AuditCategory.SECURITY);
    }

    @Test
    void everyCategoryHasAtLeastOneAction() {
        // Otherwise the category exists only nominally and a query over it returns
        // nothing forever.
        for (AuditCategory category : AuditCategory.values()) {
            assertThat(Arrays.stream(AuditAction.values())
                    .anyMatch(action -> action.category() == category))
                    .as("category %s has an action", category)
                    .isTrue();
        }
    }

    @Test
    void actionStringsAreDerivedLowerSnakeCase() {
        assertThat(AuditAction.API_KEY_ROTATED.value()).isEqualTo("api_key_rotated");
        assertThat(AuditAction.PRODUCTION_APPROVED.value()).isEqualTo("production_approved");
    }

    @Test
    void everyActionValueIsUnique() {
        // A duplicate would make two different actions indistinguishable in a query.
        assertThat(AuditAction.allValues()).doesNotHaveDuplicates();
    }

    @Test
    void parsingRoundTripsEveryAction() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(AuditAction.parse(action.value())).contains(action);
        }
    }

    @Test
    void unknownHistoricalActionsParseEmptyRatherThanThrowing() {
        // Audit history must stay readable after the vocabulary changes. A row that
        // can no longer be classified is still a row an investigator needs to see.
        assertThat(AuditAction.parse("something.recorded_before_the_catalog")).isEmpty();
        assertThat(AuditAction.parse(null)).isEmpty();
        assertThat(AuditAction.parse("  ")).isEmpty();
    }

    @Test
    void theTypoThatMotivatesACatalogCannotBeExpressed() {
        // "production_acess" is the realistic mistake, and with free text it would
        // create an event no query for rejected production requests could find.
        assertThat(AuditAction.parse("production_acess.rejected")).isEmpty();
    }

    @Test
    void permissionBoundaryChangesAreMarkedSecuritySensitive() {
        assertThat(AuditAction.ROLE_ASSIGNED.isSecuritySensitive()).isTrue();
        assertThat(AuditAction.PERMISSION_GRANTED.isSecuritySensitive()).isTrue();
        assertThat(AuditAction.API_KEY_REVOKED.isSecuritySensitive()).isTrue();
        assertThat(AuditAction.EMERGENCY_REVOCATION.isSecuritySensitive()).isTrue();
    }

    @Test
    void ordinaryChangesAreNotSecuritySensitive() {
        // Overstating this marker trains operators to ignore it.
        assertThat(AuditAction.PROJECT_UPDATED.isSecuritySensitive()).isFalse();
        assertThat(AuditAction.ENVIRONMENT_CREATED.isSecuritySensitive()).isFalse();
        assertThat(AuditAction.WEBHOOK_ENDPOINT_CREATED.isSecuritySensitive()).isFalse();
    }

    @Test
    void everyActionDeclaresADefaultResourceType() {
        // So an event is not filed against an arbitrary resource kind by accident.
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.defaultResourceType()).isNotBlank();
        }
    }

    @Test
    void credentialAndScopeActionsAreDistinctResources() {
        // The two most commonly confused: a webhook key is not an API key.
        assertThat(AuditAction.API_KEY_ROTATED.defaultResourceType())
                .isNotEqualTo(AuditAction.WEBHOOK_SECRET_ROTATED.defaultResourceType());
        assertThat(AuditAction.SCOPE_ASSIGNED.defaultResourceType())
                .isNotEqualTo(AuditAction.PERMISSION_GRANTED.defaultResourceType());
    }
}
