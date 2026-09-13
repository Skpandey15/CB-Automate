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
import jakarta.annotation.PostConstruct;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;
import java.util.Optional;

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
    private final String githubOwner;
    private final String githubRepo;
    private final String githubToken;

    public PatcherService(DiffApplier diffApplier,
                          GradlePatcher gradlePatcher,
                          BuildValidator buildValidator,
                          FixRepository fixRepo,
                          VulnerabilityRepository vulnerabilityRepo,
                          ApplicationEventPublisher eventPublisher,
                          AuditService auditService,
                          ValidatedFixKafkaPublisher validatedFixPublisher,
                          @Value("${patcher.repo-root:/workspace/repo}") String repoRoot,
                          @Value("${github.owner:}") String githubOwner,
                          @Value("${github.repo:}") String githubRepo,
                          @Value("${github.token:}") String githubToken) {
        this.diffApplier = diffApplier;
        this.gradlePatcher = gradlePatcher;
        this.buildValidator = buildValidator;
        this.fixRepo = fixRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.validatedFixPublisher = validatedFixPublisher;
        this.repoRoot = repoRoot;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.githubToken = githubToken;
    }

    @PostConstruct
    public void ensureRepoCloned() {
        if (githubOwner.isBlank() || githubRepo.isBlank()) {
            log.warn("github.owner/repo not configured — patcher will not have repo available");
            return;
        }
        File repoDir = new File(repoRoot);
        if (new File(repoDir, ".git").exists()) {
            log.info("Repo already cloned at {}", repoRoot);
            return;
        }
        String cloneUrl = "https://github.com/" + githubOwner + "/" + githubRepo + ".git";
        log.info("Cloning {} into {}", cloneUrl, repoRoot);
        try {
            var cloneCmd = Git.cloneRepository().setURI(cloneUrl).setDirectory(repoDir);
            if (!githubToken.isBlank()) {
                cloneCmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("token", githubToken));
            }
            cloneCmd.call().close();
            log.info("Clone complete: {}", repoRoot);
        } catch (Exception e) {
            log.error("Failed to clone repo {}: {}", cloneUrl, e.getMessage(), e);
        }
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

    /**
     * Checks out {@code branchName} in the shared repo workspace and runs a
     * build against it. Backs the MCP "buildProject" tool, which previously
     * called an endpoint that did not exist in this service.
     */
    public synchronized BuildResult buildBranch(String branchName) throws Exception {
        checkoutBranch(branchName);
        return buildValidator.runBuild();
    }

    /**
     * Reverts the tip commit of {@code branchName} (git revert, not reset —
     * preserves history), pushes the revert, marks the fix REJECTED, and
     * rebuilds to confirm the revert is clean. Backs the MCP
     * "triggerRollback" tool, which previously called an endpoint that did
     * not exist in this service.
     */
    public synchronized BuildResult rollbackFix(String fixId, String branchName) throws Exception {
        checkoutBranch(branchName);

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoRoot, ".git"))
                .build();
             Git git = new Git(repo)) {

            RevCommit tip = git.log().setMaxCount(1).call().iterator().next();
            git.revert().include(tip).call();
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", githubToken))
                    .setRemote("origin")
                    .call();
            log.info("Reverted tip commit {} on branch {} for fix={}", tip.getName(), branchName, fixId);
        }

        Optional<Fix> fixOpt = fixRepo.findById(fixId);
        fixOpt.ifPresent(fix -> fixRepo.save(fix.withStatus(FixStatus.REJECTED)));

        return buildValidator.runBuild();
    }

    private void checkoutBranch(String branchName) throws Exception {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoRoot, ".git"))
                .build();
             Git git = new Git(repo)) {

            var creds = new UsernamePasswordCredentialsProvider("token", githubToken);
            git.fetch().setCredentialsProvider(creds).call();

            var checkout = git.checkout().setName(branchName);
            if (repo.findRef(branchName) == null) {
                checkout.setCreateBranch(true).setStartPoint("origin/" + branchName);
            }
            checkout.call();
            git.pull().setCredentialsProvider(creds).call();
        }
    }
}
