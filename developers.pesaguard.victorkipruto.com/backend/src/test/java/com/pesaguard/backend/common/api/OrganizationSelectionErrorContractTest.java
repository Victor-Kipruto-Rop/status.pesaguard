package com.pesaguard.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.pesaguard.backend.common.exception.OrganizationSelectionException;
import com.pesaguard.backend.common.exception.UnauthorizedException;

/**
 * The organization-selection challenge as the client receives it.
 *
 * <p>Exercised through {@link GlobalExceptionHandler} rather than by raising the
 * exception in isolation, because the payload is a contract with the portal: the
 * chooser has to survive being built into the error record and read back out with
 * its names intact, or the picker renders empty and the failure looks like a UI
 * bug rather than a missing field.
 *
 * <p>Deliberately a plain unit test with no Spring context: this is pure
 * request-to-response mapping and loading a container to check it would add
 * minutes and hide the behaviour being asserted.
 */
class OrganizationSelectionErrorContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void aSelectionChallengeExposesTheOrganizationsToChooseFrom() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ApiError error = handler.handleBusiness(new OrganizationSelectionException(List.of(
                new OrganizationSelectionException.Selectable(first, "Acme Payments", "acme-payments", "OWNER"),
                new OrganizationSelectionException.Selectable(second, "Acme Sandbox", "acme-sandbox", "DEVELOPER"))),
                request).getBody().error();

        assertThat(error.code()).isEqualTo("ORGANIZATION_SELECTION_REQUIRED");
        assertThat(error.selectableOrganizations()).hasSize(2);
        assertThat(error.selectableOrganizations().get(0).id()).isEqualTo(first);
        assertThat(error.selectableOrganizations().get(0).name()).isEqualTo("Acme Payments");
        assertThat(error.selectableOrganizations().get(0).slug()).isEqualTo("acme-payments");
        assertThat(error.selectableOrganizations().get(0).role()).isEqualTo("OWNER");
    }

    @Test
    void otherErrorsCarryNoChooser() {
        // Empty rather than null, so a client can iterate unconditionally without a
        // null check on a field that is only meaningful on one code.
        ApiError error = handler.handleBusiness(
                new UnauthorizedException("INVALID_CREDENTIALS", "nope"), request).getBody().error();

        assertThat(error.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(error.selectableOrganizations()).isEmpty();
    }

    @Test
    void theChooserIsNotReusableAsAValidationFailure() {
        // Violations describe rejected input; a chooser describes valid input
        // needing disambiguation. Overloading one with the other would make a 409
        // look like a 400 to any client that reads violations first.
        ApiError error = handler.handleBusiness(new OrganizationSelectionException(List.of(
                new OrganizationSelectionException.Selectable(
                        UUID.randomUUID(), "Acme", "acme", "OWNER"))),
                request).getBody().error();

        assertThat(error.violations()).isEmpty();
    }
}