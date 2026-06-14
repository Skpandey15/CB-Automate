package in.techseva.cb.notifier.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class DatadogEventService {

    private static final Logger log = LoggerFactory.getLogger(DatadogEventService.class);

    private final RestClient restClient;
    private final String environment;

    public DatadogEventService(
            @Value("${datadog.api-key:}") String apiKey,
            @Value("${datadog.site:datadoghq.com}") String site,
            @Value("${datadog.env:production}") String environment) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api." + site)
                .defaultHeader("DD-API-KEY", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.environment = environment;
    }

    @CircuitBreaker(name = "datadog-api", fallbackMethod = "logEventFallback")
    public void postPrRaisedEvent(Vulnerability vuln, Fix fix) {
        String title = "[CB] PR Raised: " + vuln.cweId() + " remediated";
        String text = String.format(
                "Compliance Buddy raised PR #%d for %s in project %s.\n" +
                        "Severity: %s | Confidence: %.0f%% | Strategy: %s\n" +
                        "PR: %s",
                fix.prNumber() != null ? fix.prNumber() : 0,
                vuln.cweId(), vuln.projectKey(),
                vuln.severity(), fix.confidence() * 100, fix.strategy(),
                fix.prUrl() != null ? fix.prUrl() : "N/A");

        postEvent(title, text, "info",
                List.of("env:" + environment, "cwe:" + vuln.cweId(),
                        "project:" + vuln.projectKey(), "severity:" + vuln.severity(),
                        "source:compliance-buddy"));
    }

    @CircuitBreaker(name = "datadog-api", fallbackMethod = "logEventFallback")
    public void postEscalationEvent(Vulnerability vuln, String reason) {
        String title = "[CB][ESCALATION] Manual review required: " + vuln.cweId();
        String text = String.format(
                "Compliance Buddy could not auto-remediate %s in %s.\nReason: %s",
                vuln.cweId(), vuln.projectKey(), reason);

        postEvent(title, text, "error",
                List.of("env:" + environment, "cwe:" + vuln.cweId(),
                        "project:" + vuln.projectKey(), "source:compliance-buddy",
                        "escalation:true"));
    }

    private void postEvent(String title, String text, String alertType, List<String> tags) {
        Map<String, Object> body = Map.of(
                "title", title,
                "text", text,
                "alert_type", alertType,
                "tags", tags,
                "source_type_name", "compliance-buddy");
        restClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.debug("Posted Datadog event: {}", title);
    }

    public void logEventFallback(String title, String text, String alertType,
                                  List<String> tags, Throwable t) {
        log.warn("Datadog circuit open, event not posted: {}", t.getMessage());
    }

    public void logEventFallback(Vulnerability vuln, Fix fix, Throwable t) {
        log.warn("Datadog circuit open for PR event: {}", t.getMessage());
    }

    public void logEventFallback(Vulnerability vuln, String reason, Throwable t) {
        log.warn("Datadog circuit open for escalation event: {}", t.getMessage());
    }
}
