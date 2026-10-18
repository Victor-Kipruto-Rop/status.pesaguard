package com.pesaguard.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Security response headers.
 *
 * <p>Each header is asserted against the specific attack it prevents, because
 * "the header is present" says nothing about whether it is set to the value that
 * actually works.
 */
class SecureHeadersFilterTest {

    private MockHttpServletResponse headers(boolean hsts) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecureHeadersFilter(hsts, 31536000).apply(response);
        return response;
    }

    @Test
    void contentIsNeverMimeSniffed() {
        // Without nosniff, a JSON error body can be re-rendered as HTML and any
        // markup inside it executed.
        assertThat(headers(false).getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void framingIsDenied() {
        // The portal is where a user reads credentials, so it is the clickjacking
        // target.
        assertThat(headers(false).getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void responsesAreNotStored() {
        // A cached usage or credential response survives logout and is readable
        // from a shared machine. This is the header most often missing on an API
        // returning billing-shaped data.
        MockHttpServletResponse response = headers(false);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    void referrersCarryNothing() {
        assertThat(headers(false).getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void crossOriginIsolationIsSet() {
        MockHttpServletResponse response = headers(false);
        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }

    @Test
    void hstsIsOffByDefault() {
        // Sending HSTS over plain HTTP is meaningless, and in a non-production
        // environment it pins the browser to the host for a year.
        assertThat(headers(false).getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void hstsIsSetWhenTerminatingTls() {
        String header = headers(true).getHeader("Strict-Transport-Security");
        assertThat(header).isNotNull();
        assertThat(header).contains("max-age=31536000").contains("includeSubDomains");
    }

    @Test
    void noContentSecurityPolicyIsAsserted() {
        // A CSP written for a document would be cargo-culted onto a JSON API
        // without protecting anything. The portal is a separate application and
        // should carry its own.
        assertThat(headers(false).getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    void powerfulBrowserFeaturesAreDenied() {
        String header = headers(false).getHeader("Permissions-Policy");
        assertThat(header).isNotNull();
        assertThat(header).contains("geolocation=()");
    }
}