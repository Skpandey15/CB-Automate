package in.techseva.cb.core.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "cb_cost_records")
public record CostRecord(
        @Id String id,
        String vulnerabilityId,
        String fixId,
        String cweId,
        String llmModel,
        String operation,       // PLANNER | RETRIEVER | GENERATOR | VALIDATOR | REVIEWER | ESCALATION
        long inputTokens,
        long outputTokens,
        double costUsd,
        boolean accepted,
        Instant recordedAt
) {
    /** GPT-4o pricing: $5 / 1M input, $15 / 1M output (as of 2026-Q2) */
    public static double calculateCost(String model, long inputTokens, long outputTokens) {
        return switch (model.toLowerCase()) {
            case "gpt-4o"           -> (inputTokens * 5.0 + outputTokens * 15.0) / 1_000_000;
            case "gpt-4o-mini"      -> (inputTokens * 0.15 + outputTokens * 0.60) / 1_000_000;
            case "claude-sonnet-4-6"-> (inputTokens * 3.0 + outputTokens * 15.0) / 1_000_000;
            // Local models cost $0
            default                 -> 0.0;
        };
    }
}
