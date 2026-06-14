package in.techseva.cb.notifier.service;

import in.techseva.cb.core.domain.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Service
public class JiraTicketService {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketService.class);

    private final RestClient restClient;
    private final String projectKey;
    private final boolean enabled;

    public JiraTicketService(
            @Value("${jira.base-url:}") String baseUrl,
            @Value("${jira.email:}") String email,
            @Value("${jira.api-token:}") String apiToken,
            @Value("${jira.project-key:CB}") String projectKey,
            @Value("${jira.enabled:false}") boolean enabled) {
        this.projectKey = projectKey;
        this.enabled = enabled;

        String credentials = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.isBlank() ? "http://jira-placeholder" : baseUrl)
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String createEscalationTicket(Vulnerability vuln, String reason, int retryCount) {
        if (!enabled) {
            log.debug("Jira disabled — skipping ticket creation for vuln={}", vuln.id());
            return null;
        }

        Map<String, Object> fields = Map.of(
                "project", Map.of("key", projectKey),
                "summary", "[CB-ESCALATION] " + vuln.cweId() + " in " + shortPath(vuln.filePath()),
                "description", Map.of(
                    "type", "doc",
                    "version", 1,
                    "content", java.util.List.of(Map.of(
                        "type", "paragraph",
                        "content", java.util.List.of(Map.of(
                            "type", "text",
                            "text", buildDescription(vuln, reason, retryCount)
                        ))
                    ))
                ),
                "issuetype", Map.of("name", "Bug"),
                "priority", Map.of("name", jiraPriority(vuln)),
                "labels", java.util.List.of("compliance-buddy", "auto-escalation", vuln.cweId().toLowerCase())
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/rest/api/3/issue")
                    .body(Map.of("fields", fields))
                    .retrieve()
                    .body(Map.class);

            String issueKey = response != null ? (String) response.get("key") : null;
            log.info("Created Jira ticket {} for vuln={} cwe={}", issueKey, vuln.id(), vuln.cweId());
            return issueKey;
        } catch (Exception e) {
            log.error("Failed to create Jira ticket for vuln={}: {}", vuln.id(), e.getMessage());
            return null;
        }
    }

    private String buildDescription(Vulnerability vuln, String reason, int retryCount) {
        return "Compliance Buddy Escalation\n\n" +
                "Vulnerability ID: " + vuln.id() + "\n" +
                "CWE: " + vuln.cweId() + "\n" +
                "Severity: " + vuln.severity() + "\n" +
                "File: " + vuln.filePath() + ":" + vuln.lineNo() + "\n" +
                "SonarQube Key: " + vuln.sonarIssueKey() + "\n" +
                "Retry Count: " + retryCount + "\n" +
                "Escalation Reason: " + reason;
    }

    private String jiraPriority(Vulnerability vuln) {
        if (vuln.severity() == null) return "Medium";
        return switch (vuln.severity()) {
            case CRITICAL, BLOCKER -> "Highest";
            case MAJOR -> "High";
            case MINOR -> "Medium";
            default -> "Low";
        };
    }

    private String shortPath(String filePath) {
        if (filePath == null) return "unknown";
        int idx = filePath.lastIndexOf('/');
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }
}
