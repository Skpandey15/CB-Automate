package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record FixKafkaEvent(
        String fixId,
        String vulnerabilityId,
        String cweId,
        String strategy,
        double confidence,
        String llmModel,
        String patchDiff,
        String explanation,
        String status,
        Instant generatedAt
) {
    @JsonCreator
    public FixKafkaEvent(
            @JsonProperty("fixId") String fixId,
            @JsonProperty("vulnerabilityId") String vulnerabilityId,
            @JsonProperty("cweId") String cweId,
            @JsonProperty("strategy") String strategy,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("llmModel") String llmModel,
            @JsonProperty("patchDiff") String patchDiff,
            @JsonProperty("explanation") String explanation,
            @JsonProperty("status") String status,
            @JsonProperty("generatedAt") Instant generatedAt) {
        this(fixId, vulnerabilityId, cweId, strategy, confidence,
                llmModel, patchDiff, explanation, status, generatedAt);
    }
}
