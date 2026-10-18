package com.pesaguard.backend.notifications.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.SafeExceptionDiagnostics;
import com.pesaguard.backend.notifications.infrastructure.NotificationQueueEventRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationRepository;

/** Processes queued events and retries due notification channels in batches. */
@Component
public class NotificationRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final NotificationRepository repository;
    private final NotificationQueueEventRepository queueRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    public NotificationRetryScheduler(NotificationRepository repository,
            NotificationQueueEventRepository queueRepository,
            NotificationService notificationService, Clock clock) {
        this.repository = repository;
        this.queueRepository = queueRepository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.notifications.retry-interval-ms:15000}")
    public void retryDue() {
        for (var id : repository.findDueRetryIds(clock.instant(), BATCH_SIZE)) {
            try {
                notificationService.retryDueChannels(id);
            } catch (RuntimeException failure) {
                log.error("Notification retry failed notificationId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${pesaguard.notifications.queue-interval-ms:1000}")
    public void processQueuedEvents() {
        for (var id : queueRepository.findReadyIds(clock.instant(), BATCH_SIZE)) {
            try {
                notificationService.processQueuedEvent(id);
            } catch (RuntimeException failure) {
                log.error("Notification event processing failed eventId={} errorType={} diagnostic={}",
                        id, failure.getClass().getSimpleName(),
                        SafeExceptionDiagnostics.stackTrace(failure));
                try {
                    notificationService.recordQueueFailure(id,
                            failure.getClass().getSimpleName());
                } catch (RuntimeException persistenceFailure) {
                    log.error("Notification event failure state could not be saved eventId={} errorType={} diagnostic={}",
                            id, persistenceFailure.getClass().getSimpleName(),
                            SafeExceptionDiagnostics.stackTrace(persistenceFailure));
                }
            }
        }
    }
}
