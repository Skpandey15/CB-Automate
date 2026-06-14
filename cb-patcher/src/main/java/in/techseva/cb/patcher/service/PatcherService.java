package in.techseva.cb.patcher.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.events.FixGeneratedEvent;
import in.techseva.cb.core.events.FixValidatedEvent;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.patcher.kafka.ValidatedFixKafkaPublisher;
import in.techseva.cb.patcher.service.BuildValidator.BuildResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PatcherService {

    private static final Logger log = LoggerFactory.getLogger(PatcherService.class);

    private final DiffApplier diffApplier;
    private final GradlePatcher gradlePatcher;
    private final BuildValidator buildValidator;
    private final FixRepository fixRepo;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final ValidatedFixKafkaPublisher validatedFixPublisher;
    private final String repoRoot;

    public PatcherService(DiffApplier diffApplier,
                          GradlePatcher gradlePatcher,
                          BuildValidator buildValidator,
                          FixRepository fixRepo,
                          VulnerabilityRepository vulnerabilityRepo,
                          ApplicationEventPublisher eventPublisher,
                          AuditService auditService,
                          ValidatedFixKafkaPublisher validatedFixPublisher,
                          @Value("${patcher.repo-root:/workspace/repo}") String repoRoot) {
        this.diffApplier = diffApplier;
        this.gradlePatcher = gradlePatcher;
        this.buildValidator = buildValidator;
        this.fixRepo = fixRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.validatedFixPublisher = validatedFixPublisher;
        this.repoRoot = repoRoot;
    }

    @Async("cbPatcherExecutor")
    @EventListener
    public void onFixGenerated(FixGeneratedEvent event) {
        Vulnerability vuln = event.getVulnerability();
        Fix fix = event.getFix();
        log.info("Patcher applying fix={} for vuln={}", fix.id(), vuln.id());

        try {
            diffApplier.applyDiff(repoRoot, fix.patchDiff());
            if (fix.gradlePatch() != null && !fix.gradlePatch().isBlank()) {
                gradlePatcher.applyGradlePatch(repoRoot, fix.gradlePatch());
            }

            BuildResult result = buildValidator.runBuild();
            Fix updated = fixRepo.save(fix.withBuildResult(result.success(), result.buildLog()));

            auditService.log(fix.id(), "Fix", result.success() ? "BUILD_VALIDATED" : "BUILD_FAILED",
                    "patcher", Map.of("exitSuccess", result.success()));

            if (result.success()) {
                vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FIX_VALIDATED));
                eventPublisher.publishEvent(new FixValidatedEvent(this, vuln, updated));
                validatedFixPublisher.publishValidated(updated, vuln);
            } else {
                vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FAILED));
                eventPublisher.publishEvent(new EscalationEvent(this, vuln, updated,
                        "Build failed. Log: " + result.buildLog()));
                log.warn("Build failed for fix={}: {}", fix.id(), result.buildLog());
            }
        } catch (Exception e) {
            log.error("Patcher failed for fix={}: {}", fix.id(), e.getMessage(), e);
            Fix failed = fixRepo.save(fix.withBuildResult(false, "PATCH ERROR: " + e.getMessage()));
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FAILED));
            eventPublisher.publishEvent(new EscalationEvent(this, vuln, failed,
                    "Patch application error: " + e.getMessage()));
        }
    }
}
