package com.pesaguard.backend.notifications.application;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.DeliveryState;

/**
 * Sends a notification on one channel.
 *
 * <p>The seam where a real transport attaches. Implementations must be honest
 * about failure: an implementation that reports success without sending turns a
 * missed security notification into a silent lie.
 */
public interface NotificationTransport {

    NotificationChannel channel();

    /**
     * Attempts delivery.
     *
     * @return success, or a failure reason. Never throws for an expected failure;
     *         a thrown exception is treated as a failure by the caller either way.
     */
    DeliveryAttemptResult send(Notification notification, String recipient);

    /**
     * The outcome of one attempt.
     *
     * @param success whether the transport accepted the attempt
     * @param successState the state to record when successful
     * @param error a bounded, non-sensitive failure reason; null on success
     */
    record DeliveryAttemptResult(boolean success, DeliveryState successState,
            boolean transientFailure, String error) {

        public static DeliveryAttemptResult delivered() {
            return new DeliveryAttemptResult(true, DeliveryState.DELIVERED, false, null);
        }

        public static DeliveryAttemptResult accepted() {
            return new DeliveryAttemptResult(true, DeliveryState.ACCEPTED, false, null);
        }

        /**
         * A failure worth retrying: a transport fault, not a bad address.
         *
         * <p>Distinguished from {@link #permanentFailure(String)} because
         * retrying an invalid address forever helps nobody.
         */
        public static DeliveryAttemptResult transientFailure(String error) {
            return new DeliveryAttemptResult(false, null, true, error);
        }

        /** A failure that will not improve: a malformed or unknown address. */
        public static DeliveryAttemptResult permanentFailure(String error) {
            return new DeliveryAttemptResult(false, null, false, error);
        }

        public boolean isTransient() {
            return !success && transientFailure;
        }
    }
}