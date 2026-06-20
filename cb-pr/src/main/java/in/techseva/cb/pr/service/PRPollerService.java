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
import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    public void ensureRepoCloned() {
        if (repoOwner.isBlank() || repoName.isBlank()) return;
        File repoDir = new File(repoRoot);
        if (new File(repoDir, ".git").exists()) {
            log.info("Repo already cloned at {}", repoRoot);
            return;
        }
        String cloneUrl = "https://github.com/" + repoOwner + "/" + repoName + ".git";
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

    /**
     * Polls for all FIX_VALIDATED vulnerabilities and raises ONE single PR
     * containing all fixes combined on a single branch.
     */
    @Scheduled(fixedDelayString = "${pr.poll-interval-ms:60000}", initialDelay = 20000)
    public synchronized void pollForValidatedFixes() {
        List<Vulnerability> pending = vulnerabilityRepo.findByStatus(VulnerabilityStatus.FIX_VALIDATED);
        if (pending.isEmpty()) {
            log.debug("No FIX_VALIDATED vulnerabilities to raise PRs for");
            return;
        }
        log.info("PR service found {} FIX_VALIDATED vulnerabilities — bundling into ONE PR", pending.size());

        // Collect all fixes that are ready
        List<VulnFixPair> pairs = new ArrayList<>();
        for (Vulnerability vuln : pending) {
            Optional<Fix> fixOpt = fixRepo.findFirstByVulnerabilityIdOrderByGeneratedAtDesc(vuln.id());
            if (fixOpt.isEmpty()) { log.warn("No fix for vuln={}", vuln.id()); continue; }
            Fix fix = fixOpt.get();
            if (fix.status() != FixStatus.BUILD_VALIDATED) {
                log.debug("Fix {} status={}, skipping", fix.id(), fix.status()); continue;
            }
            pairs.add(new VulnFixPair(vuln, fix));
        }

        if (pairs.isEmpty()) {
            log.info("No BUILD_VALIDATED fixes ready yet");
            return;
        }

        raiseBatchPR(pairs);
    }

    private void raiseBatchPR(List<VulnFixPair> pairs) {
        String branchName = "cb/security-fixes-batch";
        log.info("Raising single batch PR on branch {} for {} fixes", branchName, pairs.size());

        try {
            resetRepo();

            // Apply all diffs to the working tree
            int applied = 0;
            for (VulnFixPair pair : pairs) {
                try {
                    applyDiff(repoRoot, pair.fix().patchDiff(), pair.vuln().filePath());
                    applied++;
                } catch (Exception e) {
                    log.warn("Could not apply diff for vuln={}: {}", pair.vuln().id(), e.getMessage());
                }
            }

            boolean pushed = commitAndPush(branchName, pairs);
            if (!pushed) {
                log.warn("Nothing to push — no diffs produced file changes. Marking all as PR_RAISED to avoid retry.");
                for (VulnFixPair pair : pairs) {
                    vulnerabilityRepo.save(pair.vuln().withStatus(VulnerabilityStatus.PR_RAISED));
                }
                return;
            }

            String prTitle = "[CB] Security Fixes — " + pairs.size() + " vulnerabilities auto-remediated";
            String prBody  = buildBatchPrBody(pairs);

            GitPR pr;
            try {
                pr = githubClient.createPullRequest(repoOwner, repoName, branchName, prTitle, prBody);
            } catch (Exception prEx) {
                if (prEx.getMessage() != null && prEx.getMessage().contains("already exists")) {
                    log.warn("PR already exists for branch {}, marking all vulns as PR_RAISED", branchName);
                    for (VulnFixPair pair : pairs) {
                        vulnerabilityRepo.save(pair.vuln().withStatus(VulnerabilityStatus.PR_RAISED));
                    }
                    return;
                }
                throw prEx;
            }
            if (pr == null) throw new RuntimeException("GitHub returned null PR");

            // Mark all vulns and fixes as PR_RAISED
            for (VulnFixPair pair : pairs) {
                fixRepo.save(pair.fix().withPrDetails(pr.htmlUrl(), pr.number(), branchName));
                vulnerabilityRepo.save(pair.vuln().withStatus(VulnerabilityStatus.PR_RAISED));
                auditService.log(pair.fix().id(), "Fix", "PR_RAISED", "pr-service",
                        Map.of("prNumber", pr.number(), "prUrl", pr.htmlUrl()));
            }
            log.info("Batch PR #{} raised with {} fixes: {}", pr.number(), pairs.size(), pr.htmlUrl());

            final int prNumber = pr.number();
            final String prUrl = pr.htmlUrl();
            CompletableFuture.runAsync(() -> requestReviewers(prNumber))
                .exceptionally(ex -> { log.error("Reviewer request failed: {}", ex.getMessage()); return null; });

        } catch (Exception e) {
            log.error("Batch PR creation failed: {}", e.getMessage(), e);
            for (VulnFixPair pair : pairs) {
                auditService.log(pair.fix().id(), "Fix", "PR_FAILED", "pr-service",
                        Map.of("error", e.getMessage()));
            }
        }
    }

    private void requestReviewers(int prNumber) {
        if (reviewerUsername != null && !reviewerUsername.isBlank()) {
            githubClient.requestReviewers(repoOwner, repoName, prNumber, List.of(reviewerUsername));
        }
        githubClient.requestReviewers(repoOwner, repoName, prNumber, List.of("github-copilot"));
    }

    private boolean commitAndPush(String branchName, List<VulnFixPair> pairs) throws Exception {
        File repoDir = new File(repoRoot);
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
             Git git = new Git(repo)) {

            var status = git.status().call();
            if (status.getModified().isEmpty() && status.getAdded().isEmpty()
                    && status.getUntracked().isEmpty() && status.getChanged().isEmpty()) {
                log.warn("No changes in working tree after applying {} diffs", pairs.size());
                return false;
            }

            // Delete branch if it already exists from a prior run
            try { git.branchDelete().setBranchNames(branchName).setForce(true).call(); } catch (Exception ignored) {}

            git.checkout().setCreateBranch(true).setName(branchName).call();
            git.add().addFilepattern(".").call();

            String commitMsg = buildCommitMessage(pairs);
            git.commit()
                    .setMessage(commitMsg)
                    .setAuthor("Compliance Buddy", "cb-bot@techseva.in")
                    .setCommitter("Compliance Buddy", "cb-bot@techseva.in")
                    .call();
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", githubToken))
                    .setRemote("origin")
                    .setForce(true)
                    .call();
            log.info("Pushed branch {} with {} fixes to remote", branchName, pairs.size());
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

    private String stripCodeFences(String diff) {
        String trimmed = diff.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1).stripLeading();
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) trimmed = trimmed.substring(0, lastFence).stripTrailing();
        }
        return trimmed;
    }

    private void applyDiff(String repoRoot, String unifiedDiff, String fallbackFilePath) throws Exception {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return;
        List<FilePatch> patches = parseDiff(stripCodeFences(unifiedDiff), fallbackFilePath);
        Path repoPath = Paths.get(repoRoot);
        for (FilePatch patch : patches) {
            Path targetFile = resolveFile(repoPath, patch.filePath());
            if (targetFile == null) {
                log.warn("File not found anywhere under {}: {}", repoRoot, patch.filePath());
                continue;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(targetFile));
            applyHunks(lines, patch.hunks());
            Files.write(targetFile, lines);
            log.info("Applied patch to {}", targetFile);
        }
    }

    private Path resolveFile(Path repoRoot, String diffPath) throws Exception {
        Path direct = repoRoot.resolve(diffPath);
        if (Files.exists(direct)) return direct;
        try (var children = Files.list(repoRoot)) {
            java.util.Optional<Path> found = children.filter(Files::isDirectory)
                .map(sub -> sub.resolve(diffPath)).filter(Files::exists).findFirst();
            if (found.isPresent()) return found.get();
        }
        String fileName = Paths.get(diffPath).getFileName().toString();
        try (var walk = Files.walk(repoRoot)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName))
                .filter(p -> p.toString().replace('\\', '/').endsWith(diffPath.replace('\\', '/')))
                .findFirst().orElse(null);
        }
    }

    private List<FilePatch> parseDiff(String diff, String fallbackFilePath) throws Exception {
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
                    if (currentFile == null && fallbackFilePath != null) {
                        log.debug("No +++ b/ header found, using fallback filePath: {}", fallbackFilePath);
                        currentFile = fallbackFilePath;
                        hunks = new ArrayList<>();
                    }
                    if (hunkLines != null) hunks.add(new Hunk(hunkStart, new ArrayList<>(hunkLines)));
                    hunkStart = Integer.parseInt(hm.group(1)); hunkLines = new ArrayList<>(); continue;
                }
                if (hunkLines != null && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")))
                    hunkLines.add(line);
            }
        }
        if (currentFile != null) {
            if (hunkLines != null) hunks.add(new Hunk(hunkStart, hunkLines));
            patches.add(new FilePatch(currentFile, hunks));
        }
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

    private String buildCommitMessage(List<VulnFixPair> pairs) {
        StringBuilder sb = new StringBuilder("fix(security): Auto-remediate ");
        sb.append(pairs.size()).append(" vulnerabilities by Compliance Buddy\n\n");
        for (VulnFixPair pair : pairs) {
            sb.append("- ").append(pair.vuln().cweId())
              .append(" in ").append(fileName(pair.vuln().filePath()))
              .append(" (").append(pair.vuln().severity()).append(")")
              .append(" fix=").append(pair.fix().id()).append("\n");
        }
        return sb.toString();
    }

    private String buildBatchPrBody(List<VulnFixPair> pairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Compliance Buddy — Automated Security Fixes\n\n");
        sb.append("This PR contains **").append(pairs.size()).append(" auto-generated security fixes**.\n\n");
        sb.append("| # | CWE | Severity | File | Line | Confidence | Strategy |\n");
        sb.append("|---|-----|----------|------|------|------------|----------|\n");
        for (int i = 0; i < pairs.size(); i++) {
            Vulnerability v = pairs.get(i).vuln();
            Fix f = pairs.get(i).fix();
            sb.append("| ").append(i + 1)
              .append(" | ").append(v.cweId())
              .append(" | ").append(v.severity())
              .append(" | `").append(fileName(v.filePath())).append("`")
              .append(" | ").append(v.lineNo())
              .append(" | ").append(String.format("%.0f%%", f.confidence() * 100))
              .append(" | ").append(f.strategy())
              .append(" |\n");
        }
        sb.append("\n### Fix Explanations\n\n");
        for (int i = 0; i < pairs.size(); i++) {
            Vulnerability v = pairs.get(i).vuln();
            Fix f = pairs.get(i).fix();
            sb.append("**").append(i + 1).append(". ").append(v.cweId())
              .append(" — ").append(fileName(v.filePath())).append("**\n");
            sb.append(f.explanation()).append("\n\n");
        }
        sb.append("---\n*Generated by Compliance Buddy — review all changes before merging*");
        return sb.toString();
    }

    private String fileName(String filePath) {
        if (filePath == null) return "unknown";
        return filePath.substring(Math.max(0, filePath.lastIndexOf('/') + 1));
    }

    record VulnFixPair(Vulnerability vuln, Fix fix) {}
    record FilePatch(String filePath, List<Hunk> hunks) {}
    record Hunk(int startLine, List<String> lines) {}
}
