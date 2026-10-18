package com.pesaguard.backend.golive.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GoLiveVerificationWorker {

    private final GoLiveService goLiveService;

    public GoLiveVerificationWorker(GoLiveService goLiveService) {
        this.goLiveService = goLiveService;
    }

    @Scheduled(fixedDelayString = "${pesaguard.golive.verification-poll-ms:1000}")
    public void processNext() {
        goLiveService.processNextVerificationJob();
    }
}
