package in.techseva.cb.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.mcp.client.CBPatcherClient;
import in.techseva.cb.mcp.client.GitHubMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CBMcpTools {

    private static final Logger log = LoggerFactory.getLogger(CBMcpTools.class);

    private final VulnerabilityRepository vulnRepo;
    private final FixRepository fixRepo;
    private final GitHubMcpClient gitHubClient;
    private final CBPatcherClient patcherClient;
    private final ObjectMapper objectMapper;

    public CBMcpTools(VulnerabilityRepository vulnRepo,
                      FixRepository fixRepo,
                      GitHubMcpClient gitHubClient,
                      CBPatcherClient patcherClient,
                      ObjectMapper objectMapper) {
        this.vulnRepo = vulnRepo;
        this.fixRepo = fixRepo;
        this.gitHubClient = gitHubClient;
        this.patcherClient = patcherClient;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Find previously validated security fixes stored in Compliance Buddy for a given CWE ID. Returns a JSON array of matching fixes including their patch diffs and strategies.")
    public String findPreviousFixes(
            @ToolParam(description = "CWE identifier such as CWE-89 or CWE-79") String cweId,
            @ToolParam(description = "Maximum number of fixes to return (1-10)") int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        try {
            // Find vulnerabilities with this CWE and get their validated fixes
            List<Vulnerability> vulns = vulnRepo.findAll().stream()
                .filter(v -> cweId.equalsIgnoreCase(v.cweId()))
                .limit(safeLimit * 3L)
                .toList();

            List<Fix> validatedFixes = vulns.stream()
                .flatMap(v -> fixRepo.findByVulnerabilityId(v.id()).stream())
                .filter(f -> f.buildValidated() || f.status() == FixStatus.BUILD_VALIDATED)
                .limit(safeLimit)
                .toList();

            if (validatedFixes.isEmpty()) {
                return "{\"message\":\"No validated fixes found for " + cweId + "\",\"fixes\":[]}";
            }
            return objectMapper.writeValueAsString(
                validatedFixes.stream().map(f -> new FixSummaryDto(
                    f.id(), cweId, f.strategy(), f.confidence(), f.explanation(), f.patchDiff()
                )).toList()
            );
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @Tool(description = "Get detailed information about a Sonar vulnerability from Compliance Buddy by its Sonar issue key.")
    public String getSonarIssue(
            @ToolParam(description = "SonarQube issue key e.g. AYxyz123") String sonarIssueKey
    ) {
        Optional<Vulnerability> vuln = vulnRepo.findBySonarIssueKey(sonarIssueKey);
        if (vuln.isEmpty()) {
            return "{\"error\":\"Vulnerability not found for key: " + sonarIssueKey + "\"}";
        }
        try {
            return objectMapper.writeValueAsString(vuln.get());
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Serialization error\"}";
        }
    }

    @Tool(description = "Get the unified diff of a GitHub pull request by its PR number. Useful for reviewing changes before accepting or rejecting.")
    public String getPRDiff(
            @ToolParam(description = "GitHub pull request number") int prNumber
    ) {
        try {
            log.info("MCP: getPRDiff prNumber={}", prNumber);
            return gitHubClient.getPRDiff(prNumber);
        } catch (Exception e) {
            return "Error fetching PR diff: " + e.getMessage();
        }
    }

    @Tool(description = "Trigger a Gradle build of the target repository on the given branch via the Compliance Buddy patcher service. Returns the build result: SUCCESS or FAILURE with logs.")
    public String buildProject(
            @ToolParam(description = "Git branch name to build") String branchName
    ) {
        try {
            log.info("MCP: buildProject branch={}", branchName);
            return patcherClient.triggerBuild(branchName);
        } catch (Exception e) {
            return "Build trigger failed: " + e.getMessage();
        }
    }

    @Tool(description = "Create a GitHub pull request for a security fix branch. Returns the PR URL and number on success.")
    public String createPR(
            @ToolParam(description = "PR title") String title,
            @ToolParam(description = "PR body / description in Markdown") String body,
            @ToolParam(description = "Source branch name to merge from") String headBranch,
            @ToolParam(description = "Target branch to merge into (usually 'main')") String baseBranch
    ) {
        try {
            log.info("MCP: createPR title={} head={}", title, headBranch);
            return gitHubClient.createPR(title, body, headBranch, baseBranch);
        } catch (Exception e) {
            return "PR creation failed: " + e.getMessage();
        }
    }

    record FixSummaryDto(String id, String cweId, String strategy, double confidence,
                         String explanation, String patchDiff) {}
}
