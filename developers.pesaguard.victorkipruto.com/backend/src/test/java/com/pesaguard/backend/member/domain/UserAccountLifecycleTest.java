package com.pesaguard.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Account lifecycle rules.
 *
 * <p>The deletion tests exist to pin down the grace period, because the failure
 * mode is silent and unrecoverable: a deletion that completes immediately cannot
 * be walked back once the row is gone.
 */
class UserAccountLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final Duration GRACE = Duration.ofDays(30);

    private UserAccount account() {
        return UserAccount.create("dev@example.com", "Dev", "hash");
    }

    // --- Email verification -------------------------------------------------

    @Test
    void aNewAccountHasNotVerifiedItsEmail() {
        assertThat(account().isEmailVerified()).isFalse();
    }

    @Test
    void verifyingRecordsTheTimeAndClearsTheOutstandingToken() {
        UserAccount account = account();
        account.beginEmailVerification("hash-1", T0);

        account.verifyEmail(T0);

        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.getEmailVerifiedAt()).isEqualTo(T0);
        assertThat(account.matchesEmailVerificationHash("hash-1")).isFalse();
    }

    @Test
    void reissuingInvalidatesThePreviousToken() {
        UserAccount account = account();
        account.beginEmailVerification("hash-1", T0);
        account.beginEmailVerification("hash-2", T0);

        // Two live tokens would leave an address the developer abandoned
        // still verifiable afterwards.
        assertThat(account.matchesEmailVerificationHash("hash-1")).isFalse();
        assertThat(account.matchesEmailVerificationHash("hash-2")).isTrue();
    }

    @Test
    void aBlankTokenHashIsRejected() {
        UserAccount account = account();

        assertThatThrownBy(() -> account.beginEmailVerification("  ", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnverifiedAccountMatchesNoTokenWhenNoneIsOutstanding() {
        // Guards a null-pointer path: matchesEmailVerificationHash must tolerate
        // a null stored hash, not only a mismatched one.
        assertThat(account().matchesEmailVerificationHash("anything")).isFalse();
    }

    @Test
    void clearingVerificationAllowsTheAddressToBeVerifiedAgain() {
        UserAccount account = account();
        account.beginEmailVerification("hash-1", T0);
        account.verifyEmail(T0);

        account.clearEmailVerification(T0);

        assertThat(account.isEmailVerified()).isFalse();
    }

    // --- Deletion -----------------------------------------------------------

    @Test
    void requestingDeletionKeepsTheAccountUsableDuringTheGracePeriod() {
        UserAccount account = account();

        account.requestDeletion(T0);

        // Deliberate: a deletion discovered a week late must be recoverable.
        assertThat(account.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        assertThat(account.getStatus().canAuthenticate()).isTrue();
    }

    @Test
    void deletionDoesNotCompleteBeforeTheGracePeriodElapses() {
        UserAccount account = account();
        account.requestDeletion(T0);

        boolean completed = account.completeDeletionIfElapsed(T0.plus(GRACE).minusSeconds(1), GRACE);

        assertThat(completed).isFalse();
        assertThat(account.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
    }

    @Test
    void deletionCompletesOnceTheGracePeriodElapses() {
        UserAccount account = account();
        account.requestDeletion(T0);

        boolean completed = account.completeDeletionIfElapsed(T0.plus(GRACE), GRACE);

        assertThat(completed).isTrue();
        assertThat(account.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(account.getStatus().isTerminal()).isTrue();
    }

    @Test
    void aPendingDeletionCanBeCancelledDuringTheGracePeriod() {
        UserAccount account = account();
        account.requestDeletion(T0);

        account.cancelDeletion(T0.plusSeconds(60));

        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.getDeletionRequestedAt()).isNull();
    }

    @Test
    void cancellationIsRejectedWhenNoDeletionIsPending() {
        UserAccount account = account();

        assertThatThrownBy(() -> account.cancelDeletion(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletingTwiceIsRejected() {
        UserAccount account = account();
        account.requestDeletion(T0);

        assertThatThrownBy(() -> account.requestDeletion(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anElapsedDeletionIsNotRunTwiceByARepeatedSweep() {
        UserAccount account = account();
        account.requestDeletion(T0);
        account.completeDeletionIfElapsed(T0.plus(GRACE), GRACE);

        // A scheduled sweep must be safe to run repeatedly.
        boolean second = account.completeDeletionIfElapsed(T0.plus(GRACE).plusSeconds(1), GRACE);

        assertThat(second).isFalse();
        assertThat(account.getStatus()).isEqualTo(UserStatus.DELETED);
    }

    @Test
    void theCompletionTimeIsVisibleWhileDeletionIsPending() {
        UserAccount account = account();
        account.requestDeletion(T0);

        assertThat(account.deletionCompletesAt(GRACE))
                .contains(T0.plus(GRACE));
    }

    @Test
    void thereIsNoCompletionTimeWhenNoDeletionIsPending() {
        assertThat(account().deletionCompletesAt(GRACE)).isEmpty();
    }

    @Test
    void aSuspendedAccountCanStillCompleteItsOwnDeletion() {
        UserAccount account = account();
        account.suspend(T0);
        account.requestDeletion(T0);

        // A developer suspended for abuse must not be trapped: if they cannot
        // sign in, they can never remove themselves.
        assertThat(account.getStatus().canCompleteDeletion()).isTrue();
    }

    // --- Terminality -------------------------------------------------------

    @Test
    void deletedIsTheOnlyTerminalStatus() {
        assertThat(UserStatus.DELETED.isTerminal()).isTrue();
        assertThat(UserStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(UserStatus.SUSPENDED.isTerminal()).isFalse();
        assertThat(UserStatus.DEACTIVATED.isTerminal()).isFalse();
        assertThat(UserStatus.PENDING_DELETION.isTerminal()).isFalse();
    }

    @Test
    void aSuspendedAccountCannotAuthenticate() {
        assertThat(UserStatus.SUSPENDED.canAuthenticate()).isFalse();
    }

    @Test
    void aDeletedAccountCannotAuthenticate() {
        assertThat(UserStatus.DELETED.canAuthenticate()).isFalse();
    }

    // --- Suspension, restoration, deactivation ------------------------------

    @Test
    void suspensionBlocksAuthenticationAndIsReversible() {
        UserAccount account = account();

        account.suspend(T0);
        assertThat(account.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(account.getStatus().canAuthenticate()).isFalse();

        account.restore(T0.plusSeconds(60));
        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.getStatus().canAuthenticate()).isTrue();
    }

    @Test
    void aDeletedAccountCannotBeSuspended() {
        UserAccount account = account();
        account.requestDeletion(T0);
        account.completeDeletionIfElapsed(T0.plus(GRACE), GRACE);

        assertThatThrownBy(() -> account.suspend(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeletedAccountCannotBeRestored() {
        UserAccount account = account();
        account.requestDeletion(T0);
        account.completeDeletionIfElapsed(T0.plus(GRACE), GRACE);

        // Terminal by design: reinstating would resurrect a developer who asked
        // to be forgotten.
        assertThatThrownBy(() -> account.restore(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoringRequiresCancellingAPendingDeletionFirst() {
        UserAccount account = account();
        account.requestDeletion(T0);

        assertThatThrownBy(() -> account.restore(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeactivatedAccountCannotAuthenticate() {
        UserAccount account = account();

        account.deactivate(T0);

        assertThat(account.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(account.getStatus().canAuthenticate()).isFalse();
        assertThat(account.isActive()).isFalse();
    }

    @Test
    void aDeactivatedAccountCanBeRestored() {
        UserAccount account = account();
        account.deactivate(T0);

        account.restore(T0.plusSeconds(60));

        assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
