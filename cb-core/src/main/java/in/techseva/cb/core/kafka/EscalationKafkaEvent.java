package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record EscalationKafkaEvent(
        @JsonProperty("vulnerabilityId")   String vulnerabilityId,
        @JsonProperty("sonarIssueKey")     String sonarIssueKey,
        @JsonProperty("cweId")             String cweId,
        @JsonProperty("severity")          String severity,
        @JsonProperty("filePath")          String filePath,
        @JsonProperty("retryCount")        int retryCount,
        @JsonProperty("rejectionReasons")  List<String> rejectionReasons,
        @JsonProperty("lastFixId")         String lastFixId,
        @JsonProperty("escalatedAt")       Instant escalatedAt
) {}
