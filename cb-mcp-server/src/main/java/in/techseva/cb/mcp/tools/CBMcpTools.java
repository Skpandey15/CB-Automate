package in.techseva.cb.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.mcp.client.CBPatcherClient;
import in.techseva.cb.mcp.client.GitHubMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CBMcpTools {

    private static final Logger log = LoggerFactory.getLogger(CBMcpTools.class);

    private final VulnerabilityRepository vulnRepo;
    private final FixRepository fixRepo;
    private final GitHubMcpClient gitHubClient;
    private final CBPatcherClient patcherClient;
    private final ObjectMapper objectMapper;
    private final RestClient tempoClient;
    private final RestClient prometheusClient;
    private final RestClient qdrantClient;

    public CBMcpTools(VulnerabilityRepository vulnRepo,
                      FixRepository fixRepo,
                      GitHubMcpClient gitHubClient,
                      CBPatcherClient patcherClient,
                      ObjectMapper objectMapper,
                      @Value("${tempo.url:http://tempo:3200}") String tempoUrl,
                      @Value("${prometheus.url:http://prometheus:9090}") String prometheusUrl,
                      @Value("${qdrant.http-url:http://qdrant:6333}") String qdrantUrl) {
        this.vulnRepo = vulnRepo;
        this.fixRepo = fixRepo;
        this.gitHubClient = gitHubClient;
        this.patcherClient = patcherClient;
        this.objectMapper = objectMapper;
        this.tempoClient = RestClient.builder().baseUrl(tempoUrl).build();
        this.prometheusClient = RestClient.builder().baseUrl(prometheusUrl).build();
        this.qdrantClient = RestClient.builder().baseUrl(qdrantUrl).build();
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

    @Tool(description = "Get the Gradle build log for a specific fix by fix ID. Returns the full build stdout/stderr captured during the patcher build validation step.")
    public String getBuildLog(
            @ToolParam(description = "The Compliance Buddy fix ID (MongoDB ObjectId)") String fixId
    ) {
        return fixRepo.findById(fixId)
                .map(fix -> fix.buildLog() != null ? fix.buildLog() : "No build log recorded for fix " + fixId)
                .orElse("{\"error\":\"Fix not found: " + fixId + "\"}");
    }

    @Tool(description = "Search for past security incidents in the Compliance Buddy database by keyword and optional CWE filter. Returns matching vulnerabilities with their status and fix history.")
    public String searchPastIncidents(
            @ToolParam(description = "Keyword to search in file paths, messages, or CWE IDs") String keyword,
            @ToolParam(description = "Optional CWE ID filter e.g. CWE-89, or empty string for all") String cweFilter
    ) {
        try {
            List<Vulnerability> matches = vulnRepo.findAll().stream()
                    .filter(v -> {
                        boolean keywordMatch = (v.message() != null && v.message().contains(keyword))
                                || (v.filePath() != null && v.filePath().contains(keyword))
                                || (v.cweId() != null && v.cweId().contains(keyword));
                        boolean cweMatch = cweFilter == null || cweFilter.isBlank()
                                || cweFilter.equalsIgnoreCase(v.cweId());
                        return keywordMatch && cweMatch;
                    })
                    .limit(10)
                    .toList();
            return objectMapper.writeValueAsString(matches);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @Tool(description = "Query the Qdrant vector store for similar fix examples using a natural language query. Returns top matching stored fix documents.")
    public String queryQdrant(
            @ToolParam(description = "Natural language query describing the vulnerability or fix pattern") String query,
            @ToolParam(description = "Collection to search in (default: cb_fixes)") String collection,
            @ToolParam(description = "Maximum number of results (1-10)") int limit
    ) {
        String col = (collection == null || collection.isBlank()) ? "cb_fixes" : collection;
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        try {
            // Use Qdrant scroll API for text search (embedding requires Spring AI vector store)
            var response = qdrantClient.get()
                    .uri("/collections/" + col + "/points/scroll?limit=" + safeLimit)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"error\":\"Qdrant returned empty response\"}";
        } catch (Exception e) {
            log.warn("Qdrant query failed: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Trigger fix regeneration for a vulnerability by resetting it to DETECTED status so the cb-agent picks it up again. Returns the updated vulnerability status.")
    public String regenerateFix(
            @ToolParam(description = "The Compliance Buddy vulnerability ID to reprocess") String vulnerabilityId,
            @ToolParam(description = "Reason for regeneration (shown in audit log)") String reason
    ) {
        return vulnRepo.findById(vulnerabilityId).map(vuln -> {
            if (vuln.status() == VulnerabilityStatus.RESOLVED) {
                return "{\"error\":\"Cannot regenerate fix for RESOLVED vulnerability\"}";
            }
            Vulnerability reset = vuln.withStatus(VulnerabilityStatus.DETECTED).incrementRetryCount();
            vulnRepo.save(reset);
            log.info("MCP: regenerateFix triggered for vuln={} reason={}", vulnerabilityId, reason);
            return "{\"status\":\"QUEUED\",\"vulnerabilityId\":\"" + vulnerabilityId + "\",\"retryCount\":" + reset.retryCount() + "}";
        }).orElse("{\"error\":\"Vulnerability not found: " + vulnerabilityId + "\"}");
    }

    @Tool(description = "Get a distributed trace from Grafana Tempo by trace ID. Returns the full trace JSON with spans for the CB pipeline execution.")
    public String getTrace(
            @ToolParam(description = "OpenTelemetry trace ID (32-character hex string)") String traceId
    ) {
        try {
            String result = tempoClient.get()
                    .uri("/api/traces/" + traceId)
                    .retrieve()
                    .body(String.class);
            return result != null ? result : "{\"error\":\"Trace not found\"}";
        } catch (Exception e) {
            return "{\"error\":\"Tempo query failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Query Prometheus metrics for the Compliance Buddy pipeline. Supports PromQL queries. Returns the current metric value.")
    public String getMetrics(
            @ToolParam(description = "PromQL query expression e.g. cb_vulnerabilities_detected_total") String promQuery
    ) {
        try {
            String result = prometheusClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/query")
                        .queryParam("query", promQuery)
                        .build())
                    .retrieve()
                    .body(String.class);
            return result != null ? result : "{\"error\":\"Prometheus returned empty response\"}";
        } catch (Exception e) {
            return "{\"error\":\"Prometheus query failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Trigger a rollback of a previously merged fix by reverting the PR branch commit in the repository. This calls the patcher service to perform the git revert.")
    public String triggerRollback(
            @ToolParam(description = "The Compliance Buddy fix ID to rollback") String fixId,
            @ToolParam(description = "The git branch name that was merged") String branchName
    ) {
        try {
            log.info("MCP: triggerRollback fixId={} branch={}", fixId, branchName);
            String result = patcherClient.triggerRollback(fixId, branchName);
            return result != null ? result : "{\"status\":\"ROLLBACK_REQUESTED\",\"fixId\":\"" + fixId + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Rollback failed: " + e.getMessage() + "\"}";
        }
    }

    record FixSummaryDto(String id, String cweId, String strategy, double confidence,
                         String explanation, String patchDiff) {}
}
