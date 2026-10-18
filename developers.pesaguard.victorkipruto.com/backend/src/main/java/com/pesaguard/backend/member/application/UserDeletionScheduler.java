package com.pesaguard.backend.member.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;

@Component
public class UserDeletionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDeletionScheduler.class);

    private final UserAccountRepository accountRepository;
    private final UserLifecycleService lifecycleService;
    private final Clock clock;

    public UserDeletionScheduler(
            UserAccountRepository accountRepository,
            UserLifecycleService lifecycleService,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.accounts.deletion-scan-interval-ms:3600000}")
    public void completeElapsedDeletions() {
        Instant cutoff = clock.instant().minus(UserLifecycleService.DELETION_GRACE_PERIOD);
        List<UserAccount> dueAccounts = accountRepository.findPendingDeletionBefore(
                UserStatus.PENDING_DELETION, cutoff);
        for (UserAccount account : dueAccounts) {
            try {
                lifecycleService.completeDeletion(account.getId());
            } catch (DataAccessException | BusinessException failure) {
                LOGGER.error("Could not complete scheduled account deletion for {}",
                        account.getId(), failure);
            }
        }
    }
}
