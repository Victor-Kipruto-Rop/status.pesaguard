package com.pesaguard.backend.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.BusinessException;

class OrganizationNamePolicyTest {

    @Test
    void trimsAndAcceptsNamesWithUnicodeAndCommonPunctuation() {
        assertThat(OrganizationNamePolicy.validate("  Élan Systems & Co.  "))
                .isEqualTo("Élan Systems & Co.");
    }

    @Test
    void rejectsBlankShortLongAndControlCharacterNames() {
        assertThatThrownBy(() -> OrganizationNamePolicy.validate("  "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrganizationNamePolicy.validate("A"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrganizationNamePolicy.validate("A".repeat(121)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrganizationNamePolicy.validate("Acme\u0000Corp"))
                .isInstanceOf(BusinessException.class);
    }
}
