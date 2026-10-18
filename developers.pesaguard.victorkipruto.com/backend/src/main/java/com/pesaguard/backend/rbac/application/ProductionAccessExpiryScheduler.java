package com.pesaguard.backend.rbac.application;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.rbac.domain.ProductionAccessStatus;
import com.pesaguard.backend.rbac.infrastructure.ProductionAccessRequestRepository;

/** Moves elapsed live and suspended production grants to the terminal EXPIRED state. */
@Component
public class ProductionAccessExpiryScheduler {

    private static final int BATCH_SIZE = 500;
    private final ProductionAccessRequestRepository repository;
    private final Clock clock;

    public ProductionAccessExpiryScheduler(ProductionAccessRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.production-access.expiry-interval-ms:60000}")
    @Transactional
    public void expireDueGrants() {
        var now = clock.instant();
        var due = repository.findByStatusInAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                List.of(ProductionAccessStatus.ACTIVE, ProductionAccessStatus.SUSPENDED),
                now, PageRequest.of(0, BATCH_SIZE));
        due.forEach(request -> request.expireIfElapsed(now));
        repository.saveAll(due);
    }
}
