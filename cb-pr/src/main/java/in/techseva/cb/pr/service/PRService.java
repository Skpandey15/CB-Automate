package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.FixValidatedEvent;
import in.techseva.cb.core.events.PRRaisedEvent;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.patch.DiffApplier;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.client.GitHubEnterpriseClient.GitPR;
import in.techseva.cb.pr.client.Version1Client;
import in.techseva.cb.pr.kafka.FeedbackKafkaPublisher;
import in.techseva.cb.pr.service.OpaGovernanceService.GovernanceResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
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

@Service
public class PRService {

    private static final Logger log = LoggerFactory.getLogger(PRService.class);

    private final GitHubEnterpriseClient githubClient;
    private final Version1Client version1Client;
    private final OntologyMapper ontologyMapper;
    private final OpaGovernanceService opaGovernance;
    private final FeedbackKafkaPublisher feedbackPublisher;
    private final FixRepository fixRepo;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final String repoOwner;
    private final String repoName;
    private final String repoRoot;
    private final String githubToken;

    public PRService(GitHubEnterpriseClient githubClient,
                     Version1Client version1Client,
                     OntologyMapper ontologyMapper,
                     OpaGovernanceService opaGovernance,
                     FeedbackKafkaPublisher feedbackPublisher,
                     FixRepository fixRepo,
                     VulnerabilityRepository vulnerabilityRepo,
                     ApplicationEventPublisher eventPublisher,
                     AuditService auditService,
                     @Value("${github.owner}") String repoOwner,
                     @Value("${github.repo}") String repoName,
                     @Value("${patcher.repo-root:/workspace/repo}") String repoRoot,
                     @Value("${github.token}") String githubToken) {
        this.githubClient = githubClient;
        this.version1Client = version1Client;
        this.ontologyMapper = ontologyMapper;
        this.opaGovernance = opaGovernance;
        this.feedbackPublisher = feedbackPublisher;
        this.fixRepo = fixRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.repoRoot = repoRoot;
        this.githubToken = githubToken;
    }

