package com.pesaguard.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DeveloperTenantIdTest {

    private static final String ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000002";
    private static final String ENVIRONMENT_ID = "00000000-0000-0000-0000-000000000003";

    @Test
    void derivesTheCrossLanguageSha256VectorFromExactUtf8Input() {
        assertThat(DeveloperTenantId.derive(ORGANIZATION_ID, PROJECT_ID, ENVIRONMENT_ID))
                .isEqualTo("dp_2934bbfdc6fee932108c14a00ca22a9e8b6217d596b1d0988edefd178e1cb1b7");
    }

    @Test
    void rejectsNullWhitespaceNonCanonicalAndMalformedIdentifiers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeveloperTenantId.derive(null, PROJECT_ID, ENVIRONMENT_ID));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeveloperTenantId.derive(" " + ORGANIZATION_ID, PROJECT_ID, ENVIRONMENT_ID));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeveloperTenantId.derive(
                        "00000000-0000-0000-0000-00000000000A", PROJECT_ID, ENVIRONMENT_ID));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeveloperTenantId.derive(ORGANIZATION_ID, "1-2-3-4-5", ENVIRONMENT_ID));
    }
}
