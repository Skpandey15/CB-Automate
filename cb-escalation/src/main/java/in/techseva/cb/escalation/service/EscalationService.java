package in.techseva.cb.escalation.service;

import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Autonomous escalation agent: creates Jira ticket, sends Teams webhook, and generates RCA report.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final RestClient jiraClient;
    private final RestClient teamsClient;
    private final AuditService auditService;
    private final boolean jiraEnabled;
    private final boolean teamsEnabled;
    private final String jiraProjectKey;

    public EscalationService(
            @Value("${jira.base-url:}") String jiraBaseUrl,
            @Value("${jira.email:}") String jiraEmail,
            @Value("${jira.api-token:}") String jiraToken,
            @Value("${jira.project-key:CB}") String jiraProjectKey,
            @Value("${jira.enabled:false}") boolean jiraEnabled,
            @Value("${teams.webhook-url:}") String teamsWebhookUrl,
            @Value("${teams.enabled:false}") boolean teamsEnabled,
            AuditService auditService) {
        this.jiraProjectKey = jiraProjectKey;
        this.jiraEnabled = jiraEnabled;
        this.teamsEnabled = teamsEnabled;
        this.auditService = auditService;

        String jiraCreds = Base64.getEncoder().encodeToString((jiraEmail + ":" + jiraToken).getBytes());
        this.jiraClient = RestClient.builder()
                .baseUrl(jiraBaseUrl.isBlank() ? "http://jira-placeholder" : jiraBaseUrl)
                .defaultHeader("Authorization", "Basic " + jiraCreds)
                .build();

        this.teamsClient = RestClient.builder()
                .baseUrl(teamsWebhookUrl.isBlank() ? "http://teams-placeholder" : teamsWebhookUrl)
                .build();
    }

    @Async
    public void escalate(Vulnerability vuln, EscalationKafkaEvent event) {
        log.info("EscalationService: processing vuln={} severity={} retryCount={}",
                vuln.id(), vuln.severity(), event.retryCount());

        String jiraKey = createJiraTicket(vuln, event);
        sendTeamsAlert(vuln, event, jiraKey);
        String rcaReport = generateRcaReport(vuln, event, jiraKey);

        auditService.log(vuln.id(), "Vulnerability", "ESCALATED_BY_AGENT", "escalation-agent",
                Map.of(
                    "jiraKey", jiraKey != null ? jiraKey : "N/A",
                    "retryCount", event.retryCount(),
                    "rcaLength", rcaReport.length()
                ));

        log.info("Escalation complete: vuln={} jira={} rcaBytes={}",
                vuln.id(), jiraKey, rcaReport.length());
    }

    private String createJiraTicket(Vulnerability vuln, EscalationKafkaEvent event) {
        if (!jiraEnabled) {
            log.debug("Jira disabled — skipping ticket for vuln={}", vuln.id());
            return null;
        }
        try {
            String summary = "[CB-AUTO] " + vuln.cweId() + " escalation in " + shortPath(vuln.filePath())
                    + " (retry=" + event.retryCount() + ")";
            Map<String, Object> fields = Map.of(
                    "project", Map.of("key", jiraProjectKey),
                    "summary", summary,
                    "description", Map.of(
                        "type", "doc", "version", 1,
                        "content", List.of(Map.of(
                            "type", "paragraph",
                            "content", List.of(Map.of("type", "text", "text", buildJiraDescription(vuln, event)))
                        ))
                    ),
                    "issuetype", Map.of("name", "Bug"),
                    "priority", Map.of("name", jiraPriority(vuln)),
                    "labels", List.of("compliance-buddy", "auto-escalation", vuln.cweId().toLowerCase())
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = jiraClient.post()
                    .uri("/rest/api/3/issue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fields", fields))
                    .retrieve()
                    .body(Map.class);
            return resp != null ? (String) resp.get("key") : null;
        } catch (Exception e) {
            log.error("Jira ticket creation failed for vuln={}: {}", vuln.id(), e.getMessage());
            return null;
        }
    }

    private void sendTeamsAlert(Vulnerability vuln, EscalationKafkaEvent event, String jiraKey) {
        if (!teamsEnabled) {
            log.debug("Teams disabled — skipping alert for vuln={}", vuln.id());
            return;
        }
        try {
            Map<String, Object> card = Map.of(
                "type", "message",
                "attachments", List.of(Map.of(
                    "contentType", "application/vnd.microsoft.card.adaptive",
                    "content", Map.of(
                        "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type", "AdaptiveCard",
                        "version", "1.4",
                        "body", List.of(
                            Map.of("type", "TextBlock", "text", "🚨 CB Escalation Agent — " + vuln.cweId(),
                                   "weight", "Bolder", "size", "Large", "color", "Attention"),
                            Map.of("type", "FactSet", "facts", List.of(
                                Map.of("title", "Vulnerability", "value", vuln.id()),
                                Map.of("title", "CWE", "value", vuln.cweId()),
                                Map.of("title", "Severity", "value", vuln.severity() != null ? vuln.severity().name() : "UNKNOWN"),
                                Map.of("title", "File", "value", vuln.filePath() + ":" + vuln.lineNo()),
                                Map.of("title", "Retry Count", "value", String.valueOf(event.retryCount())),
                                Map.of("title", "Jira Ticket", "value", jiraKey != null ? jiraKey : "N/A"),
                                Map.of("title", "Action Required", "value", "Manual remediation needed")
                            ))
                        )
                    )
                ))
            );
            teamsClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(card)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Teams escalation alert sent for vuln={}", vuln.id());
        } catch (Exception e) {
            log.error("Teams alert failed for vuln={}: {}", vuln.id(), e.getMessage());
        }
    }

    private String generateRcaReport(Vulnerability vuln, EscalationKafkaEvent event, String jiraKey) {
        String timestamp = ISO.format(Instant.now().atZone(ZoneOffset.UTC));
        return """
                # Root Cause Analysis Report
                Generated: %s | Jira: %s

                ## Vulnerability
                - ID: %s
                - CWE: %s
                - Severity: %s
                - SonarQube Key: %s
                - File: %s:%d

                ## Escalation Summary
                - Retry Count: %d
                - Last Fix ID: %s
                - Rejection Reasons:
                %s

                ## Recommended Action
                This vulnerability exceeded the automated remediation retry limit (%d attempts).
                Manual intervention is required. A Jira ticket has been created: %s

                ## Next Steps
                1. Assign ticket %s to a senior engineer
                2. Review the rejection reasons listed above
                3. Implement a manual fix or adjust the remediation strategy
                4. After fix is merged, mark the vulnerability as RESOLVED in Compliance Buddy
                """.formatted(
                    timestamp, jiraKey != null ? jiraKey : "N/A",
                    vuln.id(), vuln.cweId(),
                    vuln.severity() != null ? vuln.severity().name() : "UNKNOWN",
                    vuln.sonarIssueKey(), vuln.filePath(), vuln.lineNo(),
                    event.retryCount(), event.lastFixId() != null ? event.lastFixId() : "none",
                    event.rejectionReasons() != null
                            ? event.rejectionReasons().stream().map(r -> "  - " + r).reduce("", (a, b) -> a + "\n" + b)
                            : "  - No rejection reasons recorded",
                    event.retryCount(), jiraKey != null ? jiraKey : "N/A",
                    jiraKey != null ? jiraKey : "N/A"
        );
    }

    private String buildJiraDescription(Vulnerability vuln, EscalationKafkaEvent event) {
        return "Compliance Buddy Autonomous Escalation\n\n" +
                "Vulnerability: " + vuln.id() + "\n" +
                "CWE: " + vuln.cweId() + "\n" +
                "Severity: " + (vuln.severity() != null ? vuln.severity().name() : "UNKNOWN") + "\n" +
                "File: " + vuln.filePath() + ":" + vuln.lineNo() + "\n" +
                "SonarQube Key: " + vuln.sonarIssueKey() + "\n" +
                "Retry Count: " + event.retryCount() + "\n" +
                "Last Fix ID: " + (event.lastFixId() != null ? event.lastFixId() : "none") + "\n" +
                "Rejection Reasons: " + (event.rejectionReasons() != null
                        ? String.join("; ", event.rejectionReasons()) : "none");
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
