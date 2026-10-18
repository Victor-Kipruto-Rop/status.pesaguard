package com.pesaguard.backend.rbac.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.rbac.domain.ProductionAccessHistoryEntry;
import com.pesaguard.backend.rbac.domain.ProductionAccessStatus;

/**
 * One step in a request's review history.
 *
 * <p>Notes and evidence are returned verbatim as the reviewer wrote them. A
 * reviewer citing a ticket or a control mapping is making an audit claim, and
 * paraphrasing it would misrepresent the record.
 */
public record ProductionAccessHistoryView(
        UUID id,
        ProductionAccessStatus fromStatus,
        ProductionAccessStatus toStatus,
        UUID actorId,
        String note,
        String evidence,
        Instant recordedAt) {

    public static ProductionAccessHistoryView from(ProductionAccessHistoryEntry entry) {
        return new ProductionAccessHistoryView(entry.getId(), entry.getFromStatus(),
                entry.getToStatus(), entry.getActorId(), entry.getNote(), entry.getEvidence(),
                entry.getRecordedAt());
    }
}