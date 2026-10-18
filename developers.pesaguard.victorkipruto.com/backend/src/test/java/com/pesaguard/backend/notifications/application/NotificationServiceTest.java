package com.pesaguard.backend.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.notifications.infrastructure.NotificationPreferenceRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationQueueEventRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationQueueEventRepository queueRepository;
    @Mock
    private NotificationPreferenceRepository preferencesRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private NotificationTransport emailTransport;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        when(emailTransport.channel()).thenReturn(NotificationChannel.EMAIL);
        service = new NotificationService(notificationRepository, queueRepository,
                preferencesRepository, new NotificationRuleEngine(), List.of(emailTransport),
                Clock.systemUTC(), userRepository);
    }

    @Test
    void notifyWritesQueueItemWithoutCallingDeliveryTransport() {
        when(queueRepository.insertIfAbsent(any(UUID.class), any(UUID.class), any(UUID.class),
                anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), isNull(), any()))
                .thenReturn(1);
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UUID eventId = service.notify(organizationId, userId, NotificationType.API_KEY_CREATED,
                "API key created", "A key was created.");

        assertThat(eventId).isNotNull();
        verify(queueRepository).insertIfAbsent(any(UUID.class), org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq("API_KEY_CREATED"),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.eq("API key created"),
                org.mockito.ArgumentMatchers.eq("A key was created."), isNull(), isNull(), isNull(),
                isNull(), any());
        verify(emailTransport, never()).send(any(), any());
    }
}
