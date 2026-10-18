package com.pesaguard.backend.analytics.algorithm.alerting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Escalation scheduling for unacknowledged alerts.
 *
 * <p>An alert that no one acknowledges must not sit silently. Escalation walks an
 * alert through a ladder of levels - on-call, then team lead, then manager - advancing
 * one level each time an acknowledgement deadline passes. This guarantees that an
 * unattended alert eventually reaches someone accountable, which is the difference
 * between an alert and a gap in coverage.
 *
 * <p>Escalation is monotonic: an alert only moves up the ladder, never back down, even
 * if it is acknowledged late. Once escalated, the higher tier has been paged and that
 * cannot be undone. Acknowledgement stops further escalation but does not reset the
 * level, so the record of who was actually paged is preserved for the post-incident
 * review.
 *
 * <p>Levels are ordered by delay: each level names the delay after which it fires and
 * the target it notifies. The scheduler is deterministic given the alert's age and its
 * current level, and takes "now" as a parameter so it can be driven by event time or
 * tested without a real clock.
 */
public final class EscalationScheduler {

    private EscalationScheduler() {
    }

    /** One rung of the escalation ladder. */
    public record EscalationLevel(long delayMillis, String target, String channel) {
    }

    /** An alert being tracked through escalation. */
    public record EscalationState(
            String alertId,
            int currentLevel,
            String currentTarget,
            boolean acknowledged,
            long lastEscalatedMillis) {
    }

    /**
     * Determine the level an alert of the given age should be at.
     *
     * <p>Finds the highest level whose delay has elapsed. Level 0 is the first target
     * and always applies once any level exists, so an alert starts at level 0 and
     * climbs as its age exceeds each successive delay.
     *
     * @param ladder levels sorted by ascending delay
     * @param ageMillis time since the alert first fired
     */
    public static int levelForAge(List<EscalationLevel> ladder, long ageMillis) {
        List<EscalationLevel> sorted = new ArrayList<>(ladder);
        sorted.sort(Comparator.comparingLong(EscalationLevel::delayMillis));
        int level = 0;
        for (EscalationLevel rung : sorted) {
            if (ageMillis >= rung.delayMillis()) {
                level++;
            }
        }
        return Math.min(level, Math.max(sorted.size() - 1, 0));
    }

    /**
     * Advance an alert to the level its age warrants, unless it is acknowledged.
     *
     * <p>Returns the same state unchanged when acknowledged - escalation stops but the
     * level is not reset. Returns a new state at the appropriate level otherwise. The
     * {@code lastEscalatedMillis} is set to {@code nowMillis} whenever the level
     * actually increases, so a caller can avoid re-paging a target that was just
     * contacted.
     */
    public static EscalationState escalate(EscalationState current, List<EscalationLevel> ladder,
                                          long ageMillis, long nowMillis) {
        if (current.acknowledged()) {
            return current;
        }
        int targetLevel = levelForAge(ladder, ageMillis);
        if (targetLevel <= current.currentLevel()) {
            return current;
        }
        List<EscalationLevel> sorted = new ArrayList<>(ladder);
        sorted.sort(Comparator.comparingLong(EscalationLevel::delayMillis));
        EscalationLevel rung = sorted.get(targetLevel);
        return new EscalationState(current.alertId(), targetLevel, rung.target(),
                false, nowMillis);
    }

    /**
     * Acknowledge an alert, halting further escalation without lowering its level.
     *
     * <p>Acknowledgement records who was reached and stops the clock; it does not
     * pretend the earlier escalations did not happen.
     */
    public static EscalationState acknowledge(EscalationState current) {
        return new EscalationState(current.alertId(), current.currentLevel(),
                current.currentTarget(), true, current.lastEscalatedMillis());
    }
}
