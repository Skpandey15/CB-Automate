package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ReviewFeedbackKafkaEvent(
        @JsonProperty("fixId")            String fixId,
        @JsonProperty("vulnerabilityId")  String vulnerabilityId,
        @JsonProperty("cweId")            String cweId,
        @JsonProperty("decision")         String decision,   // ACCEPTED | REJECTED
        @JsonProperty("reason")           String reason,
        @JsonProperty("patchDiff")        String patchDiff,
        @JsonProperty("strategy")         String strategy,
        @JsonProperty("decidedAt")        Instant decidedAt
) {}
