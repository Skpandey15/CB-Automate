package in.techseva.cb.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls cb-api's remediation-runs endpoints (ADR-0002 Track A) so an MCP
 * client (Claude Desktop, Cursor, etc.) can trigger and check a
 * repo-URL dependency-CVE remediation run directly, not just via HTTP.
 * Unlike CBPatcherClient's target, cb-api is itself auth-gated behind
 * ApiKeyAuthFilter, so this client authenticates with X-API-Key.
 */
@Component
public class CBApiClient {

    private static final Logger log = LoggerFactory.getLogger(CBApiClient.class);

    private final RestClient restClient;

    @Autowired
    public CBApiClient(RestClient.Builder restClientBuilder,
                        @Value("${cb-api.url:http://cb-api:8080}") String apiUrl,
                        @Value("${cb-api.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000); // starting a run just enqueues it -- status polling is separate

        this.restClient = restClientBuilder
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .defaultHeader("X-API-Key", apiKey)
                .build();
    }

    /**
     * Test-only seam: the primary constructor always installs its own
     * timeout-configured request factory, which would clobber a
     * MockRestServiceServer-bound factory if tests went through it (the
     * same issue GitHubEnterpriseClient hit -- see its Javadoc/commit
     * history). Tests build their own RestClient against a mocked
     * transport and inject it directly instead.
     */
    CBApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public String startRemediationRun(String repoUrl, String branch, boolean publish, List<String> recipients) {
        try {
            Map<String, Object> body = Map.of(
                    "repoUrl", repoUrl,
                    "branch", branch,
                    "publish", publish,
                    "recipients", recipients != null ? recipients : List.of());
            String response = restClient.post()
                    .uri("/api/v1/remediation-runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"error\":\"cb-api returned an empty response\"}";
        } catch (Exception e) {
            log.error("startRemediationRun failed for repo={} branch={}: {}", repoUrl, branch, e.getMessage());
            throw new RuntimeException("cb-api remediation-runs error: " + e.getMessage(), e);
        }
    }

    public String getRemediationRunStatus(String runId) {
        try {
            String response = restClient.get()
                    .uri("/api/v1/remediation-runs/{runId}", runId)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"error\":\"cb-api returned an empty response\"}";
        } catch (Exception e) {
            log.error("getRemediationRunStatus failed for runId={}: {}", runId, e.getMessage());
            throw new RuntimeException("cb-api remediation-runs error: " + e.getMessage(), e);
        }
    }
}
