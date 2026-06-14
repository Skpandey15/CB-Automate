package in.techseva.cb.notifier.service;

import in.techseva.cb.core.domain.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sends adaptive card notifications to a Microsoft Teams channel via incoming webhook.
 */
@Service
public class TeamsNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TeamsNotificationService.class);

    private final RestClient restClient;
    private final boolean enabled;

    public TeamsNotificationService(
            @Value("${teams.webhook-url:}") String webhookUrl,
            @Value("${teams.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.restClient = RestClient.builder()
                .baseUrl(webhookUrl.isBlank() ? "http://teams-placeholder" : webhookUrl)
                .build();
    }

    public void sendEscalationAlert(Vulnerability vuln, String reason, String jiraTicketKey, int retryCount) {
        if (!enabled) {
            log.debug("Teams webhook disabled — skipping notification for vuln={}", vuln.id());
            return;
        }

        Map<String, Object> card = buildAdaptiveCard(vuln, reason, jiraTicketKey, retryCount);
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(card)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Teams notification sent for vuln={} jira={}", vuln.id(), jiraTicketKey);
        } catch (Exception e) {
            log.error("Failed to send Teams notification for vuln={}: {}", vuln.id(), e.getMessage());
        }
    }

    private Map<String, Object> buildAdaptiveCard(Vulnerability vuln, String reason,
                                                   String jiraTicketKey, int retryCount) {
        String title = "⚠️ Compliance Buddy Escalation — " + vuln.cweId();
        String color = severityColor(vuln);

        return Map.of(
            "type", "message",
            "attachments", List.of(Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", Map.of(
                    "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                    "type", "AdaptiveCard",
                    "version", "1.4",
                    "msteams", Map.of("width", "Full"),
                    "body", List.of(
                        Map.of("type", "TextBlock", "text", title, "weight", "Bolder", "size", "Large", "color", color),
                        Map.of("type", "FactSet", "facts", List.of(
                            fact("CWE", vuln.cweId()),
                            fact("Severity", vuln.severity() != null ? vuln.severity().name() : "UNKNOWN"),
                            fact("File", shortPath(vuln.filePath()) + ":" + vuln.lineNo()),
                            fact("Retry Count", String.valueOf(retryCount)),
                            fact("Jira Ticket", jiraTicketKey != null ? jiraTicketKey : "N/A"),
                            fact("Reason", reason)
                        ))
                    )
                )
            ))
        );
    }

    private Map<String, String> fact(String title, String value) {
        return Map.of("title", title, "value", value);
    }

    private String severityColor(Vulnerability vuln) {
        if (vuln.severity() == null) return "Default";
        return switch (vuln.severity()) {
            case CRITICAL, BLOCKER -> "Attention";
            case MAJOR -> "Warning";
            default -> "Default";
        };
    }

    private String shortPath(String filePath) {
        if (filePath == null) return "unknown";
        int idx = filePath.lastIndexOf('/');
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }
}
