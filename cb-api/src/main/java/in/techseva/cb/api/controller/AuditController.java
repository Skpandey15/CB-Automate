package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.AuditEvent;
import in.techseva.cb.core.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Immutable audit trail (90-day TTL)")
@SecurityRequirement(name = "ApiKeyAuth")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(summary = "List all audit events, newest first")
    public ResponseEntity<List<AuditEvent>> list() {
        return ResponseEntity.ok(auditService.findAll());
    }

    @GetMapping("/entity/{entityId}")
    @Operation(summary = "Get audit events for a specific entity")
    public ResponseEntity<List<AuditEvent>> byEntity(@PathVariable String entityId) {
        return ResponseEntity.ok(auditService.findByEntityId(entityId));
    }
}
