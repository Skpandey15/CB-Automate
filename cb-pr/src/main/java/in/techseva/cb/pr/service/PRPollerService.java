package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.client.GitHubEnterpriseClient.GitPR;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PRPollerService {

    private static final Logger log = LoggerFactory.getLogger(PRPollerService.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

    private final VulnerabilityRepository vulnerabilityRepo;
    private final FixRepository fixRepo;
    private final GitHubEnterpriseClient githubClient;
    private final OntologyMapper ontologyMapper;
    private final AuditService auditService;
    private final AIReviewService aiReviewService;
    private final String repoRoot;
    private final String repoOwner;
    private final String repoName;
    private final String githubToken;
    private final String reviewerUsername;

    public PRPollerService(VulnerabilityRepository vulnerabilityRepo,
                           FixRepository fixRepo,
                           GitHubEnterpriseClient githubClient,
                           OntologyMapper ontologyMapper,
                           AuditService auditService,
                           AIReviewService aiReviewService,
                           @Value("${patcher.repo-root:/workspace/repo}") String repoRoot,
                           @Value("${github.owner}") String repoOwner,
                           @Value("${github.repo}") String repoName,
                           @Value("${github.token}") String githubToken,
                           @Value("${github.reviewer-username:}") String reviewerUsername) {
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.fixRepo = fixRepo;
        this.githubClient = githubClient;
        this.ontologyMapper = ontologyMapper;
        this.auditService = auditService;
        this.aiReviewService = aiReviewService;
        this.repoRoot = repoRoot;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.githubToken = githubToken;
        this.reviewerUsername = reviewerUsername;
    }

    @Scheduled(fixedDelayString = "${pr.poll-interval-ms:60000}", initialDelay = 20000)
    public synchronized void pollForValidatedFixes() {
        List<Vulnerability> pending = vulnerabilityRepo.findByStatus(VulnerabilityStatus.FIX_VALIDATED);
        if (pending.isEmpty()) {
            log.debug("No FIX_VALIDATED vulnerabilities to raise PRs for");
            return;
        }
        log.info("PR service found {} FIX_VALIDATED vulnerabilities", pending.size());

        for (Vulnerability vuln : pending) {
            Optional<Fix> fixOpt = fixRepo.findFirstByVulnerabilityIdOrderByGeneratedAtDesc(vuln.id());
            if (fixOpt.isEmpty()) { log.warn("No fix for vuln={}", vuln.id()); continue; }
            Fix fix = fixOpt.get();
            if (fix.status() != FixStatus.BUILD_VALIDATED) {
                log.debug("Fix {} status={}, skipping", fix.id(), fix.status()); continue;
            }
            raisePR(vuln, fix);
        }
    }

    private void raisePR(Vulnerability vuln, Fix fix) {
        log.info("Raising PR for fix={} vuln={}", fix.id(), vuln.id());
        String branchName = "cb/fix-" + (vuln.cweId() != null ? vuln.cweId().toLowerCase().replace(":", "-") : "unknown")
                + "-" + fix.id().substring(0, Math.min(12, fix.id().length()));
        try {
            resetRepo();
            applyDiff(repoRoot, fix.patchDiff());

            boolean pushed = commitAndPush(branchName, vuln, fix);
            if (!pushed) {
                log.warn("Nothing to push for fix={} (diff may be empty or file not found), skipping PR", fix.id());
                // Still mark as PR_RAISED with a note so we don't retry endlessly
                vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.PR_RAISED));
                return;
            }

            String prTitle = "[CB] Fix " + vuln.cweId() + " in " +
                    (vuln.filePath() != null ? vuln.filePath().substring(Math.max(0, vuln.filePath().lastIndexOf('/') + 1)) : "unknown") +
                    " (severity: " + vuln.severity() + ")";
            String prBody = buildPrBody(vuln, fix);
            GitPR pr = null;
            try {
                pr = githubClient.createPullRequest(repoOwner, repoName, branchName, prTitle, prBody);
            } catch (Exception prEx) {
                // PR already exists is acceptable — mark as raised anyway
                if (prEx.getMessage() != null && prEx.getMessage().contains("already exists")) {
                    log.warn("PR already exists for branch {}, marking vuln as PR_RAISED anyway", branchName);
                    vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.PR_RAISED));
                    return;
                }
                throw prEx;
            }
            if (pr == null) throw new RuntimeException("GitHub returned null PR");
            fixRepo.save(fix.withPrDetails(pr.htmlUrl(), pr.number(), branchName));
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.PR_RAISED));
            auditService.log(fix.id(), "Fix", "PR_RAISED", "pr-service",
                    Map.of("prNumber", pr.number(), "prUrl", pr.htmlUrl()));
            log.info("PR #{} raised: {}", pr.number(), pr.htmlUrl());

            // Fire-and-forget: reviewer requests and AI review run in background
            // so the scheduler thread is not blocked by network/GPT calls
            final int prNumber = pr.number();
            final String prUrl = pr.htmlUrl();
            final Vulnerability vulnFinal = vuln;
            final Fix fixFinal = fix;
            CompletableFuture.runAsync(() -> {
                requestReviewers(prNumber);
                aiReviewService.reviewAndPost(vulnFinal, fixFinal, prNumber, prUrl);
            }).exceptionally(ex -> {
                log.error("Async reviewer/review failed for PR #{}: {}", prNumber, ex.getMessage(), ex);
                return null;
            });

        } catch (Exception e) {
            log.error("PR creation failed for fix={}: {}", fix.id(), e.getMessage(), e);
            auditService.log(fix.id(), "Fix", "PR_FAILED", "pr-service", Map.of("error", e.getMessage()));
        }
    }

    private void requestReviewers(int prNumber) {
        // Human reviewer
        if (reviewerUsername != null && !reviewerUsername.isBlank()) {
            githubClient.requestReviewers(repoOwner, repoName, prNumber, List.of(reviewerUsername));
        }
        // GitHub Copilot code review (may fail gracefully if not enabled on the repo)
        githubClient.requestReviewers(repoOwner, repoName, prNumber, List.of("github-copilot"));
    }

    private boolean commitAndPush(String branchName, Vulnerability vuln, Fix fix) throws Exception {
        File repoDir = new File(repoRoot);
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
             Git git = new Git(repo)) {

            // Check if there are any changes to commit
            var status = git.status().call();
            if (status.getModified().isEmpty() && status.getAdded().isEmpty()
                    && status.getUntracked().isEmpty() && status.getChanged().isEmpty()) {
                log.warn("No changes in working tree for fix={}", fix.id());
                return false;
            }

            // Delete local branch if it already exists from a previous run
            try { git.branchDelete().setBranchNames(branchName).setForce(true).call(); } catch (Exception ignored) {}

            git.checkout().setCreateBranch(true).setName(branchName).call();
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("fix(" + vuln.cweId() + "): Auto-remediated by Compliance Buddy\n\n" +
                            "Vulnerability: " + vuln.sonarIssueKey() + "\n" +
                            "Fix: " + fix.id() + "\n" +
                            "Confidence: " + fix.confidence())
                    .setAuthor("Compliance Buddy", "cb-bot@techseva.in")
                    .setCommitter("Compliance Buddy", "cb-bot@techseva.in")
                    .call();
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", githubToken))
                    .setRemote("origin")
                    .call();
            log.info("Pushed branch {} to remote", branchName);
            return true;
        }
    }

    private void resetRepo() {
        try {
            File repoDir = new File(repoRoot);
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(new File(repoDir, ".git"))
                    .build();
                 Git git = new Git(repo)) {
                git.checkout().setName("main").call();
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
                git.clean().setCleanDirectories(true).setForce(true).call();
            }
            log.debug("Repo reset to main/HEAD");
        } catch (Exception e) {
            log.warn("Repo reset failed: {}", e.getMessage());
        }
    }

    private void applyDiff(String repoRoot, String unifiedDiff) throws Exception {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return;
        List<FilePatch> patches = parseDiff(unifiedDiff);
        for (FilePatch patch : patches) {
            Path targetFile = Paths.get(repoRoot, patch.filePath());
            if (!Files.exists(targetFile)) { log.warn("File not found: {}", targetFile); continue; }
            List<String> lines = new ArrayList<>(Files.readAllLines(targetFile));
            applyHunks(lines, patch.hunks());
            Files.write(targetFile, lines);
            log.info("Applied patch to {}", targetFile);
        }
    }

    private List<FilePatch> parseDiff(String diff) throws Exception {
        List<FilePatch> patches = new ArrayList<>();
        String currentFile = null;
        List<Hunk> hunks = new ArrayList<>();
        List<String> hunkLines = null;
        int hunkStart = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(diff))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher fm = FILE_HEADER.matcher(line);
                if (fm.matches()) {
                    if (currentFile != null && hunkLines != null) hunks.add(new Hunk(hunkStart, new ArrayList<>(hunkLines)));
                    if (currentFile != null) patches.add(new FilePatch(currentFile, new ArrayList<>(hunks)));
                    currentFile = fm.group(1); hunks = new ArrayList<>(); hunkLines = null; continue;
                }
                Matcher hm = HUNK_HEADER.matcher(line);
                if (hm.matches()) {
                    if (hunkLines != null) hunks.add(new Hunk(hunkStart, new ArrayList<>(hunkLines)));
                    hunkStart = Integer.parseInt(hm.group(1)); hunkLines = new ArrayList<>(); continue;
                }
                if (hunkLines != null && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")))
                    hunkLines.add(line);
            }
        }
        if (currentFile != null) { if (hunkLines != null) hunks.add(new Hunk(hunkStart, hunkLines)); patches.add(new FilePatch(currentFile, hunks)); }
        return patches;
    }

    private void applyHunks(List<String> lines, List<Hunk> hunks) {
        int offset = 0;
        for (Hunk h : hunks) {
            int idx = Math.max(0, h.startLine() - 1 + offset);
            List<String> nl = new ArrayList<>(); int consumed = 0;
            for (String hl : h.lines()) {
                if (hl.startsWith("+")) nl.add(hl.substring(1));
                else if (hl.startsWith("-")) consumed++;
                else if (hl.startsWith(" ")) { nl.add(hl.substring(1)); consumed++; }
            }
            lines.subList(idx, Math.min(idx + consumed, lines.size())).clear();
            lines.addAll(idx, nl);
            offset += nl.size() - consumed;
        }
    }

    private String buildPrBody(Vulnerability vuln, Fix fix) {
        return "## Compliance Buddy -- Automated Security Fix\n\n" +
                "| Field | Value |\n|-------|-------|\n" +
                "| **CWE** | " + vuln.cweId() + " |\n" +
                "| **Severity** | " + vuln.severity() + " |\n" +
                "| **OWASP** | " + vuln.owaspCategory() + " |\n" +
                "| **File** | `" + vuln.filePath() + "` line " + vuln.lineNo() + " |\n" +
                "| **Confidence** | " + String.format("%.0f%%", fix.confidence() * 100) + " |\n" +
                "| **Strategy** | " + fix.strategy() + " |\n" +
                "| **Model** | " + fix.llmModel() + " |\n\n" +
                "### Explanation\n\n" + fix.explanation() + "\n\n" +
                "---\n*Generated by Compliance Buddy -- do not merge without human review*";
    }

    record FilePatch(String filePath, List<Hunk> hunks) {}
    record Hunk(int startLine, List<String> lines) {}
}
