package in.techseva.cb.scanner.controller;

import in.techseva.cb.scanner.service.ScannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scanner")
public class ScanTriggerController {

    private final ScannerService scannerService;

    public ScanTriggerController(ScannerService scannerService) {
        this.scannerService = scannerService;
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
}
