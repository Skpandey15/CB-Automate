package in.techseva.cb.scanner.controller;

import in.techseva.cb.scanner.dependency.DependencyScannerService;
import in.techseva.cb.scanner.service.ScannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scanner")
public class ScanTriggerController {

    private final ScannerService scannerService;
    private final DependencyScannerService dependencyScannerService;

    public ScanTriggerController(ScannerService scannerService,
                                  DependencyScannerService dependencyScannerService) {
        this.scannerService = scannerService;
        this.dependencyScannerService = dependencyScannerService;
    }

    @PostMapping("/scan/{projectKey}")
    public ResponseEntity<Map<String, Object>> triggerScan(@PathVariable String projectKey) {
        int findings = scannerService.scanProject(projectKey);
        return ResponseEntity.accepted()
                .body(Map.of("projectKey", projectKey, "newFindings", findings, "status", "completed"));
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerAllScans() {
        scannerService.scheduledScan();
        return ResponseEntity.accepted()
                .body(Map.of("status", "completed", "message", "All configured projects scanned"));
    }

    /**
     * Trigger an on-demand dependency vulnerability scan of the configured repo root.
     * Parses build.gradle files → queries OSS Index → creates vulnerability records.
     *
     * POST /api/v1/scanner/deps
     * POST /api/v1/scanner/deps/{projectKey}
     */
    @PostMapping({"/deps", "/deps/{projectKey}"})
    public ResponseEntity<Map<String, Object>> triggerDepScan(
            @PathVariable(required = false) String projectKey) {
        String key = projectKey != null ? projectKey : "gradle-project";
        int findings = dependencyScannerService.scanDependencies(key);
        return ResponseEntity.accepted()
                .body(Map.of("projectKey", key, "newFindings", findings,
                             "type", "DEPENDENCY", "status", "completed"));
    }
}
