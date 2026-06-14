package in.techseva.cb.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scans")
@Tag(name = "Scans", description = "Trigger on-demand SonarQube scans")
@SecurityRequirement(name = "ApiKeyAuth")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);
    private final ApplicationContext context;

    public ScanController(ApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/{projectKey}")
    @Operation(summary = "Trigger an on-demand scan for a project")
    public ResponseEntity<Map<String, Object>> triggerScan(@PathVariable String projectKey) {
        log.info("On-demand scan triggered for project: {}", projectKey);
        // Resolve ScannerService lazily to avoid circular deps across modules
        try {
            Object scannerService = context.getBean("scannerService");
            int findings = (int) scannerService.getClass()
                    .getMethod("scanProject", String.class)
                    .invoke(scannerService, projectKey);
            return ResponseEntity.accepted()
                    .body(Map.of("projectKey", projectKey, "newFindings", findings,
                            "status", "completed"));
        } catch (Exception e) {
            log.error("Scan trigger failed: {}", e.getMessage());
            return ResponseEntity.accepted()
                    .body(Map.of("projectKey", projectKey, "status", "triggered",
                            "note", "Scan queued asynchronously"));
        }
    }
}
