package in.techseva.cb.api.controller;

import in.techseva.cb.api.domain.RemediationRun;
import in.techseva.cb.api.service.RemediationRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ADR-0002 Track A: {repoUrl, branch} in, a dependency-CVE-fix PR out --
 * wraps the existing scripts/dependency-remediation/remediate.py job
 * (ADR-0001) instead of requiring it to be run manually from the CLI.
 */
@RestController
@RequestMapping("/api/v1/remediation-runs")
@Tag(name = "Remediation Runs", description = "Trigger and track repo-URL dependency-CVE remediation runs")
@SecurityRequirement(name = "ApiKeyAuth")
public class RemediationRunController {

    private final RemediationRunService service;

    public RemediationRunController(RemediationRunService service) {
        this.service = service;
    }

    public record CreateRunRequest(String repoUrl, String branch, boolean publish, List<String> recipients) {}

    @PostMapping
    @Operation(summary = "Start a dependency-CVE remediation run for a GitHub repo and branch")
    public ResponseEntity<?> create(@RequestBody CreateRunRequest request) {
        if (!RemediationRunService.isValidRepo(request.repoUrl())) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl must be 'owner/repo'"));
        }
        if (!RemediationRunService.isValidBranch(request.branch())) {
            return ResponseEntity.badRequest().body(Map.of("error", "branch is invalid or missing"));
        }
        List<String> recipients = request.recipients() != null ? request.recipients() : List.of();
        if (request.publish()) {
            if (recipients.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "publish=true requires at least one recipient"));
            }
            for (String recipient : recipients) {
                if (!RemediationRunService.isValidRecipient(recipient)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "invalid recipient email: " + recipient));
                }
            }
        }

        RemediationRun run = service.startRun(request.repoUrl(), request.branch(), request.publish(), recipients);
        // Separate call from startRun's own persistence step -- must cross the
        // Spring proxy boundary (see RemediationRunService.startRun's Javadoc)
        // for @Async to actually apply instead of silently running inline.
        service.runAsync(run.runId(), run.repoUrl(), run.branch(), run.publish(), run.recipients());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get the status and result of a remediation run")
    public ResponseEntity<RemediationRun> get(@PathVariable String runId) {
        return service.findRun(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
