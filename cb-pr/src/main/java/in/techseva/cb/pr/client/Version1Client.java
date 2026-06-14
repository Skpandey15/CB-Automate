package in.techseva.cb.pr.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class Version1Client {

    private static final Logger log = LoggerFactory.getLogger(Version1Client.class);

    private final RestClient restClient;
    private final String defaultAssignee;

    public Version1Client(
            @Value("${version1.api-url}") String apiUrl,
            @Value("${version1.token}") String token,
            @Value("${version1.default-assignee:CB-Bot}") String defaultAssignee) {
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/json")
                .build();
        this.defaultAssignee = defaultAssignee;
    }

    @CircuitBreaker(name = "version1-api", fallbackMethod = "createTaskFallback")
    public String createSecurityTask(String title, String description, String severity, String cweId) {
        Map<String, Object> body = Map.of(
                "AssetType", "Story",
                "Attributes", Map.of(
                        "Name", Map.of("act", "set", "val", title),
                        "Description", Map.of("act", "set", "val", description),
                        "Priority", Map.of("act", "set", "val", mapSeverityToPriority(severity)),
                        "Custom_CWE", Map.of("act", "set", "val", cweId),
                        "Status", Map.of("act", "set", "val", "Future")));

        V1AssetResponse response = restClient.post()
                .uri("/rest-1.v1/Data/Story")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(V1AssetResponse.class);

        String taskId = response != null ? response.id() : null;
        log.info("Created Version1 task: {}", taskId);
        return taskId;
    }

    public String createTaskFallback(String title, String description, String severity,
                                      String cweId, Throwable t) {
        log.warn("Version1 unavailable, task not created: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "version1-api")
    public void updateTaskStatus(String taskId, String status, String prUrl) {
        if (taskId == null) return;
        Map<String, Object> body = Map.of(
                "AssetType", "Story",
                "id", taskId,
                "Attributes", Map.of(
                        "Status", Map.of("act", "set", "val", status),
                        "Custom_PRUrl", Map.of("act", "set", "val", prUrl != null ? prUrl : "")));
        restClient.post()
                .uri("/rest-1.v1/Data/Story/{id}", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Updated Version1 task {} to status {}", taskId, status);
    }

    private String mapSeverityToPriority(String severity) {
        return switch (severity != null ? severity.toUpperCase() : "") {
            case "BLOCKER", "CRITICAL" -> "High";
            case "MAJOR" -> "Medium";
            default -> "Low";
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record V1AssetResponse(@JsonProperty("id") String id) {}
}
