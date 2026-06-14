package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.FixValidatedEvent;
import in.techseva.cb.core.events.PRRaisedEvent;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.client.GitHubEnterpriseClient.GitPR;
import in.techseva.cb.pr.client.Version1Client;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
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
        log.info("PR service raising PR for fix={} vuln={}", fix.id(), vuln.id());

        try {
            String branchName = buildBranchName(vuln, fix);
            commitAndPush(branchName, vuln, fix);

            String prBody = buildPrBody(vuln, fix);
            String prTitle = buildPrTitle(vuln);

            GitPR pr = githubClient.createPullRequest(repoOwner, repoName, branchName, prTitle, prBody);
            if (pr == null) throw new RuntimeException("GitHub returned null PR");

            // Create Version1 ticket if not already exists
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

        } catch (Exception e) {
            log.error("PR creation failed for fix={}: {}", fix.id(), e.getMessage(), e);
            auditService.log(fix.id(), "Fix", "PR_FAILED", "pr-service",
                    Map.of("error", e.getMessage()));
        }
    }

    private void commitAndPush(String branchName, Vulnerability vuln, Fix fix)
            throws Exception {
        File repoDir = new File(repoRoot);
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
             Git git = new Git(repo)) {

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
                    .setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("token", githubToken))
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

    private String buildPrBody(Vulnerability vuln, Fix fix) {
        return "## Compliance Buddy — Automated Security Fix\n\n" +
                "| Field | Value |\n" +
                "|-------|-------|\n" +
                "| **CWE** | " + vuln.cweId() + " |\n" +
                "| **Severity** | " + vuln.severity() + " |\n" +
                "| **OWASP** | " + vuln.owaspCategory() + " |\n" +
                "| **File** | `" + vuln.filePath() + "` line " + vuln.lineNo() + " |\n" +
                "| **Confidence** | " + String.format("%.0f%%", fix.confidence() * 100) + " |\n" +
                "| **Strategy** | " + fix.strategy() + " |\n" +
                "| **Model** | " + fix.llmModel() + " |\n\n" +
                "### Explanation\n\n" + fix.explanation() + "\n\n" +
                "### Traceability (JSON-LD)\n\n```json\n" +
                ontologyMapper.vulnerabilityToJsonLdString(vuln) + "\n```\n\n" +
                "---\n*Generated by [Compliance Buddy](https://techseva.in/cb) — " +
                "do not merge without human review*";
    }
}
