package in.techseva.cb.core.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record FixKafkaEvent(
        @JsonProperty("fixId")            String fixId,
        @JsonProperty("vulnerabilityId")  String vulnerabilityId,
        @JsonProperty("cweId")            String cweId,
        @JsonProperty("strategy")         String strategy,
        @JsonProperty("confidence")       double confidence,
        @JsonProperty("llmModel")         String llmModel,
        @JsonProperty("patchDiff")        String patchDiff,
        @JsonProperty("explanation")      String explanation,
        @JsonProperty("status")           String status,
        @JsonProperty("generatedAt")      Instant generatedAt
) {}
