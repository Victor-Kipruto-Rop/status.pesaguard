package com.pesaguard.backend.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Preference safety.
 *
 * <p>The decision this encodes: a user's stated preference is honoured, but a
 * preference that would silence a security-critical event is <b>rejected</b> at
 * the point it is set, rather than silently overridden at send time. Silently
 * ignoring a request is worse than refusing it, because it leaves the user
 * believing they are covered when they are not.
 */
class NotificationPreferencesTest {

    private final UUID userId = UUID.randomUUID();

    private Map<NotificationCategory, Set<NotificationChannel>> disabled(
            NotificationCategory category, NotificationChannel... channels) {
        EnumMap<NotificationCategory, Set<NotificationChannel>> map =
                new EnumMap<>(NotificationCategory.class);
        map.put(category, EnumSet.copyOf(java.util.List.of(channels)));
        return map;
    }

    @Test
    void defaultsEnableOnlyConfiguredChannels() {
        NotificationPreferences preferences = NotificationPreferences.defaultsFor(userId);

        for (NotificationChannel channel : NotificationChannel.values()) {
            assertThat(preferences.isEnabled(NotificationType.CREDENTIAL_COMPROMISE, channel))
                    .isEqualTo(channel == NotificationChannel.EMAIL
                            || channel == NotificationChannel.IN_APP);
            assertThat(preferences.isEnabled(NotificationType.QUOTA_WARNING, channel))
                    .isEqualTo(channel == NotificationChannel.EMAIL
                            || channel == NotificationChannel.IN_APP);
        }
    }

    @Test
    void aMandatoryEventCannotHaveEmailDisabled() {
        assertThatThrownBy(() -> NotificationPreferences.of(userId,
                disabled(NotificationCategory.SECURITY, NotificationChannel.EMAIL)))
                .isInstanceOf(NotificationPreferences.UnsafePreferenceException.class)
                .hasMessageContaining("security-critical");
    }

    @Test
    void aRevokedKeyCannotBeSilencedThroughTheCredentialCategory() {
        // API_KEY_REVOKED is mandatory even though it lives in CREDENTIAL, which is
        // the case that would be missed by checking the category name alone.
        assertThatThrownBy(() -> NotificationPreferences.of(userId,
                disabled(NotificationCategory.CREDENTIAL, NotificationChannel.EMAIL)))
                .isInstanceOf(NotificationPreferences.UnsafePreferenceException.class);
    }

    @Test
    void aSuspendedProductionGrantCannotBeSilenced() {
        assertThatThrownBy(() -> NotificationPreferences.of(userId,
                disabled(NotificationCategory.PRODUCTION, NotificationChannel.EMAIL)))
                .isInstanceOf(NotificationPreferences.UnsafePreferenceException.class);
    }

    @Test
    void discretionaryCategoriesMayDisableEmail() {
        // Usage and webhook notifications are ordinary product mail; a user who
        // does not want them should be able to say so.
        NotificationPreferences preferences = NotificationPreferences.of(userId,
                disabled(NotificationCategory.USAGE, NotificationChannel.EMAIL));

        assertThat(preferences.isEnabled(NotificationType.QUOTA_WARNING,
                NotificationChannel.EMAIL)).isFalse();
        // In-app remains, and remains mandatory.
        assertThat(preferences.isEnabled(NotificationType.QUOTA_WARNING,
                NotificationChannel.IN_APP)).isTrue();
    }

    @Test
    void inAppCanNeverBeDisabled() {
        // It is the durable record: a notification nobody can read again did not
        // happen.
        assertThatThrownBy(() -> NotificationPreferences.of(userId,
                disabled(NotificationCategory.USAGE, NotificationChannel.IN_APP)))
                .isInstanceOf(NotificationPreferences.UnsafePreferenceException.class)
                .hasMessageContaining("durable record");
    }

    @Test
    void ofEnabledValidatesOnReadAsWellAsOnWrite() {
        // A row written by any other route must not be able to express an unsafe
        // preference either: the read path re-validates rather than trusting
        // stored data.
        EnumMap<NotificationCategory, Set<NotificationChannel>> unsafe =
                new EnumMap<>(NotificationCategory.class);
        unsafe.put(NotificationCategory.SECURITY, EnumSet.of(NotificationChannel.IN_APP));

        assertThatThrownBy(() -> NotificationPreferences.ofEnabled(userId, unsafe))
                .isInstanceOf(NotificationPreferences.UnsafePreferenceException.class);
    }

    @Test
    void categoryFlagsMatchTheCatalog() {
        // hasMandatoryEvents is derived, so a new mandatory event cannot be
        // forgotten here.
        assertThat(NotificationCategory.SECURITY.allowsDisablingEmail()).isFalse();
        assertThat(NotificationCategory.CREDENTIAL.allowsDisablingEmail()).isFalse();
        assertThat(NotificationCategory.PRODUCTION.allowsDisablingEmail()).isFalse();
        assertThat(NotificationCategory.USAGE.allowsDisablingEmail()).isTrue();
        assertThat(NotificationCategory.WEBHOOK.allowsDisablingEmail()).isTrue();
    }

    @Test
    void enabledChannelsRoundTripThroughTheStoredForm() {
        NotificationPreferences preferences = NotificationPreferences.of(userId,
                disabled(NotificationCategory.USAGE, NotificationChannel.EMAIL));

        var enabled = preferences.enabledByCategory();

        assertThat(enabled.get(NotificationCategory.USAGE))
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP);
        assertThat(enabled.get(NotificationCategory.SECURITY))
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL,
                        NotificationChannel.IN_APP);
    }

    @Test
    void emptyDisabledChannelSetKeepsEveryChannelEnabled() {
        EnumMap<NotificationCategory, Set<NotificationChannel>> disabled =
                new EnumMap<>(NotificationCategory.class);
        disabled.put(NotificationCategory.USAGE, Set.of());

        NotificationPreferences preferences = NotificationPreferences.of(userId, disabled);

        assertThat(preferences.enabledByCategory().get(NotificationCategory.USAGE))
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
    }
}
