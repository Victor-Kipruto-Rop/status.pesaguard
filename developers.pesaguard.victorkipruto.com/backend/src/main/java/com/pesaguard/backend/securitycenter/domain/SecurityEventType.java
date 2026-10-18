package com.pesaguard.backend.securitycenter.domain;

/**
 * Categories of security signal surfaced to a developer.
 *
 * <p>Each value names something <em>observed</em>, not something concluded. None
 * of them declares fraud, compromise, or misconduct as fact. An anomaly is an
 * anomaly until a human resolves it, and conflating the two would make a
 * detection that turns out to be a bug indistinguishable from a real incident in
 * the developer's eyes.
 *
 * <p>Severity is deliberately absent. Ranking a signal requires context the
 * platform does not have, and a fixed ordering would imply a confidence the data
 * does not support. Resolution is recorded instead.
 */
public enum SecurityEventType {

    /**
     * A revoked or expired key was presented.
     *
     * <p>The strongest signal here: a credential that should not work was used.
     * It is usually a stale client deployment, and occasionally a leak.
     */
    REVOKED_CREDENTIAL_USAGE,

    /**
     * A key was used from an address outside its allowlist.
     *
     * <p>Worth flagging even when the attempt was refused, because repeated
     * attempts from varying addresses look different from one misconfigured host.
     */
    ALLOWLIST_VIOLATION,

    /**
     * Request volume far outside a key's own recent history.
     *
     * <p>Judged against the key's own baseline rather than a global threshold, so
     * a naturally high-traffic key is not reported every hour.
     */
    ABNORMAL_API_USAGE,

    /**
     * A token or refresh token was presented more than once.
     *
     * <p>Refresh-token reuse is the classic signal of a stolen token: legitimate
     * clients rotate a family forward and never present an old one again.
     */
    TOKEN_REPLAY,

    /**
     * Many consecutive authentication or authorization failures for one subject.
     *
     * <p>Distinct from rate limiting: this is about a persistent stream of
     * failures, which suggests guessing rather than a burst.
     */
    REPEATED_FAILURES,

    /**
     * A webhook endpoint was targeted far more often than usual, or from an
     * unexpected source.
     *
     * <p>Distinct from abnormal API usage because the traffic is inbound to the
     * customer's endpoint rather than outbound from their key.
     */
    SUSPICIOUS_WEBHOOK_ACTIVITY,

    /**
     * A successful sign-in from a device family the account has not used before.
     *
     * <p>Roughly as common as it sounds innocent: a new phone, a new browser, a
     * work laptop after a home one. It is worth surfacing because it is also the
     * ordinary shape of a stolen password, and it is the one signal available
     * without the platform having any prior knowledge of the person.
     */
    UNFAMILIAR_DEVICE_SIGNIN,

    /**
     * A credential attempted scopes it has not been granted.
     *
     * <p>Frequently benign: scopes get added to an integration over time. Worth
     * surfacing because it is also what a confused-deputy attempt looks like.
     */
    SCOPE_ABUSE,

    /**
     * A tenant-scoped resource was accessed by an actor outside its organization.
     *
     * <p>The most serious category, because unlike the others a genuine hit is a
     * containment failure rather than a misconfiguration.
     */
    AUTHORIZATION_FAILURE;

    /**
     * Whether a hit in this category is expected to be benign in normal use.
     *
     * <p>Used to prioritise, never to suppress. A suppressed signal is a signal
     * nobody can investigate later.
     */
    public boolean isCommonlyBenign() {
        return this == SCOPE_ABUSE || this == REPEATED_FAILURES
                || this == ALLOWLIST_VIOLATION || this == UNFAMILIAR_DEVICE_SIGNIN;
    }

    /**
     * Whether a hit can indicate an actual containment failure.
     *
     * <p>Only these warrant urgent attention. The rest are hygiene signals.
     */
    public boolean isSerious() {
        return this == AUTHORIZATION_FAILURE || this == REVOKED_CREDENTIAL_USAGE
                || this == TOKEN_REPLAY;
    }
}