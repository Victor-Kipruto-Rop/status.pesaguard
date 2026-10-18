package com.pesaguard.backend.oauth.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Redirect URI and origin validation.
 *
 * <p>Redirect URI handling is the single most exploited area of an OAuth server,
 * so the rules are deliberately strict and deliberately boring:
 * <ul>
 *   <li>Exact string match only. No prefix, subdomain, or wildcard matching - a
 *       registered URI must equal the presented URI character for character.</li>
 *   <li>{@code https} is required, except for loopback hosts, which RFC 8252
 *       permits over {@code http} for native apps.</li>
 *   <li>No fragments (RFC 6749 §3.1.2), no embedded credentials, no wildcard
 *       host.</li>
 * </ul>
 */
public final class RedirectUriPolicy {

    private RedirectUriPolicy() {
    }

    public static boolean isValidRedirectUri(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return false;
        }
        String candidate = value.trim();
        if (candidate.contains("*") || candidate.contains("#")) {
            return false;
        }
        try {
            URI uri = new URI(candidate);
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            if (uri.getHost() == null || uri.getQuery() == null && candidate.contains("?")) {
                // A query is permitted, but only when properly delimited by the URI parser.
                return false;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean loopback = isLoopbackHost(uri.getHost());
            if ("https".equals(scheme)) {
                return true;
            }
            return "http".equals(scheme) && loopback;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /**
     * Exact-match comparison. Normalization is limited to surrounding whitespace
     * so that a registered URI cannot be widened by encoding tricks.
     */
    public static boolean matches(String registered, String presented) {
        if (registered == null || presented == null) {
            return false;
        }
        return constantTimeEquals(registered.trim(), presented.trim());
    }

    public static boolean isAllowedOrigin(String origin, Set<String> allowedOrigins) {
        if (origin == null || allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }
        return allowedOrigins.stream().anyMatch(candidate -> constantTimeEquals(candidate.trim(), origin.trim()));
    }

    /**
     * Builds the post-consent redirect. Values are percent-encoded, and a null or
     * blank value is omitted rather than emitted as an empty parameter, so a
     * client that sent no {@code state} never receives {@code state=}.
     */
    public static String appendQuery(String redirectUri, String firstName, String firstValue,
            String secondName, String secondValue) {
        StringBuilder builder = new StringBuilder(redirectUri);
        char separator = redirectUri.contains("?") ? '&' : '?';
        appendParameter(builder, separator, firstName, firstValue);
        appendParameter(builder, '&', secondName, secondValue);
        return builder.toString();
    }

    private static void appendParameter(StringBuilder builder, char separator, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        builder.append(separator).append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        return candidate.equals("localhost")
                || candidate.equals("127.0.0.1")
                || candidate.equals("::1")
                || candidate.equals("[::1]");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}