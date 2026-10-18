package com.pesaguard.backend.member.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountPauseScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountPauseScheduler.class);

    private final UserLifecycleService lifecycleService;

    public AccountPauseScheduler(UserLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${pesaguard.account.pause-poll-ms:60000}")
    public void reactivateExpiredPauses() {
        int reactivated = lifecycleService.reactivateDueAccounts();
        if (reactivated > 0) {
            log.info("Automatically reactivated {} paused account(s)", reactivated);
        }
    }
}
