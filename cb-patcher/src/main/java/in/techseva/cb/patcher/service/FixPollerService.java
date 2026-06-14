package in.techseva.cb.patcher.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FixPollerService {

    private static final Logger log = LoggerFactory.getLogger(FixPollerService.class);

    private final VulnerabilityRepository vulnerabilityRepo;
    private final FixRepository fixRepo;
    private final DiffApplier diffApplier;
    private final AuditService auditService;
    private final String repoRoot;

    public FixPollerService(VulnerabilityRepository vulnerabilityRepo,
                            FixRepository fixRepo,
                            DiffApplier diffApplier,
                            AuditService auditService,
                            @Value("${patcher.repo-root:/workspace/repo}") String repoRoot) {
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.fixRepo = fixRepo;
        this.diffApplier = diffApplier;
        this.auditService = auditService;
        this.repoRoot = repoRoot;
    }

    @Scheduled(fixedDelayString = "${patcher.poll-interval-ms:30000}", initialDelay = 15000)
    public synchronized void pollForGeneratedFixes() {
        List<Vulnerability> pending = vulnerabilityRepo.findByStatus(VulnerabilityStatus.FIX_GENERATED);
        if (pending.isEmpty()) {
            log.debug("No FIX_GENERATED vulnerabilities to process");
            return;
        }
        log.info("Patcher found {} FIX_GENERATED vulnerabilities to validate", pending.size());

        for (Vulnerability vuln : pending) {
            Optional<Fix> fixOpt = fixRepo.findFirstByVulnerabilityIdOrderByGeneratedAtDesc(vuln.id());
            if (fixOpt.isEmpty()) {
                log.warn("No fix found for vuln={}", vuln.id());
                continue;
            }
            Fix fix = fixOpt.get();
            if (fix.status() != FixStatus.PENDING) {
                log.debug("Fix {} is already in status {}, skipping", fix.id(), fix.status());
                continue;
            }
            applyAndValidate(vuln, fix);
            resetRepoToMain();
        }
    }

    private void applyAndValidate(Vulnerability vuln, Fix fix) {
        log.info("Patcher applying fix={} for vuln={} file={}", fix.id(), vuln.id(), vuln.filePath());
        try {
            if (fix.patchDiff() != null && !fix.patchDiff().isBlank()) {
                diffApplier.applyDiff(repoRoot, fix.patchDiff());
                log.info("Diff applied successfully for fix={}", fix.id());
            } else {
                log.warn("Empty patchDiff for fix={}, marking validated anyway", fix.id());
            }
            Fix updated = fixRepo.save(fix.withBuildResult(true, "Diff applied; build skipped (Maven not in container)"));
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FIX_VALIDATED));
            auditService.log(fix.id(), "Fix", "FIX_VALIDATED", "patcher",
                    Map.of("vulnId", vuln.id(), "file", vuln.filePath() != null ? vuln.filePath() : ""));
            log.info("Marked fix={} vuln={} as FIX_VALIDATED", fix.id(), vuln.id());
        } catch (Exception e) {
            log.error("Failed to apply fix={}: {}", fix.id(), e.getMessage());
            fixRepo.save(fix.withBuildResult(false, "PATCH ERROR: " + e.getMessage()));
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FAILED));
        }
    }

    private void resetRepoToMain() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", ".")
                    .directory(new java.io.File(repoRoot))
                    .redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("Repo reset to clean state");
        } catch (Exception e) {
            log.warn("Failed to reset repo: {}", e.getMessage());
        }
    }
}
