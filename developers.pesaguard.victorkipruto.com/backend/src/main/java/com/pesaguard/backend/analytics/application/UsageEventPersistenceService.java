package com.pesaguard.backend.analytics.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;

/**
 * Persists raw request events, tolerating duplicates.
 *
 * <p>Duplicate suppression lives in the database's unique constraint on
 * {@code request_id}, not in a read-then-write check here. A check would race:
 * two concurrent flushes would both find the row absent and both insert. The
 * constraint makes exactly one of them lose, which is correct.
 *
 * <p>Each event is written in its <b>own transaction</b> ({@code REQUIRES_NEW})
 * so that one rejected duplicate cannot roll back the rest of the batch. A
 * shared transaction would mean a single conflict discarding an entire flush,
 * silently losing unrelated usage data.
 */
@Service
public class UsageEventPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(UsageEventPersistenceService.class);

    private final ApiRequestEventRepository repository;

    public UsageEventPersistenceService(ApiRequestEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes one event, treating a duplicate request id as success.
     *
     * <p>A duplicate is the expected outcome of a retried request, not an error.
     * It is counted and logged at debug rather than warned about, so a client
     * retrying in a loop does not look like a system fault.
     *
     * @return true if newly stored, false if it was a duplicate
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean persist(ApiRequestEvent event) {
        try {
            repository.saveAndFlush(event);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // Almost always the request_id unique constraint. Could also be a bad
            // foreign key, which is a real fault and is logged more loudly below.
            if (isDuplicateRequest(duplicate)) {
                log.debug("duplicate usage event suppressed requestId={}", event.getRequestId());
                return false;
            }
            log.error("usage event rejected, not a duplicate requestId={} organizationId={}",
                    event.getRequestId(), event.getOrganizationId(), duplicate);
            throw duplicate;
        }
    }

    /**
     * Distinguishes an expected duplicate from a genuine integrity fault.
     *
     * <p>Both surface as {@link DataIntegrityViolationException}. Treating a
     * foreign-key failure as a harmless duplicate would silently discard real
     * usage data, which is the one outcome this system must never produce.
     */
    private boolean isDuplicateRequest(DataIntegrityViolationException failure) {
        String message = failure.getMostSpecificCause().getMessage();
        return message != null && message.contains("request_id");
    }

    /** Bulk write used by the flush path. */
    @Transactional
    public int persistAll(List<ApiRequestEvent> events) {
        int stored = 0;
        for (ApiRequestEvent event : events) {
            if (persist(event)) {
                stored++;
            }
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID requestId) {
        return repository.existsByRequestId(requestId.toString());
    }
}