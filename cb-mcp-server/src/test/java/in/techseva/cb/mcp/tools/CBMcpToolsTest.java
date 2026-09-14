package in.techseva.cb.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.mcp.client.CBApiClient;
import in.techseva.cb.mcp.client.CBPatcherClient;
import in.techseva.cb.mcp.client.GitHubMcpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CBMcpToolsTest {

    @Mock VulnerabilityRepository vulnRepo;
    @Mock FixRepository fixRepo;
    @Mock GitHubMcpClient gitHubClient;
    @Mock CBPatcherClient patcherClient;
    @Mock CBApiClient apiClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private CBMcpTools tools() {
        return new CBMcpTools(vulnRepo, fixRepo, gitHubClient, patcherClient, apiClient, objectMapper,
                "http://tempo.invalid", "http://prometheus.invalid", "http://qdrant.invalid");
    }

    private Vulnerability vuln(String id, String cweId, VulnerabilityStatus status) {
        return vuln(id, cweId, status, "sql injection risk");
    }

    private Vulnerability vuln(String id, String cweId, VulnerabilityStatus status, String message) {
        return new Vulnerability(id, "SONAR-" + id, cweId, Severity.CRITICAL,
                "java:S2078", 42, "30min", status,
                message, "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 0, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private Fix validatedFix(String id, String vulnId) {
        return new Fix(id, vulnId, "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, null, null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }

    @Test
    void findPreviousFixes_returnsOnlyBuildValidatedFixesForMatchingCwe() {
        var vuln1 = vuln("v1", "CWE-89", VulnerabilityStatus.RESOLVED);
        var vuln2 = vuln("v2", "CWE-79", VulnerabilityStatus.RESOLVED); // different CWE, excluded
        when(vulnRepo.findAll()).thenReturn(List.of(vuln1, vuln2));
        when(fixRepo.findByVulnerabilityId("v1")).thenReturn(List.of(validatedFix("f1", "v1")));

        String result = tools().findPreviousFixes("CWE-89", 5);

        assertThat(result).contains("parameterize").doesNotContain("CWE-79");
    }

    @Test
    void findPreviousFixes_noneFound_returnsMessageJson() {
        when(vulnRepo.findAll()).thenReturn(List.of());

        String result = tools().findPreviousFixes("CWE-89", 5);

        assertThat(result).contains("No validated fixes found");
    }

    @Test
    void getSonarIssue_found_returnsSerializedVulnerability() {
        when(vulnRepo.findBySonarIssueKey("SONAR-v1")).thenReturn(Optional.of(vuln("v1", "CWE-89", VulnerabilityStatus.DETECTED)));

        String result = tools().getSonarIssue("SONAR-v1");

        assertThat(result).contains("CWE-89");
    }

    @Test
    void getSonarIssue_notFound_returnsErrorJson() {
        when(vulnRepo.findBySonarIssueKey("missing")).thenReturn(Optional.empty());

        String result = tools().getSonarIssue("missing");

        assertThat(result).contains("error").contains("not found");
    }

    @Test
    void regenerateFix_resolvedVulnerability_refusesAndDoesNotSave() {
        when(vulnRepo.findById("v1")).thenReturn(Optional.of(vuln("v1", "CWE-89", VulnerabilityStatus.RESOLVED)));

        String result = tools().regenerateFix("v1", "manual re-trigger");

        assertThat(result).contains("Cannot regenerate fix for RESOLVED vulnerability");
        verify(vulnRepo, never()).save(any());
    }

    @Test
    void regenerateFix_notResolvedVulnerability_resetsToDetectedAndIncrementsRetry() {
        when(vulnRepo.findById("v1")).thenReturn(Optional.of(vuln("v1", "CWE-89", VulnerabilityStatus.FAILED)));
        when(vulnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = tools().regenerateFix("v1", "manual re-trigger");

        assertThat(result).contains("QUEUED").contains("\"retryCount\":1");
        var captor = ArgumentCaptor.forClass(Vulnerability.class);
        verify(vulnRepo).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(VulnerabilityStatus.DETECTED);
    }

    @Test
    void regenerateFix_vulnerabilityNotFound_returnsErrorJson() {
        when(vulnRepo.findById("missing")).thenReturn(Optional.empty());

        String result = tools().regenerateFix("missing", "reason");

        assertThat(result).contains("error").contains("not found");
    }

    @Test
    void searchPastIncidents_matchesKeywordInMessageFilePathOrCwe() {
        var matching = vuln("v1", "CWE-89", VulnerabilityStatus.DETECTED, "sql injection risk");
        var nonMatching = vuln("v2", "CWE-79", VulnerabilityStatus.DETECTED, "reflected XSS in template");
        when(vulnRepo.findAll()).thenReturn(List.of(matching, nonMatching));

        String result = tools().searchPastIncidents("sql injection", "");

        assertThat(result).contains("\"id\":\"v1\"").doesNotContain("\"id\":\"v2\"");
    }

    @Test
    void searchPastIncidents_withCweFilter_narrowsFurther() {
        var v1 = vuln("v1", "CWE-89", VulnerabilityStatus.DETECTED, "sql injection risk");
        var v2 = vuln("v2", "CWE-79", VulnerabilityStatus.DETECTED, "sql injection risk"); // same message, different CWE
        when(vulnRepo.findAll()).thenReturn(List.of(v1, v2));

        String result = tools().searchPastIncidents("sql injection", "CWE-79");

        assertThat(result).contains("\"id\":\"v2\"").doesNotContain("\"id\":\"v1\"");
    }

    @Test
    void getBuildLog_fixFoundWithLog_returnsLog() {
        when(fixRepo.findById("f1")).thenReturn(Optional.of(validatedFix("f1", "v1")));

        assertThat(tools().getBuildLog("f1")).isEqualTo("build ok");
    }

    @Test
    void getBuildLog_fixNotFound_returnsErrorJson() {
        when(fixRepo.findById("missing")).thenReturn(Optional.empty());

        assertThat(tools().getBuildLog("missing")).contains("error").contains("not found");
    }

    @Test
    void buildProject_delegatesToPatcherClient() {
        when(patcherClient.triggerBuild("cb/fix-1")).thenReturn("{\"status\":\"success\"}");

        assertThat(tools().buildProject("cb/fix-1")).isEqualTo("{\"status\":\"success\"}");
    }

    @Test
    void buildProject_patcherThrows_returnsErrorStringInsteadOfPropagating() {
        when(patcherClient.triggerBuild("cb/fix-1")).thenThrow(new RuntimeException("patcher unreachable"));

        assertThat(tools().buildProject("cb/fix-1")).contains("Build trigger failed");
    }

    @Test
    void triggerRollback_delegatesToPatcherClient() {
        when(patcherClient.triggerRollback("f1", "cb/fix-1")).thenReturn("{\"status\":\"reverted\"}");

        assertThat(tools().triggerRollback("f1", "cb/fix-1")).isEqualTo("{\"status\":\"reverted\"}");
    }

    @Test
    void getPRDiff_delegatesToGitHubClient() {
        when(gitHubClient.getPRDiff(42)).thenReturn("diff content");

        assertThat(tools().getPRDiff(42)).isEqualTo("diff content");
    }

    @Test
    void getPRDiff_gitHubThrows_returnsErrorStringInsteadOfPropagating() {
        when(gitHubClient.getPRDiff(42)).thenThrow(new RuntimeException("github unreachable"));

        assertThat(tools().getPRDiff(42)).contains("Error fetching PR diff");
    }

    @Test
    void createPR_delegatesToGitHubClient() {
        when(gitHubClient.createPR("title", "body", "head", "main")).thenReturn("{\"number\":7}");

        assertThat(tools().createPR("title", "body", "head", "main")).isEqualTo("{\"number\":7}");
    }

    @Test
    void triggerRemediationRun_delegatesToApiClientWithParsedRecipients() {
        when(apiClient.startRemediationRun("owner/repo", "main", true, List.of("a@b.com", "c@d.com")))
                .thenReturn("{\"runId\":\"run-1\",\"status\":\"RUNNING\"}");

        String result = tools().triggerRemediationRun("owner/repo", "main", true, "a@b.com, c@d.com");

        assertThat(result).contains("run-1");
    }

    @Test
    void triggerRemediationRun_noRecipients_passesEmptyList() {
        when(apiClient.startRemediationRun("owner/repo", "main", false, List.of()))
                .thenReturn("{\"runId\":\"run-1\"}");

        assertThat(tools().triggerRemediationRun("owner/repo", "main", false, "")).contains("run-1");
    }

    @Test
    void triggerRemediationRun_apiClientThrows_returnsErrorStringInsteadOfPropagating() {
        when(apiClient.startRemediationRun(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new RuntimeException("cb-api unreachable"));

        assertThat(tools().triggerRemediationRun("owner/repo", "main", false, ""))
                .contains("Failed to start remediation run");
    }

    @Test
    void getRemediationRunStatus_delegatesToApiClient() {
        when(apiClient.getRemediationRunStatus("run-1")).thenReturn("{\"status\":\"FIXED\"}");

        assertThat(tools().getRemediationRunStatus("run-1")).isEqualTo("{\"status\":\"FIXED\"}");
    }

    @Test
    void getRemediationRunStatus_apiClientThrows_returnsErrorStringInsteadOfPropagating() {
        when(apiClient.getRemediationRunStatus("run-1")).thenThrow(new RuntimeException("cb-api unreachable"));

        assertThat(tools().getRemediationRunStatus("run-1")).contains("Failed to fetch remediation run status");
    }
}
