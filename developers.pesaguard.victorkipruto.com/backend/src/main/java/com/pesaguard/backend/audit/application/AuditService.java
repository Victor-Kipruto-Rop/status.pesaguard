package com.pesaguard.backend.audit.application;

import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.audit.infrastructure.AuditEventRepository;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;

@Service
public class AuditService {

    private static final String GENESIS_HASH = "0".repeat(64);
    /**
     * Canonical form version.
     *
     * <p>Bumped to 3 when {@code projectId} was added to the signed fields. Adding
     * a field to a hash chain without a version bump would invalidate every
     * existing event hash, and an audit log that reports itself tampered on
     * upgrade is worse than one missing a column.
     */
    private static final short HASH_VERSION = 3;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AuditEventRepository auditEventRepository;
    private final OrganizationRepository organizationRepository;
    private final SecretKey auditKey;

    public AuditService(
            AuditEventRepository auditEventRepository,
            OrganizationRepository organizationRepository,
            @Qualifier("auditHmacKey") SecretKey auditKey) {
        this.auditEventRepository = auditEventRepository;
        this.organizationRepository = organizationRepository;
        this.auditKey = auditKey;
    }

    /**
     * Appends an event that belongs to no project.
     *
     * <p>Delegates with a null project. An organization-level change such as a
     * membership removal genuinely has no project, and forcing callers to pass a
     * placeholder to satisfy a signature would put a false attribution into the log.
     */
    @Transactional
    public AuditEvent append(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            UUID requestId,
            Map<String, ?> metadata) {
        return append(organizationId, null, actorUserId, action, resourceType, resourceId,
                requestId, metadata);
    }

    @Transactional
    public AuditEvent append(
            UUID organizationId,
            UUID projectId,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            UUID requestId,
            Map<String, ?> metadata) {
        Organization organization = organizationRepository.findByIdForAudit(organizationId)
                .orElseThrow(() -> new IllegalStateException("Organization missing while appending audit event"));
        long sequence = organization.nextAuditSequence();
        String previousHash = auditEventRepository
                .findTopByOrganizationIdOrderBySequenceNumberDesc(organizationId)
                .map(AuditEvent::getEventHash)
                .orElse(GENESIS_HASH);
        String canonicalMetadata = canonicalMetadata(metadata);
        UUID eventId = UUID.randomUUID();
        String ipAddress = RequestContext.currentRemoteAddress();
        String userAgent = RequestContext.currentUserAgent();
        String eventHash = CredentialCryptoService.hmacSha256(
                auditKey,
                canonical(eventId, organizationId, projectId, sequence, actorUserId, action,
                        resourceType, resourceId, requestId, ipAddress, userAgent,
                        canonicalMetadata, previousHash));
        AuditEvent event = new AuditEvent(
                eventId,
                organizationId,
                projectId,
                sequence,
                actorUserId,
                action,
                resourceType,
                resourceId,
                requestId,
                requestId,
                ipAddress,
                userAgent,
                HASH_VERSION,
                canonicalMetadata,
                previousHash,
                eventHash);
        // save, not saveAndFlush.
        //
        // The event hash is computed in memory and the sequence number comes from the
        // organization row already read above, so nothing here needs the insert to
        // have round-tripped. Flushing forces an extra round trip inside the
        // transaction, holding a connection longer for no benefit on a path that runs
        // on every security-sensitive action.
        return auditEventRepository.save(event);
    }

    private String canonicalMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        StringBuilder canonical = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : metadata.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            if (!first) {
                canonical.append(',');
            }
            first = false;
            appendJsonString(canonical, entry.getKey());
            canonical.append(':');
            Object value = entry.getValue();
            if (value == null || value instanceof Boolean || value instanceof Number) {
                canonical.append(value);
            } else {
                appendJsonString(canonical, String.valueOf(value));
            }
        }
        return canonical.append('}').toString();
    }

    private void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append(String.format("\\u%04x", codePoint));
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        });
        output.append('"');
    }

    private String canonical(
            UUID eventId,
            UUID organizationId,
            UUID projectId,
            long sequence,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            UUID requestId,
            String ipAddress,
            String userAgent,
            String metadata,
            String previousHash) {
        return String.join("\n",
                eventId.toString(),
                organizationId.toString(),
                projectId == null ? "" : projectId.toString(),
                Long.toString(sequence),
                actorUserId.toString(),
                action,
                resourceType,
                resourceId,
                requestId.toString(),
                ipAddress == null ? "" : ipAddress,
                userAgent == null ? "" : userAgent,
                metadata,
                previousHash);
    }
}
