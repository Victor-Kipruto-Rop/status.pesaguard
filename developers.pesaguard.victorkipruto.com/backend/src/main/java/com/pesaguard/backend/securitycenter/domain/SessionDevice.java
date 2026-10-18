package com.pesaguard.backend.securitycenter.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Device details for a session, derived from request headers.
 *
 * <p>Purely derived, never stored raw. A {@code User-Agent} string is
 * attacker-controlled and can contain anything, including something that looks
 * like markup. Only short, sanitised, display-safe facts are retained: a coarse
 * device family and an operating system.
 *
 * <p>This class deliberately produces less than it could. An exact user agent
 * string is more precise, and persisting it would help an investigation, but it
 * is also an unbounded, untrusted, potentially script-bearing string that ends up
 * in a security surface. Coarse classification answers "is this a device I
 * recognise?" without carrying the payload.
 */
public final class SessionDevice {

    /** Bound on the label actually returned. */
    private static final int MAX_LABEL_LENGTH = 64;

    /**
     * Bound on the string used for classification.
     *
     * <p>Larger than {@link #MAX_LABEL_LENGTH} because truncating an agent first
     * can drop the very token that distinguishes one browser from another: Edge
     * advertises "Chrome", so a shortened string reports Edge as Chrome.
     */
    private static final int MAX_MATCH_LENGTH = 512;

    private SessionDevice() {
    }

    /**
     * A coarse device label, e.g. "Chrome on Windows".
     *
     * <p>Never null: an unrecognised agent is "Unknown client" rather than empty,
     * so a session never displays as having no device at all.
     */
    public static String describe(String userAgent) {
        String browser = browser(userAgent);
        String os = operatingSystem(userAgent);
        if (browser == null && os == null) {
            return "Unknown client";
        }
        if (os == null) {
            return browser;
        }
        if (browser == null) {
            return os;
        }
        return browser + " on " + os;
    }

    private static String browser(String userAgent) {
        String agent = normalise(userAgent);
        if (agent == null) {
            return null;
        }
        // Order matters: Edge advertises "Chrome", and Safari's version token also
        // contains "Safari".
        if (agent.contains("edg/") || agent.contains("edge/")) {
            return "Edge";
        }
        if (agent.contains("opr/") || agent.contains("opera")) {
            return "Opera";
        }
        if (agent.contains("firefox/")) {
            return "Firefox";
        }
        if (agent.contains("chrome/") || agent.contains("crios/")) {
            return "Chrome";
        }
        if (agent.contains("safari/")) {
            return "Safari";
        }
        if (agent.contains("curl/") || agent.contains("wget/")) {
            return "Command line client";
        }
        if (agent.contains("bot/") || agent.contains("crawler") || agent.contains("spider")) {
            return "Bot";
        }
        return null;
    }

    private static String operatingSystem(String userAgent) {
        String agent = normalise(userAgent);
        if (agent == null) {
            return null;
        }
        if (agent.contains("windows")) {
            return "Windows";
        }
        if (agent.contains("android")) {
            return "Android";
        }
        if (agent.contains("iphone") || agent.contains("ipad")) {
            return "iOS";
        }
        if (agent.contains("mac os") || agent.contains("macos")) {
            return "macOS";
        }
        if (agent.contains("linux") || agent.contains("x11")) {
            return "Linux";
        }
        return null;
    }

    /**
     * Strips anything that must not reach a stored or rendered label, then bounds
     * the result for matching.
     *
     * <p>Markup characters are stripped rather than escaped: these labels are for
     * display and comparison, and a label containing markup should not survive
     * into a security dashboard at all.
     */
    private static String normalise(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(userAgent.length());
        for (int index = 0; index < userAgent.length(); index++) {
            char character = userAgent.charAt(index);
            if (character >= 0x20 && character != 0x7F
                    && character != '<' && character != '>' && character != '"') {
                cleaned.append(character);
            }
        }
        String result = cleaned.toString().toLowerCase(Locale.ROOT);
        return result.length() <= MAX_MATCH_LENGTH
                ? result
                : result.substring(0, MAX_MATCH_LENGTH);
    }

    /**
     * Whether two labels plausibly describe the same client.
     *
     * <p>Used to flag a session appearing from an unfamiliar device family.
     * Coarse by design: two different Windows machines both report "Chrome on
     * Windows", so this flags a <em>change of kind</em>, not a new physical
     * device. Claiming more precision would be a false claim of capability.
     */
    public static boolean sameDeviceFamily(Optional<String> first, String second) {
        return first.map(value -> value.equals(second)).orElse(false);
    }
}