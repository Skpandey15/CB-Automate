package in.techseva.cb.api.controller;

import in.techseva.cb.api.client.ScannerClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scans")
@Tag(name = "Scans", description = "Trigger on-demand SonarQube scans")
@SecurityRequirement(name = "ApiKeyAuth")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);
    private final ScannerClient scannerClient;

    public ScanController(ScannerClient scannerClient) {
        this.scannerClient = scannerClient;
    }

    @PostMapping("/{projectKey}")
    @Operation(summary = "Trigger an on-demand scan for a project")
    public ResponseEntity<Map<String, Object>> triggerScan(@PathVariable String projectKey) {
        log.info("On-demand scan triggered for project: {}", projectKey);
        try {
            int findings = scannerClient.triggerScan(projectKey);
            return ResponseEntity.accepted()
                    .body(Map.of("projectKey", projectKey, "newFindings", findings,
                            "status", "completed"));
        } catch (ScannerClient.ScannerUnavailableException e) {
            log.error("Scan trigger failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("projectKey", projectKey, "status", "failed",
                            "error", "cb-scanner is unreachable or returned an error"));
        }
    }
}
