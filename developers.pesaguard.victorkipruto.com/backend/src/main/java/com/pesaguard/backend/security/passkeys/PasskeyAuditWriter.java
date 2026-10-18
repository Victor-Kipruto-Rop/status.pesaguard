package com.pesaguard.backend.security.passkeys;

import java.util.Map;
import java.util.UUID;
import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;

@Service
public class PasskeyAuditWriter {
    private final AuditService auditService;
    private final PasskeyUnattributedAuthenticationFailureRepository unattributedFailures;
    private final Clock clock;

    public PasskeyAuditWriter(
            AuditService auditService,
            PasskeyUnattributedAuthenticationFailureRepository unattributedFailures,
            Clock clock) {
        this.auditService = auditService;
        this.unattributedFailures = unattributedFailures;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID organizationId, UUID userId, String action, String result) {
        if (organizationId == null || userId == null) return;
        auditService.append(organizationId, userId, action, "passkey", userId.toString(),
                RequestContext.currentRequestId(), Map.of("result", result));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnattributedAuthenticationFailure() {
        unattributedFailures.save(new PasskeyUnattributedAuthenticationFailure(UUID.randomUUID(), clock.instant()));
    }
}
