package in.techseva.cb.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class GitHubMcpClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubMcpClient.class);

    private final RestClient restClient;
    private final String owner;
    private final String repo;
    private final ObjectMapper objectMapper;

    public GitHubMcpClient(
            @Value("${github.api-url:https://api.github.com}") String apiUrl,
            @Value("${github.token:}") String token,
            @Value("${github.owner:}") String owner,
            @Value("${github.repo:}") String repo,
            ObjectMapper objectMapper) {
        this.owner = owner;
        this.repo = repo;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
            .baseUrl(apiUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    public String getPRDiff(int prNumber) {
        return restClient.get()
            .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
            .header(HttpHeaders.ACCEPT, "application/vnd.github.diff")
            .retrieve()
            .body(String.class);
    }

    public String createPR(String title, String body, String headBranch, String baseBranch) {
        try {
            Map<String, Object> payload = Map.of(
                "title", title,
                "body", body,
                "head", headBranch,
                "base", baseBranch
            );
            String json = objectMapper.writeValueAsString(payload);
            String response = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(String.class);

            // Extract URL and number from response
            @SuppressWarnings("unchecked")
            Map<String, Object> prData = objectMapper.readValue(response, Map.class);
            return String.format("{\"url\":\"%s\",\"number\":%s}",
                prData.get("html_url"), prData.get("number"));
        } catch (Exception e) {
            log.error("createPR failed: {}", e.getMessage());
            throw new RuntimeException("GitHub createPR error: " + e.getMessage(), e);
        }
    }
}
