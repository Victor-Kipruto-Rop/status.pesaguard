package com.pesaguard.backend.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Redirect URI handling is the most exploited area of an OAuth server. These
 * tests pin the strict behaviour: exact match, https unless loopback, no
 * fragments, no wildcards, no embedded credentials.
 */
class RedirectUriPolicyTest {

    @Test
    void acceptsHttpsUris() {
        assertThat(RedirectUriPolicy.isValidRedirectUri("https://developers.example.com/callback")).isTrue();
        assertThat(RedirectUriPolicy.isValidRedirectUri("https://app.example.com/oauth/callback?x=1")).isTrue();
    }

@Test
    void appendQueryEchoesStateAndEncodesIt() {
        String url = RedirectUriPolicy.appendQuery(
                "https://app.example.com/cb", "code", "abc/def+ghi", "state", "a b&c=d");

        assertThat(url).isEqualTo("https://app.example.com/cb?code=abc%2Fdef%2Bghi&state=a+b%26c%3Dd");
    }

    @Test
    void appendQueryOmitsAnAbsentStateEntirely() {
        String url = RedirectUriPolicy.appendQuery("https://app.example.com/cb", "code", "abc", "state", null);

        assertThat(url).isEqualTo("https://app.example.com/cb?code=abc");
        assertThat(url).doesNotContain("state");
    }

    @Test
    void appendQueryPreservesAnExistingQueryString() {
        String url = RedirectUriPolicy.appendQuery(
                "https://app.example.com/cb?tenant=acme", "code", "abc", "state", "xyz");

        assertThat(url).isEqualTo("https://app.example.com/cb?tenant=acme&code=abc&state=xyz");
    }

    @Test
    void rejectsWildcardsAndFragments() {
        assertThat(RedirectUriPolicy.isValidRedirectUri("https://*.example.com/callback")).isFalse();
        assertThat(RedirectUriPolicy.isValidRedirectUri("https://app.example.com/#token")).isFalse();
    }

    @Test
    void rejectsPlainHttpExceptForLoopback() {
        assertThat(RedirectUriPolicy.isValidRedirectUri("http://app.example.com/callback")).isFalse();
        assertThat(RedirectUriPolicy.isValidRedirectUri("http://localhost:8080/callback")).isTrue();
        assertThat(RedirectUriPolicy.isValidRedirectUri("http://127.0.0.1:9000/callback")).isTrue();
        assertThat(RedirectUriPolicy.isValidRedirectUri("http://[::1]:9000/callback")).isTrue();
    }

    @Test
    void rejectsEmbeddedCredentialsAndJunk() {
        assertThat(RedirectUriPolicy.isValidRedirectUri("https://user:pass@app.example.com/cb")).isFalse();
        assertThat(RedirectUriPolicy.isValidRedirectUri("not-a-uri")).isFalse();
        assertThat(RedirectUriPolicy.isValidRedirectUri("")).isFalse();
        assertThat(RedirectUriPolicy.isValidRedirectUri(null)).isFalse();
    }

    @Test
    void matchingIsExactAndNotPrefixBased() {
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://app.example.com/cb")).isTrue();
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://app.example.com/cb/extra")).isFalse();
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://app.example.com/cb?next=evil")).isFalse();
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://evil.example.com/cb")).isFalse();
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://app.example.com/cb.evil.com")).isFalse();
    }

    @Test
    void matchingIsCaseSensitive() {
        assertThat(RedirectUriPolicy.matches(
                "https://app.example.com/cb", "https://APP.example.com/cb")).isFalse();
    }

    @Test
    void nullsNeverMatch() {
        assertThat(RedirectUriPolicy.matches(null, "https://a.example")).isFalse();
        assertThat(RedirectUriPolicy.matches("https://a.example", null)).isFalse();
    }

    @Test
    void originsMustBeExplicitlyAllowlisted() {
        assertThat(RedirectUriPolicy.isAllowedOrigin(
                "https://app.example.com", java.util.Set.of("https://app.example.com"))).isTrue();
        assertThat(RedirectUriPolicy.isAllowedOrigin(
                "https://evil.example.com", java.util.Set.of("https://app.example.com"))).isFalse();
        assertThat(RedirectUriPolicy.isAllowedOrigin("https://app.example.com", java.util.Set.of())).isFalse();
        assertThat(RedirectUriPolicy.isAllowedOrigin(null, java.util.Set.of("https://a.example"))).isFalse();
    }
}