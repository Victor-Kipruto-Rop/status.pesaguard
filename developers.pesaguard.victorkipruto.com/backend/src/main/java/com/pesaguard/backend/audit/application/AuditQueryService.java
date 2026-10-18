package com.pesaguard.backend.audit.application;

import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.audit.infrastructure.AuditEventRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class AuditQueryService {

    private final AuditEventRepository repository;
    private final CredentialCryptoService crypto;
    private final AuthorizationService authorizationService;
    private final SecretKey auditKey;

    public AuditQueryService(AuditEventRepository repository, CredentialCryptoService crypto,
            AuthorizationService authorizationService,
            @Qualifier("auditHmacKey") SecretKey auditKey) {
        this.repository = repository;
        this.crypto = crypto;
        this.authorizationService = authorizationService;
        this.auditKey = auditKey;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> list(AuthenticatedUser principal, int page, int size) {
        authorizationService.requirePermission(principal, Permission.AUDIT_READ);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return repository.findByOrganizationIdOrderBySequenceNumberDesc(
                principal.organizationId(), PageRequest.of(Math.max(page, 0), safeSize));
    }

    @Transactional(readOnly = true)
    public String exportCsv(AuthenticatedUser principal, int limit) {
        authorizationService.requirePermission(principal, Permission.AUDIT_READ);
        if (limit < 1 || limit > 10_000) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "AUDIT_EXPORT_LIMIT_INVALID",
                    "Export limit must be between 1 and 10000 records.");
        }
        var events = repository.findByOrganizationIdOrderBySequenceNumberDesc(
                principal.organizationId(), PageRequest.of(0, limit)).getContent();
        StringBuilder csv = new StringBuilder(
                "sequence,action,resource_type,resource_id,actor_user_id,request_id,created_at\r\n");
        for (AuditEvent event : events) {
            csv.append(event.getSequenceNumber()).append(',')
                    .append(csvCell(event.getAction())).append(',')
                    .append(csvCell(event.getResourceType())).append(',')
                    .append(csvCell(event.getResourceId())).append(',')
                    .append(csvCell(event.getActorUserId().toString())).append(',')
                    .append(csvCell(event.getRequestId() == null ? "" : event.getRequestId().toString())).append(',')
                    .append(csvCell(event.getCreatedAt().toString())).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        int first = 0;
        while (first < safe.length() && Character.isWhitespace(safe.charAt(first))) {
            first++;
        }
        if (first < safe.length() && "=+-@".indexOf(safe.charAt(first)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    @Transactional(readOnly = true)
    public boolean verifyChain(AuthenticatedUser principal) {
        authorizationService.requirePermission(principal, Permission.AUDIT_READ);
        List<AuditEvent> events = repository.findByOrganizationIdOrderBySequenceNumberAsc(principal.organizationId());
        String previousHash = "0".repeat(64);
        long expectedSequence = 1;
        for (AuditEvent event : events) {
            if (event.getSequenceNumber() != expectedSequence
                    || !constantTime(previousHash, event.getPreviousHash())) {
                return false;
            }
            String canonical = canonical(event);
            if (!constantTime(event.getEventHash(), CredentialCryptoService.hmacSha256(auditKey, canonical))) {
                return false;
            }
            previousHash = event.getEventHash();
            expectedSequence++;
        }
        return true;
    }

    /**
     * The canonical form an event was hashed under.
     *
     * <p>Explicit per version rather than "anything that is not v1 is current":
     * that shortcut makes every historical row fail verification the moment a new
     * version is added, and a chain that reports itself tampered after an upgrade
     * is indistinguishable from a real tamper.
     */
    private String canonical(AuditEvent event) {
        if (event.getHashVersion() == 1) {
            return String.join("\n",
                    event.getId().toString(), event.getOrganizationId().toString(),
                    Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                    event.getAction(), event.getResourceType(), event.getResourceId(),
                    event.getRequestId().toString(), event.getMetadata(), event.getPreviousHash());
        }
        if (event.getHashVersion() == 2) {
            return String.join("\n",
                event.getId().toString(), event.getOrganizationId().toString(),
                Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getRequestId().toString(),
                event.getIpAddress() == null ? "" : event.getIpAddress(),
                event.getUserAgent() == null ? "" : event.getUserAgent(),
                event.getMetadata(), event.getPreviousHash());
        }
        // v3 adds the project the event concerns to the signed fields.
        return String.join("\n",
                event.getId().toString(), event.getOrganizationId().toString(),
                event.getProjectId() == null ? "" : event.getProjectId().toString(),
                Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getRequestId().toString(),
                event.getIpAddress() == null ? "" : event.getIpAddress(),
                event.getUserAgent() == null ? "" : event.getUserAgent(),
                event.getMetadata(), event.getPreviousHash());
    }

    private boolean constantTime(String left, String right) {
        return left != null && right != null && crypto.constantTimeEquals(left, right);
    }
}
