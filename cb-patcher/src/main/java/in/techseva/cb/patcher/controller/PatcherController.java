package in.techseva.cb.patcher.controller;

import in.techseva.cb.patcher.service.BuildValidator.BuildResult;
import in.techseva.cb.patcher.service.PatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Backs cb-mcp-server's "buildProject" and "triggerRollback" MCP tools
 * (CBPatcherClient), which previously called these two endpoints with no
 * controller in this service to receive them.
 */
@RestController
@RequestMapping("/api/patcher")
public class PatcherController {

    private static final Logger log = LoggerFactory.getLogger(PatcherController.class);

    private final PatcherService patcherService;

    public PatcherController(PatcherService patcherService) {
        this.patcherService = patcherService;
    }

    public record BuildRequest(String branchName) {}
    public record RollbackRequest(String fixId, String branchName) {}

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> build(@RequestBody BuildRequest request) {
        if (request.branchName() == null || request.branchName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "branchName is required"));
        }
        try {
            BuildResult result = patcherService.buildBranch(request.branchName());
            return response(result);
        } catch (Exception e) {
            log.error("Build failed for branch={}: {}", request.branchName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody RollbackRequest request) {
        if (request.fixId() == null || request.fixId().isBlank()
                || request.branchName() == null || request.branchName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fixId and branchName are required"));
        }
        try {
            BuildResult result = patcherService.rollbackFix(request.fixId(), request.branchName());
            return response(result);
        } catch (Exception e) {
            log.error("Rollback failed for fixId={}: {}", request.fixId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> response(BuildResult result) {
        return ResponseEntity.ok(Map.of(
                "status", result.success() ? "success" : "build-failed",
                "buildLog", result.buildLog()));
    }
}
