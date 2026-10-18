package com.pesaguard.backend.changelog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pesaguard.backend.changelog.api.ChangelogEntryRequest;
import com.pesaguard.backend.changelog.domain.ChangelogEntry;
import com.pesaguard.backend.changelog.infrastructure.ChangelogEntryAuditRepository;
import com.pesaguard.backend.changelog.infrastructure.ChangelogEntryRepository;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;

@ExtendWith(MockitoExtension.class)
class ChangelogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private final UUID operatorId = UUID.randomUUID();

    @Mock
    private ChangelogEntryRepository repository;

    @Mock
    private ChangelogEntryAuditRepository auditRepository;

    private ChangelogService service;

    @BeforeEach
    void setUp() {
        service = new ChangelogService(repository, auditRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publicFeedContainsOnlyPublishedEntries() {
        when(repository.findByStatusOrderByPublishedAtDescIdDesc("PUBLISHED")).thenReturn(List.of());

        assertThat(service.published()).isEmpty();
    }

    @Test
    void operatorDraftsRequireReadCapabilityAndUseUpdatedOrder() {
        when(repository.findByStatusOrderByUpdatedAtDescIdDesc("DRAFT")).thenReturn(List.of());

        assertThat(service.drafts(operator(OperatorCapability.PLATFORM_CONFIG_READ))).isEmpty();

        verify(repository).findByStatusOrderByUpdatedAtDescIdDesc("DRAFT");
    }

    @Test
    void operatorDraftReadsRequireTheConfigurationReadCapability() {
        assertThatThrownBy(() -> service.drafts(operator(OperatorCapability.PLATFORM_CONFIG_WRITE)))
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);

        verifyNoInteractions(repository, auditRepository);
    }

    @Test
    void newEntriesAreDraftsUntilAnOperatorPublishesThem() {
        when(repository.saveAndFlush(any(ChangelogEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var draft = service.createDraft(
                new ChangelogEntryRequest("test-version", "Test changelog entry", "Test content.", "IMPROVEMENT"),
                operator(OperatorCapability.PLATFORM_CONFIG_WRITE));

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.publishedAt()).isNull();
        verify(repository).saveAndFlush(any(ChangelogEntry.class));
        verify(auditRepository).save(argThat(audit -> audit.getEntryId().equals(draft.id())
                && audit.getOperatorId().equals(operatorId)
                && audit.getAction().equals("DRAFT_CREATED")
                && audit.getReason().equals("Approved developer platform release notes")));
    }

    @Test
    void publishSetsAnAuditablePublicationTimestamp() {
        ChangelogEntry entry = ChangelogEntry.draft(
                "test-version", "Test changelog entry", "Test content.", "IMPROVEMENT", operatorId);
        when(repository.findByIdForUpdate(entry.getId())).thenReturn(Optional.of(entry));
        when(repository.saveAndFlush(entry)).thenReturn(entry);

        var published = service.publish(entry.getId(), operator(OperatorCapability.PLATFORM_CONFIG_WRITE));

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.publishedAt()).isEqualTo(NOW);
        verify(auditRepository).save(argThat(audit -> audit.getEntryId().equals(entry.getId())
                && audit.getAction().equals("PUBLISHED")
                && audit.getOccurredAt().equals(NOW)));
    }

    @Test
    void republishingAnEntryDoesNotChangeItsTimestampOrCreateDuplicateAudit() {
        ChangelogEntry entry = ChangelogEntry.draft(
                "1.2.0", "Webhook retries", "Delivery now retries.", "IMPROVEMENT", operatorId);
        entry.publish(NOW.minusSeconds(60));
        when(repository.findByIdForUpdate(entry.getId())).thenReturn(Optional.of(entry));
        when(repository.saveAndFlush(entry)).thenReturn(entry);

        var published = service.publish(entry.getId(), operator(OperatorCapability.PLATFORM_CONFIG_WRITE));

        assertThat(published.publishedAt()).isEqualTo(NOW.minusSeconds(60));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void operatorWritesRequireAReasonBeforePersisting() {
        AuthenticatedOperator withoutReason = new AuthenticatedOperator(operatorId, "release-editor",
                EnumSet.of(OperatorCapability.PLATFORM_CONFIG_WRITE), null);

        assertThatThrownBy(() -> service.createDraft(
                new ChangelogEntryRequest("1.2.0", "Title", "Body", "FIX"), withoutReason))
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);

        verifyNoInteractions(repository, auditRepository);
    }

    @Test
    void operatorWritesRequireTheConfigurationWriteCapability() {
        AuthenticatedOperator readOnly = operator(OperatorCapability.PLATFORM_CONFIG_READ);

        assertThatThrownBy(() -> service.createDraft(
                new ChangelogEntryRequest("1.2.0", "Title", "Body", "FIX"), readOnly))
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);

        verifyNoInteractions(repository, auditRepository);
    }

    @Test
    void updatingDraftRecordsTheOperatorReason() {
        ChangelogEntry entry = ChangelogEntry.draft(
                "1.2.0", "Original", "Draft content.", "FIX", operatorId);
        when(repository.findByIdForUpdate(entry.getId())).thenReturn(Optional.of(entry));
        when(repository.saveAndFlush(entry)).thenReturn(entry);

        service.updateDraft(entry.getId(),
                new ChangelogEntryRequest("1.2.1", "Revised", "Verified content.", "IMPROVEMENT"),
                operator(OperatorCapability.PLATFORM_CONFIG_WRITE));

        verify(auditRepository).save(argThat(audit -> audit.getEntryId().equals(entry.getId())
                && audit.getAction().equals("DRAFT_UPDATED")
                && audit.getReason().equals("Approved developer platform release notes")));
    }

    @Test
    void publishedEntriesCannotBeChangedByDraftEndpoint() {
        ChangelogEntry entry = ChangelogEntry.draft(
                "1.2.0", "Original", "Published content.", "FIX", operatorId);
        entry.publish(NOW);
        when(repository.findByIdForUpdate(entry.getId())).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.updateDraft(entry.getId(),
                new ChangelogEntryRequest("1.2.1", "Changed", "Changed content.", "FIX"),
                operator(OperatorCapability.PLATFORM_CONFIG_WRITE)))
                .isInstanceOf(com.pesaguard.backend.common.exception.BusinessException.class)
                .hasMessageContaining("immutable");

        verify(repository, org.mockito.Mockito.never()).saveAndFlush(any(ChangelogEntry.class));
        verifyNoInteractions(auditRepository);
    }

    private AuthenticatedOperator operator(OperatorCapability capability) {
        return new AuthenticatedOperator(operatorId, "release-editor", EnumSet.of(capability),
                "Approved developer platform release notes");
    }
}
