package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record EscalationKafkaEvent(
        String vulnerabilityId,
        String sonarIssueKey,
        String cweId,
        String severity,
        String filePath,
        int retryCount,
        List<String> rejectionReasons,
        String lastFixId,
        Instant escalatedAt
) {
    @JsonCreator
    public EscalationKafkaEvent(
            @JsonProperty("vulnerabilityId") String vulnerabilityId,
            @JsonProperty("sonarIssueKey") String sonarIssueKey,
            @JsonProperty("cweId") String cweId,
            @JsonProperty("severity") String severity,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("retryCount") int retryCount,
            @JsonProperty("rejectionReasons") List<String> rejectionReasons,
            @JsonProperty("lastFixId") String lastFixId,
            @JsonProperty("escalatedAt") Instant escalatedAt) {
        this(vulnerabilityId, sonarIssueKey, cweId, severity, filePath,
                retryCount, rejectionReasons, lastFixId, escalatedAt);
    }
}
