package com.pesaguard.backend.changelog.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.changelog.api.ChangelogEntryRequest;
import com.pesaguard.backend.changelog.api.ChangelogEntryView;
import com.pesaguard.backend.changelog.domain.ChangelogEntry;
import com.pesaguard.backend.changelog.domain.ChangelogEntryAudit;
import com.pesaguard.backend.changelog.infrastructure.ChangelogEntryAuditRepository;
import com.pesaguard.backend.changelog.infrastructure.ChangelogEntryRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;

@Service
public class ChangelogService {

    private final ChangelogEntryRepository repository;
    private final ChangelogEntryAuditRepository auditRepository;
    private final Clock clock;

    public ChangelogService(
            ChangelogEntryRepository repository,
            ChangelogEntryAuditRepository auditRepository,
            Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ChangelogEntryView> published() {
        return repository.findByStatusOrderByPublishedAtDescIdDesc("PUBLISHED")
                .stream().map(ChangelogService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<ChangelogEntryView> drafts(AuthenticatedOperator operator) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_READ);
        return repository.findByStatusOrderByUpdatedAtDescIdDesc("DRAFT")
                .stream().map(ChangelogService::toView).toList();
    }

    @Transactional
    public ChangelogEntryView createDraft(ChangelogEntryRequest request, AuthenticatedOperator operator) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        String reason = operator.requireReason();
        ChangelogEntry entry = ChangelogEntry.draft(
                request.version(), request.title(), request.body(), request.category(), operator.operatorId());
        ChangelogEntry saved = repository.saveAndFlush(entry);
        recordAudit(saved, operator, "DRAFT_CREATED", reason);
        return toView(saved);
    }

    @Transactional
    public ChangelogEntryView updateDraft(
            UUID entryId,
            ChangelogEntryRequest request,
            AuthenticatedOperator operator) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        String reason = operator.requireReason();
        ChangelogEntry entry = repository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Changelog entry"));
        if ("PUBLISHED".equals(entry.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CHANGELOG_ENTRY_PUBLISHED",
                    "Published changelog entries are immutable.");
        }
        entry.update(request.version(), request.title(), request.body(), request.category());
        ChangelogEntry saved = repository.saveAndFlush(entry);
        recordAudit(saved, operator, "DRAFT_UPDATED", reason);
        return toView(saved);
    }

    @Transactional
    public ChangelogEntryView publish(UUID entryId, AuthenticatedOperator operator) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        String reason = operator.requireReason();
        ChangelogEntry entry = repository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Changelog entry"));
        boolean alreadyPublished = "PUBLISHED".equals(entry.getStatus());
        entry.publish(clock.instant());
        ChangelogEntry saved = repository.saveAndFlush(entry);
        if (!alreadyPublished) {
            recordAudit(saved, operator, "PUBLISHED", reason);
        }
        return toView(saved);
    }

    private void recordAudit(
            ChangelogEntry entry,
            AuthenticatedOperator operator,
            String action,
            String reason) {
        auditRepository.save(ChangelogEntryAudit.record(
                entry.getId(), operator.operatorId(), operator.subject(), action, reason, clock.instant()));
    }

    private static ChangelogEntryView toView(ChangelogEntry entry) {
        return new ChangelogEntryView(entry.getId(), entry.getVersion(), entry.getTitle(),
                entry.getBody(), entry.getCategory(), entry.getStatus(),
                entry.getPublishedAt(), entry.getCreatedAt());
    }
}