    @Async("cbPrExecutor")
    @EventListener
    public void onFixValidated(FixValidatedEvent event) {
        Vulnerability vuln = event.getVulnerability();
        Fix fix = event.getFix();
        log.info("PR service: OPA check + PR creation for fix={} vuln={}", fix.id(), vuln.id());

        // OPA governance gate — must pass before PR is created
        GovernanceResult governance = opaGovernance.evaluate(vuln, fix);
        if (!governance.allowed()) {
            log.warn("OPA governance BLOCKED PR for fix={}: violations={}", fix.id(), governance.violations());
            auditService.log(fix.id(), "Fix", "OPA_BLOCKED", "pr-service",
                    Map.of("violations", governance.violations()));
            feedbackPublisher.publishRejected(fix, vuln, governance.violations());
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FAILED));
            return;
        }

        try {
            String branchName = buildBranchName(vuln, fix);
            commitAndPush(branchName, vuln, fix);

            String prBody = buildPrBody(vuln, fix, governance);
            String prTitle = buildPrTitle(vuln);

            GitPR pr = githubClient.createPullRequest(repoOwner, repoName, branchName, prTitle, prBody);
            if (pr == null) throw new RuntimeException("GitHub returned null PR");

            String v1TaskId = vuln.version1TaskId();
            if (v1TaskId == null) {
                v1TaskId = version1Client.createSecurityTask(
                        prTitle,
                        "Auto-remediated by Compliance Buddy. PR: " + pr.htmlUrl(),
                        vuln.severity() != null ? vuln.severity().name() : "UNKNOWN",
                        vuln.cweId());
            }
            version1Client.updateTaskStatus(v1TaskId, "In Progress", pr.htmlUrl());

            Fix updated = fixRepo.save(fix.withPrDetails(pr.htmlUrl(), pr.number(), branchName));
            Vulnerability vulnUpdated = vulnerabilityRepo.save(
                    vuln.withVersion1TaskId(v1TaskId).withStatus(VulnerabilityStatus.PR_RAISED));

            auditService.log(fix.id(), "Fix", "PR_RAISED", "pr-service",
                    Map.of("prNumber", pr.number(), "prUrl", pr.htmlUrl()));
            eventPublisher.publishEvent(new PRRaisedEvent(this, vulnUpdated, updated));

            // Publish ACCEPTED feedback — PR was successfully raised (human will review)
            feedbackPublisher.publishAccepted(updated, vulnUpdated);

        } catch (Exception e) {
            log.error("PR creation failed for fix={}: {}", fix.id(), e.getMessage(), e);
            auditService.log(fix.id(), "Fix", "PR_FAILED", "pr-service",
                    Map.of("error", e.getMessage()));
        }
    }

    private void commitAndPush(String branchName, Vulnerability vuln, Fix fix) throws Exception {
        File repoDir = new File(repoRoot);
        UsernamePasswordCredentialsProvider creds =
                new UsernamePasswordCredentialsProvider("token", githubToken);

        if (!new File(repoDir, ".git").exists()) {
            String cloneUrl = "https://github.com/" + repoOwner + "/" + repoName + ".git";
            log.info("Cloning {} into {}", cloneUrl, repoDir);
            Git.cloneRepository()
                    .setURI(cloneUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(creds)
                    .call()
                    .close();
            log.info("Clone complete");
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
             Git git = new Git(repo)) {

            git.checkout().setName("main").call();
            git.pull().setCredentialsProvider(creds).call();

            if (fix.patchDiff() != null && !fix.patchDiff().isBlank()) {
                new DiffApplier().applyDiff(repoRoot, fix.patchDiff());
                log.info("Applied patchDiff for fix={}", fix.id());
            }

            git.checkout().setCreateBranch(true).setName(branchName).call();
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("fix(" + vuln.cweId() + "): " + buildPrTitle(vuln) +
                            "\n\nAuto-remediated by Compliance Buddy\n" +
                            "Vulnerability: " + vuln.sonarIssueKey() + "\n" +
                            "Fix: " + fix.id() + "\n" +
                            "Confidence: " + fix.confidence())
                    .setAuthor("Compliance Buddy", "cb-bot@techseva.in")
                    .call();
            git.push()
                    .setCredentialsProvider(creds)
                    .setRemote("origin")
                    .call();
            log.info("Pushed branch {} to remote", branchName);
        }
    }

    private String buildBranchName(Vulnerability vuln, Fix fix) {
        String cweClean = vuln.cweId() != null ? vuln.cweId().toLowerCase().replace(":", "-") : "unknown";
        return "cb/fix-" + cweClean + "-" + fix.id().substring(0, Math.min(8, fix.id().length()));
    }

    private String buildPrTitle(Vulnerability vuln) {
        return "[CB] Fix " + vuln.cweId() + " in " +
                (vuln.filePath() != null ? vuln.filePath().substring(
                        Math.max(0, vuln.filePath().lastIndexOf('/') + 1)) : "unknown") +
                " (severity: " + vuln.severity() + ")";
    }

    private String buildPrBody(Vulnerability vuln, Fix fix, GovernanceResult governance) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Compliance Buddy — Automated Security Fix\n\n");
        sb.append("| Field | Value |\n|-------|-------|\n");
        sb.append("| **CWE** | ").append(vuln.cweId()).append(" |\n");
        sb.append("| **Severity** | ").append(vuln.severity()).append(" |\n");
        sb.append("| **OWASP** | ").append(vuln.owaspCategory()).append(" |\n");
        sb.append("| **File** | `").append(vuln.filePath()).append("` line ").append(vuln.lineNo()).append(" |\n");
        sb.append("| **Confidence** | ").append(String.format("%.0f%%", fix.confidence() * 100)).append(" |\n");
        sb.append("| **Strategy** | ").append(fix.strategy()).append(" |\n");
        sb.append("| **Model** | ").append(fix.llmModel()).append(" |\n");
        sb.append("| **OPA** | ✅ Passed (").append(governance.violations().size()).append(" violations) |\n\n");
        sb.append("### Explanation\n\n").append(fix.explanation()).append("\n\n");
        sb.append("### Traceability (JSON-LD)\n\n```json\n");
        sb.append(ontologyMapper.vulnerabilityToJsonLdString(vuln));
        sb.append("\n```\n\n---\n*Generated by [Compliance Buddy](https://techseva.in/cb) — ");
        sb.append("do not merge without human review*");
        return sb.toString();
    }
}
