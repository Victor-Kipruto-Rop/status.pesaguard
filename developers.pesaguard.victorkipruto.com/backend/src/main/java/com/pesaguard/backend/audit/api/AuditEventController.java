package com.pesaguard.backend.audit.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.audit.application.AuditQueryService;
import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditQueryService auditQueryService;

    public AuditEventController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    ApiResponse<PageResponse<AuditEventView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<AuditEvent> result = auditQueryService.list(principal, page, size);
        List<AuditEventView> items = result.getContent().stream().map(this::toView).toList();
        return ApiResponse.of(PageResponse.of(items, result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<String> export(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1000") int limit) {
        String csv = auditQueryService.exportCsv(principal, limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pesaguard-audit.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    @GetMapping("/verify")
    ApiResponse<AuditVerificationView> verify(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(new AuditVerificationView(auditQueryService.verifyChain(principal)));
    }

    private AuditEventView toView(AuditEvent event) {
        return new AuditEventView(event.getId(), event.getSequenceNumber(), event.getActorUserId(),
                event.getAction(), event.getResourceType(), event.getResourceId(), event.getRequestId(),
                event.getCorrelationId(), event.getIpAddress(), event.getUserAgent(), event.getHashVersion(),
                event.getMetadata(), event.getPreviousHash(), event.getEventHash(), event.getCreatedAt());
    }

    public record AuditVerificationView(boolean valid) {
    }
}
