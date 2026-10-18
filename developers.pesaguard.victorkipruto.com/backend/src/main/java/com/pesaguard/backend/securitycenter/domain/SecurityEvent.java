package com.pesaguard.backend.securitycenter.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A security signal raised against a developer's organization.
 *
 * <p>Immutable once recorded. A signal is an observation at a point in time, and
 * the value of it lies in not being able to edit what was seen after the fact.
 * Resolution is a separate field precisely so that acknowledging a finding never
 * rewrites the finding.
 *
 * <p>Detection never auto-resolves. A detector that closes its own alerts is a
 * detector whose silence cannot be distinguished from "nothing is wrong", which
 * is the one state where a security centre must never be quiet.
 */
public final class SecurityEvent {

    private SecurityEvent() {
    }

    /**
     * How a signal is being handled.
     *
     * <p>Every state here was set by a person. {@link #OPEN} is the state a
     * detector produces, and it is the only one.
     */
    public enum Resolution {
        /** Raised, nobody has looked yet. */
        OPEN,

        /** A person looked and judged it not to be a problem. */
        DISMISSED,

        /** A person confirmed something real and acted on it. */
        CONFIRMED,

        /**
         * Work is in progress.
         *
         * <p>Kept distinct from {@link #CONFIRMED} so an unresolved incident is
         * never counted as a closed one.
         */
        INVESTIGATING
    }

    /**
     * One detected signal.
     *
     * @param id identity, stable for the life of the record
     * @param organizationId the owning tenant; every read is scoped by this
     * @param type what was observed
     * @param subjectId the credential, session, or endpoint the signal concerns
     * @param subjectKind a label for what {@code subjectId} refers to, since
     *        these are different id spaces
     * @param detail human-readable context, already redacted
     * @param detectedAt when it was observed, not when it was stored
     * @param resolution current state, always human-set
     * @param resolvedBy who resolved it, null while open
     * @param resolvedAt when it was resolved, null while open
     * @param resolutionNote why, required whenever resolving
     */
    public record Record(
            UUID id,
            UUID organizationId,
            SecurityEventType type,
            UUID subjectId,
            String subjectKind,
            String detail,
            Instant detectedAt,
            Resolution resolution,
            UUID resolvedBy,
            Instant resolvedAt,
            String resolutionNote) {

        public boolean isOpen() {
            return resolution == Resolution.OPEN;
        }

        /**
         * Whether this finding has been dealt with.
         *
         * <p>Only {@link Resolution#DISMISSED} and {@link Resolution#CONFIRMED}
         * count. An investigation still in progress is not closed, and counting it
         * as such would let a long-running incident quietly drop off a dashboard.
         */
        public boolean isClosed() {
            return resolution == Resolution.DISMISSED || resolution == Resolution.CONFIRMED;
        }

        public static Record detected(UUID organizationId, SecurityEventType type, UUID subjectId,
                String subjectKind, String detail, Instant detectedAt) {
            if (organizationId == null) {
                // Defence in depth. A signal with no tenant could be read by the
                // wrong organization, so it is never constructible.
                throw new IllegalArgumentException("A security event requires an organization");
            }
            if (type == null) {
                throw new IllegalArgumentException("A security event requires a type");
            }
            return new Record(UUID.randomUUID(), organizationId, type, subjectId, subjectKind,
                    detail, detectedAt, Resolution.OPEN, null, null, null);
        }
    }
}