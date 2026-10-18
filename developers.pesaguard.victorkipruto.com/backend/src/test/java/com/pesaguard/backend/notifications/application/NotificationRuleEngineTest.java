package com.pesaguard.backend.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.domain.NotificationType;

class NotificationRuleEngineTest {

    private final NotificationRuleEngine rules = new NotificationRuleEngine();

    @Test
    void securityEventsHaveCriticalSeverityAndEmailRouting() {
        assertThat(rules.severityFor(NotificationType.CREDENTIAL_COMPROMISE))
                .isEqualTo(NotificationSeverity.SECURITY);
        assertThat(rules.channelsFor(NotificationType.CREDENTIAL_COMPROMISE,
                EnumSet.of(NotificationChannel.IN_APP)))
                .contains(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
    }

    @Test
    void unavailableProvidersAreNotSelectedByUserPreferences() {
        assertThat(rules.channelsFor(NotificationType.SUPPORT_TICKET_CREATED,
                EnumSet.of(NotificationChannel.IN_APP, NotificationChannel.SMS,
                        NotificationChannel.BROWSER_PUSH)))
                .containsExactly(NotificationChannel.IN_APP);
    }

    @Test
    void explicitlyConfiguredProvidersCanBeSelected() {
        assertThat(rules.channelsFor(NotificationType.SUPPORT_TICKET_CREATED,
                EnumSet.of(NotificationChannel.IN_APP, NotificationChannel.SMS),
                EnumSet.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL,
                        NotificationChannel.SMS)))
                .contains(NotificationChannel.SMS, NotificationChannel.IN_APP);
    }
}
