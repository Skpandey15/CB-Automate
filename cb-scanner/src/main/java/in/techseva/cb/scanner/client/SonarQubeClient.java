package in.techseva.cb.scanner.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

@Component
public class SonarQubeClient {

    private static final Logger log = LoggerFactory.getLogger(SonarQubeClient.class);

    private final RestClient restClient;

    public SonarQubeClient(
            @Value("${sonarqube.url}") String sonarUrl,
            @Value("${sonarqube.token}") String sonarToken) {
        this.restClient = RestClient.builder()
                .baseUrl(sonarUrl)
                .defaultHeader("Authorization", "Bearer " + sonarToken)
                .build();
    }

    @CircuitBreaker(name = "sonarqube-api", fallbackMethod = "emptyIssues")
    @Retry(name = "sonarqube-api")
    public SonarIssuesResponse getSecurityIssues(String projectKey, int page) {
        log.debug("Fetching SonarQube issues for project={} page={}", projectKey, page);
        return restClient.get()
                .uri("/api/issues/search?componentKeys={key}&types=VULNERABILITY,BUG,CODE_SMELL&p={page}&ps=100&resolved=false&languages=java",
                        projectKey, page)
                .retrieve()
                .body(SonarIssuesResponse.class);
    }

    public SonarIssuesResponse emptyIssues(String projectKey, int page, Throwable t) {
        log.warn("SonarQube circuit open for project={}: {}", projectKey, t.getMessage());
        return new SonarIssuesResponse(new SonarPaging(0, 1, 0), Collections.emptyList());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonarIssuesResponse(
            @JsonProperty("paging") SonarPaging paging,
            @JsonProperty("issues") List<SonarIssue> issues) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonarPaging(
            @JsonProperty("pageIndex") int pageIndex,
            @JsonProperty("pageSize") int pageSize,
            @JsonProperty("total") int total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonarIssue(
            @JsonProperty("key") String key,
            @JsonProperty("rule") String rule,
            @JsonProperty("severity") String severity,
            @JsonProperty("component") String component,
            @JsonProperty("project") String project,
            @JsonProperty("line") Integer line,
            @JsonProperty("message") String message,
            @JsonProperty("effort") String effort,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("type") String type) {}
}
