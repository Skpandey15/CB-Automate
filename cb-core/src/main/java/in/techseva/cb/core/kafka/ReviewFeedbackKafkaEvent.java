package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ReviewFeedbackKafkaEvent(
        String fixId,
        String vulnerabilityId,
        String cweId,
        String decision,   // ACCEPTED | REJECTED
        String reason,
        String patchDiff,
        String strategy,
        Instant decidedAt
) {
    @JsonCreator
    public ReviewFeedbackKafkaEvent(
            @JsonProperty("fixId") String fixId,
            @JsonProperty("vulnerabilityId") String vulnerabilityId,
            @JsonProperty("cweId") String cweId,
            @JsonProperty("decision") String decision,
            @JsonProperty("reason") String reason,
            @JsonProperty("patchDiff") String patchDiff,
            @JsonProperty("strategy") String strategy,
            @JsonProperty("decidedAt") Instant decidedAt) {
        this(fixId, vulnerabilityId, cweId, decision, reason, patchDiff, strategy, decidedAt);
    }
}
